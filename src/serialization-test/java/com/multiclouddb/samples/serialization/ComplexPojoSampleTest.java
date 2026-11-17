// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.multiclouddb.samples.serialization.ComplexPojoSample.MAPPER;
import static com.multiclouddb.samples.serialization.ComplexPojoSample.MAP_TYPE;
import static org.junit.jupiter.api.Assertions.*;

class ComplexPojoSampleTest {
    @Test
    void previewRunsOneComplexOrderWithJackson() throws Exception {
        PrintStream original = System.out;
        var output = new ByteArrayOutputStream();
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            assertEquals(0, new ComplexPojoSample().run(new String[]{"preview"}));
        } finally {
            System.setOut(original);
        }
        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(List.of("PREVIEW jackson/baseline"),
                text.lines().filter(line -> line.startsWith("PREVIEW ")).toList());
    }

    @Test
    void ordinaryNumbersSurviveUntypedJsonReadbackWithoutRelaxingComparisons() throws Exception {
        var untyped = new ObjectMapper();
        Order input = OrderFixtures.create("unit-test", 1);
        Map<String, Object> returned = untyped.readValue(
                untyped.writeValueAsString(MAPPER.convertValue(input, MAP_TYPE)), MAP_TYPE);
        Map<String, Object> expected = ExpectedDocuments.load("unit-test", 1);
        assertEquals(List.of(), ExpectedDocuments.differences(expected, returned));
        Order restored = MAPPER.convertValue(returned, Order.class);
        assertEquals(List.of(), ExpectedDocuments.differences(expected, MAPPER.convertValue(restored, MAP_TYPE)));
        returned.put("sequence", input.sequence() + 1);
        assertFalse(ExpectedDocuments.differences(expected, returned).isEmpty());
    }

    @Test
    void jacksonMatchesIndependentFixtureAndRestoresJavaTypes() throws Exception {
        Order input = OrderFixtures.create("unit-test", 1);
        Map<String, Object> expected = ExpectedDocuments.load("unit-test", 1);
        Map<String, Object> document = MAPPER.convertValue(input, MAP_TYPE);
        assertEquals(List.of(), ExpectedDocuments.differences(expected, document));
        Order restored = MAPPER.convertValue(document, Order.class);
        assertEquals(input, restored);
        assertTrue(document.containsKey("note"));
        assertNull(document.get("note"));
        assertTrue(restored.optionalFields().containsKey("giftMessage"));
        assertFalse(restored.optionalFields().containsKey("coupon"));
    }

    @Test
    void comparatorDistinguishesValueChangesAndMissingVersusNull() {
        assertTrue(ExpectedDocuments.differences(new BigDecimal("125.50"), 125.5).isEmpty());
        assertFalse(ExpectedDocuments.differences(new BigDecimal("125.50"), 125.0).isEmpty());
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("note", null);
        assertFalse(ExpectedDocuments.differences(nullable, Map.of()).isEmpty());
        assertFalse(ExpectedDocuments.differences(Map.of(), nullable).isEmpty());
        assertFalse(ExpectedDocuments.differences(List.of(1, 2), List.of(1)).isEmpty());
    }

    @Test
    void providerFieldsAreRemovedOnlyAtRootAndUnexpectedApplicationFieldsStillFail() throws Exception {
        Map<String, Object> expected = ExpectedDocuments.load("test", 1);
        Map<String, Object> returned = new LinkedHashMap<>(expected);
        returned.put("id", "case");
        returned.put("partitionKey", "test");
        returned.put("_etag", "synthetic");
        assertEquals(List.of(), ExpectedDocuments.differences(expected, ExpectedDocuments.payload(returned)));
        returned.put("unexpected", true);
        assertFalse(ExpectedDocuments.differences(expected, ExpectedDocuments.payload(returned)).isEmpty());
        assertTrue(ExpectedDocuments.payload(Map.of("nested", Map.of("id", "business-id")))
                .containsKey("nested"));
    }

    @Test
    void liveRequiresExactOptInAndExplicitTargets() {
        assertTrue(LiveSettings.preview(new String[0]));
        assertTrue(LiveSettings.preview(new String[]{"preview"}));
        assertFalse(LiveSettings.preview(new String[]{"live", "cosmos", "--allow-live"}));
        assertFalse(LiveSettings.preview(new String[]{"live", "dynamo", "--allow-live"}));
        assertThrows(IllegalArgumentException.class, () ->
                LiveSettings.preview(new String[]{"live", "cosmos", "--allow-live", "--include-numeric-limits"}));
        assertThrows(IllegalArgumentException.class, () ->
                LiveSettings.preview(new String[]{"preview", "--include-numeric-limits"}));
        assertThrows(IllegalArgumentException.class, () ->
                LiveSettings.preview(new String[]{"preview", "--unknown"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.preview(new String[]{"cosmos"}));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.preview(new String[]{"live", "cosmos"}));
        assertThrows(IllegalArgumentException.class, () ->
                LiveSettings.preview(new String[]{"live", "spanner", "--allow-live"}));
        String[] args = {"live", "cosmos", "--allow-live"};
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.load(args, Map.of()));
        var env = new HashMap<>(Map.of("MCDB_DATABASE", "sandbox", "MCDB_COLLECTION", "orders",
                "MCDB_RUN_PREFIX", "test", "MCDB_COSMOS_ENDPOINT", "https://example.documents.azure.com/"));
        assertTrue(LiveSettings.load(args, env).config().auth().isEmpty());
        env.put("MCDB_COSMOS_ENDPOINT", "https://user:secret@example.com/");
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.load(args, env));
        env.put("MCDB_COSMOS_ENDPOINT", "https://example.documents.azure.com/");
        env.put("MCDB_RUN_PREFIX", "*");
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.load(args, env));
    }

    @Test
    void customerWorkflowReplacesThenRestoresAndDeletesOnlyCreatedKey() throws Exception {
        List<String> operations = new ArrayList<>();
        Map<MulticloudDbKey, Map<String, Object>> store = new HashMap<>();
        MulticloudDbKey unrelated = MulticloudDbKey.of("unrelated", "do-not-touch");
        store.put(unrelated, Map.of("business", "untouched"));
        var client = fake((method, args) -> {
            operations.add(method);
            if (method.equals("query")) {
                QueryRequest query = (QueryRequest) args[1];
                assertEquals("own-run", query.partitionKey());
                assertEquals("runId = @runId", query.expression());
                return new QueryPage(store.entrySet().stream()
                        .filter(entry -> entry.getKey().partitionKey().equals("own-run"))
                        .map(Map.Entry::getValue).toList(), null);
            }
            MulticloudDbKey key = (MulticloudDbKey) args[1];
            assertEquals("own-run", key.partitionKey());
            switch (method) {
                case "create", "update", "upsert" -> {
                    if (method.equals("create") && args[2] instanceof Map<?, ?> input
                            && input.get("unmappedInstant") instanceof Instant) {
                        throw failure(MulticloudDbErrorCategory.INVALID_REQUEST, null);
                    }
                    if (method.equals("create") && store.containsKey(key)) {
                        throw failure(MulticloudDbErrorCategory.CONFLICT, 400);
                    }
                    Map<String, Object> document = MAPPER.convertValue(args[2], MAP_TYPE);
                    if (method.equals("update")) {
                        assertFalse(document.containsKey("customer"));
                    }
                    document.put("partitionKey", key.partitionKey());
                    document.put("sortKey", key.sortKey());
                    store.put(key, document);
                    return null;
                }
                case "read" -> {
                    return store.containsKey(key) ? new DocumentResult(store.get(key)) : null;
                }
                case "delete" -> {
                    store.remove(key);
                    return null;
                }
                default -> throw new AssertionError("Unexpected SDK call " + method);
            }
        });
        new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run");
        assertEquals(List.of("create", "read", "create", "create", "read",
                "update", "read", "upsert", "read", "query", "delete", "read"), operations);
        assertEquals(Map.of(unrelated, Map.of("business", "untouched")), store);
    }

    @Test
    void unconfirmedCreateNeverTriggersDeleteAndConfirmedCreateFailureStillCleansUp() throws Exception {
        for (boolean createSucceeds : List.of(false, true)) {
            List<String> operations = new ArrayList<>();
            var client = fake((method, args) -> {
                operations.add(method);
                if (method.equals("create") && !createSucceeds) {
                    throw new IllegalStateException("Simulated unconfirmed write");
                }
                if (method.equals("read") && operations.size() == 2) {
                    throw new IllegalStateException("Simulated read failure");
                }
                return null;
            });
            new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run");
            assertEquals(createSucceeds ? List.of("create", "read", "delete", "read") : List.of("create"), operations);
        }
    }

    @Test
    void queryFollowsEmptyPagesAndRemainsPartitionScoped() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = fake((method, args) -> {
            assertEquals("query", method);
            QueryRequest request = (QueryRequest) args[1];
            assertEquals("own-run", request.partitionKey());
            if (calls.getAndIncrement() == 0) {
                assertNull(request.continuationToken());
                return new QueryPage(List.of(), "next");
            }
            assertEquals("next", request.continuationToken());
            return new QueryPage(List.of(Map.of("id", "case", "revision", 3, "runId", "own-run")), null);
        });
        assertEquals(1, ComplexPojoSample.awaitQuery(client,
                new ResourceAddress("db", "orders"), "own-run", "case").size());
        assertEquals(2, calls.get());
    }

    @Test
    void queryStopsAtPageBudget() {
        AtomicInteger calls = new AtomicInteger();
        var client = fake((method, args) -> {
            calls.incrementAndGet();
            return new QueryPage(List.of(), "endless");
        });
        assertThrows(IllegalStateException.class, () -> ComplexPojoSample.awaitQuery(client,
                new ResourceAddress("db", "orders"), "own-run", "case"));
        assertEquals(10, calls.get());
    }

    @Test
    void unexpectedAuthenticationFailureIsNotTreatedAsAnExpectedConflict() {
        List<String> operations = new ArrayList<>();
        MulticloudDbException denied = failure(MulticloudDbErrorCategory.AUTHENTICATION_FAILED, 400);
        var client = fake((method, args) -> {
            operations.add(method);
            if (method.equals("read")) {
                return null;
            }
            throw denied;
        });
        assertSame(denied, assertThrows(MulticloudDbException.class, () ->
                new ComplexPojoSample().demonstrateFailureHandling(client, new ResourceAddress("db", "orders"),
                        MulticloudDbKey.of("own-run", "baseline"), Map.of("runId", "own-run"))));
        assertEquals(List.of("read", "create"), operations);
    }

    @Test
    void diagnosticsExposeActualOptionalFieldsWithoutNativeTextOrTokens() {
        MulticloudDbException error = new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.CONFLICT, "PRIVATE-MESSAGE", ProviderId.DYNAMO, "create", false,
                400, Map.of("nativeMessage", "PRIVATE-DETAIL")), new RuntimeException("PRIVATE-CAUSE"))
                .withDiagnostics(OperationDiagnostics.builder(ProviderId.DYNAMO, "create", Duration.ofMillis(4))
                        .requestId("synthetic-request-1").etag("PRIVATE-ETAG").sessionToken("PRIVATE-SESSION").build());
        String text = ComplexPojoSample.errorSummary(error);
        assertTrue(text.contains("category=CONFLICT"));
        assertTrue(text.contains("statusCode=400"));
        assertTrue(text.contains("requestId=synthetic-request-1"));
        assertTrue(text.contains("statusCode=null")); // Error status and diagnostics status are distinct.
        assertFalse(text.contains("PRIVATE"));
        assertTrue(ComplexPojoSample.errorSummary(failure(MulticloudDbErrorCategory.INVALID_REQUEST, null))
                .contains("diagnostics=<unavailable>"));
        assertTrue(ComplexPojoSample.diagnosticsSummary(
                OperationDiagnostics.builder(ProviderId.COSMOS, "query", Duration.ZERO)
                        .requestId("bad\nidentifier").build()).contains("requestId=<redacted>"));
    }

    private static MulticloudDbException failure(MulticloudDbErrorCategory category, Integer status) {
        return new MulticloudDbException(new MulticloudDbError(
                category, "Synthetic test error", ProviderId.DYNAMO, "create", false, status, Map.of()));
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(String method, Object[] args);
    }

    // Tests only customer orchestration; this is NOT provider or live E2E evidence.
    private static MulticloudDbClient fake(Call call) {
        return (MulticloudDbClient) Proxy.newProxyInstance(MulticloudDbClient.class.getClassLoader(),
                new Class<?>[]{MulticloudDbClient.class},
                (proxy, method, args) -> call.invoke(method.getName(), args));
    }
}
