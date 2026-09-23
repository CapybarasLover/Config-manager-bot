package com.petr.db;

import com.petr.db.dao.ConfigDao;
import com.petr.db.dao.UserDao;
import com.petr.db.entity.Config;
import com.petr.db.entity.User;
import com.petr.panel.dto.PanelClient;

import java.util.List;
import java.util.Objects;

public class DbService {
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

    public String getCountryByConfigName(String configName) {
        Config config = configDao.getConfigByName(configName);
        return config != null ? config.getCountry() : null;
    }

    public String getConfigName(Long tgId, String country) {
        Config config = configDao.getConfigByUserIdAndCountry(tgId, country);
        return config != null ? config.getConfigName() : null;
    }

    public boolean userHasConfig(Long tgId, String country) {
        return configDao.getConfigByUserIdAndCountry(tgId, country) != null;
    }

    public boolean userHasAnyConfig(Long tgId) {
        return !configDao.getConfigsByUserId(tgId).isEmpty();
    }

    public boolean userHasAcceptedConfig(Long tgId) {
        return userHasAnyConfig(tgId) && Objects.equals(userDao.getUserStatus(tgId), "a");
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

    public void setUserStatusAccepted(Long tgId) {
        userDao.setUserStatus("a", tgId);
    }

    public String deleteConfigByName(String configName) {
        Config config = configDao.getConfigByName(configName);
        if (config == null) {
            return null;
        }

        Long tgId = config.getTgUser().getId();
        String country = config.getCountry();

        configDao.deleteConfigByUserIdAndCountry(tgId, country);

        boolean hasAnyConfigLeft = userHasAnyConfig(tgId);
        userDao.setUserHasConfig(tgId, hasAnyConfigLeft);

        if (!hasAnyConfigLeft) {
            userDao.setUserStatus("w", tgId);
        }

        return String.valueOf(tgId);
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
            boolean hasAnyConfig = userHasAnyConfig(user.getId());

            String status;
            if ("a".equals(user.getWaitAccept()) && hasAnyConfig) {
                status = "конфиг одобрен";
            } else if (hasAnyConfig) {
                status = "ожидает одобрения";
            } else {
                status = "без конфига";
            }

            sb.append(i++).append(". ").append(name)
                    .append(" | id: ").append(user.getId())
                    .append(" | ").append(status).append("\n");
        }

        return sb.toString();
    }

    public String syncFromPanel(List<PanelClient> clients, String country) {
        int usersCreated = 0;
        int configsSaved = 0;
        String effectiveCountry = (country == null || country.isBlank()) ? "latv" : country;

        for (PanelClient client : clients) {
            Long tgId = client.getTgId();

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
                if (!"a".equals(user.getWaitAccept())) {
                    userDao.setUserStatus("a", tgId);
                }
            }

            // Панель — источник истины: ссылки обновляем и у уже существующих конфигов
            Config existing = configDao.getConfigByUserIdAndCountry(tgId, effectiveCountry);
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
                configsSaved++;
            }
        }

        return String.format(
                "Синхронизация завершена: %d новых пользователей, %d новых конфигов сохранено.",
                usersCreated,
                configsSaved
        );
    }
}