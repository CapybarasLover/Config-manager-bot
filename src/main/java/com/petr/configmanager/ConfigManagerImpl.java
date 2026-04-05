package com.petr.configmanager;

import com.petr.db.DbService;
import com.petr.panel.service.PanelService;
import com.petr.panel.service.PanelServiceGermImpl;
import io.github.natanimn.telebof.BotContext;
import io.github.natanimn.telebof.types.updates.Message;

import java.io.IOException;

public class ConfigManagerImpl implements ConfigManager {
    private final PanelService panelServiceGerm = new PanelServiceGermImpl();
    private final DbService dbService = new DbService();

    @Override
    public String getAllConfigs() {
        return panelServiceGerm.listClients();
    }

    //TODO по хорошему написать так же попытки получить запросом с панели, с локальной бд и возврат null или пустой строки
    @Override
    public String[] getConfigs(Long userId, String username) throws IOException, InterruptedException {
        if(dbService.userHasAcceptedConfig(userId)){
            String[] configs = dbService.getConfigsById(userId);

            return configs;
        } else if(!dbService.userHasConfig(userId)){
            String[] configs = panelServiceGerm.createClient(username, userId);

            System.out.println(dbService.setConfig(userId, username, configs[0], configs[1]));
            System.out.println(dbService.setUserHasConfig(userId, true));

            return new String[]{};
        } else {
            return new String[]{};
        }
    }

    @Override
    public String deleteConfig(String clientName) {

        return "";
    }

    @Override
    public String onStart(Long id, String username) {
        return dbService.findUserById(id) != null
                ? "Пользователь найден!"
                : dbService.addUser(id, username);
    }
}
