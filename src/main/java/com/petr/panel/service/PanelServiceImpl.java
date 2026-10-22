package com.petr.panel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petr.configmanager.ConfigType;
import com.petr.panel.ApiRequests;
import com.petr.panel.ApiRequestsImpl;
import com.petr.panel.PanelConfig;
import com.petr.panel.dto.PanelClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Единая реализация PanelService. Логика одинакова для всех панелей —
 * различается только {@link PanelConfig} (ID inbound'ов, sub-база, метка).
 */
public class PanelServiceImpl implements PanelService {

    /** host:port после '@' (IPv6 в квадратных скобках тоже поддерживается). */
    private static final Pattern LINK_PORT = Pattern.compile("@(?:\\[[^\\]]+\\]|[^:/?#@\\[\\]]+):(\\d+)");

    private final String label;
    private final ApiRequests api;
    private final ObjectMapper mapper = new ObjectMapper();

    private final int wsInbound;
    private final Integer xhttpInbound; // null → XHTTP не используется
    private final Integer realityInbound; // null → Reality не используется
    private final String subBaseUrl;

    public PanelServiceImpl(PanelConfig cfg) {
        this.label = cfg.label;
        this.api = new ApiRequestsImpl(cfg);
        this.wsInbound = cfg.wsInbound;
        this.xhttpInbound = cfg.xhttpInbound;
        this.realityInbound = cfg.realityInbound;
        this.subBaseUrl = cfg.subBaseUrl;
        System.out.println("[Panel:" + label + "] WS inbound=" + wsInbound
                + ", XHTTP inbound=" + xhttpInbound + ", REALITY inbound=" + realityInbound
                + ", subBaseUrl=" + subBaseUrl);
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
            return new String[]{null, null, null, null};
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

        String[] links = classifyLinks(api.getClientLinks(email), inboundPorts());
        String subLink = (subId == null || subId.isEmpty()) ? null : createSubLink(subId);
        return new String[]{links[0], subLink, links[1], links[2]};
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

        Map<Integer, Integer> ports = inboundPorts();
        for (JsonNode rec : arr) {
            long tgId = rec.path("tgId").asLong(0);
            String email = rec.path("email").asText("");
            String subId = rec.path("subId").asText("");
            // Без tgId берём только бот-конфиги (`_config`) — их DbService сопоставит по имени
            if (email.isEmpty() || (tgId == 0 && !email.endsWith("_config"))) {
                continue;
            }

            String[] links = classifyLinks(api.getClientLinks(email), ports);
            String subLink = subId.isEmpty() ? null : createSubLink(subId);

            result.add(new PanelClient(tgId, email, links[0], subLink, links[1], links[2]));
        }

        return result;
    }

    @Override
    public String mergeInbounds() throws IOException, InterruptedException {
        JsonNode root = mapper.readTree(api.listClients().body());
        if (!root.path("success").asBoolean(false)) {
            return "[" + label + "] clients/list вернул success=false.";
        }

        int attached = 0;
        int skipped = 0;
        int errors = 0;

        for (JsonNode rec : root.path("obj")) {
            String email = rec.path("email").asText("");
            // Бот-конфиги опознаём по суффиксу `_config` или по tgId
            boolean botClient = email.endsWith("_config") || rec.path("tgId").asLong(0) != 0;
            if (email.isEmpty() || !botClient) {
                skipped++;
                continue;
            }

            Set<Integer> current = new HashSet<>();
            for (JsonNode n : rec.path("inboundIds")) {
                current.add(n.asInt());
            }

            List<Integer> missing = new ArrayList<>();
            // WS-клиенты получают XHTTP (объединение WS+XHTTP под одного клиента)
            if (xhttpInbound != null && current.contains(wsInbound) && !current.contains(xhttpInbound)) {
                missing.add(xhttpInbound);
            }
            // Reality inbound добавляется всем бот-клиентам
            if (realityInbound != null && !current.contains(realityInbound)) {
                missing.add(realityInbound);
            }

            if (missing.isEmpty()) {
                skipped++;
                continue;
            }
            try {
                api.attachClient(email, missing);
                attached++;
            } catch (Exception e) {
                errors++;
                System.out.println("[" + label + "] merge attach failed for " + email + ": " + e.getMessage());
            }
        }

        return String.format("[%s] Объединение inbound'ов: дополнено клиентов=%d, пропущено=%d, ошибок=%d.",
                label, attached, skipped, errors);
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
        if (realityInbound != null && !ids.contains(realityInbound)) {
            ids.add(realityInbound);
        }
        return ids;
    }

    /** Порт каждого настроенного inbound'а (inboundId → port). Недоступные inbound'ы пропускаются. */
    private Map<Integer, Integer> inboundPorts() {
        Map<Integer, Integer> ports = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        ids.add(wsInbound);
        if (xhttpInbound != null) ids.add(xhttpInbound);
        if (realityInbound != null) ids.add(realityInbound);

        for (Integer id : ids) {
            try {
                JsonNode inbound = api.getInbound(id);
                int port = inbound == null ? 0 : inbound.path("port").asInt(0);
                if (port > 0) {
                    ports.put(id, port);
                }
            } catch (Exception e) {
                System.out.println("[Panel:" + label + "] не удалось получить порт inbound=" + id + ": " + e.getMessage());
            }
        }
        return ports;
    }

    /**
     * Раскладывает ссылки панели на [wsLink, xhttpLink, realityLink].
     * Сначала сопоставляем по порту inbound'а (однозначно, если порты различны),
     * иначе — по типу транспорта: {@code type=xhttp}/{@code type=splithttp} → XHTTP,
     * {@code type=ws} → WS, прочее → Reality (если он настроен), иначе WS.
     */
    private String[] classifyLinks(List<String> rawLinks, Map<Integer, Integer> ports) {
        Integer wsPort = ports.get(wsInbound);
        Integer xhttpPort = xhttpInbound == null ? null : ports.get(xhttpInbound);
        Integer realityPort = realityInbound == null ? null : ports.get(realityInbound);

        String[] result = new String[3]; // 0=ws, 1=xhttp, 2=reality
        for (String link : rawLinks) {
            int slot = slotByPort(linkPort(link), wsPort, xhttpPort, realityPort);
            if (slot < 0) {
                slot = slotByType(link);
            }
            if (result[slot] == null) {
                result[slot] = link; // первая ссылка inbound'а — основная
            }
        }
        return result;
    }

    private static int slotByPort(Integer port, Integer wsPort, Integer xhttpPort, Integer realityPort) {
        if (port == null) return -1;
        int slot = -1;
        int matches = 0;
        if (port.equals(wsPort)) { slot = 0; matches++; }
        if (port.equals(xhttpPort)) { slot = 1; matches++; }
        if (port.equals(realityPort)) { slot = 2; matches++; }
        return matches == 1 ? slot : -1; // одинаковые порты у inbound'ов — решаем по типу
    }

    private int slotByType(String link) {
        if (link.contains("type=xhttp") || link.contains("type=splithttp")) return 1;
        if (link.contains("type=ws")) return 0;
        return realityInbound != null ? 2 : 0;
    }

    /** Порт из ссылки: vmess — base64-JSON, остальные — host:port после '@'. */
    private Integer linkPort(String link) {
        try {
            if (link.startsWith("vmess://")) {
                String b64 = link.substring("vmess://".length());
                int hash = b64.indexOf('#');
                if (hash >= 0) b64 = b64.substring(0, hash);
                String json = new String(Base64.getDecoder().decode(b64.trim()), StandardCharsets.UTF_8);
                int port = mapper.readTree(json).path("port").asInt(0);
                return port > 0 ? port : null;
            }
            Matcher m = LINK_PORT.matcher(link);
            return m.find() ? Integer.valueOf(m.group(1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String createSubLink(String subId) {
        return subBaseUrl + subId; // subBaseUrl уже оканчивается на "/"
    }
}
