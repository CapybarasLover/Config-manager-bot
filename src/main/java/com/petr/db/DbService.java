package com.petr.db;

import com.petr.db.dao.ConfigDao;
import com.petr.db.dao.UserDao;

public class DbService {
    final private ConfigDao configDao;
    final private UserDao userDao;

    public DbService() {
        configDao = new ConfigDao();
        userDao = new UserDao();
    }


}
