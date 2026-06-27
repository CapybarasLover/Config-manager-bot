package com.petr.panel;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

/**
 * Client-centric API 3x-ui (≥ v3.2.5): клиент — самостоятельная сущность
 * (один email/UUID/subId), привязываемая к нескольким inbound'ам.
 * Эндпоинты под /panel/api/clients/* принимают/отдают JSON.
 */
public interface ApiRequests {

    /** POST /panel/api/clients/add — создать клиента сразу в нескольких inbound'ах. */
    String addClient(List<Integer> inboundIds, UUID uuid, String subId, String email, long tgId)
            throws IOException, InterruptedException;

    /** POST /panel/api/clients/{email}/attach — привязать существующего клиента к ещё одному inbound'у. */
    String attachClient(String email, List<Integer> inboundIds)
            throws IOException, InterruptedException;

    /** POST /panel/api/clients/del/{email} — удалить клиента из всех inbound'ов сразу. */
    String deleteClient(String email) throws IOException, InterruptedException;

    /** GET /panel/api/clients/get/{email} — клиент + его inboundIds (obj), либо null если не найден. */
    JsonNode getClient(String email) throws IOException, InterruptedException;

    /** GET /panel/api/clients/links/{email} — готовые vless-ссылки по всем привязанным inbound'ам. */
    List<String> getClientLinks(String email) throws IOException, InterruptedException;

    /** GET /panel/api/clients/list — все клиенты (ClientRecord: email/subId/tgId). */
    HttpResponse<String> listClients() throws IOException, InterruptedException;

    /** GET /panel/api/inbounds/list — список inbound'ов (нужен скрипту объединения). */
    HttpResponse<String> getInboundsList() throws IOException, InterruptedException;
}
