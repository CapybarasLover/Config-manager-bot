package com.petr.configmanager;

import com.petr.db.DbService;
import com.petr.panel.service.PanelService;

import com.petr.panel.service.PanelServiceLatvImpl;

import java.io.IOException;

public class ConfigManagerImpl implements ConfigManager {
    private final PanelService panelService = new PanelServiceLatvImpl();
    private final DbService dbService = new DbService();

    @Override
    public String getAllConfigs() throws IOException, InterruptedException {
        return panelService.listClients();
    }

    //TODO по хорошему написать так же попытки получить запросом с панели, с локальной бд и возврат null или пустой строки
    @Override
    public String[] getConfigs(Long userId, String username) throws IOException, InterruptedException {
        if(dbService.userHasAcceptedConfig(userId)){
            String[] configs = dbService.getConfigsById(userId);
            return configs;
        } else if(!dbService.userHasConfig(userId)){
            String[] configs = panelService.createClient(username, userId);

            System.out.println(dbService.setConfig(userId, username, configs[0], configs[1]));
            System.out.println(dbService.setUserHasConfig(userId, true));

            return new String[]{};
        } else {
            return new String[]{};
        }
    }

    public String[] getConfigs(Long userId) throws IOException, InterruptedException {
        if(dbService.userHasAcceptedConfig(userId)){
            String[] configs = dbService.getConfigsById(userId);
            return configs;
        } else {
            return new String[] {};
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
}
