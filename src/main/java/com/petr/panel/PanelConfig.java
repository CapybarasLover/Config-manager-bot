package com.petr.panel;

import com.petr.configmanager.ConfigType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Конфигурация одной 3x-ui панели. Логика запросов/сервиса одинакова для всех
 * панелей — различается только этот конфиг (URL, креды, ID inbound'ов, sub-база).
 */
public class PanelConfig {

    /** Коды стран в порядке кнопок; совпадают со значениями {@code config.country} в БД. */
    public static final List<String> COUNTRIES = List.of("latv", "germ", "pol", "fin");

    /**
     * Включённые страны: ENABLED_COUNTRIES через запятую (например "latv,germ,fin"), пусто — все из
     * {@link #COUNTRIES}. Выключенная страна пропадает из бота целиком (кнопки, статус, синк), её env не нужны,
     * панель не опрашивается; записи в БД остаются, и страну можно вернуть.
     */
    public static List<String> enabledCountries() {
        String v = env("ENABLED_COUNTRIES");
        if (v == null || v.isBlank()) {
            return COUNTRIES;
        }
        List<String> wanted = Arrays.stream(v.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .toList();
        for (String c : wanted) {
            if (!COUNTRIES.contains(c)) {
                throw new RuntimeException("ENABLED_COUNTRIES: неизвестная страна \"" + c + "\", есть " + COUNTRIES);
            }
        }
        return COUNTRIES.stream().filter(wanted::contains).toList();
    }

    public final String label;          // метка для логов ("Latv" / "Germ" / "Pol" / "Fin")
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
     * Показываем выбор типа, когда есть XHTTP и ещё хотя бы один протокол: xHTTP и WS/Reality — заметно
     * разные транспорты, между ними есть смысл выбирать (Германия, Варшава, Финляндия). Панель без XHTTP
     * (Рига WS+Reality, reality-only) выбор не предлагает и сразу отдаёт все свои протоколы.
     */
    public boolean offersTypeChoice() {
        return xhttpInbound != null && singleTypes().size() >= 2;
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
            case "fin" -> fin();
            default -> throw new IllegalArgumentException("Неизвестная страна: " + country);
        };
    }

    /**
     * Латвийская панель (Рига). WS inbound: LATV_WS_INBOUND_PROD/DEV (по умолчанию prod=1 riga-ws, dev=3;
     * 0 — выключен). XHTTP на Риге нет. Reality inbound: LATV_REALITY_INBOUND_PROD/DEV (по умолчанию prod=8,
     * в dev — выключен).
     */
    public static PanelConfig latv() {
        boolean dev = "dev".equals(System.getProperty("app.env"));
        Integer ws = parseOrDisabled(env(dev ? "LATV_WS_INBOUND_DEV" : "LATV_WS_INBOUND_PROD"), dev ? 3 : 1);
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
     * Reality inbound: 5 (env GERM_REALITY_INBOUND); 0 — inbound выключен.
     */
    public static PanelConfig germ() {
        Integer ws = parseOrDisabled(env("GERM_WS_INBOUND"), 3);
        Integer xhttp = parseOrDisabled(env("GERM_XHTTP_INBOUND"), 2);
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

    /** Польская панель (Варшава), см. {@link #optionalPanel}: префикс POL. */
    public static PanelConfig pol() {
        return optionalPanel("POL", "Pol", "Польши");
    }

    /** Финская панель, см. {@link #optionalPanel}: префикс FIN. */
    public static PanelConfig fin() {
        return optionalPanel("FIN", "Fin", "Финляндии");
    }

    /**
     * Необязательная панель: без {P}_PANEL_HOME_URL возвращает null, и кнопки страны нет. Нужны XUI_API_TOKEN_{P}
     * и {P}_SUB_BASE_URL. Умолчаний у ID inbound'ов нет: {P}_WS_INBOUND, {P}_XHTTP_INBOUND, {P}_REALITY_INBOUND
     * (пусто или 0 — inbound не используется), нужен хотя бы один.
     */
    private static PanelConfig optionalPanel(String prefix, String label, String countryGenitive) {
        String url = env(prefix + "_PANEL_HOME_URL");
        if (url == null || url.isBlank()) {
            return null;
        }
        Integer ws = optionalInbound(prefix + "_WS_INBOUND");
        Integer xhttp = optionalInbound(prefix + "_XHTTP_INBOUND");
        Integer reality = optionalInbound(prefix + "_REALITY_INBOUND");
        if (ws == null && xhttp == null && reality == null) {
            throw new RuntimeException("Для " + countryGenitive + " не задан ни один inbound: " + prefix
                    + "_WS_INBOUND, " + prefix + "_XHTTP_INBOUND, " + prefix + "_REALITY_INBOUND");
        }
        return new PanelConfig(
                label,
                url,
                req("XUI_API_TOKEN_" + prefix),
                ws,
                xhttp,
                reality,
                req(prefix + "_SUB_BASE_URL")
        );
    }

    // ── Хелперы ───────────────────────────────────────────────────────────────

    /** Переменная окружения; системное свойство env.ИМЯ её перекрывает (сценарные тесты, локальный запуск). */
    private static String env(String name) {
        String override = System.getProperty("env." + name);
        return override != null ? override : System.getenv(name);
    }

    private static String req(String name) {
        String v = env(name);
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
