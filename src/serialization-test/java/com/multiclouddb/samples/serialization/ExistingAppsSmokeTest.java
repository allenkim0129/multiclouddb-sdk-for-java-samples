// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.samples.riskplatform.RiskPlatformApp;
import com.multiclouddb.samples.riskplatform.tenant.TenantManager;
import com.multiclouddb.samples.todo.TodoApp;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTP handlers and customer mapping; explicitly NOT native-provider E2E. */
class ExistingAppsSmokeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void todoHttpReadsQueriesMergesUpdatesAndHandlesMissingAndSdkFailure() throws Exception {
        FakeDocuments documents = new FakeDocuments();
        HttpServer server = new TodoApp(documents.client()).startServer(0);
        try {
            assertEquals(200, request(server, "GET", "/", null).statusCode());
            assertEquals(201, request(server, "POST", "/api/todos",
                    "{\"id\":\"synthetic-todo\",\"title\":\"Synthetic task\"}").statusCode());
            assertEquals("Synthetic task", json(request(server, "GET", "/api/todos/synthetic-todo", null))
                    .path("title").asText());
            JsonNode updated = json(request(server, "PUT", "/api/todos/synthetic-todo", "{\"completed\":true}"));
            assertEquals("Synthetic task", updated.path("title").asText());
            assertTrue(updated.path("completed").asBoolean());
            assertFalse(updated.path("createdAt").asText().isEmpty());
            JsonNode rows = json(request(server, "GET", "/api/todos", null));
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).path("completed").asBoolean());
            assertEquals(404, request(server, "GET", "/api/todos/missing", null).statusCode());
            assertEquals(404, request(server, "PUT", "/api/todos/missing", "{\"completed\":true}").statusCode());
            assertEquals(200, request(server, "DELETE", "/api/todos/synthetic-todo", null).statusCode());
            assertEquals(404, request(server, "GET", "/api/todos/synthetic-todo", null).statusCode());
            documents.failure = syntheticFailure();
            HttpResponse<String> failed = request(server, "GET", "/api/todos/synthetic-todo", null);
            assertEquals(500, failed.statusCode()); // Existing REST policy, not the SDK's raw status code.
            assertEquals(MulticloudDbErrorCategory.THROTTLED.getValue(), json(failed).path("category").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void riskHttpReplaysTenantPortfolioPositionDashboardAndAlertAcknowledgement() throws Exception {
        FakeDocuments documents = new FakeDocuments();
        TenantManager tenants = new TenantManager(documents.client());
        tenants.createTenant("synthetic", "Synthetic tenant", "STARTER", "Demo");
        HttpServer server = new RiskPlatformApp(tenants).startServer(0);
        try {
            assertEquals(200, request(server, "GET", "/", null).statusCode());
            assertEquals("Synthetic tenant", json(request(server, "GET", "/api/tenants/synthetic", null))
                    .path("name").asText());
            assertEquals(1, json(request(server, "GET", "/api/tenants", null)).size());
            assertEquals(404, request(server, "GET", "/api/tenants/missing", null).statusCode());
            HttpResponse<String> portfolio = request(server, "POST", "/api/portfolios?tenant=synthetic",
                    "{\"name\":\"Synthetic portfolio\"}");
            assertEquals(201, portfolio.statusCode());
            String portfolioId = json(portfolio).path("portfolioId").asText();
            String positionBody = MAPPER.writeValueAsString(Map.of("portfolioId", portfolioId, "symbol", "SYN",
                    "quantity", 2, "averageCost", 10, "currentPrice", 12));
            assertEquals(201, request(server, "POST", "/api/positions?tenant=synthetic", positionBody).statusCode());
            assertEquals(1, json(request(server, "GET",
                    "/api/positions?tenant=synthetic&portfolio=" + portfolioId, null)).size());
            String alertBody = MAPPER.writeValueAsString(Map.of("portfolioId", portfolioId,
                    "message", "Synthetic threshold", "severity", "HIGH"));
            HttpResponse<String> alert = request(server, "POST", "/api/alerts?tenant=synthetic", alertBody);
            assertEquals(201, alert.statusCode());
            String alertId = json(alert).path("alertId").asText();
            HttpResponse<String> dashboard = request(server, "GET", "/api/dashboard?tenant=synthetic", null);
            assertEquals(200, dashboard.statusCode());
            JsonNode before = json(dashboard);
            assertEquals(24.0, before.path("summary").path("totalAUM").asDouble());
            assertEquals(1, before.path("summary").path("openAlerts").asInt());
            HttpResponse<String> acknowledged = request(server, "PUT",
                    "/api/alerts/" + alertId + "/acknowledge?tenant=synthetic&portfolio=" + portfolioId, "{}");
            assertEquals(200, acknowledged.statusCode());
            JsonNode changed = json(acknowledged);
            assertEquals("ACKNOWLEDGED", changed.path("status").asText());
            assertTrue(changed.path("acknowledged").asBoolean());
            assertEquals("Synthetic threshold", changed.path("message").asText());
            assertEquals("HIGH", changed.path("severity").asText());
            assertEquals(0, json(request(server, "GET", "/api/dashboard?tenant=synthetic", null))
                    .path("summary").path("openAlerts").asInt());
            assertEquals(404, request(server, "PUT",
                    "/api/alerts/missing/acknowledge?tenant=synthetic&portfolio=" + portfolioId, "{}").statusCode());
            documents.failure = syntheticFailure();
            assertEquals(500, request(server, "GET", "/api/tenants/synthetic", null).statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shippedCosmosExamplesNoLongerPassRemovedTransportSetting() throws Exception {
        for (String name : List.of("todo-app", "risk-platform", "change-feed")) {
            for (String suffix : List.of("-cosmos.properties", "-cosmos-cloud.properties.template")) {
                Properties properties = new Properties();
                try (var input = Files.newInputStream(Path.of("src/main/resources", name + suffix))) {
                    properties.load(input);
                }
                assertFalse(properties.containsKey("multiclouddb.connection.connectionMode"), name + suffix);
            }
        }
    }

    private HttpResponse<String> request(HttpServer server, String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static MulticloudDbException syntheticFailure() {
        return new MulticloudDbException(new MulticloudDbError(MulticloudDbErrorCategory.THROTTLED,
                "Synthetic service failure", ProviderId.DYNAMO, "read", true, 429, Map.of()));
    }

    private static final class FakeDocuments {
        private record Location(ResourceAddress address, MulticloudDbKey key) { }
        private final Map<Location, Map<String, Object>> store = new ConcurrentHashMap<>();
        private volatile MulticloudDbException failure;

        MulticloudDbClient client() {
            return (MulticloudDbClient) Proxy.newProxyInstance(MulticloudDbClient.class.getClassLoader(),
                    new Class<?>[]{MulticloudDbClient.class}, (proxy, method, args) -> {
                        if (method.getName().equals("providerId")) {
                            return ProviderId.DYNAMO;
                        }
                        if (failure != null) {
                            throw failure;
                        }
                        ResourceAddress address = (ResourceAddress) args[0];
                        if (method.getName().equals("query")) {
                            QueryRequest query = (QueryRequest) args[1];
                            assertTrue(query.expression() == null || query.expression().equals("status = @status"),
                                    "This small fake does not implement a general query engine");
                            return new QueryPage(store.entrySet().stream()
                                    .filter(entry -> entry.getKey().address().equals(address))
                                    .filter(entry -> query.partitionKey() == null
                                            || entry.getKey().key().partitionKey().equals(query.partitionKey()))
                                    .map(Map.Entry::getValue)
                                    .filter(doc -> query.expression() == null
                                            || doc.get("status").equals(query.parameters().get("status")))
                                    .map(FakeDocuments::copy).toList(), null);
                        }
                        MulticloudDbKey key = (MulticloudDbKey) args[1];
                        Location location = new Location(address, key);
                        return switch (method.getName()) {
                            case "upsert" -> {
                                assertInstanceOf(Map.class, args[2], "App must convert JSON before the public SDK call");
                                Map<String, Object> document = copy(args[2]);
                                document.put("partitionKey", key.partitionKey());
                                document.put("sortKey", key.sortKey());
                                store.put(location, document);
                                yield null;
                            }
                            case "read" -> store.containsKey(location)
                                    ? new DocumentResult(copy(store.get(location))) : null;
                            case "delete" -> {
                                store.remove(location);
                                yield null;
                            }
                            default -> throw new AssertionError("Unexpected call; no provisioning allowed: " + method.getName());
                        };
                    });
        }

        private static Map<String, Object> copy(Object document) {
            return new LinkedHashMap<>(MAPPER.convertValue(document, new TypeReference<Map<String, Object>>() { }));
        }
    }
}
