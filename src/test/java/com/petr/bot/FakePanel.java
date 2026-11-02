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
 */
final class FakePanel {

    static final class Client {
        final String email;
        final String uuid;
        final String subId;
        long tgId;
        final TreeSet<Integer> inbounds = new TreeSet<>();

        Client(String email, String uuid, String subId, long tgId, Integer... inbounds) {
            this.email = email;
            this.uuid = uuid;
            this.subId = subId;
            this.tgId = tgId;
            Collections.addAll(this.inbounds, inbounds);
        }
    }

    private record Inbound(int port, String network, String security) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final String token;
    private final String host;
    private final Map<Integer, Inbound> inbounds = new TreeMap<>();
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
        inbounds.put(id, new Inbound(port, network, security));
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

    String link(Client c, int inboundId) {
        Inbound in = inbounds.get(inboundId);
        String extra = in.security.equals("reality") ? "&pbk=PBK&fp=chrome&sni=www.sap.com&sid=ab12&spx=%2F" : "";
        return "vless://" + c.uuid + "@" + host + ":" + in.port + "?type=" + in.network
                + "&encryption=none&security=" + in.security + extra + "#inb" + inboundId + "-" + c.email;
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
        for (JsonNode id : mapper.readTree(body).path("inboundIds")) {
            if (!inbounds.containsKey(id.asInt())) {
                fail(ex, "inbound not found: " + id.asInt());
                return;
            }
            c.inbounds.add(id.asInt());
        }
        writes.add("attach " + email + " " + c.inbounds);
        ok(ex, null);
    }

    private ObjectNode record(Client c) {
        ObjectNode rec = mapper.createObjectNode();
        rec.put("id", c.uuid);
        rec.put("email", c.email);
        rec.put("subId", c.subId);
        rec.put("tgId", c.tgId);
        rec.put("flow", "");
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
