package com.petr.configmanager;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface ConfigManager {
    /** Страны с настроенной панелью, в порядке кнопок. */
    List<String> availableCountries();

    /** В стране есть и WS, и xHTTP — пользователь выбирает тип конфига. */
    boolean offersTypeChoice(String country);

    /** Тип конфига, когда выбирать не из чего: "both" — все протоколы панели. */
    String defaultConfigType(String country);

    /** Протоколы страны для кнопок выбора ("ws" / "xhttp" / "reality"); кнопка «Все» — это "both". */
    List<String> configTypes(String country);

    String getAllConfigs() throws IOException, InterruptedException;

    String[] getConfigs(Long userId, String username, String configType, String country)
            throws IOException, InterruptedException;

    /**
     * Одобренные конфиги пользователя по всем странам: country → [ws, sub, xhttp, reality]. Пусто, если не одобрен.
     * Ссылки сверяются с панелью; если она недоступна — возвращаются сохранённые в БД.
     */
    Map<String, String[]> getConfigs(Long userId) throws IOException, InterruptedException;

    /** Страны, по которым заявка пользователя ждёт решения админа. */
    List<String> getWaitingCountries(Long userId);

    /** Страны, по которым заявка пользователя отклонена. */
    List<String> getRejectedCountries(Long userId);

    String getExistingConfigName(Long userId);

    String getExistingCountry(Long userId);

    String deleteConfig(String configName) throws IOException, InterruptedException;

    String onStart(Long id, String username);

    String getWaitingConfigs();

    /** @param country страна заявки; null — кнопка старого формата без страны, решение по всем ожидающим заявкам */
    Decision approveConfig(Long id, String country);

    /** @param country страна заявки; null — кнопка старого формата без страны, решение по всем ожидающим заявкам */
    Decision rejectConfig(Long id, String country);

    boolean isRegistered(long id);

    String listUsers();

    String syncFromPanel() throws IOException, InterruptedException;
}