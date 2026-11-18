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
        returned.put("sequence", input.getSequence() + 1);
        assertFalse(documentsEqual(expected, returned));
    }

    @Test
    void inlineJsonCreatesOrderWithoutChangingNestedDataOrRunId() throws Exception {
        String runId = "unit-\"test\\path";
        Order input = createOrder(runId, 3);
        Map<String, Object> expected = inputDocument(runId, 3);
        Map<String, Object> document = MAPPER.convertValue(input, MAP_TYPE);
        assertTrue(documentsEqual(expected, document));
        assertEquals(runId, input.getRunId());
        assertEquals(3, input.getRevision());
        Order restored = MAPPER.convertValue(document, Order.class);
        assertTrue(documentsEqual(input, restored));
        assertTrue(document.containsKey("note"));
        assertNull(document.get("note"));
        assertTrue(restored.getOptionalFields().containsKey("giftMessage"));
        assertFalse(restored.getOptionalFields().containsKey("coupon"));
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
    void customerWorkflowFiltersTwoOrdersAndDeletesOnlyCreatedKeys() throws Exception {
        List<String> operations = new ArrayList<>();
        Map<MulticloudDbKey, Map<String, Object>> store = new HashMap<>();
        MulticloudDbKey unrelated = MulticloudDbKey.of("unrelated", "do-not-touch");
        store.put(unrelated, Map.of("business", "untouched"));
        var client = fake((method, args) -> {
            operations.add(method);
            if (method.equals("query")) {
                QueryRequest query = (QueryRequest) args[1];
                assertEquals("own-run", query.partitionKey());
                assertEquals("status = @status", query.expression());
                assertEquals(Map.of("status", "NEW"), query.parameters());
                List<Map<String, Object>> partition = store.entrySet().stream()
                        .filter(entry -> entry.getKey().partitionKey().equals("own-run"))
                        .map(Map.Entry::getValue).toList();
                assertEquals(2, partition.size(), "Both orders must exist in the queried partition");
                assertEquals(1, partition.stream().filter(row -> "PAID".equals(row.get("status"))).count());
                return new QueryPage(partition.stream()
                        .filter(row -> query.parameters().get("status").equals(row.get("status")))
                        .toList(), null);
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
                        Map<String, Object> expectedUpdate = payload(store.get(key));
                        int nextRevision = ((Number) expectedUpdate.get("revision")).intValue() + 1;
                        if (nextRevision == 2) {
                            expectedUpdate.put("status", "PAID");
                        } else {
                            assertEquals(3, nextRevision);
                            expectedUpdate.put("note", "Updated directly in the Map");
                        }
                        expectedUpdate.put("revision", nextRevision);
                        assertTrue(documentsEqual(expectedUpdate, document),
                                "Both update styles must preserve every unedited field");
                        Order updated = MAPPER.convertValue(document, Order.class);
                        assertEquals(Order.Status.PAID, updated.getStatus());
                        assertEquals(nextRevision, updated.getRevision());
                    }
                    document.put("partitionKey", key.partitionKey());
                    document.put("sortKey", key.sortKey());
                    store.put(key, document);
                    return null;
                }
                case "read" -> {
                    assertEquals(2, store.keySet().stream()
                            .filter(storedKey -> storedKey.partitionKey().equals("own-run")).count());
                    assertEquals("jackson-baseline", key.sortKey(), "Point read selects only the first order");
                    assertEquals("synthetic-order-paid",
                            store.get(MulticloudDbKey.of("own-run", "jackson-paid")).get("orderId"));
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
        assertTrue(lines.stream().allMatch(line -> line.startsWith("PASS ") || line.startsWith("QUERY page=")),
                lines.toString());
        assertTrue(lines.contains("QUERY page=1 diagnostics=<unavailable>"), lines.toString());
        assertEquals(List.of("create", "create", "read",
                "update", "read", "update", "read", "upsert", "query", "delete", "delete"), operations);
        assertEquals(Map.of(unrelated, Map.of("business", "untouched")), store);
    }

    @Test
    void secondCreateFailurePreservesExistingDocumentAndQueryFailureCleansUpBoth() throws Exception {
        var firstKey = MulticloudDbKey.of("own-run", "jackson-baseline");
        var secondKey = MulticloudDbKey.of("own-run", "jackson-paid");
        for (boolean secondCreateSucceeds : List.of(false, true)) {
            Map<MulticloudDbKey, Map<String, Object>> store = new HashMap<>();
            Map<String, Object> existing = Map.of("business", "do-not-touch");
            if (!secondCreateSucceeds) {
                store.put(secondKey, existing);
            }
            List<MulticloudDbKey> deleted = new ArrayList<>();
            IllegalStateException failure = new IllegalStateException("Simulated failure");
            var client = fake((method, args) -> {
                if (method.equals("query")) {
                    throw failure;
                }
                MulticloudDbKey key = (MulticloudDbKey) args[1];
                return switch (method) {
                    case "create", "update", "upsert" -> {
                        if (method.equals("create") && key.equals(secondKey) && !secondCreateSucceeds) {
                            throw failure;
                        }
                        store.put(key, MAPPER.convertValue(args[2], MAP_TYPE));
                        yield null;
                    }
                    case "read" -> new DocumentResult(store.get(key));
                    case "delete" -> {
                        deleted.add(key);
                        store.remove(key);
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected call " + method);
                };
            });

            assertSame(failure, assertThrows(IllegalStateException.class, () ->
                    new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run")));
            assertEquals(secondCreateSucceeds ? List.of(secondKey, firstKey) : List.of(firstKey), deleted);
            assertEquals(secondCreateSucceeds ? Map.of() : Map.of(secondKey, existing), store);
        }
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
                if (method.equals("read")) {
                    throw new IllegalStateException("Simulated read failure");
                }
                return null;
            });
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run"));
            assertEquals(createSucceeds ? "Simulated read failure" : "Simulated unconfirmed write",
                    failure.getMessage());
            assertEquals(createSucceeds
                    ? List.of("create", "create", "read", "delete", "delete") : List.of("create"), operations);
        }
    }

    @Test
    void queryFollowsEmptyPagesAndRemainsPartitionScoped() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> row = inputDocument("own-run", 4);
        OperationDiagnostics diagnostics = OperationDiagnostics.builder(ProviderId.COSMOS, "query", Duration.ofMillis(12))
                .requestId("synthetic-request-1").statusCode(200).requestCharge(2.5).itemCount(0)
                .etag("PRIVATE-ETAG").sessionToken("PRIVATE-SESSION").build();
        var client = clientWithQuery((method, args) -> {
            assertEquals("query", method);
            QueryRequest request = (QueryRequest) args[1];
            assertEquals("own-run", request.partitionKey());
            assertEquals("status = @status", request.expression());
            assertEquals(Map.of("status", "NEW"), request.parameters());
            if (calls.getAndIncrement() == 0) {
                assertNull(request.continuationToken());
                return new QueryPage(List.of(), "PRIVATE-CONTINUATION", diagnostics);
            }
            assertEquals("PRIVATE-CONTINUATION", request.continuationToken());
            return new QueryPage(List.of(row), null);
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run");
        } finally {
            System.setOut(originalOut);
        }
        assertEquals(2, calls.get());
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("QUERY page=1 diagnostics{provider=cosmos, operation=query, duration=PT0.012S"));
        assertTrue(text.contains("requestId=synthetic-request-1, statusCode=200, requestCharge=2.5, itemCount=0"));
        assertTrue(text.contains("etagPresent=true, sessionTokenPresent=true"));
        assertTrue(text.contains("QUERY page=2 diagnostics=<unavailable>"));
        assertFalse(text.contains("PRIVATE"));

        String optional = queryDiagnosticsSummary(OperationDiagnostics.builder(ProviderId.DYNAMO, "query", Duration.ZERO)
                .requestId("bad\nidentifier").build());
        assertTrue(optional.contains("requestId=<redacted>, statusCode=null, requestCharge=0.0"));
        assertTrue(optional.contains("etagPresent=false, sessionTokenPresent=false"));
    }

    @Test
    void queryStopsAtPageBudget() {
        AtomicInteger calls = new AtomicInteger();
        var client = clientWithQuery((method, args) -> {
            calls.incrementAndGet();
            return new QueryPage(List.of(), "endless");
        });
        AssertionError error = assertThrows(AssertionError.class, () ->
                new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run"));
        assertEquals("Query page budget exceeded", error.getMessage());
        assertEquals(10, calls.get());
    }

    @Test
    void queryRejectsEmptyOrStaleResultsWithoutRetrying() throws Exception {
        List<Map<String, Object>> stale = List.of(inputDocument("own-run", 1));
        for (List<Map<String, Object>> rows : List.of(List.<Map<String, Object>>of(), stale)) {
            AtomicInteger calls = new AtomicInteger();
            var client = clientWithQuery((method, args) -> {
                assertEquals("query", method);
                calls.incrementAndGet();
                return new QueryPage(rows, null);
            });

            AssertionError error = assertThrows(AssertionError.class, () ->
                    new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run"));
            assertTrue(error.getMessage().contains(rows.isEmpty() ? "query NEW order count" : "query vs upsert input"));
            assertEquals(1, calls.get());
        }
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
        assertTrue(error.getMessage().contains("\"note\":\"unexpected\""));
        assertEquals(List.of("create", "create", "read", "delete", "delete"), operations);
    }

    @Test
    void finallyPropagatesDeleteFailureWhenReadAlsoFails() {
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
        assertSame(deleteFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(List.of("create", "create", "read", "delete", "delete"), operations);
    }

    @Test
    void missingOrStaleReadFailsWithoutRetryAndStillDeletesOwnedDocument() throws Exception {
        for (boolean missing : List.of(true, false)) {
            Map<String, Object> stale = inputDocument("own-run", 0);
            List<String> operations = new ArrayList<>();
            var client = fake((method, args) -> {
                operations.add(method);
                return switch (method) {
                    case "create", "delete" -> null;
                    case "read" -> missing ? null : new DocumentResult(stale);
                    default -> throw new AssertionError("Must not continue to " + method);
                };
            });

            AssertionError error = assertThrows(AssertionError.class, () ->
                    new ComplexPojoSample().exercise(client, new ResourceAddress("db", "orders"), "own-run"));
            assertTrue(error.getMessage().contains(missing ? "Document not found after create" : "read vs original"));
            assertEquals(List.of("create", "create", "read", "delete", "delete"), operations);
        }
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

    // Let query scenarios exercise the real workflow without repeating its CRUD test setup.
    private static MulticloudDbClient clientWithQuery(Call query) {
        Map<MulticloudDbKey, Map<String, Object>> store = new HashMap<>();
        return fake((method, args) -> {
            if (method.equals("query")) {
                return query.invoke(method, args);
            }
            MulticloudDbKey key = (MulticloudDbKey) args[1];
            return switch (method) {
                case "create", "update", "upsert" -> {
                    store.put(key, MAPPER.convertValue(args[2], MAP_TYPE));
                    yield null;
                }
                case "read" -> store.containsKey(key) ? new DocumentResult(store.get(key)) : null;
                case "delete" -> {
                    store.remove(key);
                    yield null;
                }
                default -> throw new AssertionError("Unexpected call " + method);
            };
        });
    }

    // Tests only customer orchestration; this is NOT provider or live E2E evidence.
    private static MulticloudDbClient fake(Call call) {
        return (MulticloudDbClient) Proxy.newProxyInstance(MulticloudDbClient.class.getClassLoader(),
                new Class<?>[]{MulticloudDbClient.class},
                (proxy, method, args) -> call.invoke(method.getName(), args));
    }
}
