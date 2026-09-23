package com.petr.configmanager;

import java.io.IOException;
import java.util.Map;

public interface ConfigManager {
    String getAllConfigs() throws IOException, InterruptedException;

    String[] getConfigs(Long userId, String username, String configType, String country)
            throws IOException, InterruptedException;

    /** Одобренные конфиги пользователя по всем странам: country → [ws, sub, xhttp, reality]. Пусто, если не одобрен. */
    Map<String, String[]> getConfigs(Long userId) throws IOException, InterruptedException;

    String getExistingConfigName(Long userId);

    String getExistingCountry(Long userId);

    String deleteConfig(String configName) throws IOException, InterruptedException;

    String onStart(Long id, String username);

    String getWaitingConfigs();

    String acceptConfig(Long id);

    boolean isRegistered(long id);

    String listUsers();

    String syncFromPanel() throws IOException, InterruptedException;
}