package com.petr.panel;

/**
 * Конфигурация одной 3x-ui панели. Логика запросов/сервиса одинакова для всех
 * панелей — различается только этот конфиг (URL, креды, ID inbound'ов, sub-база).
 */
public class PanelConfig {

    public final String label;          // метка для логов ("Latv" / "Germ")
    public final String baseUrl;        // оканчивается на "/"
    public final String apiToken;       // API-токен панели (Authorization: Bearer)
    public final int wsInbound;         // ID WS inbound'а
    public final Integer xhttpInbound;  // ID XHTTP inbound'а; null → XHTTP не используется
    public final Integer realityInbound;  // ID Reality inbound'а (добавляется всем клиентам); null → не используется
    public final String subBaseUrl;     // база ссылки подписки (оканчивается на "/")

    public PanelConfig(String label, String baseUrl, String apiToken,
                       int wsInbound, Integer xhttpInbound, Integer realityInbound, String subBaseUrl) {
        this.label = label;
        this.baseUrl = normalizeSlash(baseUrl);
        this.apiToken = apiToken;
        this.wsInbound = wsInbound;
        this.xhttpInbound = xhttpInbound;
        this.realityInbound = realityInbound;
        this.subBaseUrl = normalizeSlash(subBaseUrl);
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

    private static String normalizeSlash(String url) {
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url : url + "/";
    }
}
