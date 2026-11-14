package com.petr.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Эмулятор client-centric API 3x-ui. Форма ответов снята с боевых панелей v3.8.5:
 * email регистрозависим, «нет клиента» — это 200 + success=false, повторный subId при
 * создании отклоняется, а attach дубли subId (старые пары _config/_xhttp) пропускает.
 * flow хранится по inbound'ам, как в v3.8.5–v3.11: при add/attach он снимается там, где Vision
 * невозможен (только TCP + Reality/TLS или VLESS Encryption), attach берёт flow с уже привязанных
 * inbound'ов, а bulkAdjust ставит его на все подходящие inbound'ы клиента.
 */
final class FakePanel {

    static final class Client {
        final String email;
        final String uuid;
        final String subId;
        long tgId;
        final TreeSet<Integer> inbounds = new TreeSet<>();
        /** flow на каждом inbound'е; нет записи — пустой flow (старые клиенты). */
        final Map<Integer, String> flows = new ConcurrentHashMap<>();

        Client(String email, String uuid, String subId, long tgId, Integer... inbounds) {
            this.email = email;
            this.uuid = uuid;
            this.subId = subId;
            this.tgId = tgId;
            Collections.addAll(this.inbounds, inbounds);
        }
    }

    private record Inbound(int port, String network, String security, boolean vlessEncryption) {
        boolean visionEligible() {
            return vlessEncryption || (network.equals("tcp") && (security.equals("reality") || security.equals("tls")));
        }
    }

    static final String VISION = "xtls-rprx-vision";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String token;
    private final String host;
    private final Map<Integer, Inbound> inbounds = new TreeMap<>();
    /** Дочерний inbound fallback'а (слушает 127.0.0.1) → главный, через который его видят клиенты. */
    private final Map<Integer, Integer> fallbackMaster = new TreeMap<>();
    final Map<String, Client> clients = new ConcurrentHashMap<>();
    final List<String> writes = Collections.synchronizedList(new ArrayList<>());
    private final HttpServer server;

    /** Если не 0 — все запросы отвечают этим HTTP-статусом (панель «лежит»). */
    volatile int failStatus = 0;
    /** Если не null — запросы висят, пока latch не отпустят (панель не отвечает). */
    volatile CountDownLatch hang = null;
    /** Задержка на создание клиента — расширяет окно гонки при двойном нажатии. */
    volatile long addDelayMs = 0;

    FakePanel(int port, String token, String host) throws IOException {
        this.token = token;
        this.host = host;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    FakePanel inbound(int id, int port, String network, String security) {
        return inbound(id, port, network, security, false);
    }

    /** vlessEncryption — VLESS Encryption (mlkem768x25519plus): Vision доступен на любом транспорте. */
    FakePanel inbound(int id, int port, String network, String security, boolean vlessEncryption) {
        inbounds.put(id, new Inbound(port, network, security, vlessEncryption));
        return this;
    }

    /**
     * Inbound {@code childId} — fallback inbound'а {@code masterId}, как в 3x-ui 3.11: сам он слушает
     * localhost без TLS, а в ссылке панель подставляет порт и Reality главного. inbounds/get при этом
     * отдаёт собственный порт дочернего.
     */
    FakePanel fallback(int childId, int masterId) {
        fallbackMaster.put(childId, masterId);
        return this;
    }

    void reset() {
        clients.clear();
        writes.clear();
        failStatus = 0;
        addDelayMs = 0;
        CountDownLatch h = hang;
        hang = null;
        if (h != null) h.countDown();
    }

    Client put(String email, String uuid, String subId, long tgId, Integer... ids) {
        Client c = new Client(email, uuid, subId, tgId, ids);
        clients.put(email, c);
        return c;
    }

    TreeSet<Integer> inboundsOf(String email) {
        Client c = clients.get(email);
        return c == null ? new TreeSet<>() : c.inbounds;
    }

    String flowOf(String email, int inboundId) {
        Client c = clients.get(email);
        return c == null ? "" : c.flows.getOrDefault(inboundId, "");
    }

    /** Как EffectiveFlow панели: первый непустой flow по возрастанию ID inbound'а. */
    private static String effectiveFlow(Client c) {
        for (int id : c.inbounds) {
            String f = c.flows.getOrDefault(id, "");
            if (!f.isEmpty()) return f;
        }
        return "";
    }

    private void bindFlow(Client c, int inboundId, String flow) {
        c.flows.put(inboundId, inbounds.get(inboundId).visionEligible() ? flow : "");
    }

    String link(Client c, int inboundId) {
        Inbound in = inbounds.get(inboundId);
        Integer masterId = fallbackMaster.get(inboundId);
        Inbound shown = masterId == null ? in : inbounds.get(masterId); // порт и TLS — главного
        String extra = shown.security.equals("reality") ? "&pbk=PBK&fp=chrome&sni=www.sap.com&sid=ab12&spx=%2F" : "";
        String flow = c.flows.getOrDefault(inboundId, "");
        if (!flow.isEmpty()) extra += "&flow=" + flow;
        String encryption = in.vlessEncryption ? "mlkem768x25519plus.native.0rtt.TEST" : "none";
        return "vless://" + c.uuid + "@" + host + ":" + shown.port + "?type=" + in.network
                + "&encryption=" + encryption + "&security=" + shown.security + extra + "#inb" + inboundId + "-" + c.email;
    }

    private void handle(HttpExchange ex) throws IOException {
        Activity.touch();
        try {
            CountDownLatch h = hang;
            if (h != null) {
                h.await();
            }
            if (failStatus != 0) {
                respond(ex, failStatus, "{\"panel\":\"internal failure at http://secret-panel-host/\"}");
                return;
            }
            if (!("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                respond(ex, 401, "{}");
                return;
            }
            route(ex);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Activity.touch();
        }
    }

    private void route(HttpExchange ex) throws IOException, InterruptedException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (method.equals("GET") && path.startsWith("/panel/api/clients/get/")) {
            Client c = clients.get(path.substring("/panel/api/clients/get/".length()));
            if (c == null) {
                notFound(ex);
                return;
            }
            ObjectNode obj = mapper.createObjectNode();
            obj.set("client", record(c));
            obj.set("inboundIds", ids(c));
            obj.putNull("externalLinks");
            obj.putNull("tunnelAllowedIPs");
            obj.put("usedTraffic", 0);
            ok(ex, obj);
        } else if (method.equals("GET") && path.startsWith("/panel/api/clients/links/")) {
            Client c = clients.get(path.substring("/panel/api/clients/links/".length()));
            if (c == null) {
                notFound(ex);
                return;
            }
            ArrayNode arr = mapper.createArrayNode();
            for (int id : c.inbounds) arr.add(link(c, id));
            ok(ex, arr);
        } else if (method.equals("GET") && path.equals("/panel/api/clients/list")) {
            ArrayNode arr = mapper.createArrayNode();
            clients.values().stream().sorted((a, b) -> a.email.compareTo(b.email))
                    .forEach(c -> arr.add(record(c)));
            ok(ex, arr);
        } else if (method.equals("POST") && path.equals("/panel/api/clients/add")) {
            add(ex, body);
        } else if (method.equals("POST") && path.endsWith("/attach")) {
            attach(ex, path, body);
        } else if (method.equals("POST") && path.equals("/panel/api/clients/bulkAdjust")) {
            bulkAdjust(ex, body);
        } else if (method.equals("POST") && path.startsWith("/panel/api/clients/del/")) {
            String email = path.substring("/panel/api/clients/del/".length());
            if (clients.remove(email) == null) {
                notFound(ex);
                return;
            }
            writes.add("del " + email);
            ok(ex, null);
        } else if (method.equals("GET") && path.startsWith("/panel/api/inbounds/get/")) {
            int id = Integer.parseInt(path.substring("/panel/api/inbounds/get/".length()));
            Inbound in = inbounds.get(id);
            if (in == null) {
                notFound(ex);
                return;
            }
            ObjectNode obj = mapper.createObjectNode();
            obj.put("id", id);
            obj.put("port", in.port);
            ok(ex, obj);
        } else {
            respond(ex, 404, "{\"success\":false,\"msg\":\"no route " + path + "\"}");
        }
    }

    private synchronized void addChecked(HttpExchange ex, String email, JsonNode client, JsonNode inboundIds)
            throws IOException {
        for (Client other : clients.values()) {
            if (other.email.equalsIgnoreCase(email)) {
                fail(ex, "Email already in use: " + email);
                return;
            }
            if (other.subId.equals(client.path("subId").asText())) {
                fail(ex, "duplicate subId");
                return;
            }
        }
        Client c = new Client(email, client.path("id").asText(), client.path("subId").asText(), client.path("tgId").asLong());
        for (JsonNode id : inboundIds) {
            if (!inbounds.containsKey(id.asInt())) {
                fail(ex, "inbound not found: " + id.asInt());
                return;
            }
            c.inbounds.add(id.asInt());
            bindFlow(c, id.asInt(), client.path("flow").asText(""));
        }
        clients.put(email, c);
        writes.add("add " + email + " " + c.inbounds);
        ok(ex, null);
    }

    private void add(HttpExchange ex, String body) throws IOException, InterruptedException {
        JsonNode root = mapper.readTree(body);
        JsonNode client = root.path("client");
        if (addDelayMs > 0) Thread.sleep(addDelayMs);
        addChecked(ex, client.path("email").asText(), client, root.path("inboundIds"));
    }

    private synchronized void attach(HttpExchange ex, String path, String body) throws IOException {
        String email = path.substring("/panel/api/clients/".length(), path.length() - "/attach".length());
        Client c = clients.get(email);
        if (c == null) {
            notFound(ex);
            return;
        }
        String flow = effectiveFlow(c);
        for (JsonNode id : mapper.readTree(body).path("inboundIds")) {
            if (!inbounds.containsKey(id.asInt())) {
                fail(ex, "inbound not found: " + id.asInt());
                return;
            }
            if (c.inbounds.add(id.asInt())) {
                bindFlow(c, id.asInt(), flow);
            }
        }
        writes.add("attach " + email + " " + c.inbounds);
        ok(ex, null);
    }

    /** Только flow (addDays/addBytes = 0): "none" снимает, Vision ставится на подходящие inbound'ы. */
    private synchronized void bulkAdjust(HttpExchange ex, String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        String flow = root.path("flow").asText("");
        int adjusted = 0;
        ArrayNode skipped = mapper.createArrayNode();
        for (JsonNode e : root.path("emails")) {
            Client c = clients.get(e.asText());
            if (c == null) {
                skipped.add(mapper.createObjectNode().put("email", e.asText()).put("reason", "client not found"));
                continue;
            }
            boolean honored = false;
            for (int id : c.inbounds) {
                if (flow.equals("none")) {
                    c.flows.put(id, "");
                    honored = true;
                } else if (flow.startsWith(VISION) && inbounds.get(id).visionEligible()) {
                    c.flows.put(id, flow);
                    honored = true;
                }
            }
            if (honored) adjusted++;
            else skipped.add(mapper.createObjectNode().put("email", c.email).put("reason", "flow not supported on inbound"));
            writes.add("bulkAdjust " + c.email + " flow=" + flow);
        }
        ObjectNode obj = mapper.createObjectNode();
        obj.put("adjusted", adjusted);
        if (!skipped.isEmpty()) obj.set("skipped", skipped);
        ok(ex, obj);
    }

    private ObjectNode record(Client c) {
        ObjectNode rec = mapper.createObjectNode();
        rec.put("id", c.uuid);
        rec.put("email", c.email);
        rec.put("subId", c.subId);
        rec.put("tgId", c.tgId);
        rec.put("flow", effectiveFlow(c));
        rec.put("enable", true);
        rec.set("inboundIds", ids(c));
        return rec;
    }

    private ArrayNode ids(Client c) {
        ArrayNode arr = mapper.createArrayNode();
        c.inbounds.forEach(arr::add);
        return arr;
    }

    private void ok(HttpExchange ex, JsonNode obj) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("success", true);
        root.put("msg", "");
        if (obj == null) root.putNull("obj");
        else root.set("obj", obj);
        respond(ex, 200, root.toString());
    }

    private void notFound(HttpExchange ex) throws IOException {
        fail(ex, "Obtain (record not found)");
    }

    private void fail(HttpExchange ex, String msg) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("success", false);
        root.put("msg", msg);
        root.putNull("obj");
        respond(ex, 200, root.toString());
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
