package com.petr.panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.petr.exception.DuplicateEmailException;
import com.petr.exception.RequestException;
import com.petr.exception.RetryAttemptsLeftException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Единая реализация client-centric API 3x-ui. Аутентификация — по API-токену
 * (заголовок {@code Authorization: Bearer <token>}): для не-браузерных клиентов
 * панель пропускает такие запросы мимо CSRF и сессии. Поведение одинаково для
 * всех панелей — отличается только {@link PanelConfig}.
 */
public class ApiRequestsImpl implements ApiRequests {

    private static final int MAX_RETRIES = 2;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // HTTP/2 вызывает проблемы с некоторыми 3x-ui
            .build();

    private final String tag;
    private final URI baseUri;
    private final String apiToken;

    public ApiRequestsImpl(PanelConfig cfg) {
        this.tag = "[Api:" + cfg.label + "]";
        this.baseUri = URI.create(cfg.baseUrl);
        this.apiToken = cfg.apiToken;
        System.out.println(tag + " baseUri=" + baseUri + " (auth: Bearer API-token)");
    }

    // ── Retry-обёртка ───────────────────────────────────────────────────────

    private <T> T executeWithRetry(ThrowingSupplier<T> request) throws IOException, InterruptedException {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < MAX_RETRIES) {
            try {
                return request.get();
            } catch (DuplicateEmailException ex) {
                throw ex; // бизнес-ошибка панели — retry не поможет
            } catch (RequestException ex) {
                lastException = ex;
                System.out.println(tag + " Запрос упал: " + ex.getMessage() + ", повтор...");
            } catch (Exception ex) {
                lastException = ex;
                System.out.println(tag + " Необработанное исключение: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            }
            attempts++;
        }

        String reason = lastException == null ? "неизвестная причина"
                : lastException.getClass().getSimpleName() + ": " + lastException.getMessage();
        throw new RetryAttemptsLeftException("Too many attempts. Last error: " + reason, MAX_RETRIES);
    }

    // ── client-centric API ────────────────────────────────────────────────────

    @Override
    public String addClient(List<Integer> inboundIds, UUID uuid, String subId, String email, long tgId)
            throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve("panel/api/clients/add");
            String payload = buildCreatePayload(inboundIds, uuid, subId, email, tgId);

            HttpResponse<String> response = client.send(jsonPost(url, payload), HttpResponse.BodyHandlers.ofString());
            System.out.println(tag + " addClient inbounds=" + inboundIds +
                    " status=" + response.statusCode() + " body=" + response.body());

            if (response.statusCode() != 200) {
                throw new RequestException(
                        "addClient HTTP " + response.statusCode() + " body=" + response.body(),
                        response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            if (json.path("success").asBoolean(false)) {
                return "конфиг удачно добавлен на панель (inbounds=" + inboundIds + ")!";
            }
            String msg = json.path("msg").asText("неизвестная ошибка панели");
            if (isDuplicateEmail(msg)) {
                throw new DuplicateEmailException(email);
            }
            throw new RequestException("addClient success=false: " + msg, 200);
        });
    }

    @Override
    public String attachClient(String email, List<Integer> inboundIds) throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve("panel/api/clients/" + encodePath(email) + "/attach");
            String payload = buildInboundIdsBody(inboundIds);

            HttpResponse<String> response = client.send(jsonPost(url, payload), HttpResponse.BodyHandlers.ofString());
            System.out.println(tag + " attachClient email=" + email + " inbounds=" + inboundIds +
                    " status=" + response.statusCode() + " body=" + response.body());

            if (response.statusCode() != 200) {
                throw new RequestException(
                        "attachClient HTTP " + response.statusCode() + " body=" + response.body(),
                        response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            if (json.path("success").asBoolean(false)) {
                return "клиент привязан к inbounds=" + inboundIds;
            }
            throw new RequestException("attachClient success=false: " +
                    json.path("msg").asText("неизвестная ошибка панели"), 200);
        });
    }

    @Override
    public String deleteClient(String email) throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve("panel/api/clients/del/" + encodePath(email));

            HttpResponse<String> response = client.send(
                    authed(url).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println(tag + " deleteClient email=" + email +
                    " status=" + response.statusCode() + " body=" + response.body());

            if (response.statusCode() != 200) {
                throw new RequestException("HTTP " + response.statusCode(), response.statusCode());
            }
            return "Клиент " + email + " удален!";
        });
    }

    @Override
    public JsonNode getClient(String email) throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve("panel/api/clients/get/" + encodePath(email));

            HttpResponse<String> response = client.send(authed(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            System.out.println(tag + " getClient email=" + email + " status=" + response.statusCode());

            if (response.statusCode() != 200) {
                throw new RequestException("HTTP " + response.statusCode() + " body=" + response.body(), response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            if (!json.path("success").asBoolean(false)) {
                return null; // клиента нет
            }
            return json.path("obj");
        });
    }

    @Override
    public List<String> getClientLinks(String email) throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve("panel/api/clients/links/" + encodePath(email));

            HttpResponse<String> response = client.send(authed(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            System.out.println(tag + " getClientLinks email=" + email + " status=" + response.statusCode());

            if (response.statusCode() != 200) {
                throw new RequestException("HTTP " + response.statusCode() + " body=" + response.body(), response.statusCode());
            }

            List<String> links = new ArrayList<>();
            JsonNode json = mapper.readTree(response.body());
            JsonNode obj = json.path("obj");
            if (obj.isArray()) {
                for (JsonNode link : obj) {
                    links.add(link.asText());
                }
            }
            return links;
        });
    }

    @Override
    public HttpResponse<String> listClients() throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve("panel/api/clients/list");
            System.out.println(tag + " listClients → " + url);

            HttpResponse<String> response = client.send(authed(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            System.out.println(tag + " listClients status=" + response.statusCode());

            if (response.statusCode() != 200) {
                throw new RequestException("HTTP " + response.statusCode() + " body=" + response.body(), response.statusCode());
            }
            return response;
        });
    }

    @Override
    public HttpResponse<String> getInboundsList() throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve("panel/api/inbounds/list");
            System.out.println(tag + " getInboundsList → " + url);

            HttpResponse<String> response = client.send(authed(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            System.out.println(tag + " getInboundsList status=" + response.statusCode());

            if (response.statusCode() != 200) {
                throw new RequestException("HTTP " + response.statusCode() + " body=" + response.body(), response.statusCode());
            }
            return response;
        });
    }

    // ── Хелперы ───────────────────────────────────────────────────────────────

    /** Builder с общими заголовками (Bearer-токен + User-Agent). */
    private HttpRequest.Builder authed(URI url) {
        return HttpRequest.newBuilder(url)
                .header("Authorization", "Bearer " + apiToken)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
    }

    private HttpRequest jsonPost(URI url, String body) {
        return authed(url)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
    }

    private String buildCreatePayload(List<Integer> inboundIds, UUID uuid, String subId, String email, long tgId) {
        ObjectNode clientNode = mapper.createObjectNode();
        clientNode.put("id", uuid.toString());
        clientNode.put("flow", "");
        clientNode.put("email", email);
        clientNode.put("limitIp", 0);
        clientNode.put("totalGB", 0);
        clientNode.put("expiryTime", 0);
        clientNode.put("enable", true);
        clientNode.put("tgId", tgId);
        clientNode.put("subId", subId);
        clientNode.put("comment", "created from tg bot");
        clientNode.put("reset", 0);

        ObjectNode root = mapper.createObjectNode();
        root.set("client", clientNode);
        root.set("inboundIds", toIntArray(inboundIds));
        return root.toString();
    }

    private String buildInboundIdsBody(List<Integer> inboundIds) {
        ObjectNode root = mapper.createObjectNode();
        root.set("inboundIds", toIntArray(inboundIds));
        return root.toString();
    }

    private ArrayNode toIntArray(List<Integer> ids) {
        ArrayNode arr = mapper.createArrayNode();
        for (Integer id : ids) {
            arr.add(id);
        }
        return arr;
    }

    private static boolean isDuplicateEmail(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("duplicate email") || m.contains("email already in use");
    }

    private static String encodePath(String segment) {
        // path-сегмент: пробелы как %20, не как '+'
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
