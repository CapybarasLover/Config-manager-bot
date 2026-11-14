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

    /** Только включённые (ENABLED_COUNTRIES) и настроенные страны, в порядке кнопок. */
    private final Map<String, PanelConfig> panelConfigs = new LinkedHashMap<>();
    private final Map<String, PanelService> panels = new LinkedHashMap<>();
    private final DbService dbService = new DbService();
    private final Map<Long, Object> userLocks = new ConcurrentHashMap<>();

    public ConfigManagerImpl() {
        List<String> enabled = PanelConfig.enabledCountries();
        System.out.println("[ConfigManager] включённые страны: " + enabled);
        for (String country : enabled) {
            PanelConfig cfg = PanelConfig.forCountry(country);
            if (cfg == null) {
                System.out.println("[ConfigManager] панель " + country + " не настроена — страна скрыта");
                continue;
            }
            panelConfigs.put(country, cfg);
            panels.put(country, new PanelServiceImpl(cfg));
        }
    }

    /** Нет панели — не подменяем другой страной: иначе конфиг создастся не на том сервере. */
    private PanelService panelFor(String country) {
        PanelService panel = panels.get(country);
        if (panel == null) {
            throw new IllegalStateException("Панель страны " + country + " не настроена");
        }
        return panel;
    }

    private PanelConfig panelConfigFor(String country) {
        PanelConfig cfg = panelConfigs.get(country);
        if (cfg == null) {
            throw new IllegalStateException("Панель страны " + country + " не настроена");
        }
        return cfg;
    }

    @Override
    public List<String> availableCountries() {
        return List.copyOf(panels.keySet());
    }

    @Override
    public boolean offersTypeChoice(String country) {
        return panelConfigFor(country).offersTypeChoice();
    }

    @Override
    public String defaultConfigType(String country) {
        return panelConfigFor(country).defaultType().toCallbackData();
    }

    @Override
    public List<String> configTypes(String country) {
        return panelConfigFor(country).singleTypes().stream().map(ConfigType::toCallbackData).toList();
    }

    @Override
    public String getAllConfigs() throws IOException, InterruptedException {
        return panelFor("latv").listClients();
    }

    @Override
    public String[] getConfigs(Long userId, String configName, String configType, String country)
            throws IOException, InterruptedException {

        String effectiveCountry = nvl(country, "latv");
        ConfigType type = ConfigType.fromString(nvl(configType, "both"));
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

        // Все включённые страны пользователя в порядке кнопок (раньше показывалась только первая по алфавиту —
        // Германия). Конфиги выключенных стран не показываем: их серверы, как правило, уже не работают
        for (String country : panels.keySet()) {
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
        return onlyAvailable(dbService.getCountriesWithStatus(userId, DbService.STATUS_WAITING));
    }

    @Override
    public List<String> getRejectedCountries(Long userId) {
        return onlyAvailable(dbService.getCountriesWithStatus(userId, DbService.STATUS_REJECTED));
    }

    /** Заявка в выключенную страну не должна висеть у пользователя «ожидает одобрения» бесконечно. */
    private List<String> onlyAvailable(List<String> countries) {
        return countries.stream().filter(panels::containsKey).toList();
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
        // Ошибка одной панели (недоступна, страна выключена) не должна оставлять клиента на остальных
        List<String> failed = new java.util.ArrayList<>();
        for (Config config : deleted) {
            try {
                panelFor(config.getCountry()).deleteClient(configName);
            } catch (Exception e) {
                failed.add(config.getCountry() + " (" + e.getMessage() + ")");
            }
        }

        if (!failed.isEmpty()) {
            return "Конфиг удалён из БД (id=" + tgId + "), но с панели удалить не удалось: " + String.join(", ", failed);
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
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, PanelService> panel : panels.entrySet()) {
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append(panel.getKey()).append(": ")
                    .append(dbService.syncFromPanel(panel.getValue().getClients(), panel.getKey()));
        }
        return result.toString();
    }

    private static String nvl(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }
}
