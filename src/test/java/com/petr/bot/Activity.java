package com.petr.bot;

/** Время последней активности фейков: по нему стенд понимает, что бот закончил обработку. */
final class Activity {
    private static volatile long last = System.nanoTime();

    private Activity() {
    }

    static void touch() {
        last = System.nanoTime();
    }

    static long idleMillis() {
        return (System.nanoTime() - last) / 1_000_000;
    }
}
