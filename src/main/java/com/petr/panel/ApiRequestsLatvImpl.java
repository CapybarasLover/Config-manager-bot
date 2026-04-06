package com.petr.panel;

import com.petr.exception.LoginException;
import com.petr.exception.RequestException;
import com.petr.exception.RetryAttemptsLeftException;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class ApiRequestsLatvImpl implements ApiRequests{
    private final int MAX_RETRIES = 2;
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    private URI baseUri = URI.create(System.getenv("LATV_PANEL_HOME_URL"));
    private final String settingsTemplate = "{\"clients\": [{ " +
            "\"id\": \"%s\", " +
            "\"flow\": \"\", " +
            "\"email\": \"%s\", " +
            "\"limitIp\": 0, " +
            "\"totalGB\": 0, " +
            "\"expiryTime\": 0, " +
            "\"enable\": true, " +
            "\"tgId\": \"%s\", " +
            "\"subId\": \"%s\", " +
            "\"comment\": \"created from tg bot\", " +
            "\"reset\": 0 }]}";

    public void ApiRequests() throws IOException, InterruptedException {
        login();
    }

    private <T> T executeWithRetry(ApiRequestsLatvImpl.ThrowingSupplier<T> requestToRetry) throws IOException, InterruptedException {
        int attempts = 0;
        Exception lastException = null;

        while(attempts < MAX_RETRIES){
            try {
                return requestToRetry.get();
            } catch(RequestException requestEx){
                lastException = requestEx;
                System.out.println("Request failed: " + requestEx.getMessage() + ", status=" + requestEx.getStatusCode());
                login();
            } catch(LoginException loginEx){
                lastException = loginEx;
                System.out.println("Login failed: " + loginEx.getMessage() + ", status=" + loginEx.getStatusCode());
            }
            catch(Exception ex) {
                lastException = ex;
                System.out.println("Unhandled exception: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            }
            attempts++;
        }

        String reason = lastException == null ? "unknown reason"
                : lastException.getClass().getSimpleName() + ": " + lastException.getMessage();
        throw new RetryAttemptsLeftException("Too many attempts. Last error: " + reason,  MAX_RETRIES);
    }

    private void login() throws LoginException, IOException, InterruptedException {
        String username = System.getenv("XUI_USERNAME_LATV");
        String password = System.getenv("XUI_PASSWORD_LATV");

        URI loginUri = baseUri.resolve("login");
        String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) +
                "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(loginUri)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if(response.statusCode() != 200){
            System.out.println(response.statusCode());
            System.out.println(response.headers());
            System.out.println(response.body());
            System.out.println(response);
            throw new LoginException("Login failed: ", response.statusCode());
        }
        else{
            System.out.println(response.statusCode());
            System.out.println(response.headers());
            System.out.println(response.body());
        }
    }

    @Override
    public String addClientRequest(String inboundId, UUID uuid, UUID subUuid, String configName, long tgId) throws IOException, InterruptedException {
        return executeWithRetry(()-> {
            URI addClientUrl = baseUri.resolve("panel/api/inbounds/addClient");

            String settings = String.format(settingsTemplate, uuid.toString(), configName, tgId, subUuid);

            String body = "id=" + URLEncoder.encode(inboundId, StandardCharsets.UTF_8) +
                    "&settings=" + URLEncoder.encode(settings, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(addClientUrl)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if(response.statusCode() == 200){
                return "конфиг удачно добавлен на панель!";
            }

            if(response.statusCode() == 404){
                throw new RequestException(
                        "LATV addClient returned 404. url=" + addClientUrl + ", body=" + response.body(),
                        response.statusCode()
                );
            }

            throw new RequestException(
                    "Unexpected status from LATV addClient. status=" + response.statusCode() + ", url=" + addClientUrl + ", body=" + response.body(),
                    response.statusCode()
            );
        });
    }

    @Override
    public HttpResponse<String> getAllConfigsRequest() throws IOException, InterruptedException {
        return executeWithRetry(() ->{
            URI getConfigsUrl = baseUri.resolve("panel/api/inbounds/list");
            System.out.println(getConfigsUrl);
            HttpRequest request = HttpRequest.newBuilder(getConfigsUrl)
                    .GET()
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if(response.statusCode() != 200){
                throw new RequestException("Bad status", response.statusCode());
            }
            return response;
        });
    }

    @Override
    public String deleteClient(String inboundId, String configName) throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI deleteClientUrl = baseUri.resolve(String
                    .format("panel/api/inbounds/%s/delClientByEmail/%s", inboundId, configName));

            HttpRequest request = HttpRequest.newBuilder(deleteClientUrl)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if(response.statusCode() != 200){
                throw new RequestException("Bad status", response.statusCode());            }

            return "Клиент " + configName + " удален!";
        });
    }
}
