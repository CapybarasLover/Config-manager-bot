package com.petr.panel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petr.configmanager.ConfigType;
import com.petr.panel.ApiRequests;
import com.petr.panel.ApiRequestsImpl;
import com.petr.panel.PanelConfig;
import com.petr.panel.dto.PanelClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Единая реализация PanelService. Логика одинакова для всех панелей —
 * различается только {@link PanelConfig} (ID inbound'ов, sub-база, метка).
 */
public class PanelServiceImpl implements PanelService {

    private final String label;
    private final ApiRequests api;
    private final ObjectMapper mapper = new ObjectMapper();

    private final int wsInbound;
    private final Integer xhttpInbound; // null → XHTTP не используется
    private final String subBaseUrl;

    public PanelServiceImpl(PanelConfig cfg) {
        this.label = cfg.label;
        this.api = new ApiRequestsImpl(cfg);
        this.wsInbound = cfg.wsInbound;
        this.xhttpInbound = cfg.xhttpInbound;
        this.subBaseUrl = cfg.subBaseUrl;
        System.out.println("[Panel:" + label + "] WS inbound=" + wsInbound
                + ", XHTTP inbound=" + xhttpInbound + ", subBaseUrl=" + subBaseUrl);
    }

    @Override
    public String listClients() throws IOException, InterruptedException {
        return api.listClients().body();
    }

    @Override
    public String deleteClient(String email) throws IOException, InterruptedException {
        return api.deleteClient(email);
    }

    @Override
    public String[] createClient(String email, long tgId, ConfigType type)
            throws IOException, InterruptedException {

        List<Integer> targetIds = targetInboundIds(type);
        if (targetIds.isEmpty()) {
            System.out.println("[Panel:" + label + "] нет целевых inbound'ов для type=" + type + " — пропуск");
            return new String[]{null, null, null};
        }

        JsonNode existing = api.getClient(email);
        String subId;

        if (existing == null || existing.isMissingNode() || existing.isNull()) {
            UUID uuid = UUID.randomUUID();
            subId = UUID.randomUUID().toString();
            api.addClient(targetIds, uuid, subId, email, tgId);
        } else {
            subId = existing.path("client").path("subId").asText("");
            Set<Integer> current = new HashSet<>();
            for (JsonNode n : existing.path("inboundIds")) {
                current.add(n.asInt());
            }
            List<Integer> missing = new ArrayList<>();
            for (Integer id : targetIds) {
                if (!current.contains(id)) {
                    missing.add(id);
                }
            }
            if (!missing.isEmpty()) {
                api.attachClient(email, missing);
            }
            System.out.println("[Panel:" + label + "] клиент " + email + " уже на панели, subId=" + subId
                    + ", привязано доп. inbounds=" + missing);
        }

        return buildLinks(email, subId);
    }

    @Override
    public List<PanelClient> getClients() throws IOException, InterruptedException {
        String body = api.listClients().body();
        JsonNode root = mapper.readTree(body);

        List<PanelClient> result = new ArrayList<>();
        if (!root.path("success").asBoolean(false)) {
            return result;
        }

        JsonNode arr = root.path("obj");
        if (!arr.isArray()) {
            return result;
        }

        for (JsonNode rec : arr) {
            long tgId = rec.path("tgId").asLong(0);
            if (tgId == 0) {
                continue;
            }
            String email = rec.path("email").asText("");
            String subId = rec.path("subId").asText("");
            if (email.isEmpty()) {
                continue;
            }

            String[] links = classifyLinks(api.getClientLinks(email));
            String subLink = subId.isEmpty() ? null : createSubLink(subId);

            result.add(new PanelClient(tgId, email, links[0], subLink, links[1]));
        }

        return result;
    }

    @Override
    public String mergeInbounds() throws IOException, InterruptedException {
        if (xhttpInbound == null) {
            return "[" + label + "] XHTTP inbound не настроен — объединение пропущено.";
        }

        JsonNode root = mapper.readTree(api.getInboundsList().body());
        if (!root.path("success").asBoolean(false)) {
            return "[" + label + "] inbounds/list вернул success=false.";
        }

        // Собираем email'ы, уже присутствующие в XHTTP-inbound, и список WS-клиентов
        Set<String> xhttpEmails = new HashSet<>();
        JsonNode wsClients = null;
        for (JsonNode inboundNode : root.path("obj")) {
            int id = inboundNode.path("id").asInt(-1);
            if (id == xhttpInbound) {
                for (JsonNode c : clientsOf(inboundNode)) {
                    xhttpEmails.add(c.path("email").asText(""));
                }
            } else if (id == wsInbound) {
                wsClients = clientsOf(inboundNode);
            }
        }

        if (wsClients == null) {
            return "[" + label + "] WS inbound id=" + wsInbound + " не найден.";
        }

        int attached = 0;
        int skipped = 0;
        int errors = 0;

        // Бот-конфиги опознаём по суффиксу `_config` (tgId в settings inbound'а не хранится)
        for (JsonNode c : wsClients) {
            String email = c.path("email").asText("");
            if (email.isEmpty() || !email.endsWith("_config")) {
                skipped++;
                continue;
            }
            if (xhttpEmails.contains(email)) {
                skipped++; // уже в XHTTP-inbound
                continue;
            }
            try {
                api.attachClient(email, List.of(xhttpInbound));
                attached++;
            } catch (Exception e) {
                errors++;
                System.out.println("[" + label + "] merge attach failed for " + email + ": " + e.getMessage());
            }
        }

        return String.format("[%s] Объединение inbound'ов: привязано=%d, пропущено=%d, ошибок=%d.",
                label, attached, skipped, errors);
    }

    /** Возвращает массив clients из settings inbound'а — settings может быть объектом или JSON-строкой. */
    private JsonNode clientsOf(JsonNode inboundNode) throws IOException {
        JsonNode s = inboundNode.path("settings");
        JsonNode settings = s.isObject() ? s : mapper.readTree(s.asText("{}"));
        return settings.path("clients");
    }

    // ── Хелперы ───────────────────────────────────────────────────────────────

    private List<Integer> targetInboundIds(ConfigType type) {
        List<Integer> ids = new ArrayList<>();
        if (type.includesWs()) {
            ids.add(wsInbound);
        }
        if (type.includesXhttp() && xhttpInbound != null) {
            ids.add(xhttpInbound);
        }
        return ids;
    }

    private String[] buildLinks(String email, String subId) throws IOException, InterruptedException {
        String[] links = classifyLinks(api.getClientLinks(email));
        String subLink = (subId == null || subId.isEmpty()) ? null : createSubLink(subId);
        return new String[]{links[0], subLink, links[1]};
    }

    /**
     * Раскладывает vless-ссылки панели на [wsLink, xhttpLink].
     * XHTTP определяется по {@code type=xhttp}/{@code type=splithttp};
     * всё остальное считается WS (WS никогда не теряется).
     */
    private static String[] classifyLinks(List<String> rawLinks) {
        String wsLink = null;
        String xhttpLink = null;
        for (String link : rawLinks) {
            if (link.contains("type=xhttp") || link.contains("type=splithttp")) {
                xhttpLink = link;
            } else {
                wsLink = link;
            }
        }
        return new String[]{wsLink, xhttpLink};
    }

    private String createSubLink(String subId) {
        return subBaseUrl + subId; // subBaseUrl уже оканчивается на "/"
    }
}
