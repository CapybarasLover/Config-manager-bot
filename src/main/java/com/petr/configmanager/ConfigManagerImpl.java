package com.petr.configmanager;

import com.petr.db.DbService;
import com.petr.db.entity.Config;
import com.petr.panel.PanelConfig;
import com.petr.panel.service.PanelService;
import com.petr.panel.service.PanelServiceImpl;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigManagerImpl implements ConfigManager {

    private final PanelService latvPanelService = new PanelServiceImpl(PanelConfig.latv());
    private final PanelService germPanelService = new PanelServiceImpl(PanelConfig.germ());
    private final DbService dbService = new DbService();

    private PanelService panelFor(String country) {
        return "germ".equalsIgnoreCase(country) ? germPanelService : latvPanelService;
    }

    @Override
    public String getAllConfigs() throws IOException, InterruptedException {
        return latvPanelService.listClients();
    }

    @Override
    public String[] getConfigs(Long userId, String configName, String configType, String country)
            throws IOException, InterruptedException {

        String effectiveCountry = nvl(country, "latv");
        ConfigType type = ConfigType.fromString(nvl(configType, "ws"));

        boolean hasConfigForCountry = dbService.userHasConfig(userId, effectiveCountry);

        // Имя берём из БД, если конфиг уже есть — иначе клиент на панели «раздвоится»
        String nameToUse = hasConfigForCountry ? dbService.getConfigName(userId, effectiveCountry) : null;
        if (nameToUse == null || nameToUse.isBlank()) {
            nameToUse = configName;
        }

        // Всегда сверяемся с панелью, а не с БД: createClient идемпотентен (create-or-attach) —
        // создаст клиента или привяжет недостающие inbound'ы под тем же UUID/subId.
        // Раньше решение «что докинуть» принималось по ссылкам в БД, и при выборе «Оба»
        // после одного типа бот возвращал старый конфиг, ничего не добавляя на панель.
        String[] panelResult = panelFor(effectiveCountry).createClient(nameToUse, userId, type);

        String[] existing = dbService.getConfigsByIdAndCountry(userId, effectiveCountry);
        String sub = panelResult[1] != null ? panelResult[1] : (existing.length > 1 ? existing[1] : null);
        dbService.setConfig(userId, nameToUse, panelResult[0], sub, panelResult[2], panelResult[3], effectiveCountry);

        // Первый запрос для этой страны — ждём одобрения админом
        if (!hasConfigForCountry) {
            dbService.setUserHasConfig(userId, true);
            return new String[]{};
        }

        return dbService.userHasAcceptedConfig(userId)
                ? dbService.getConfigsByIdAndCountry(userId, effectiveCountry)
                : new String[]{};
    }

    @Override
    public Map<String, String[]> getConfigs(Long userId) throws IOException, InterruptedException {
        Map<String, String[]> result = new LinkedHashMap<>();
        if (!dbService.userHasAcceptedConfig(userId)) {
            return result;
        }

        // Все страны пользователя, Латвия первой (раньше показывалась только первая по алфавиту — Германия)
        for (String country : List.of("latv", "germ")) {
            String[] configs = dbService.getConfigsByIdAndCountry(userId, country);
            if (configs.length > 0) {
                result.put(country, configs);
            }
        }
        return result;
    }

    @Override
    public String getExistingConfigName(Long userId) {
        String country = getExistingCountry(userId);
        if (country == null) {
            return null;
        }
        return dbService.getConfigName(userId, country);
    }

    @Override
    public String getExistingCountry(Long userId) {
        List<Config> configs = dbService.getAllConfigsByUserId(userId);
        if (configs == null || configs.isEmpty()) {
            return null;
        }
        return configs.get(0).getCountry();
    }

    @Override
    public String deleteConfig(String configName) throws IOException, InterruptedException {
        String country = nvl(dbService.getCountryByConfigName(configName), "latv");
        String tgId = dbService.deleteConfigByName(configName);

        if (tgId == null) {
            return "Конфиг \"" + configName + "\" не найден в базе данных.";
        }

        try {
            panelFor(country).deleteClient(configName);
        } catch (Exception e) {
            return "Конфиг удалён из БД (id=" + tgId + "), но с панели удалить не удалось: " + e.getMessage();
        }

        return "Конфиг \"" + configName + "\" удалён (пользователь id=" + tgId + ").";
    }

    @Override
    public String onStart(Long id, String username) {
        return dbService.findUserById(id) != null
                ? "Пользователь найден!"
                : dbService.addUser(id, username);
    }

    @Override
    public String getWaitingConfigs() {
        return "";
    }

    @Override
    public String acceptConfig(Long id) {
        dbService.setUserStatusAccepted(id);
        return "Пользователю разрешено получить конфиг!";
    }

    @Override
    public boolean isRegistered(long id) {
        return dbService.findUserById(id) != null;
    }

    @Override
    public String listUsers() {
        return dbService.formatAllUsers();
    }

    @Override
    public String syncFromPanel() throws IOException, InterruptedException {
        String latvResult = dbService.syncFromPanel(latvPanelService.getClients(), "latv");
        String germResult = dbService.syncFromPanel(germPanelService.getClients(), "germ");
        return latvResult + "\n" + germResult;
    }

    private static String nvl(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }
}
