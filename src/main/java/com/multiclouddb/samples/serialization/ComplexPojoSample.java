// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.samples.ConfigLoader;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Order -> Jackson Map -> public SDK -> Map -> Order, using an existing Cosmos/Dynamo target. */
public final class ComplexPojoSample {
    // Support Java dates as ISO strings and preserve decimals in untyped Maps.
    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    public static void main(String[] args) {
        try {
            new ComplexPojoSample().run(args);
        } catch (Exception | AssertionError failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            // This is the only error boundary. Never expose native messages, causes or credentials.
            throw new IllegalStateException(safeFailureMessage(failure));
        }
    }

    void run(String[] args) throws Exception {
        check(args.length == 1, "Usage: <cosmos|dynamo>");
        String provider = args[0];
        check(provider.equals("cosmos") || provider.equals("dynamo"), "Usage: <cosmos|dynamo>");

        // Use the same properties loader as the other samples; do not create any cloud resources.
        ConfigLoader.AppConfig config = ConfigLoader.load("serialization-" + provider + "-cloud.properties");
        checkRequiredProperties(config, provider);
        String database = config.property("multiclouddb.database");
        String collection = config.property("multiclouddb.collection");
        String runId = config.property("multiclouddb.runPrefix") + "-" + UUID.randomUUID();
        ResourceAddress address = new ResourceAddress(database, collection);

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(config.sdk())) {
            exercise(client, address, runId);
        } catch (Exception | AssertionError failure) {
            // Report only the exact candidate key on failure, never account credentials.
            System.err.println("Inspect this run's document if a write was attempted: "
                    + runId + "/jackson-baseline");
            throw failure;
        }

        System.out.println("PASS: all checks and cleanup completed");
    }

    void exercise(MulticloudDbClient client, ResourceAddress address, String runId) throws Exception {
        // One unique partition per run. Cleanup may delete only this exact, confirmed-created key.
        MulticloudDbKey key = MulticloudDbKey.of(runId, "jackson-baseline");

        // Compare Jackson's output with independent JSON before writing anything.
        Order original = createOrder(runId, 1);
        Map<String, Object> expected = loadExpected(runId, 1);
        Map<String, Object> document = MAPPER.convertValue(original, MAP_TYPE);
        checkEquals("POJO -> Map before I/O", expected, document);

        // A failed/ambiguous create must not trigger deletion of a possibly pre-existing document.
        client.create(address, key, document);

        // Register cleanup only after create succeeds. It runs even if a later check throws,
        // and try-with-resources preserves the original error if cleanup also fails.
        try (AutoCloseable ownedDocument = () -> client.delete(address, key)) {
            System.out.println("PASS create");

            // Read the stored Map and restore the complex Order directly with Jackson.
            Map<String, Object> returned = awaitRead(client, address, key, 1);
            Map<String, Object> payload = payload(returned);
            checkEquals("read vs independent expected", expected, payload);
            Order restored = MAPPER.convertValue(payload, Order.class);
            checkEquals("restored POJO", expected, MAPPER.convertValue(restored, MAP_TYPE));

            // At this SDK pin, update REPLACES the body, not individual fields.
            // This deliberately small body must remove customer/items and the other omitted fields.
            Map<String, Object> replacement = Map.of("orderId", original.orderId(),
                    "runId", runId, "revision", 2, "status", "PAID");
            client.update(address, key, replacement);

            Map<String, Object> afterUpdate = awaitRead(client, address, key, 2);
            checkEquals("update replaces the body", replacement, payload(afterUpdate));

            // Put the full POJO back with upsert. Revision 3 distinguishes it from earlier writes.
            Order fullOrder = createOrder(runId, 3);
            Map<String, Object> fullDocument = MAPPER.convertValue(fullOrder, MAP_TYPE);
            client.upsert(address, key, fullDocument);

            // Read back the upsert through query, then restore its single order.
            expected = loadExpected(runId, 3);
            List<Map<String, Object>> rows = awaitQuery(client, address, runId);
            checkEquals("query row count", 1, rows.size());
            Map<String, Object> row = rows.get(0);
            checkEquals("query vs independent expected", expected, payload(row));
            Order queried = MAPPER.convertValue(payload(row), Order.class);
            checkEquals("restored query POJO", expected, MAPPER.convertValue(queried, MAP_TYPE));
        }

        System.out.println("PASS delete");
    }

    // Everything below supports the walkthrough above; no separate helper classes are needed.

    static Order createOrder(String runId, int revision) {
        // Visible synthetic inputs; edit alongside resources/fixtures/baseline.json.
        // Map.of rejects nulls. Include giftMessage as null and leave coupon absent.
        Map<String, Object> optionalFields = new LinkedHashMap<>();
        optionalFields.put("giftMessage", null);

        return new Order("synthetic-order-baseline", runId, revision, Order.Status.NEW,
                new Order.Customer("Synthetic Customer",
                        new Order.Address("Example City", "00123")),
                List.of(new Order.LineItem("SYNTHETIC-A", 2, new BigDecimal("50.25"), List.of("demo", "fragile")),
                        new Order.LineItem("SYNTHETIC-B", 1, new BigDecimal("25.00"), List.of())),
                Map.of("channel", "sample", "region", "test"), optionalFields,
                Instant.parse("2026-01-02T03:04:05.123456789Z"), LocalDate.parse("2026-01-05"),
                null, 12345L, new BigInteger("42"), new BigDecimal("125.50"));
    }

    private static void checkRequiredProperties(ConfigLoader.AppConfig config, String provider) {
        List<String> names = new ArrayList<>(List.of("multiclouddb.provider",
                "multiclouddb.database", "multiclouddb.collection", "multiclouddb.runPrefix"));
        names.addAll(provider.equals("cosmos")
                ? List.of("multiclouddb.connection.endpoint", "multiclouddb.connection.key")
                : List.of("multiclouddb.connection.region"));

        // Check presence only. The SDK/service owns format and naming rules.
        for (String name : names) {
            String value = config.property(name);
            check(value != null && !value.isBlank(),
                    "Missing required property: " + name + ". Include the config directory on the runtime classpath.");
        }

        check(config.sdk().provider().id().equals(provider),
                "Config provider must match the cosmos/dynamo argument");
    }

    static Map<String, Object> loadExpected(String runId, int revision) throws IOException {
        try (var input = ComplexPojoSample.class.getResourceAsStream("/fixtures/baseline.json")) {
            if (input == null) {
                throw new IOException("Missing expected fixture: baseline.json");
            }

            Map<String, Object> expected = MAPPER.readValue(input, MAP_TYPE);
            expected.put("runId", runId);
            expected.put("revision", revision);
            return expected;
        }
    }

    static Map<String, Object> payload(Map<String, Object> returned) {
        // Strip known root provider fields, never nested business fields or unexpected application data.
        Map<String, Object> result = new LinkedHashMap<>(returned);
        List.of("id", "partitionKey", "sortKey", "_rid", "_self", "_etag", "_attachments", "_ts")
                .forEach(result::remove);
        return result;
    }

    static boolean documentsEqual(Object expected, Object actual) {
        JsonNode expectedJson = MAPPER.valueToTree(expected);
        JsonNode actualJson = MAPPER.valueToTree(actual);

        // Jackson compares structure, list order and null/missing fields. Compare numeric VALUES
        // exactly across provider Number subclasses (125.50 == 125.5), without double coercion.
        return expectedJson.equals((left, right) ->
                left.isNumber() && right.isNumber()
                        ? left.decimalValue().compareTo(right.decimalValue())
                        : (left.equals(right) ? 0 : 1), actualJson);
    }

    private static void checkEquals(String label, Object expected, Object actual) {
        check(documentsEqual(expected, actual), label + ": expected=" + expected + ", actual=" + actual);
        System.out.println("PASS " + label);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message); // Always active; no JVM -ea flag needed.
        }
    }

    // Live providers may briefly return an older revision. Retry visibility only, not errors
    // or changed payload values; each comparison above fails immediately.
    static Map<String, Object> awaitRead(MulticloudDbClient client, ResourceAddress address,
                                       MulticloudDbKey key, int revision) throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            DocumentResult result = client.read(address, key);
            if (result != null && documentsEqual(revision, result.document().get("revision"))) {
                return result.document();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Expected revision not visible after five reads");
    }

    static List<Map<String, Object>> awaitQuery(MulticloudDbClient client, ResourceAddress address,
                                               String runId) throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            List<Map<String, Object>> rows = new ArrayList<>();
            String token = null;
            int pages = 0;

            // Follow tokens even for empty pages, but never perform an unbounded scan.
            do {
                QueryRequest request = QueryRequest.builder().partitionKey(runId)
                        .expression("runId = @runId").parameters(Map.of("runId", runId))
                        .maxPageSize(2).continuationToken(token).build();
                var page = client.query(address, request);
                rows.addAll(page.items());
                token = page.continuationToken();
                check(++pages < 10 || token == null, "Query page budget exceeded");
            } while (token != null);

            if (!rows.isEmpty() && rows.stream().allMatch(row -> documentsEqual(3, row.get("revision")))) {
                return rows;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Expected query rows not visible after five queries");
    }

    static String safeFailureMessage(Throwable failure) {
        String detail = failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName();
        if (failure instanceof MulticloudDbException sdk) {
            detail += " category=" + sdk.error().category() + " statusCode=" + sdk.error().statusCode();
        }
        return "FAIL " + detail + "; cleanup/client-close failures=" + failure.getSuppressed().length
                + ". Inspect the printed document key if a write was attempted. Native details suppressed.";
    }
}
