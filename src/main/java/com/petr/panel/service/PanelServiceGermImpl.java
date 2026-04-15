package com.petr.panel.service;

import com.petr.panel.ApiRequests;
import com.petr.panel.ApiRequestsGermImpl;

import java.io.IOException;
import java.util.UUID;

public class PanelServiceGermImpl implements PanelService {
    private final ApiRequests api = new ApiRequestsGermImpl();
    private String inbound;
    public PanelServiceGermImpl(){
        inbound = "1";
    }

    private String createVlessLink(String clientName, UUID uuid) {
        return "vless://" + uuid
                + "@bimbambom.site:443?type=tcp&encryption=none&security=reality"
                + "&pbk=qlrfiL3_hu1kNDIWCE7rsXD5XXE0jAhlHLjmMgEIcD4"
                + "&fp=chrome&sni=www.microsoft.com&sid=651dc697&spx=%2F&flow=xtls-rprx-vision#Germany-"
                + clientName;
    }

    private String createSubLink(UUID uuid) {
        return String.format("http://bimbambom.site:2096/sub/%s", uuid);
    }

    @Override
    public String listClients() throws IOException, InterruptedException {
        return api.getAllConfigsRequest().body();
    } // TODO написать десериализацию возвращаемого JSON

    @Override
    public String deleteClient(String clientName) throws IOException, InterruptedException {
        return api.deleteClient(inbound, clientName);
    }

    @Override
    public String[] createClient(String clientName, long tgId) throws IOException, InterruptedException {
        UUID uuid = UUID.randomUUID();
        UUID subUuid = UUID.randomUUID();

        String subLink = createSubLink(uuid);
        String vlessLink = createVlessLink(clientName, subUuid);

        api.addClientRequest(inbound, uuid, subUuid, clientName, tgId);

        return new String[]{vlessLink, subLink};
    }
}
