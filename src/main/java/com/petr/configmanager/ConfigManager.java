package com.petr.configmanager;

import io.github.natanimn.telebof.BotContext;
import io.github.natanimn.telebof.types.updates.Message;

import java.io.IOException;

public interface ConfigManager {
    String getAllConfigs();
    String[] getConfigs(Long userId, String username) throws IOException, InterruptedException;
    String deleteConfig(String configName);
    String onStart(Long id, String username);
}
