package com.petr;

import com.petr.bot.Bot;
import com.petr.db.dao.UserDao;
import com.petr.db.entity.User;
import com.petr.db.migration.DatabaseMigration;
import com.petr.panel.ApiRequests;
import com.petr.panel.ApiRequestsLatvImpl;
import com.petr.panel.service.PanelServiceLatvImpl;

import java.io.IOException;
import java.util.UUID;

public class Start {
    public static void main(String[] args) throws IOException, InterruptedException {
        DatabaseMigration.startMigration(System.getProperty("app.env"));
        Bot bot = new Bot();
        bot.startBot();
    }
}
