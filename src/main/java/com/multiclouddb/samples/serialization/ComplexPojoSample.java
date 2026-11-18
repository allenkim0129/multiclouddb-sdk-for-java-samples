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
import com.multiclouddb.samples.ConfigLoader;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live Cosmos/Dynamo sample: Order -> Jackson Map -> public SDK -> Map -> Order.
 * Start with run() for account setup and exercise() for the database workflow.
 * Jackson mapping belongs to this application; the SDK accepts and returns Maps.
 */
public final class ComplexPojoSample {
    public static final String SDK_SHA = "ef339fb6529a1b367987bff0b4884c4127708fd9";
    // JavaTimeModule supports Instant/LocalDate; use ISO text instead of numeric dates.
    // BigDecimal preserves decimal values when Jackson creates an untyped Map.
    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    // Retains Map<String, Object> as Jackson's conversion target despite Java type erasure.
    static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private int failures;

    /**
     * Process entry point. run() returns the accumulated workflow result; failures that escape
     * that workflow are reported here without printing native exception details.
     * A nonzero process exit lets a developer or script distinguish failure from completion.
     */
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

    /**
     * Account setup and client lifetime, separate from the POJO walkthrough in exercise().
     * No SDK client is created until the command and required configuration have been checked.
     */
    int run(String[] args) throws IOException, InterruptedException {
        // The only argument selects Cosmos or Dynamo. There is no preview mode:
        // a valid invocation with valid settings proceeds to real database operations.
        if (args.length != 1 || !(args[0].equals("cosmos") || args[0].equals("dynamo"))) {
            System.err.println("FAIL configuration: Usage: <cosmos|dynamo>");
            return 1;
        }
        ConfigLoader.AppConfig appConfig;
        try {
            // Same loader as the other samples. The default resource follows the provider name.
            // -Dmulticlouddb.config selects a different classpath resource, and the shared loader
            // applies multiclouddb.* system-property overrides. It does not read MCDB_* variables.
            appConfig = ConfigLoader.load("serialization-" + args[0] + "-cloud.properties");
        } catch (IllegalArgumentException invalid) {
            safeError("configuration", invalid);
            return 1;
        }
        // The shared loader can return settings even when a resource is missing.
        // Check only that required values were supplied; leave naming/format rules to the SDK
        // and provider. Errors name properties, not their potentially secret values.
        List<String> requiredProperties = new ArrayList<>(List.of("multiclouddb.provider",
                "multiclouddb.database", "multiclouddb.collection", "multiclouddb.runPrefix"));
        requiredProperties.addAll(args[0].equals("cosmos")
                ? List.of("multiclouddb.connection.endpoint", "multiclouddb.connection.key")
                : List.of("multiclouddb.connection.region"));
        for (String name : requiredProperties) {
            if (appConfig.property(name) == null || appConfig.property(name).isBlank()) {
                System.err.println("FAIL configuration: Missing required property: " + name
                        + ". Include the config directory on the runtime classpath.");
                return 1;
            }
        }
        // An explicitly selected resource may belong to another provider. Reject that mismatch
        // rather than connecting somewhere different from what the command requested.
        if (!appConfig.sdk().provider().id().equals(args[0])) {
            System.err.println("FAIL configuration: Config provider must match the cosmos/dynamo argument");
            return 1;
        }
        String database = appConfig.property("multiclouddb.database");
        String collection = appConfig.property("multiclouddb.collection");
        String prefix = appConfig.property("multiclouddb.runPrefix");
        // ResourceAddress names an existing logical database/collection; this sample never
        // provisions a database, container or table. The SHA is the expected build pin:
        // the build script verifies artifacts, while these messages show runtime provenance.
        ResourceAddress address = new ResourceAddress(database, collection);
        System.out.println("Expected SDK SHA (use the pinned build script): " + SDK_SHA);
        System.out.println("SDK loaded from: " + MulticloudDbClient.class.getProtectionDomain().getCodeSource().getLocation());
        // Each run gets its own partition, isolating its writes and queries from earlier runs.
        String runId = prefix + "-" + UUID.randomUUID();
        System.out.println("LIVE provider=" + appConfig.sdk().provider().id()
                + " database=" + address.database()
                + " collection=" + address.collection() + " runId=" + runId);
        System.out.println("Existing targets only. Each confirmed-created key is deleted in finally.");
        // One client serves the whole workflow. Closing it releases SDK resources, but is
        // separate from deleting test data: exercise() owns that cleanup in its own finally block.
        MulticloudDbClient client = MulticloudDbClientFactory.create(appConfig.sdk());
        try {
            exercise(client, address, runId);
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

    /**
     * Follow this method to review customer code end to end:
     * create -> read/restore -> replacement update -> full upsert -> query/restore -> delete.
     * ExpectedDocuments is assertion scaffolding; Jackson and client calls are the integration.
     */
    void exercise(MulticloudDbClient client, ResourceAddress address,
                  String runId) throws IOException, InterruptedException {
        // The provider maps this logical key to Cosmos partitionKey/id or Dynamo partitionKey/sortKey.
        MulticloudDbKey key = MulticloudDbKey.of(runId, "jackson-baseline");
        // Track whether create returned successfully, not merely whether it was attempted.
        // step identifies the operation that failed without exposing a native exception message.
        boolean created = false;
        String step = "prepare";
        System.out.println("\nLIVE jackson/baseline"
                + " partitionKey=" + key.partitionKey() + " sortKey/id=" + key.sortKey());
        try {
            // Prepare two independent inputs: the Java object to write and hand-authored JSON
            // describing its expected data. Both receive this run's ID and initial revision.
            // Printing them makes it possible to review the inputs before judging SDK behavior.
            Order original = OrderFixtures.create(runId, 1);
            Map<String, Object> expected = ExpectedDocuments.load(runId, 1);
            System.out.println("Original POJO: " + original);
            show("Independent expected JSON", expected);
            // The SDK never receives Order: Jackson first converts its entire nested object graph.
            Map<String, Object> document = MAPPER.convertValue(original, MAP_TYPE);
            show("Jackson -> Map", document);
            // Stop before writing if application mapping already changed the expected data.
            // That failure belongs to the customer conversion, not the database round trip.
            if (!check("POJO -> Map before I/O", expected, document)) {
                return;
            }
            // create receives the address, logical key and neutral Map, not the original Order.
            // It returns no document here; the following read verifies what can be retrieved.
            step = "create";
            client.create(address, key, document);
            created = true;
            System.out.println("PASS create");
            // Intentional error examples use only our newly created key or an unwritten sibling key.
            step = "representative failure handling";
            demonstrateFailureHandling(client, address, key, document);
            // Retrieve revision 1, then check both the returned application payload and the
            // reconstructed Order. Successful retrieval alone does not prove correct mapping.
            step = "read after create";
            Map<String, Object> returned = awaitRead(client, address, key, 1);
            show("SDK read Map (including provider fields)", returned);
            // Strip only known provider-owned fields before restoring the application's Order.
            Map<String, Object> payload = ExpectedDocuments.payload(returned);
            check("read vs independent expected", expected, payload);
            Order restored = MAPPER.convertValue(payload, Order.class);
            System.out.println("Restored POJO: " + restored);
            check("restored POJO vs independent expected", expected, MAPPER.convertValue(restored, MAP_TYPE));

            // In this pinned SDK, update replaces the body; omitted customer/items must disappear.
            // Revision 2 intentionally contains too little data for a complete Order, so compare
            // it as a Map instead of trying to restore fields that were deliberately removed.
            step = "update";
            Map<String, Object> replacement = Map.of("orderId", original.orderId(),
                    "runId", runId, "revision", 2, "status", "PAID");
            show("update input: deliberately incomplete body", replacement);
            client.update(address, key, replacement);
            Map<String, Object> afterUpdate = awaitRead(client, address, key, 2);
            show("SDK read after update", afterUpdate);
            check("update REPLACES body on Cosmos/Dynamo (customer/items must be absent)",
                    replacement, ExpectedDocuments.payload(afterUpdate));

            // Restore the complete document after the deliberately incomplete replacement.
            // Revision 3 lets the read helper distinguish this write from an older visible version.
            // The input and expectation are rebuilt explicitly rather than reusing the partial Map.
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

            // Query is a separate public SDK path from point-read. Restrict it to this run,
            // expect exactly one matching document, and restore each returned payload to Order.
            // Compare against revision 3 because the full upsert was the most recent write.
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
            // An operation/conversion exception stops later steps, but still reaches finally.
            // A failed assertion instead increments the counter and allows further observations.
            failures++;
            safeError(step, failure);
            if (step.equals("create") && !created) {
                System.err.println("Create not confirmed: no delete attempted. If the write committed before an error,"
                        + " inspect ONLY the exact key printed above; never delete a conflict's existing document.");
            }
        } finally {
            // A failed create could be a conflict with someone else's data: never delete that key.
            if (created) {
                cleanup(client, address, key);
            }
        }
    }

    /**
     * Deliberately demonstrate three public API outcomes: missing read, duplicate create,
     * and an unsupported Map value. Only the expected category counts as a successful probe;
     * authentication, throttling or other unexpected errors propagate to the workflow.
     */
    void demonstrateFailureHandling(MulticloudDbClient client, ResourceAddress address,
                                    MulticloudDbKey ownedKey, Map<String, Object> document) {
        // This sibling key is never written by the sample. Missing read returns null rather
        // than throwing; the application decides how that becomes its own not-found response.
        MulticloudDbKey missingKey = MulticloudDbKey.of(ownedKey.partitionKey(),
                ownedKey.sortKey() + "-never-created");
        DocumentResult missing = client.read(address, missingKey);
        if (missing == null) {
            System.out.println("PASS missing read: null; application chooses its not-found response, no exception");
        } else {
            failures++;
            System.err.println("FAIL missing read returned a document; no write/delete attempted on that key");
        }

        // Reuse the confirmed-created key to provoke CONFLICT without creating another document.
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

    /**
     * Delete only the confirmed-created document and verify its absence with bounded reads.
     * Failed/ambiguous cleanup remains a failure and prints the exact key for manual inspection;
     * it never falls back to deleting a partition, container, database or table.
     */
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

    /**
     * Wait for the requested application revision to become visible, at most five reads.
     * Missing/stale results may be retried; exceptions propagate and payload value mismatches
     * are checked by the caller, not retried away. Metadata is printed but is not part of Order.
     */
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

    /**
     * Read bounded pages from this run's partition and retain only the requested document key.
     * A continuation token means another page may exist, even if the current page is empty.
     * Retry missing/stale revision-3 results at most five times; never scan without a page budget.
     */
    static List<Map<String, Object>> awaitQuery(MulticloudDbClient client, ResourceAddress address,
                                                String runId, String caseId) throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            List<Map<String, Object>> rows = new ArrayList<>();
            String token = null;
            int pages = 0;
            do {
                // Scope to this run's partition and follow tokens even when a page contains no items.
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

    /**
     * Record a failed comparison without throwing, so later operations can provide more evidence.
     * The final exit code reflects every recorded failure; printing later PASS lines does not erase it.
     */
    private boolean check(String label, Object expected, Object actual) {
        List<String> differences = ExpectedDocuments.differences(expected, actual);
        System.out.println((differences.isEmpty() ? "PASS " : "FAIL ") + label);
        differences.forEach(difference -> System.out.println("  " + difference));
        if (!differences.isEmpty()) {
            failures++;
        }
        return differences.isEmpty();
    }

    // JSON makes the document readable; the additional leaf types reveal Java representations
    // that JSON alone cannot distinguish (for example Integer, Long and BigDecimal).
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

    // Report the portable error separately from optional diagnostics. Their status fields
    // are not interchangeable: an error can have an HTTP status while diagnostics has none.
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
