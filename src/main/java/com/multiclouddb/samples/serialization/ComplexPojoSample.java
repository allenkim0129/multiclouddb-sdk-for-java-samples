// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Order -> Jackson Map -> public SDK -> Map -> Order, using an existing Cosmos/Dynamo target. */
public final class ComplexPojoSample {
    // Edit this synthetic input to see exactly which nested data the sample exercises.
    static final String ORDER_JSON = """
            {
              "orderId": "synthetic-order-baseline",
              "runId": "replaced-at-runtime",
              "revision": 1,
              "status": "NEW",
              "customer": {
                "name": "Synthetic Customer",
                "address": {"city": "Example City", "postalCode": "00123"}
              },
              "items": [
                {"sku": "SYNTHETIC-A", "quantity": 2, "unitPrice": 50.25, "tags": ["demo", "fragile"]},
                {"sku": "SYNTHETIC-B", "quantity": 1, "unitPrice": 25.00, "tags": []}
              ],
              "labels": {"channel": "sample", "region": "test"},
              "optionalFields": {"giftMessage": null},
              "placedAt": "2026-01-02T03:04:05.123456789Z",
              "deliveryDate": "2026-01-05",
              "note": null,
              "sequence": 12345,
              "externalNumber": 42,
              "total": 125.50
            }
            """;

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

        // JSON is just a convenient way to build the input POJO; the SDK receives its Map.
        Order original = createOrder(runId, 1);
        Map<String, Object> document = MAPPER.convertValue(original, MAP_TYPE);

        // A failed/ambiguous create must not trigger deletion of a possibly pre-existing document.
        client.create(address, key, document);

        // Enter the cleanup scope only after create succeeds.
        try {
            System.out.println("PASS create");

            // Read the stored Map and restore the complex Order directly with Jackson.
            DocumentResult readResult = client.read(address, key);
            check(readResult != null, "Document not found after create");
            Map<String, Object> returned = readResult.document();
            Map<String, Object> payload = payload(returned);
            checkEquals("read vs original", document, payload);
            Order restored = MAPPER.convertValue(payload, Order.class);
            checkEquals("restored POJO", original, restored);

            // Modify the restored POJO directly, then map the complete object for update.
            // update replaces the full document, so keep all the other fields.
            restored.setStatus(Order.Status.PAID);
            restored.setRevision(2);
            Map<String, Object> updatedDocument = MAPPER.convertValue(restored, MAP_TYPE);
            client.update(address, key, updatedDocument);

            DocumentResult updateResult = client.read(address, key);
            check(updateResult != null, "Document not found after POJO update");
            Map<String, Object> afterUpdate = updateResult.document();
            checkEquals("update vs modified POJO", updatedDocument, payload(afterUpdate));
            Order restoredUpdate = MAPPER.convertValue(payload(afterUpdate), Order.class);
            checkEquals("restored updated POJO", restored, restoredUpdate);

            // Alternatively, edit the returned Map without converting it to a POJO.
            // payload makes a mutable copy without provider metadata; all business fields remain.
            Map<String, Object> editedMap = payload(afterUpdate);
            editedMap.put("note", "Updated directly in the Map");
            editedMap.put("revision", 3);
            client.update(address, key, editedMap);

            DocumentResult mapUpdateResult = client.read(address, key);
            check(mapUpdateResult != null, "Document not found after Map update");
            Map<String, Object> afterMapUpdate = mapUpdateResult.document();
            checkEquals("update vs edited Map", editedMap, payload(afterMapUpdate));

            // Demonstrate upsert with a fresh order from the JSON input (status NEW, revision 4).
            Order fullOrder = createOrder(runId, 4);
            Map<String, Object> fullDocument = MAPPER.convertValue(fullOrder, MAP_TYPE);
            client.upsert(address, key, fullDocument);

            // Read back the upsert through query, then restore its single order.
            List<Map<String, Object>> rows = queryDocuments(client, address, runId);
            checkEquals("query row count", 1, rows.size());
            Map<String, Object> row = rows.get(0);
            checkEquals("query vs upsert input", fullDocument, payload(row));
            Order queried = MAPPER.convertValue(payload(row), Order.class);
            checkEquals("restored query POJO", fullOrder, queried);
        } finally {
            // Delete our document even if a read, write or comparison fails.
            client.delete(address, key);
        }

        System.out.println("PASS delete");
    }

    // Everything below supports the walkthrough above; no separate helper classes are needed.

    static Order createOrder(String runId, int revision) throws JsonProcessingException {
        Map<String, Object> input = MAPPER.readValue(ORDER_JSON, MAP_TYPE);
        input.put("runId", runId);
        input.put("revision", revision);
        return MAPPER.convertValue(input, Order.class);
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
        check(documentsEqual(expected, actual), label + ": expected=" + MAPPER.valueToTree(expected)
                + ", actual=" + MAPPER.valueToTree(actual));
        System.out.println("PASS " + label);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message); // Always active; no JVM -ea flag needed.
        }
    }

    static List<Map<String, Object>> queryDocuments(MulticloudDbClient client, ResourceAddress address,
                                                    String runId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String token = null;
        int pages = 0;

        // Fetch each page once. Continuation tokens mean more pages, not retries.
        do {
            QueryRequest request = QueryRequest.builder().partitionKey(runId)
                    .expression("runId = @runId").parameters(Map.of("runId", runId))
                    .maxPageSize(2).continuationToken(token).build();
            var page = client.query(address, request);
            rows.addAll(page.items());
            token = page.continuationToken();
            check(++pages < 10 || token == null, "Query page budget exceeded");
        } while (token != null);

        return rows;
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
