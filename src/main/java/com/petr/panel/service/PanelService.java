package com.petr.panel.service;

import java.io.IOException;

public interface PanelService {
    String listClients() throws IOException, InterruptedException;
    String deleteClient(String clientId) throws IOException, InterruptedException;
    String[] createClient(String clientName, long tgId) throws IOException, InterruptedException; // Этот метод должен вызывать сохранение клиента на сервер, или возвращать готовый конфиг из бд
}
