package com.petr.configmanager;

import com.petr.db.DbService;
import com.petr.db.entity.Config;
import com.petr.panel.PanelConfig;
import com.petr.panel.service.PanelService;
import com.petr.panel.service.PanelServiceImpl;

import java.io.IOException;
import java.util.List;

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

        boolean hasAccepted = dbService.userHasAcceptedConfig(userId);
        boolean hasConfigForCountry = dbService.userHasConfig(userId, effectiveCountry);

        // Уже одобрен и есть конфиг для этой страны — возможно, докинуть недостающий тип
        if (hasAccepted && hasConfigForCountry) {
            String[] existing = dbService.getConfigsByIdAndCountry(userId, effectiveCountry);

            boolean hasWs = existing.length > 0 && existing[0] != null;
            boolean hasXhttp = existing.length > 2 && existing[2] != null;

            boolean needWs = type.includesWs() && !hasWs;
            boolean needXhttp = type.includesXhttp() && !hasXhttp;

            if (!needWs && !needXhttp) {
                return existing;
            }

            String nameToUse = dbService.getConfigName(userId, effectiveCountry);
            if (nameToUse == null || nameToUse.isBlank()) {
                nameToUse = configName;
            }

            // Панель сама создаст/привяжет недостающие inbound'ы под одним UUID/subId
            String[] panelResult = panelFor(effectiveCountry).createClient(nameToUse, userId, type);

            String ws = panelResult[0] != null ? panelResult[0] : (existing.length > 0 ? existing[0] : null);
            String sub = panelResult[1] != null ? panelResult[1] : (existing.length > 1 ? existing[1] : null);
            String xhttp = panelResult[2] != null ? panelResult[2] : (existing.length > 2 ? existing[2] : null);

            dbService.setConfig(userId, nameToUse, ws, sub, xhttp, effectiveCountry);
            return dbService.getConfigsByIdAndCountry(userId, effectiveCountry);
        }

        // Первый запрос для этой страны — создаём клиента, ждём одобрения админом
        if (!hasConfigForCountry) {
            String[] panelResult = panelFor(effectiveCountry).createClient(configName, userId, type);

            dbService.setConfig(
                    userId,
                    configName,
                    panelResult[0],
                    panelResult[1],
                    panelResult[2],
                    effectiveCountry
            );
            dbService.setUserHasConfig(userId, true);

            return new String[]{};
        }

        return new String[]{};
    }

    @Override
    public String[] getConfigs(Long userId) throws IOException, InterruptedException {
        if (!dbService.userHasAcceptedConfig(userId)) {
            return new String[]{};
        }

        String country = getExistingCountry(userId);
        if (country == null) {
            return new String[]{};
        }

        return dbService.getConfigsByIdAndCountry(userId, country);
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
