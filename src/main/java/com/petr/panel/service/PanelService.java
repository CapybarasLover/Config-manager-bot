package com.petr.panel.service;

import com.petr.configmanager.ConfigType;
import com.petr.panel.dto.PanelClient;

import java.io.IOException;
import java.util.List;

public interface PanelService {

    /** Сырой список клиентов панели (для отладки/админ-вывода). */
    String listClients() throws IOException, InterruptedException;

    /**
     * Создаёт или дополняет клиента (client-centric): один email/UUID/subId,
     * привязанный к нужным inbound'ам. Если клиент уже есть — недостающие
     * inbound'ы привязываются (attach), UUID/subId переиспользуются.
     *
     * @param email конечный email клиента на панели (он же config_name)
     * Доп. inbound (если настроен) привязывается всегда, независимо от type.
     *
     * @param type  WS / XHTTP / BOTH
     * @return [wsLink, subLink, xhttpLink, realityLink] — null для отсутствующих
     */
    String[] createClient(String email, long tgId, ConfigType type)
            throws IOException, InterruptedException;

    /** Удаляет клиента из всех inbound'ов сразу. */
    String deleteClient(String email) throws IOException, InterruptedException;

    /** Клиенты панели для синхронизации с БД. */
    List<PanelClient> getClients() throws IOException, InterruptedException;

    /**
     * Разовое объединение inbound'ов: привязывает каждого WS-клиента бота
     * к XHTTP-inbound'у, а всех клиентов бота — к Reality inbound'у (попадает в
     * подписку). Ничего не удаляет, идемпотентно. Возвращает отчёт.
     */
    String mergeInbounds() throws IOException, InterruptedException;
}
