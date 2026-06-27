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
     * @param type  WS / XHTTP / BOTH
     * @return [wsVlessLink, subLink, xhttpVlessLink] — null для отсутствующих типов
     */
    String[] createClient(String email, long tgId, ConfigType type)
            throws IOException, InterruptedException;

    /** Удаляет клиента из всех inbound'ов сразу. */
    String deleteClient(String email) throws IOException, InterruptedException;

    /** Клиенты панели для синхронизации с БД. */
    List<PanelClient> getClients() throws IOException, InterruptedException;

    /**
     * Разовое объединение inbound'ов: привязывает каждого WS-клиента (с tgId)
     * к XHTTP-inbound'у, приводя его к client-centric модели. Старые отдельные
     * XHTTP-клиенты не трогает. Возвращает отчёт.
     */
    String mergeInbounds() throws IOException, InterruptedException;
}
