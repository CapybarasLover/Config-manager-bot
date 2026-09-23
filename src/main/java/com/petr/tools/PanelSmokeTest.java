package com.petr.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.petr.configmanager.ConfigType;
import com.petr.panel.ApiRequests;
import com.petr.panel.ApiRequestsImpl;
import com.petr.panel.PanelConfig;
import com.petr.panel.service.PanelService;
import com.petr.panel.service.PanelServiceImpl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Смоук-тест против живой панели. Создаёт тестового клиента {@value #EMAIL} (tgId=0,
 * в БД не попадает), проверяет сценарий «один тип → потом Оба», наличие Reality в
 * подписке и раскладку ссылок, затем удаляет клиента. Реальных клиентов не трогает.
 *
 * Запуск (те же env, что и боту):
 *   java -Dapp.env=prod -cp target/Config_bot-2.0-SNAPSHOT.jar com.petr.tools.PanelSmokeTest [latv|germ|both]
 */
public class PanelSmokeTest {

    private static final String EMAIL = "claudetest_config";

    private static int failures = 0;

    public static void main(String[] args) {
        String target = (args.length > 0) ? args[0].trim().toLowerCase() : "both";
        if (target.equals("inbounds")) {
            listInbounds(safeCfg("latv"));
            listInbounds(safeCfg("germ"));
            return;
        }
        if (target.equals("both") || target.equals("latv")) {
            run(safeCfg("latv"));
        }
        if (target.equals("both") || target.equals("germ")) {
            run(safeCfg("germ"));
        }
        System.out.println(failures == 0 ? "\n=== ВСЁ ОК ===" : "\n=== ПРОВАЛОВ: " + failures + " ===");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static PanelConfig safeCfg(String country) {
        try {
            return "germ".equals(country) ? PanelConfig.germ() : PanelConfig.latv();
        } catch (Exception e) {
            fail("[" + country + "] конфиг панели не собран: " + e.getMessage());
            return null;
        }
    }

    private static void run(PanelConfig cfg) {
        if (cfg == null) return;
        String tag = "[" + cfg.label + "]";
        System.out.println("\n=== " + tag + " WS=" + cfg.wsInbound + " XHTTP=" + cfg.xhttpInbound
                + " REALITY=" + cfg.realityInbound + " ===");

        ApiRequests api = new ApiRequestsImpl(cfg);
        PanelService panel = new PanelServiceImpl(cfg);
        boolean hasXhttp = cfg.xhttpInbound != null;

        try {
            if (api.getClient(EMAIL) != null) {
                System.out.println(tag + " остался тестовый клиент с прошлого запуска — удаляю");
                api.deleteClient(EMAIL);
            }

            // Шаг 1: один тип (XHTTP, если есть, иначе WS)
            ConfigType first = hasXhttp ? ConfigType.XHTTP : ConfigType.WS;
            String[] r1 = panel.createClient(EMAIL, 0, first);
            print(tag + " шаг 1 (" + first + ")", r1);
            check(tag, "подписка есть", r1[1] != null);
            check(tag, "Reality-ссылка есть", cfg.realityInbound == null || r1[3] != null);
            if (hasXhttp) {
                check(tag, "xHTTP-ссылка есть", r1[2] != null);
                check(tag, "WS ещё НЕ привязан", r1[0] == null);
            } else {
                check(tag, "WS-ссылка есть", r1[0] != null);
                check(tag, "xHTTP нет", r1[2] == null);
            }

            // Шаг 2: «Оба» — должен добавиться недостающий inbound (фикс бага)
            String[] r2 = panel.createClient(EMAIL, 0, ConfigType.BOTH);
            print(tag + " шаг 2 (BOTH)", r2);
            check(tag, "WS-ссылка есть", r2[0] != null);
            check(tag, "xHTTP-ссылка " + (hasXhttp ? "есть" : "отсутствует"), (r2[2] != null) == hasXhttp);
            check(tag, "Reality-ссылка есть", cfg.realityInbound == null || r2[3] != null);
            check(tag, "подписка не поменялась", r1[1] != null && r1[1].equals(r2[1]));

            JsonNode client = api.getClient(EMAIL);
            List<Integer> ids = new ArrayList<>();
            if (client != null) client.path("inboundIds").forEach(n -> ids.add(n.asInt()));
            System.out.println(tag + " inboundIds клиента: " + ids);
            check(tag, "привязан к WS " + cfg.wsInbound, ids.contains(cfg.wsInbound));
            if (hasXhttp) check(tag, "привязан к XHTTP " + cfg.xhttpInbound, ids.contains(cfg.xhttpInbound));
            if (cfg.realityInbound != null) check(tag, "привязан к Reality " + cfg.realityInbound, ids.contains(cfg.realityInbound));

            // Шаг 3: подписка отдаёт все протоколы
            int expected = 1 + (hasXhttp ? 1 : 0) + (cfg.realityInbound != null ? 1 : 0);
            int inSub = countSubLinks(r2[1]);
            System.out.println(tag + " ссылок в подписке: " + inSub + " (ожидается ≥ " + expected + ")");
            check(tag, "в подписке все протоколы", inSub >= expected);
        } catch (Exception e) {
            fail(tag + " исключение: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        } finally {
            try {
                api.deleteClient(EMAIL);
                check(tag, "тестовый клиент удалён", api.getClient(EMAIL) == null);
            } catch (Exception e) {
                fail(tag + " НЕ УДАЛОСЬ удалить " + EMAIL + " — удали вручную: " + e.getMessage());
            }
        }
    }

    /** Печатает inbound'ы панели: id, remark, протокол, транспорт, порт, включён ли. */
    private static void listInbounds(PanelConfig cfg) {
        if (cfg == null) return;
        System.out.println("\n=== [" + cfg.label + "] inbounds ===");
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(new ApiRequestsImpl(cfg).getInboundsList().body());
            for (JsonNode in : root.path("obj")) {
                JsonNode s = in.path("streamSettings");
                JsonNode stream = s.isObject() ? s
                        : new com.fasterxml.jackson.databind.ObjectMapper().readTree(s.asText("{}"));
                System.out.printf("  id=%-3d port=%-6d %-6s %-8s/%-8s enable=%s  %s%n",
                        in.path("id").asInt(), in.path("port").asInt(), in.path("protocol").asText(),
                        stream.path("network").asText(), stream.path("security").asText(),
                        in.path("enable").asBoolean(), in.path("remark").asText());
            }
        } catch (Exception e) {
            System.out.println("  ошибка: " + e.getMessage());
        }
    }

    /** Скачивает подписку и считает строки-ссылки (тело — base64 или plain). */
    private static int countSubLinks(String subUrl) throws Exception {
        if (subUrl == null) return 0;
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(subUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.out.println("подписка вернула HTTP " + resp.statusCode());
            return 0;
        }
        String body = resp.body().trim();
        if (!body.contains("://")) {
            body = new String(Base64.getMimeDecoder().decode(body), StandardCharsets.UTF_8);
        }
        return (int) body.lines().filter(l -> l.contains("://")).count();
    }

    private static void print(String title, String[] r) {
        System.out.println(title + ":\n  ws      = " + shortLink(r[0]) + "\n  sub     = " + r[1]
                + "\n  xhttp   = " + shortLink(r[2]) + "\n  reality = " + shortLink(r[3]));
    }

    /** Без UUID — только схема и host:port?params, чтобы не светить ключи в логах. */
    private static String shortLink(String link) {
        if (link == null) return null;
        int at = link.indexOf('@');
        String rest = at >= 0 ? link.substring(at + 1) : link;
        return link.substring(0, link.indexOf("://") + 3) + "…@" + (rest.length() > 90 ? rest.substring(0, 90) + "…" : rest);
    }

    private static void check(String tag, String what, boolean ok) {
        System.out.println(tag + (ok ? " ✔ " : " ✘ ") + what);
        if (!ok) failures++;
    }

    private static void fail(String msg) {
        System.out.println("✘ " + msg);
        failures++;
    }
}
