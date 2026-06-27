package com.petr.tools;

import com.petr.panel.PanelConfig;
import com.petr.panel.service.PanelService;
import com.petr.panel.service.PanelServiceImpl;

/**
 * Разовый скрипт объединения inbound'ов под client-centric модель.
 *
 * Для каждого WS-клиента с tgId привязывает (attach) XHTTP-inbound к тому же
 * клиенту — один UUID/subId оказывается в обоих inbound'ах, и подписка
 * /sub/{subId} начинает отдавать обе ссылки. Старые отдельные `_xhttp`-клиенты
 * НЕ трогает и НЕ удаляет. Операция идемпотентна — повторный запуск безопасен.
 *
 * Запуск (нужны те же env, что и боту, и -Dapp.env для выбора Latv inbound'а):
 *   java -Dapp.env=prod -cp target/Config_bot-2.0-SNAPSHOT.jar com.petr.tools.MergeInbounds
 *
 * Необязательный аргумент выбора панели: latv | germ | both (по умолчанию both).
 */
public class MergeInbounds {

    public static void main(String[] args) {
        String target = (args.length > 0) ? args[0].trim().toLowerCase() : "both";
        System.out.println("=== MergeInbounds: цель=" + target + ", app.env=" + System.getProperty("app.env") + " ===");

        boolean doLatv = target.equals("both") || target.equals("latv");
        boolean doGerm = target.equals("both") || target.equals("germ");

        if (doLatv) {
            runForPanel("Latv", () -> new PanelServiceImpl(PanelConfig.latv()));
        }
        if (doGerm) {
            runForPanel("Germ", () -> new PanelServiceImpl(PanelConfig.germ()));
        }

        System.out.println("=== MergeInbounds завершён ===");
    }

    @FunctionalInterface
    private interface PanelSupplier {
        PanelService get();
    }

    private static void runForPanel(String label, PanelSupplier supplier) {
        try {
            PanelService panel = supplier.get();
            String report = panel.mergeInbounds();
            System.out.println(report);
        } catch (Exception e) {
            System.out.println("[" + label + "] объединение не выполнено: "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}
