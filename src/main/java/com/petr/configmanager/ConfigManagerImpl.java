package com.petr.configmanager;

import com.petr.db.DbService;
import com.petr.panel.service.PanelService;
import com.petr.panel.service.PanelServiceGermImpl;

public class ConfigManagerImpl implements ConfigManager {
    private final PanelService panelServiceGerm = new PanelServiceGermImpl();
    private final DbService dbService = new DbService();

    @Override
    public String getAllConfigs() {
        return panelServiceGerm.listClients();
    }

    //TODO по хорошему написать так же попытки получить запросом с панели, с локальной бд и возврат null или пустой строки
    @Override
    public String getClient(String clientName) {
        return ""; //TODO эта функция вызывается всегда - возвращает либо клиент из локальной бд либо null
    }

    @Override
    public String createClient(long tgId, String clientName) {
        return "";
    }

    @Override
    public String deleteClient(String clientName) {
        return "";
    }
}
