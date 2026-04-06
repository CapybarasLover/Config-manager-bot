package com.petr.panel.service;

import com.petr.panel.ApiRequests;
import com.petr.panel.ApiRequestsGermImpl;
import com.petr.panel.ApiRequestsLatvImpl;

import java.io.IOException;
import java.util.UUID;

public class PanelServiceLatvImpl implements PanelService {
    private final ApiRequests api = new ApiRequestsLatvImpl();

    @Override
    public String listClients() throws IOException, InterruptedException {
        return "";
    }

    @Override
    public String deleteClient(String clientId) throws IOException, InterruptedException {
        return api.deleteClient("1", clientId);
    }

    @Override
    public String[] createClient(String clientName, long tgId) throws IOException, InterruptedException {
        UUID uuid = UUID.randomUUID();
        UUID subUuid = UUID.randomUUID();

        String subLink = createSubLink(subUuid);
        String vlessLink = createVlessLink(clientName, uuid);

        api.addClientRequest("2", uuid, subUuid, clientName, tgId);

        return new String[]{vlessLink, subLink};
    }

    private String createVlessLink(String clientName, UUID uuid) {
        return "vless://" + uuid + "@petromerzlikino.site:1235?type=ws&encryption=none" +
                "&path=%2F&host=&security=none#riga-"
                + clientName;
    }

    private String createSubLink(UUID uuid) {
        return "https://petromerzlikino.site:2096/sub/" + uuid;
    }
}
