package com.petr.configmanager;

/** Итог решения админа по заявке: своё решение применилось или заявку уже закрыл кто-то другой. */
public enum Decision {
    DONE, ALREADY_APPROVED, ALREADY_REJECTED, GONE
}
