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
    public final String subBaseUrl;     // база ссылки подписки (оканчивается на "/")

    public PanelConfig(String label, String baseUrl, String apiToken,
                       int wsInbound, Integer xhttpInbound, String subBaseUrl) {
        this.label = label;
        this.baseUrl = normalizeSlash(baseUrl);
        this.apiToken = apiToken;
        this.wsInbound = wsInbound;
        this.xhttpInbound = xhttpInbound;
        this.subBaseUrl = normalizeSlash(subBaseUrl);
    }

    /** Латвийская панель. WS inbound: env (по умолчанию prod=2, dev=3). XHTTP inbound — из env (опционально). */
    public static PanelConfig latv() {
        boolean dev = "dev".equals(System.getProperty("app.env"));
        int ws = parseOr(env(dev ? "LATV_WS_INBOUND_DEV" : "LATV_WS_INBOUND_PROD"), dev ? 3 : 2);
        Integer xhttp = parseOrNull(env(dev ? "LATV_XHTTP_INBOUND_DEV" : "LATV_XHTTP_INBOUND_PROD"));
        String sub = nvl(env("LATV_SUB_BASE_URL"), "https://petromerzlikino.site:2096/sub/");
        return new PanelConfig(
                "Latv",
                req("LATV_PANEL_HOME_URL"),
                req("XUI_API_TOKEN_LATV"),
                ws,
                xhttp,
                sub
        );
    }

    /** Германская панель. WS inbound: 3 (env GERM_WS_INBOUND), XHTTP inbound: 2 (env GERM_XHTTP_INBOUND). */
    public static PanelConfig germ() {
        int ws = parseOr(env("GERM_WS_INBOUND"), 3);
        Integer xhttp = parseOr(env("GERM_XHTTP_INBOUND"), 2);
        String sub = nvl(env("GERM_SUB_BASE_URL"), "");
        return new PanelConfig(
                "Germ",
                req("GERMAN_PANEL_HOME_URL"),
                req("XUI_API_TOKEN_GERM"),
                ws,
                xhttp,
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

    private static String normalizeSlash(String url) {
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url : url + "/";
    }
}
