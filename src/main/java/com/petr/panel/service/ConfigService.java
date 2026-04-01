package com.petr.panel.service;

public interface ConfigService {
    String listClients();
    String deleteClient(String clientId);
    String createClient(String clientId); // Этот метод должен вызывать сохранение клиента на сервер, или возвращать готовый конфиг из бд
}
