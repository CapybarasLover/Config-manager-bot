package com.petr.configmanager;

/**
 * Что пользователь получает на панели. Каждый тип — ровно названный протокол; BOTH — все протоколы
 * панели (кнопка «Все»). В callback BOTH остаётся "both", чтобы работали старые кнопки «Оба».
 */
public enum ConfigType {
    WS, XHTTP, REALITY, BOTH;

    public static ConfigType fromString(String s) {
        return switch (s) {
            case "xhttp" -> XHTTP;
            case "reality" -> REALITY;
            case "both" -> BOTH;
            default -> WS;
        };
    }

    public String toCallbackData() {
        return switch (this) {
            case XHTTP -> "xhttp";
            case REALITY -> "reality";
            case BOTH -> "both";
            case WS -> "ws";
        };
    }

    public boolean includesWs() { return this == WS || this == BOTH; }
    public boolean includesXhttp() { return this == XHTTP || this == BOTH; }
    public boolean includesReality() { return this == REALITY || this == BOTH; }
}
