// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Live Cosmos/Dynamo sample; requires an explicit target and --allow-live. */
public final class ComplexPojoSample {
    public static final String SDK_SHA = "ef339fb6529a1b367987bff0b4884c4127708fd9";
    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private int failures;

    public static void main(String[] args) {
        int exit;
        try {
            exit = new ComplexPojoSample().run(args);
        } catch (IllegalArgumentException invalid) {
            safeError("startup/input mapping", invalid);
            exit = 1;
        } catch (MulticloudDbException | IllegalStateException | IOException failure) {
            safeError("startup/serialization", failure);
            exit = 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("FAIL interrupted; inspect the printed keys for possible remaining documents");
            exit = 1;
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }

    int run(String[] args) throws IOException, InterruptedException {
        LiveSettings settings;
        try {
            settings = LiveSettings.load(args, System.getenv(),
                    System.getProperty("multiclouddb.config"));
        } catch (IllegalArgumentException invalid) {
            // These validation messages contain variable names, never supplied values or native errors.
            System.err.println("FAIL configuration: " + invalid.getMessage());
            return 1;
        }
        System.out.println("Expected SDK SHA (use the pinned build script): " + SDK_SHA);
        System.out.println("SDK loaded from: " + MulticloudDbClient.class.getProtectionDomain().getCodeSource().getLocation());
        String runId = settings.prefix() + "-" + UUID.randomUUID();
        System.out.println("LIVE provider=" + settings.provider().id()
                + " database=" + settings.address().database()
                + " collection=" + settings.address().collection() + " runId=" + runId);
        System.out.println("Existing targets only. Each confirmed-created key is deleted in finally.");
        MulticloudDbClient client = MulticloudDbClientFactory.create(settings.config());
        try {
            exercise(client, settings.address(), runId);
        } finally {
            closeClient(client);
        }
        System.out.println((failures == 0 ? "PASS" : "FAIL") + " summary: " + failures + " failed checks/operations");
        return failures == 0 ? 0 : 1;
    }

    private void closeClient(MulticloudDbClient client) throws InterruptedException {
        try {
            client.close();
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception failure) {
            // AutoCloseable declares Exception; only this close call needs a generic boundary.
            failures++;
            safeError("client close", failure);
        }
    }

    void exercise(MulticloudDbClient client, ResourceAddress address,
                  String runId) throws IOException, InterruptedException {
        MulticloudDbKey key = MulticloudDbKey.of(runId, "jackson-baseline");
        boolean created = false;
        String step = "prepare";
        System.out.println("\nLIVE jackson/baseline"
                + " partitionKey=" + key.partitionKey() + " sortKey/id=" + key.sortKey());
        try {
            Order original = OrderFixtures.create(runId, 1);
            Map<String, Object> expected = ExpectedDocuments.load(runId, 1);
            System.out.println("Original POJO: " + original);
            show("Independent expected JSON", expected);
            Map<String, Object> document = MAPPER.convertValue(original, MAP_TYPE);
            show("Jackson -> Map", document);
            if (!check("POJO -> Map before I/O", expected, document)) {
                return;
            }
            step = "create";
            client.create(address, key, document);
            created = true;
            System.out.println("PASS create");
            step = "representative failure handling";
            demonstrateFailureHandling(client, address, key, document);
            step = "read after create";
            Map<String, Object> returned = awaitRead(client, address, key, 1);
            show("SDK read Map (including provider fields)", returned);
            Map<String, Object> payload = ExpectedDocuments.payload(returned);
            check("read vs independent expected", expected, payload);
            Order restored = MAPPER.convertValue(payload, Order.class);
            System.out.println("Restored POJO: " + restored);
            check("restored POJO vs independent expected", expected, MAPPER.convertValue(restored, MAP_TYPE));

            step = "update";
            Map<String, Object> replacement = Map.of("orderId", original.orderId(),
                    "runId", runId, "revision", 2, "status", "PAID");
            show("update input: deliberately incomplete body", replacement);
            client.update(address, key, replacement);
            Map<String, Object> afterUpdate = awaitRead(client, address, key, 2);
            show("SDK read after update", afterUpdate);
            check("update REPLACES body on Cosmos/Dynamo (customer/items must be absent)",
                    replacement, ExpectedDocuments.payload(afterUpdate));

            step = "upsert";
            Order restoredOrder = OrderFixtures.create(runId, 3);
            Map<String, Object> upsertDocument = MAPPER.convertValue(restoredOrder, MAP_TYPE);
            show("upsert input: complete POJO mapped again", upsertDocument);
            client.upsert(address, key, upsertDocument);
            expected = ExpectedDocuments.load(runId, 3);
            Map<String, Object> afterUpsert = awaitRead(client, address, key, 3);
            show("SDK read after upsert", afterUpsert);
            check("upsert vs independent expected", expected, ExpectedDocuments.payload(afterUpsert));
            Order upserted = MAPPER.convertValue(ExpectedDocuments.payload(afterUpsert), Order.class);
            System.out.println("Restored POJO: " + upserted);
            check("restored POJO vs independent expected", expected, MAPPER.convertValue(upserted, MAP_TYPE));

            step = "query";
            List<Map<String, Object>> rows = awaitQuery(client, address, runId, key.sortKey());
            check("query row count in this run/case", 1, rows.size());
            for (Map<String, Object> row : rows) {
                show("SDK query row", row);
                Map<String, Object> queryPayload = ExpectedDocuments.payload(row);
                check("query vs independent expected", expected, queryPayload);
                Order queried = MAPPER.convertValue(queryPayload, Order.class);
                System.out.println("Restored POJO: " + queried);
                check("restored POJO vs independent expected", expected, MAPPER.convertValue(queried, MAP_TYPE));
            }
        } catch (MulticloudDbException | IllegalArgumentException | IllegalStateException failure) {
            failures++;
            safeError(step, failure);
            if (step.equals("create") && !created) {
                System.err.println("Create not confirmed: no delete attempted. If the write committed before an error,"
                        + " inspect ONLY the exact key printed above; never delete a conflict's existing document.");
            }
        } finally {
            if (created) {
                cleanup(client, address, key);
            }
        }
    }

    void demonstrateFailureHandling(MulticloudDbClient client, ResourceAddress address,
                                    MulticloudDbKey ownedKey, Map<String, Object> document) {
        MulticloudDbKey missingKey = MulticloudDbKey.of(ownedKey.partitionKey(),
                ownedKey.sortKey() + "-never-created");
        DocumentResult missing = client.read(address, missingKey);
        if (missing == null) {
            System.out.println("PASS missing read: null; application chooses its not-found response, no exception");
        } else {
            failures++;
            System.err.println("FAIL missing read returned a document; no write/delete attempted on that key");
        }

        try {
            client.create(address, ownedKey, document);
            failures++;
            System.err.println("FAIL duplicate create unexpectedly succeeded");
        } catch (MulticloudDbException failure) {
            if (failure.error().category() != MulticloudDbErrorCategory.CONFLICT) {
                throw failure; // Authentication, throttling, etc. are not a successful conflict probe.
            }
            System.out.println("PASS expected CONFLICT: keep existing document; do not retry blindly");
            System.out.println("  " + errorSummary(failure));
        }

        try {
            // Deliberately bypass customer mapping. Instant is not a supported neutral Map value.
            client.create(address, ownedKey, Map.of("unmappedInstant", Instant.parse("2026-01-02T03:04:05Z")));
            failures++;
            System.err.println("FAIL unsupported Map value unexpectedly accepted");
        } catch (MulticloudDbException failure) {
            if (failure.error().category() != MulticloudDbErrorCategory.INVALID_REQUEST) {
                throw failure;
            }
            System.out.println("PASS expected INVALID_REQUEST: fix mapping before retrying; no new API is assumed");
            System.out.println("  " + errorSummary(failure));
        }
    }

    private void cleanup(MulticloudDbClient client, ResourceAddress address, MulticloudDbKey key)
            throws InterruptedException {
        try {
            client.delete(address, key);
            for (int attempt = 0; attempt < 5; attempt++) {
                if (client.read(address, key) == null) {
                    System.out.println("PASS cleanup: deleted own key " + key.partitionKey() + "/" + key.sortKey());
                    return;
                }
                Thread.sleep(250);
            }
            throw new IllegalStateException("Deleted document still visible after bounded reads");
        } catch (MulticloudDbException | IllegalArgumentException | IllegalStateException failure) {
            failures++;
            safeError("cleanup (delete or confirmation)", failure);
            System.err.println("Inspect/delete ONLY partitionKey=" + key.partitionKey() + " sortKey/id=" + key.sortKey());
        }
    }

    static Map<String, Object> awaitRead(MulticloudDbClient client, ResourceAddress address,
                                       MulticloudDbKey key, int revision) throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            DocumentResult result = client.read(address, key,
                    OperationOptions.builder().includeMetadata(true).build());
            if (result != null && result.document().get("revision") instanceof Number number
                    && ExpectedDocuments.differences(revision, number).isEmpty()) {
                var metadata = result.metadata();
                System.out.println("READ metadata (not operation diagnostics): "
                        + (metadata == null ? "<unavailable>" : "lastModified=" + metadata.lastModified()
                        + " ttlExpiry=" + metadata.ttlExpiry() + " versionPresent=" + (metadata.version() != null)));
                return result.document();
            }
            System.out.println("WAIT read: missing/stale revision, attempt " + (attempt + 1));
            Thread.sleep(250);
        }
        throw new IllegalStateException("Expected revision not visible after bounded reads");
    }

    static List<Map<String, Object>> awaitQuery(MulticloudDbClient client, ResourceAddress address,
                                                String runId, String caseId) throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            List<Map<String, Object>> rows = new ArrayList<>();
            String token = null;
            int pages = 0;
            do {
                var request = QueryRequest.builder().partitionKey(runId)
                        .expression("runId = @runId")
                        .parameters(Map.of("runId", runId))
                        .maxPageSize(2).continuationToken(token).build();
                var page = client.query(address, request);
                System.out.println("QUERY " + diagnosticsSummary(page.diagnostics()));
                for (Map<String, Object> row : page.items()) {
                    // Only compare the exact document created by this run.
                    if (caseId.equals(row.get("sortKey")) || caseId.equals(row.get("id"))) {
                        rows.add(row);
                    }
                }
                token = page.continuationToken();
                pages++;
                if (pages >= 10 && token != null) {
                    throw new IllegalStateException("Query page budget exceeded; no unbounded scan");
                }
            } while (token != null);
            if (!rows.isEmpty() && rows.stream().allMatch(row ->
                    ExpectedDocuments.differences(3, row.get("revision")).isEmpty())) {
                return rows;
            }
            System.out.println("WAIT query: missing/stale rows, attempt " + (attempt + 1));
            Thread.sleep(250);
        }
        throw new IllegalStateException("Expected query rows not visible after bounded queries");
    }

    private boolean check(String label, Object expected, Object actual) {
        List<String> differences = ExpectedDocuments.differences(expected, actual);
        System.out.println((differences.isEmpty() ? "PASS " : "FAIL ") + label);
        differences.forEach(difference -> System.out.println("  " + difference));
        if (!differences.isEmpty()) {
            failures++;
        }
        return differences.isEmpty();
    }

    private static void show(String label, Map<String, Object> map) throws JsonProcessingException {
        System.out.println(label + ":\n" + MAPPER
                .writerWithDefaultPrettyPrinter().writeValueAsString(map));
        ExpectedDocuments.printTypes("$", map);
    }

    private static void safeError(String step, Exception failure) {
        // Never print native exception messages/causes: they can contain auth/account details.
        System.err.println("FAIL " + step + ": " + failure.getClass().getSimpleName()
                + (failure instanceof MulticloudDbException sdk ? " " + errorSummary(sdk) : "")
                + " (native details suppressed)");
    }

    static String errorSummary(MulticloudDbException failure) {
        var error = failure.error();
        return "error{category=" + error.category()
                + " provider=" + (error.provider() == null ? "<unavailable>" : error.provider().id())
                + " operation=" + safeIdentifier(error.operation())
                + " statusCode=" + error.statusCode() + " retryableHint=" + error.retryable() + "} "
                + diagnosticsSummary(failure.diagnostics());
    }

    static String diagnosticsSummary(OperationDiagnostics diagnostics) {
        if (diagnostics == null) {
            return "diagnostics=<unavailable>";
        }
        return "diagnostics{provider=" + (diagnostics.provider() == null ? "<unavailable>" : diagnostics.provider().id())
                + " operation=" + safeIdentifier(diagnostics.operation())
                + " duration=" + diagnostics.duration()
                + " requestId=" + safeIdentifier(diagnostics.requestId())
                + " statusCode=" + diagnostics.statusCode()
                + " itemCount=" + diagnostics.itemCount() + "}";
    }

    private static String safeIdentifier(String value) {
        if (value == null) {
            return "<unavailable>";
        }
        return value.matches("[A-Za-z0-9._:-]{1,256}") ? value : "<redacted>";
    }
}
