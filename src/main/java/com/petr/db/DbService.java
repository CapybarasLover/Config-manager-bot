package com.petr.db;

import com.petr.db.dao.ConfigDao;
import com.petr.db.dao.UserDao;
import com.petr.db.entity.Config;
import com.petr.db.entity.User;

import java.util.Objects;

public class DbService {
    final private ConfigDao configDao;
    final private UserDao userDao;

    public DbService() {
        configDao = new ConfigDao();
        userDao = new UserDao();
    }

    public User findUserById(Long id) {
        return userDao.getUserById(id);
    }

    public String addUser(Long id, String tgName) {
        User user = new User();
        user.setId(id);
        user.setTgName(tgName);
        user.setHasConfig(false);
        userDao.registerUser(user);
        return "Пользователь добавлен успешно!";
    }

    public String setConfig(Long tgId, String configName, String vlessLink, String subLink) {
        configDao.saveConfig(tgId, configName, vlessLink, subLink);
        return "Конфиг сохранен успешно!";
    }

    public boolean userHasConfig(Long tgId) {
        return userDao.getUserHasConfig(tgId);
    }

    public boolean userHasAcceptedConfig(Long tgId) {
        return userDao.getUserHasConfig(tgId) && Objects.equals(userDao.getUserStatus(tgId), "a");
    }

    public String[] getConfigsById(Long tgId) {
        Config config = configDao.getConfigByUserId(tgId);
        return new String[] {
                config.getVlessLink(),
                config.getSubLink()
        };
    }

    public String setUserHasConfig(Long tgId, boolean status) {
        userDao.setUserHasConfig(tgId, status);
        return "Пользователь обновлен!";
    }
}
