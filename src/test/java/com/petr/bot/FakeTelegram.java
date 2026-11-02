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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фейковый Bot API. Воспроизводит ограничения настоящего Telegram, из-за которых падают
 * обработчики: «message is not modified», лимит 4096 символов, разбор legacy Markdown,
 * заблокировавшие бота пользователи и устаревшие callback-запросы.
 */
final class FakeTelegram {

    static final class Msg {
        final long chatId;
        final int id;
        String text;
        Map<String, String> buttons = new LinkedHashMap<>(); // текст → callback_data
        boolean deleted;

        Msg(long chatId, int id) {
            this.chatId = chatId;
            this.id = id;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final ConcurrentLinkedQueue<ObjectNode> updates = new ConcurrentLinkedQueue<>();
    private final AtomicInteger nextUpdate = new AtomicInteger(1);
    private final AtomicInteger nextMessage = new AtomicInteger(1000);
    private final AtomicInteger nextQuery = new AtomicInteger(1);

    final Map<String, Msg> messages = new ConcurrentHashMap<>();
    final List<Msg> history = Collections.synchronizedList(new ArrayList<>());
    final List<String> apiErrors = Collections.synchronizedList(new ArrayList<>());
    final Map<String, String> answers = new ConcurrentHashMap<>(); // id запроса → текст всплывашки
    final Set<String> sentQueries = ConcurrentHashMap.newKeySet();
    final Set<Long> blocked = ConcurrentHashMap.newKeySet();
    final Set<Long> unknownChats = ConcurrentHashMap.newKeySet();
    volatile boolean queriesTooOld = false;
    final AtomicInteger photos = new AtomicInteger();

    FakeTelegram(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    void reset() {
        messages.clear();
        history.clear();
        apiErrors.clear();
        answers.clear();
        sentQueries.clear();
        blocked.clear();
        unknownChats.clear();
        queriesTooOld = false;
        photos.set(0);
    }

    boolean idle() {
        return updates.isEmpty();
    }

    // ── Действия пользователя ─────────────────────────────────────────────────

    void say(long userId, String username, String text) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("message_id", nextMessage.incrementAndGet());
        msg.put("date", System.currentTimeMillis() / 1000);
        msg.set("chat", chat(userId, username));
        msg.set("from", user(userId, username));
        msg.put("text", text);
        if (text.startsWith("/")) {
            int len = text.contains(" ") ? text.indexOf(' ') : text.length();
            ArrayNode entities = mapper.createArrayNode();
            ObjectNode e = mapper.createObjectNode();
            e.put("type", "bot_command");
            e.put("offset", 0);
            e.put("length", len);
            entities.add(e);
            msg.set("entities", entities);
        }
        ObjectNode update = mapper.createObjectNode();
        update.set("message", msg);
        enqueue(update);
    }

    /** Нажимает кнопку сообщения как есть, даже если бот его уже отредактировал (устаревший клиент). */
    String click(long userId, String username, int messageId, String text, String data) {
        ObjectNode message = mapper.createObjectNode();
        message.put("message_id", messageId);
        message.put("date", System.currentTimeMillis() / 1000);
        message.set("chat", chat(userId, username));
        message.put("text", text);
        ObjectNode query = mapper.createObjectNode();
        String id = "q" + nextQuery.incrementAndGet();
        query.put("id", id);
        query.set("from", user(userId, username));
        query.set("message", message);
        query.put("chat_instance", "ci" + userId);
        query.put("data", data);
        ObjectNode update = mapper.createObjectNode();
        update.set("callback_query", query);
        sentQueries.add(id);
        enqueue(update);
        return id;
    }

    private void enqueue(ObjectNode update) {
        update.put("update_id", nextUpdate.incrementAndGet());
        updates.add(update);
        Activity.touch();
    }

    private ObjectNode chat(long id, String username) {
        ObjectNode chat = mapper.createObjectNode();
        chat.put("id", id);
        chat.put("type", "private");
        chat.put("first_name", "U" + id);
        if (username != null) chat.put("username", username);
        return chat;
    }

    private ObjectNode user(long id, String username) {
        ObjectNode u = mapper.createObjectNode();
        u.put("id", id);
        u.put("is_bot", false);
        u.put("first_name", "U" + id);
        if (username != null) u.put("username", username);
        return u;
    }

    // ── Bot API ───────────────────────────────────────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replaceAll("/+$", "");
        String method = path.substring(path.lastIndexOf('/') + 1);
        if (!method.equalsIgnoreCase("getUpdates")) Activity.touch();
        byte[] raw = ex.getRequestBody().readAllBytes();
        try {
            switch (method) {
                case "getUpdates" -> getUpdates(ex);
                case "getMe" -> ok(ex, botUser());
                case "setMyCommands", "deleteWebhook" -> ok(ex, mapper.getNodeFactory().booleanNode(true));
                case "sendMessage" -> sendMessage(ex, mapper.readTree(raw));
                case "editMessageText" -> editMessageText(ex, mapper.readTree(raw));
                case "deleteMessage" -> deleteMessage(ex, mapper.readTree(raw));
                case "answerCallbackQuery" -> answer(ex, mapper.readTree(raw));
                case "sendPhoto" -> {
                    photos.incrementAndGet();
                    ok(ex, messageJson(new Msg(0, nextMessage.incrementAndGet())));
                }
                default -> error(ex, method, 404, "Not Found: path " + ex.getRequestURI());
            }
        } finally {
            if (!method.equalsIgnoreCase("getUpdates")) Activity.touch();
        }
    }

    private void getUpdates(HttpExchange ex) throws IOException {
        long deadline = System.currentTimeMillis() + 100;
        while (updates.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        ArrayNode result = mapper.createArrayNode();
        ObjectNode u;
        while ((u = updates.poll()) != null) {
            result.add(u);
        }
        if (!result.isEmpty()) Activity.touch();
        ok(ex, result);
    }

    private void sendMessage(HttpExchange ex, JsonNode body) throws IOException {
        long chatId = body.path("chat_id").asLong();
        if (blocked.contains(chatId)) {
            error(ex, "sendMessage", 403, "Forbidden: bot was blocked by the user");
            return;
        }
        if (unknownChats.contains(chatId)) {
            error(ex, "sendMessage", 400, "Bad Request: chat not found");
            return;
        }
        String text = body.path("text").asText();
        String problem = validate(text, body);
        if (problem != null) {
            error(ex, "sendMessage", 400, problem);
            return;
        }
        Msg m = new Msg(chatId, nextMessage.incrementAndGet());
        m.text = text;
        m.buttons = parseButtons(body);
        messages.put(key(chatId, m.id), m);
        history.add(m);
        ok(ex, messageJson(m));
    }

    private void editMessageText(HttpExchange ex, JsonNode body) throws IOException {
        long chatId = body.path("chat_id").asLong();
        int id = body.path("message_id").asInt();
        Msg m = messages.get(key(chatId, id));
        if (m == null || m.deleted) {
            error(ex, "editMessageText", 400, "Bad Request: message to edit not found");
            return;
        }
        String text = body.path("text").asText();
        String problem = validate(text, body);
        if (problem != null) {
            error(ex, "editMessageText", 400, problem);
            return;
        }
        Map<String, String> buttons = parseButtons(body);
        if (text.equals(m.text) && buttons.equals(m.buttons)) {
            error(ex, "editMessageText", 400, "Bad Request: message is not modified");
            return;
        }
        m.text = text;
        m.buttons = buttons;
        ok(ex, messageJson(m));
    }

    private void deleteMessage(HttpExchange ex, JsonNode body) throws IOException {
        Msg m = messages.get(key(body.path("chat_id").asLong(), body.path("message_id").asInt()));
        if (m != null) m.deleted = true;
        ok(ex, mapper.getNodeFactory().booleanNode(true));
    }

    private void answer(HttpExchange ex, JsonNode body) throws IOException {
        String id = body.path("callback_query_id").asText();
        if (queriesTooOld) {
            error(ex, "answerCallbackQuery", 400, "Bad Request: query is too old and response timeout expired or query ID is invalid");
            return;
        }
        answers.put(id, body.path("text").asText(""));
        ok(ex, mapper.getNodeFactory().booleanNode(true));
    }

    /** Те же отказы, что и у Telegram: длина и legacy Markdown. */
    private String validate(String text, JsonNode body) {
        if (text.isEmpty()) return "Bad Request: message text is empty";
        if (text.length() > 4096) return "Bad Request: message is too long";
        String mode = body.path("parse_mode").asText("");
        if (mode.equalsIgnoreCase("markdown")) {
            String err = markdownError(text);
            if (err != null) return "Bad Request: can't parse entities: " + err;
        }
        return null;
    }

    static String markdownError(String t) {
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && "_*`[".indexOf(t.charAt(i + 1)) >= 0) {
                i += 2;
            } else if (c == '`' || c == '*' || c == '_') {
                int end = t.indexOf(c, i + 1);
                if (end < 0) return "unclosed " + c + " at " + i;
                i = end + 1;
            } else if (c == '[') {
                int end = t.indexOf(']', i + 1);
                if (end < 0) return "unclosed [ at " + i;
                i = end + 1;
            } else {
                i++;
            }
        }
        return null;
    }

    private Map<String, String> parseButtons(JsonNode body) throws IOException {
        Map<String, String> buttons = new LinkedHashMap<>();
        JsonNode markup = body.path("reply_markup");
        if (markup.isTextual()) markup = mapper.readTree(markup.asText());
        for (JsonNode row : markup.path("inline_keyboard")) {
            for (JsonNode b : row) {
                buttons.put(b.path("text").asText(), b.path("callback_data").asText());
            }
        }
        return buttons;
    }

    private static String key(long chatId, int id) {
        return chatId + ":" + id;
    }

    private ObjectNode botUser() {
        ObjectNode me = mapper.createObjectNode();
        me.put("id", 1);
        me.put("is_bot", true);
        me.put("first_name", "bot");
        me.put("username", "test_bot");
        return me;
    }

    private ObjectNode messageJson(Msg m) {
        ObjectNode j = mapper.createObjectNode();
        j.put("message_id", m.id);
        j.put("date", System.currentTimeMillis() / 1000);
        j.set("chat", chat(m.chatId, null));
        j.set("from", botUser());
        if (m.text != null) j.put("text", m.text);
        return j;
    }

    private void ok(HttpExchange ex, JsonNode result) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("ok", true);
        root.set("result", result);
        respond(ex, 200, root.toString());
    }

    private void error(HttpExchange ex, String method, int code, String description) throws IOException {
        apiErrors.add(method + ": " + description);
        ObjectNode root = mapper.createObjectNode();
        root.put("ok", false);
        root.put("error_code", code);
        root.put("description", description);
        respond(ex, code, root.toString());
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
