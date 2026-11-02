package com.petr.db;

import com.petr.db.dao.ConfigDao;
import com.petr.db.dao.UserDao;
import com.petr.db.entity.Config;
import com.petr.db.entity.User;
import com.petr.panel.dto.PanelClient;

import java.util.List;
import java.util.stream.Collectors;

public class DbService {
    public static final String STATUS_WAITING = "w";
    public static final String STATUS_APPROVED = "a";
    public static final String STATUS_REJECTED = "d";

    private final ConfigDao configDao;
    private final UserDao userDao;

    public DbService() {
        this.configDao = new ConfigDao();
        this.userDao = new UserDao();
    }

    public User findUserById(Long id) {
        return userDao.getUserById(id);
    }

    public String addUser(Long id, String tgName) {
        User user = new User();
        user.setId(id);
        user.setTgName(tgName);
        user.setHasConfig(false);
        user.setWaitAccept("w");
        userDao.registerUser(user);
        return "Пользователь добавлен успешно!";
    }

    public String setConfig(Long tgId, String configName, String vlessLink, String subLink, String xhttpLink, String realityLink, String country) {
        configDao.saveConfig(tgId, configName, vlessLink, subLink, xhttpLink, realityLink, country);
        return "Конфиг сохранен успешно!";
    }

    public Config getConfig(Long tgId, String country) {
        return configDao.getConfigByUserIdAndCountry(tgId, country);
    }

    public boolean isConfigNameTakenByOther(String configName, Long tgId) {
        return configDao.isConfigNameUsedByOtherUser(configName, tgId);
    }

    public String getConfigName(Long tgId, String country) {
        Config config = configDao.getConfigByUserIdAndCountry(tgId, country);
        return config != null ? config.getConfigName() : null;
    }

    public boolean userHasAnyConfig(Long tgId) {
        return !configDao.getConfigsByUserId(tgId).isEmpty();
    }

    public List<String> getCountriesWithStatus(Long tgId, String status) {
        return configDao.getConfigsByUserId(tgId).stream()
                .filter(c -> status.equals(c.getStatus()))
                .map(Config::getCountry)
                .toList();
    }

    /**
     * Возвращает [wsLink, subLink, xhttpLink, realityLink]. wsLink/xhttpLink/realityLink могут быть null.
     * Возвращает пустой массив если конфига нет.
     */
    public String[] getConfigsByIdAndCountry(Long tgId, String country) {
        Config config = configDao.getConfigByUserIdAndCountry(tgId, country);
        if (config == null) {
            return new String[]{};
        }
        return new String[]{config.getVlessLink(), config.getSubLink(), config.getXhttpLink(), config.getRealityLink()};
    }

    public List<Config> getAllConfigsByUserId(Long tgId) {
        return configDao.getConfigsByUserId(tgId);
    }

    public void updateXhttpLink(Long tgId, String country, String xhttpLink) {
        configDao.updateXhttpLink(tgId, country, xhttpLink);
    }

    public String setUserHasConfig(Long tgId, boolean status) {
        userDao.setUserHasConfig(tgId, status);
        return "Пользователь обновлен!";
    }

    /** @return false, если заявку уже закрыли: одобрили, отклонили или удалили */
    public boolean approveConfig(Long tgId, String country) {
        if (!configDao.compareAndSetStatus(tgId, country, STATUS_WAITING, STATUS_APPROVED)) {
            return false;
        }
        userDao.setUserStatus(STATUS_APPROVED, tgId);
        return true;
    }

    /** @return false, если заявку уже закрыли: одобрили, отклонили или удалили */
    public boolean rejectConfig(Long tgId, String country) {
        return configDao.compareAndSetStatus(tgId, country, STATUS_WAITING, STATUS_REJECTED);
    }

    public void reopenConfig(Long tgId, String country) {
        configDao.setStatus(tgId, country, STATUS_WAITING);
    }

    /** Удаляет конфиги с этим именем (по одному на страну) и возвращает удалённые. */
    public List<Config> deleteConfigsByName(String configName) {
        List<Config> configs = configDao.getConfigsByName(configName);

        for (Config config : configs) {
            Long tgId = config.getTgUser().getId();
            configDao.deleteConfigByUserIdAndCountry(tgId, config.getCountry());

            boolean hasAnyConfigLeft = userHasAnyConfig(tgId);
            userDao.setUserHasConfig(tgId, hasAnyConfigLeft);

            if (!hasAnyConfigLeft) {
                userDao.setUserStatus("w", tgId);
            }
        }

        return configs;
    }

    public void setUserName(Long tgId, String tgName) {
        userDao.setUserName(tgId, tgName);
    }

    public String formatAllUsers() {
        List<User> users = userDao.getAllUsers();
        if (users.isEmpty()) {
            return "БД пуста — пользователей нет.";
        }

        StringBuilder sb = new StringBuilder("Пользователи в БД (" + users.size() + "):\n\n");
        int i = 1;

        for (User user : users) {
            String name = user.getTgName() != null ? "@" + user.getTgName() : "(без username)";
            List<Config> configs = configDao.getConfigsByUserId(user.getId());

            String status = configs.isEmpty() ? "без конфига" : configs.stream()
                    .map(c -> c.getCountry() + ": " + statusTitle(c.getStatus()))
                    .collect(Collectors.joining(", "));

            sb.append(i++).append(". ").append(name)
                    .append(" | id: ").append(user.getId())
                    .append(" | ").append(status).append("\n");
        }

        return sb.toString();
    }

    private static String statusTitle(String status) {
        return switch (status) {
            case STATUS_APPROVED -> "одобрен";
            case STATUS_REJECTED -> "отклонён";
            default -> "ожидает одобрения";
        };
    }

    public String syncFromPanel(List<PanelClient> clients, String country) {
        int usersCreated = 0;
        int configsSaved = 0;
        int linksUpdated = 0;
        String effectiveCountry = (country == null || country.isBlank()) ? "latv" : country;

        for (PanelClient client : clients) {
            Long tgId = client.getTgId();

            // На панели без tgId (старые/ручные клиенты) — обновляем только ссылки
            // уже известного конфига, найденного по имени; пользователей не создаём
            if (tgId == 0) {
                Config byName = configDao.getConfigByNameAndCountry(client.getConfigName(), effectiveCountry);
                if (byName != null) {
                    configDao.saveConfig(
                            byName.getTgUser().getId(),
                            byName.getConfigName(),
                            client.getVlessLink(),
                            client.getSubLink() != null ? client.getSubLink() : byName.getSubLink(),
                            client.getXhttpLink(),
                            client.getRealityLink(),
                            effectiveCountry
                    );
                    linksUpdated++;
                }
                continue;
            }

            // Панель — источник истины для ссылок, но не для решения админа: клиента на панели заявка
            // получает ещё до одобрения, поэтому одобренным считаем только конфиг, которого в БД не было
            Config existing = configDao.getConfigByUserIdAndCountry(tgId, effectiveCountry);
            boolean approved = existing == null || STATUS_APPROVED.equals(existing.getStatus());

            User user = userDao.getUserById(tgId);
            if (user == null) {
                user = new User();
                user.setId(tgId);
                user.setTgName(null);
                user.setHasConfig(true);
                user.setWaitAccept("a");
                userDao.registerUser(user);
                usersCreated++;
            } else {
                if (!Boolean.TRUE.equals(user.getHasConfig())) {
                    userDao.setUserHasConfig(tgId, true);
                }
                if (approved && !"a".equals(user.getWaitAccept())) {
                    userDao.setUserStatus("a", tgId);
                }
            }

            configDao.saveConfig(
                    tgId,
                    client.getConfigName(),
                    client.getVlessLink(),
                    client.getSubLink() != null ? client.getSubLink() : (existing != null ? existing.getSubLink() : null),
                    client.getXhttpLink(),
                    client.getRealityLink(),
                    effectiveCountry
            );
            if (existing == null) {
                configDao.setStatus(tgId, effectiveCountry, STATUS_APPROVED);
                configsSaved++;
            }
        }

        return String.format(
                "Синхронизация завершена: %d новых пользователей, %d новых конфигов сохранено, "
                        + "%d конфигов без tgId обновлено по имени.",
                usersCreated,
                configsSaved,
                linksUpdated
        );
    }
}