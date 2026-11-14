package com.petr.tools;

import com.petr.panel.PanelConfig;
import com.petr.panel.service.PanelService;
import com.petr.panel.service.PanelServiceImpl;

/**
 * Разовый скрипт объединения inbound'ов под client-centric модель.
 *
 * Для каждого WS-клиента бота привязывает (attach) XHTTP-inbound, а для всех
 * клиентов бота — Reality inbound (Германия 5 / Латвия 8 / Польша из POL_REALITY_INBOUND). Один UUID/subId
 * оказывается во всех inbound'ах, и подписка /sub/{subId} отдаёт все ссылки. Старые отдельные `_xhttp`-клиенты
 * НЕ трогает и НЕ удаляет. Операция идемпотентна — повторный запуск безопасен.
 *
 * Запуск (нужны те же env, что и боту, и -Dapp.env для выбора Latv inbound'а):
 *   java -Dapp.env=prod -cp target/Config_bot-2.0-SNAPSHOT.jar com.petr.tools.MergeInbounds
 *
 * Необязательный аргумент выбора панели: latv | germ | pol | fin | both (по умолчанию both — все настроенные).
 */
public class MergeInbounds {

    public static void main(String[] args) {
        String target = (args.length > 0) ? args[0].trim().toLowerCase() : "both";
        System.out.println("=== MergeInbounds: цель=" + target + ", app.env=" + System.getProperty("app.env") + " ===");

        for (String country : PanelConfig.COUNTRIES) {
            if (target.equals("both") || target.equals(country)) {
                runForPanel(country, () -> {
                    PanelConfig cfg = PanelConfig.forCountry(country);
                    return cfg == null ? null : new PanelServiceImpl(cfg);
                });
            }
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
            if (panel == null) {
                System.out.println("[" + label + "] панель не настроена — пропуск");
                return;
            }
            String report = panel.mergeInbounds();
            System.out.println(report);
        } catch (Exception e) {
            System.out.println("[" + label + "] объединение не выполнено: "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}
