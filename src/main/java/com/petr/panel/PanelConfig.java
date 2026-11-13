package com.petr.panel;

import com.petr.configmanager.ConfigType;

import java.util.ArrayList;
import java.util.List;

/**
 * Конфигурация одной 3x-ui панели. Логика запросов/сервиса одинакова для всех
 * панелей — различается только этот конфиг (URL, креды, ID inbound'ов, sub-база).
 */
public class PanelConfig {

    /** Коды стран в порядке кнопок; совпадают со значениями {@code config.country} в БД. */
    public static final List<String> COUNTRIES = List.of("latv", "germ", "pol");

    public final String label;          // метка для логов ("Latv" / "Germ" / "Pol")
    public final String baseUrl;        // оканчивается на "/"
    public final String apiToken;       // API-токен панели (Authorization: Bearer)
    public final Integer wsInbound;     // ID WS inbound'а; null → WS не используется
    public final Integer xhttpInbound;  // ID XHTTP inbound'а; null → XHTTP не используется
    public final Integer realityInbound;  // ID Reality inbound'а; null → Reality не используется
    public final String subBaseUrl;     // база ссылки подписки (оканчивается на "/")

    public PanelConfig(String label, String baseUrl, String apiToken,
                       Integer wsInbound, Integer xhttpInbound, Integer realityInbound, String subBaseUrl) {
        this.label = label;
        this.baseUrl = normalizeSlash(baseUrl);
        this.apiToken = apiToken;
        this.wsInbound = wsInbound;
        this.xhttpInbound = xhttpInbound;
        this.realityInbound = realityInbound;
        this.subBaseUrl = normalizeSlash(subBaseUrl);
    }

    /**
     * В стране есть и WS, и xHTTP — пользователь выбирает тип конфига. На Риге (WS + Reality) выбора нет:
     * пользователь сразу получает оба протокола.
     */
    public boolean offersTypeChoice() {
        return wsInbound != null && xhttpInbound != null;
    }

    /** Кнопки выбора типа: по одной на каждый настроенный протокол (плюс общая «Все»). */
    public List<ConfigType> singleTypes() {
        List<ConfigType> types = new ArrayList<>();
        if (wsInbound != null) types.add(ConfigType.WS);
        if (xhttpInbound != null) types.add(ConfigType.XHTTP);
        if (realityInbound != null) types.add(ConfigType.REALITY);
        return types;
    }

    /** Тип, когда выбирать не из чего: все протоколы панели. */
    public ConfigType defaultType() {
        return ConfigType.BOTH;
    }

    /** Конфиг панели страны из {@link #COUNTRIES}; null — панель страны не настроена. */
    public static PanelConfig forCountry(String country) {
        return switch (country) {
            case "latv" -> latv();
            case "germ" -> germ();
            case "pol" -> pol();
            default -> throw new IllegalArgumentException("Неизвестная страна: " + country);
        };
    }

    /**
     * Латвийская панель (Рига). WS inbound: env (по умолчанию prod=1 riga-ws, dev=3). XHTTP на Риге нет.
     * Reality inbound: LATV_REALITY_INBOUND_PROD/DEV (по умолчанию prod=8, в dev — выключен).
     */
    public static PanelConfig latv() {
        boolean dev = "dev".equals(System.getProperty("app.env"));
        int ws = parseOr(env(dev ? "LATV_WS_INBOUND_DEV" : "LATV_WS_INBOUND_PROD"), dev ? 3 : 1);
        Integer reality = dev
                ? parseOrNull(env("LATV_REALITY_INBOUND_DEV"))
                : parseOrDisabled(env("LATV_REALITY_INBOUND_PROD"), 8);
        String sub = nvl(env("LATV_SUB_BASE_URL"), "https://petromerzlikino.site:2096/sub/");
        return new PanelConfig(
                "Latv",
                req("LATV_PANEL_HOME_URL"),
                req("XUI_API_TOKEN_LATV"),
                ws,
                null, // XHTTP на Риге больше нет
                reality,
                sub
        );
    }

    /**
     * Германская панель. WS inbound: 3 (env GERM_WS_INBOUND), XHTTP inbound: 2 (env GERM_XHTTP_INBOUND),
     * Reality inbound: 5 (env GERM_REALITY_INBOUND).
     */
    public static PanelConfig germ() {
        int ws = parseOr(env("GERM_WS_INBOUND"), 3);
        Integer xhttp = parseOr(env("GERM_XHTTP_INBOUND"), 2);
        Integer reality = parseOrDisabled(env("GERM_REALITY_INBOUND"), 5);
        String sub = nvl(env("GERM_SUB_BASE_URL"), "");
        return new PanelConfig(
                "Germ",
                req("GERMAN_PANEL_HOME_URL"),
                req("XUI_API_TOKEN_GERM"),
                ws,
                xhttp,
                reality,
                sub
        );
    }

    /**
     * Польская панель (Варшава). Необязательная: без POL_PANEL_HOME_URL возвращает null, и кнопки «Польша» нет.
     * Умолчаний у ID inbound'ов нет: POL_WS_INBOUND, POL_XHTTP_INBOUND, POL_REALITY_INBOUND (пусто или 0 —
     * inbound не используется), нужен хотя бы один.
     */
    public static PanelConfig pol() {
        String url = env("POL_PANEL_HOME_URL");
        if (url == null || url.isBlank()) {
            return null;
        }
        Integer ws = optionalInbound("POL_WS_INBOUND");
        Integer xhttp = optionalInbound("POL_XHTTP_INBOUND");
        Integer reality = optionalInbound("POL_REALITY_INBOUND");
        if (ws == null && xhttp == null && reality == null) {
            throw new RuntimeException("Для Польши не задан ни один inbound: POL_WS_INBOUND, POL_XHTTP_INBOUND, POL_REALITY_INBOUND");
        }
        return new PanelConfig(
                "Pol",
                url,
                req("XUI_API_TOKEN_POL"),
                ws,
                xhttp,
                reality,
                req("POL_SUB_BASE_URL")
        );
    }

    // ── Хелперы ───────────────────────────────────────────────────────────────

    private static String env(String name) {
        return System.getenv(name);
    }

    private static String req(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new RuntimeException("Переменная окружения не задана: " + name);
        }
        return v;
    }

    private static String nvl(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static Integer parseOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseOr(String v, int def) {
        Integer parsed = parseOrNull(v);
        return parsed != null ? parsed : def;
    }

    /** Пусто → значение по умолчанию; "0"/"none"/"off" → null (inbound выключен). */
    private static Integer parseOrDisabled(String v, int def) {
        if (v == null || v.isBlank()) return def;
        String t = v.trim().toLowerCase();
        if (t.equals("0") || t.equals("none") || t.equals("off")) return null;
        Integer parsed = parseOrNull(t);
        return parsed != null ? parsed : def;
    }

    /** Пусто или "0"/"none"/"off" → null (inbound не используется); не число → ошибка, а не тихое отключение. */
    private static Integer optionalInbound(String name) {
        String v = env(name);
        if (v == null || v.isBlank()) return null;
        String t = v.trim().toLowerCase();
        if (t.equals("0") || t.equals("none") || t.equals("off")) return null;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Переменная окружения " + name + " должна быть ID inbound'а, а не \"" + v + "\"");
        }
    }

    private static String normalizeSlash(String url) {
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url : url + "/";
    }
}
