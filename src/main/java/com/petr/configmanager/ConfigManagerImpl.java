package com.petr.configmanager;

import com.petr.db.DbService;
import com.petr.db.entity.Config;
import com.petr.db.entity.User;
import com.petr.exception.ConfigNameTakenException;
import com.petr.exception.DuplicateEmailException;
import com.petr.panel.PanelConfig;
import com.petr.panel.service.PanelService;
import com.petr.panel.service.PanelServiceImpl;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class ConfigManagerImpl implements ConfigManager {

    private final PanelService latvPanelService = new PanelServiceImpl(PanelConfig.latv());
    private final PanelService germPanelService = new PanelServiceImpl(PanelConfig.germ());
    private final DbService dbService = new DbService();
    private final Map<Long, Object> userLocks = new ConcurrentHashMap<>();

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
        PanelService panel = panelFor(effectiveCountry);

        // Двойное нажатие обрабатывается двумя потоками: без блокировки оба создадут клиента
        synchronized (userLocks.computeIfAbsent(userId, id -> new Object())) {
            Config existing = dbService.getConfig(userId, effectiveCountry);

            // Имя берём из БД, если конфиг уже есть — иначе клиент на панели «раздвоится»
            String nameToUse = existing != null ? existing.getConfigName() : null;
            if (nameToUse == null || nameToUse.isBlank()) {
                nameToUse = configName;
            }

            // Create-or-attach вернул бы новому пользователю чужой конфиг
            if (existing == null && (dbService.isConfigNameTakenByOther(nameToUse, userId)
                    || panel.isOwnedByOther(nameToUse, userId))) {
                throw new ConfigNameTakenException(nameToUse);
            }

            // Всегда сверяемся с панелью, а не с БД: createClient идемпотентен (create-or-attach) —
            // создаст клиента или привяжет недостающие inbound'ы под тем же UUID/subId.
            // Раньше решение «что докинуть» принималось по ссылкам в БД, и при выборе «Оба»
            // после одного типа бот возвращал старый конфиг, ничего не добавляя на панель.
            String[] panelResult;
            try {
                panelResult = panel.createClient(nameToUse, userId, type);
            } catch (DuplicateEmailException e) {
                throw new ConfigNameTakenException(nameToUse); // тот же email в другом регистре
            }

            String sub = panelResult[1] != null ? panelResult[1] : (existing != null ? existing.getSubLink() : null);
            dbService.setConfig(userId, nameToUse, panelResult[0], sub, panelResult[2], panelResult[3], effectiveCountry);

            // Первый запрос для этой страны — ждём одобрения админом
            if (existing == null) {
                dbService.setUserHasConfig(userId, true);
                return new String[]{};
            }

            if (DbService.STATUS_REJECTED.equals(existing.getStatus())) {
                dbService.reopenConfig(userId, effectiveCountry);
                return new String[]{};
            }

            // Разрешение выдаётся на страну: в одобренной стране новые типы привязаны выше, заявка не нужна
            return DbService.STATUS_APPROVED.equals(existing.getStatus())
                    ? dbService.getConfigsByIdAndCountry(userId, effectiveCountry)
                    : new String[]{};
        }
    }

    @Override
    public Map<String, String[]> getConfigs(Long userId) throws IOException, InterruptedException {
        Map<String, String[]> result = new LinkedHashMap<>();

        // Все страны пользователя, Латвия первой (раньше показывалась только первая по алфавиту — Германия)
        for (String country : List.of("latv", "germ")) {
            Config config = dbService.getConfig(userId, country);
            if (config != null && DbService.STATUS_APPROVED.equals(config.getStatus())) {
                result.put(country, freshLinks(userId, config));
            }
        }
        return result;
    }

    /** Ссылки в БД устаревают, когда inbound'ы меняются мимо бота (MergeInbounds, правки в панели). */
    private String[] freshLinks(Long userId, Config config) throws InterruptedException {
        String country = config.getCountry();
        try {
            String[] fromPanel = panelFor(country).readClient(config.getConfigName());
            if (fromPanel != null) {
                String sub = fromPanel[1] != null ? fromPanel[1] : config.getSubLink();
                dbService.setConfig(userId, config.getConfigName(), fromPanel[0], sub, fromPanel[2], fromPanel[3], country);
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[ConfigManager] не удалось обновить ссылки " + config.getConfigName()
                    + " (" + country + "): " + e.getMessage());
        }
        return dbService.getConfigsByIdAndCountry(userId, country);
    }

    @Override
    public List<String> getWaitingCountries(Long userId) {
        return dbService.getCountriesWithStatus(userId, DbService.STATUS_WAITING);
    }

    @Override
    public List<String> getRejectedCountries(Long userId) {
        return dbService.getCountriesWithStatus(userId, DbService.STATUS_REJECTED);
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
        List<Config> deleted = dbService.deleteConfigsByName(configName);

        if (deleted.isEmpty()) {
            return "Конфиг \"" + configName + "\" не найден в базе данных.";
        }

        Long tgId = deleted.get(0).getTgUser().getId();
        for (Config config : deleted) {
            try {
                panelFor(config.getCountry()).deleteClient(configName);
            } catch (Exception e) {
                return "Конфиг удалён из БД (id=" + tgId + "), но с панели (" + config.getCountry()
                        + ") удалить не удалось: " + e.getMessage();
            }
        }

        return "Конфиг \"" + configName + "\" удалён (пользователь id=" + tgId + ").";
    }

    @Override
    public String onStart(Long id, String username) {
        User user = dbService.findUserById(id);
        if (user == null) {
            return dbService.addUser(id, username);
        }
        if (!Objects.equals(user.getTgName(), username)) {
            dbService.setUserName(id, username);
        }
        return "Пользователь найден!";
    }

    @Override
    public String getWaitingConfigs() {
        return "";
    }

    @Override
    public Decision approveConfig(Long id, String country) {
        return decide(id, country, c -> dbService.approveConfig(id, c));
    }

    @Override
    public Decision rejectConfig(Long id, String country) {
        return decide(id, country, c -> dbService.rejectConfig(id, c));
    }

    private Decision decide(Long id, String country, Predicate<String> applyToCountry) {
        List<String> countries = country != null ? List.of(country) : getWaitingCountries(id);
        boolean applied = false;
        for (String c : countries) {
            applied |= applyToCountry.test(c);
        }
        if (applied) {
            return Decision.DONE;
        }

        List<String> statuses = dbService.getAllConfigsByUserId(id).stream()
                .filter(c -> country == null || country.equals(c.getCountry()))
                .map(Config::getStatus)
                .toList();
        if (statuses.isEmpty()) {
            return Decision.GONE;
        }
        return statuses.contains(DbService.STATUS_APPROVED) ? Decision.ALREADY_APPROVED : Decision.ALREADY_REJECTED;
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
