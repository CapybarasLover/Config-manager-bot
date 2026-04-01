package com.petr.panel;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.UUID;

public interface ApiRequests {
    String addClientRequest(String inboundId, UUID uuid, UUID subUuid, String name, double tgId) throws IOException, InterruptedException;
    HttpResponse<String> getAllConfigsRequest() throws IOException, InterruptedException;
    String deleteClient(String inboundId, String name);
}
