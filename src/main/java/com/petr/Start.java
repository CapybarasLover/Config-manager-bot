package com.petr;

import com.petr.db.dao.UserDao;
import com.petr.db.entity.User;
import com.petr.db.migration.DatabaseMigration;

import java.io.IOException;

public class Start {
    public static void main(String[] args) throws IOException, InterruptedException {
        DatabaseMigration.startMigration(System.getProperty("app.env"));
//        User user = new User();
//        user.setId(1234L);
//        user.setHasConfig(false);
//        user.setTgName("test");
        UserDao userDao = new UserDao();
//        userDao.registerUser(user);
    }
}
