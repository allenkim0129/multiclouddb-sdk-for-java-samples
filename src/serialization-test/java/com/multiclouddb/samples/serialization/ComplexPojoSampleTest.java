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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.multiclouddb.samples.serialization.ComplexPojoSample.*;
import static org.junit.jupiter.api.Assertions.*;

class ComplexPojoSampleTest {
    @Test
    void ordinaryNumbersSurviveUntypedJsonReadbackWithoutRelaxingComparisons() throws Exception {
        var untyped = new ObjectMapper();
        Order input = createOrder("unit-test", 1);
        Map<String, Object> returned = untyped.readValue(
                untyped.writeValueAsString(MAPPER.convertValue(input, MAP_TYPE)), MAP_TYPE);
        Map<String, Object> expected = inputDocument("unit-test", 1);
        assertTrue(documentsEqual(expected, returned));
        Order restored = MAPPER.convertValue(returned, Order.class);
        assertTrue(documentsEqual(expected, MAPPER.convertValue(restored, MAP_TYPE)));
        returned.put("sequence", input.sequence() + 1);
        assertFalse(documentsEqual(expected, returned));
    }

    @Test
    void inlineJsonCreatesOrderWithoutChangingNestedDataOrRunId() throws Exception {
        String runId = "unit-\"test\\path";
        Order input = createOrder(runId, 3);
        Map<String, Object> expected = inputDocument(runId, 3);
        Map<String, Object> document = MAPPER.convertValue(input, MAP_TYPE);
        assertTrue(documentsEqual(expected, document));
        assertEquals(runId, input.runId());
        assertEquals(3, input.revision());
        Order restored = MAPPER.convertValue(document, Order.class);
        assertEquals(input, restored);
        assertTrue(document.containsKey("note"));
        assertNull(document.get("note"));
        assertTrue(restored.optionalFields().containsKey("giftMessage"));
        assertFalse(restored.optionalFields().containsKey("coupon"));
    }

    @Test
    void comparatorDistinguishesValueChangesAndMissingVersusNull() {
        assertTrue(documentsEqual(new BigDecimal("125.50"), 125.5));
        assertFalse(documentsEqual(new BigDecimal("125.50"), 125.0));
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("note", null);
        assertFalse(documentsEqual(nullable, Map.of()));
        assertFalse(documentsEqual(Map.of(), nullable));
        assertFalse(documentsEqual(List.of(1, 2), List.of(1)));
        assertFalse(documentsEqual(List.of(1, 2), List.of(2, 1)));
        assertTrue(documentsEqual(Map.of("items", List.of(new BigDecimal("1.00"))),
                Map.of("items", List.of(1L))));
        assertFalse(documentsEqual(Map.of("items", List.of(1)), Map.of("items", List.of("1"))));
        assertFalse(documentsEqual(new BigDecimal("1.0000000000000001"), new BigDecimal("1.0")));
    }

    @Test
    void providerFieldsAreRemovedOnlyAtRootAndUnexpectedApplicationFieldsStillFail() throws Exception {
        Map<String, Object> expected = inputDocument("test", 1);
        Map<String, Object> returned = new LinkedHashMap<>(expected);
        returned.put("id", "case");
        returned.put("partitionKey", "test");
        returned.put("_etag", "synthetic");
        assertTrue(documentsEqual(expected, payload(returned)));
        returned.put("unexpected", true);
        assertFalse(documentsEqual(expected, payload(returned)));
        assertEquals(Map.of("nested", Map.of("id", "business-id")),
                payload(Map.of("nested", Map.of("id", "business-id"))));
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
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run");
        } finally {
            System.setOut(originalOut);
        }

        List<String> lines = output.toString(StandardCharsets.UTF_8).lines().toList();
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(line -> line.startsWith("PASS ")), lines.toString());
        assertEquals(List.of("create", "read",
                "update", "read", "upsert", "query", "delete"), operations);
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
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run"));
            assertEquals(createSucceeds ? "Simulated read failure" : "Simulated unconfirmed write",
                    failure.getMessage());
            assertEquals(createSucceeds ? List.of("create", "read", "delete") : List.of("create"), operations);
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
                new ResourceAddress("db", "orders"), "own-run").size());
        assertEquals(2, calls.get());
    }

    @Test
    void queryStopsAtPageBudget() {
        AtomicInteger calls = new AtomicInteger();
        var client = fake((method, args) -> {
            calls.incrementAndGet();
            return new QueryPage(List.of(), "endless");
        });
        assertThrows(AssertionError.class, () -> ComplexPojoSample.awaitQuery(client,
                new ResourceAddress("db", "orders"), "own-run"));
        assertEquals(10, calls.get());
    }

    @Test
    void authenticationFailureStopsBeforeReadOrDelete() {
        List<String> operations = new ArrayList<>();
        MulticloudDbException denied = failure(MulticloudDbErrorCategory.AUTHENTICATION_FAILED, 400);
        var client = fake((method, args) -> {
            operations.add(method);
            throw denied;
        });
        assertSame(denied, assertThrows(MulticloudDbException.class, () ->
                new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run")));
        assertEquals(List.of("create"), operations);
    }

    @Test
    void comparisonFailureStopsBeforeUpdateAndStillDeletesOwnedDocument() throws Exception {
        List<String> operations = new ArrayList<>();
        Map<String, Object> returned = inputDocument("own-run", 1);
        returned.put("note", "unexpected");
        AtomicInteger reads = new AtomicInteger();
        var client = fake((method, args) -> {
            operations.add(method);
            return switch (method) {
                case "read" -> reads.incrementAndGet() == 1 ? new DocumentResult(returned) : null;
                case "create", "delete" -> null;
                default -> throw new AssertionError("Must not continue to " + method);
            };
        });

        AssertionError error = assertThrows(AssertionError.class, () ->
                new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run"));
        assertTrue(error.getMessage().contains("read vs original"));
        assertTrue(error.getMessage().contains("note=unexpected"));
        assertEquals(List.of("create", "read", "delete"), operations);
    }

    @Test
    void cleanupFailureIsPreservedWithoutReplacingOriginalFailure() {
        IllegalStateException readFailure = new IllegalStateException("read failed");
        IllegalStateException deleteFailure = new IllegalStateException("delete failed");
        List<String> operations = new ArrayList<>();
        var client = fake((method, args) -> {
            operations.add(method);
            return switch (method) {
                case "create" -> null;
                case "read" -> throw readFailure;
                case "delete" -> throw deleteFailure;
                default -> throw new AssertionError("Unexpected call " + method);
            };
        });

        var thrown = assertThrows(IllegalStateException.class, () ->
                new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run"));
        assertSame(readFailure, thrown);
        assertArrayEquals(new Throwable[]{deleteFailure}, thrown.getSuppressed());
        assertEquals(List.of("create", "read", "delete"), operations);
    }

    @Test
    void readWaitsForVisibilityButDoesNotRetryExceptions() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        var client = fake((method, args) -> {
            assertEquals("read", method);
            return switch (reads.incrementAndGet()) {
                case 1 -> null;
                case 2 -> new DocumentResult(Map.of("revision", 1));
                default -> new DocumentResult(Map.of("revision", 3));
            };
        });
        var address = new ResourceAddress("db", "orders");
        var key = MulticloudDbKey.of("own-run", "baseline");
        assertEquals(Map.of("revision", 3), awaitRead(client, address, key, 3));
        assertEquals(3, reads.get());

        reads.set(0);
        MulticloudDbException denied = failure(MulticloudDbErrorCategory.AUTHENTICATION_FAILED, 400);
        var failingClient = fake((method, args) -> {
            reads.incrementAndGet();
            throw denied;
        });
        assertSame(denied, assertThrows(MulticloudDbException.class, () ->
                awaitRead(failingClient, address, key, 3)));
        assertEquals(1, reads.get());
    }

    @Test
    void failureMessageExposesOnlyCategoryAndStatusWithoutNativeTextOrTokens() {
        MulticloudDbException error = new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.CONFLICT, "PRIVATE-MESSAGE", ProviderId.DYNAMO, "create", false,
                400, Map.of("nativeMessage", "PRIVATE-DETAIL")), new RuntimeException("PRIVATE-CAUSE"))
                .withDiagnostics(OperationDiagnostics.builder(ProviderId.DYNAMO, "create", Duration.ofMillis(4))
                        .requestId("synthetic-request-1").etag("PRIVATE-ETAG").sessionToken("PRIVATE-SESSION").build());
        error.addSuppressed(new RuntimeException("PRIVATE-CLEANUP"));
        String text = safeFailureMessage(error);
        assertTrue(text.contains("category=CONFLICT"));
        assertTrue(text.contains("statusCode=400"));
        assertTrue(text.contains("cleanup/client-close failures=1"));
        assertFalse(text.contains("PRIVATE"));
        assertFalse(text.contains("synthetic-request-1"));
        assertTrue(safeFailureMessage(failure(MulticloudDbErrorCategory.INVALID_REQUEST, null))
                .contains("statusCode=null"));
        assertFalse(safeFailureMessage(new IllegalArgumentException("PRIVATE-CONFIG")).contains("PRIVATE"));
    }

    // Compare against the raw JSON, not against a Map generated from the POJO under test.
    private static Map<String, Object> inputDocument(String runId, int revision) throws Exception {
        Map<String, Object> input = MAPPER.readValue(ORDER_JSON, MAP_TYPE);
        input.put("runId", runId);
        input.put("revision", revision);
        return input;
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
