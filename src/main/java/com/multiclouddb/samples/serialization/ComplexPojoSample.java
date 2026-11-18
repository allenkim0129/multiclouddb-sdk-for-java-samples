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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.multiclouddb.samples.serialization.SampleChecks.check;
import static com.multiclouddb.samples.serialization.SampleChecks.checkEquals;

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

    /**
     * Failures propagate from the workflow and end the process with an exception.
     * This single boundary removes native messages/causes that could expose account credentials.
     */
    public static void main(String[] args) {
        try {
            new ComplexPojoSample().run(args);
        } catch (Exception | AssertionError failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            String message = failure instanceof AssertionError ? failure.getMessage()
                    : failure.getClass().getSimpleName()
                    + (failure instanceof MulticloudDbException sdk ? " " + errorSummary(sdk) : "");

            // Do not attach the original cause or suppressed exceptions: they may contain secrets.
            throw new IllegalStateException("FAIL " + message
                    + (failure.getSuppressed().length == 0 ? "" : "; additional cleanup/client-close failures="
                    + failure.getSuppressed().length + "; inspect the printed document key")
                    + " (native details suppressed)");
        }
    }

    /**
     * Account setup and client lifetime, separate from the POJO walkthrough in exercise().
     * No SDK client is created until the command and required configuration have been checked.
     */
    void run(String[] args) throws Exception {
        // The only argument selects Cosmos or Dynamo. There is no preview mode:
        // a valid invocation with valid settings proceeds to real database operations.
        SampleChecks.checkArguments(args);

        // Same loader as the other samples; -Dmulticlouddb.config selects another resource.
        ConfigLoader.AppConfig appConfig = ConfigLoader.load("serialization-" + args[0] + "-cloud.properties");
        SampleChecks.checkRequiredProperties(appConfig, args[0]);

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
        System.out.println("Existing targets only. Automatic cleanup deletes only a confirmed-created key.");

        // One client serves the whole workflow. Closing it releases SDK resources, but is
        // separate from deleting test data: exercise() owns its document cleanup.
        try (MulticloudDbClient client = MulticloudDbClientFactory.create(appConfig.sdk())) {
            exercise(client, address, runId);
        }

        System.out.println("PASS: all checks and cleanup completed");
    }

    /**
     * Follow this method to review customer code end to end:
     * create -> read/restore -> replacement update -> full upsert -> query/restore -> delete.
     * ExpectedDocuments is assertion scaffolding; Jackson and client calls are the integration.
     */
    void exercise(MulticloudDbClient client, ResourceAddress address,
                  String runId) throws Exception {
        // The provider maps this logical key to Cosmos partitionKey/id or Dynamo partitionKey/sortKey.
        MulticloudDbKey key = MulticloudDbKey.of(runId, "jackson-baseline");
        System.out.println("\nLIVE jackson/baseline"
                + " partitionKey=" + key.partitionKey() + " sortKey/id=" + key.sortKey());

        // Prepare the Java input and independent JSON expectation for this run and revision.
        Order original = OrderFixtures.create(runId, 1);
        Map<String, Object> expected = ExpectedDocuments.load(runId, 1);
        System.out.println("Original POJO: " + original);
        show("Independent expected JSON", expected);

        // The SDK receives a Map, not Order. A mapping mismatch throws before any write.
        Map<String, Object> document = MAPPER.convertValue(original, MAP_TYPE);
        show("Jackson -> Map", document);
        checkEquals("POJO -> Map before I/O", expected, document);

        // Only a successful create authorizes cleanup. If create throws, no delete is attempted.
        // For an ambiguous write, inspect this exact key manually; never delete a conflict's data.
        client.create(address, key, document);

        // Register deletion immediately after create. try-with-resources runs it on success or
        // failure, like finally, and preserves the original error if cleanup also throws.
        try (AutoCloseable ownedDocument = () -> cleanup(client, address, key)) {
            System.out.println("PASS create");

            // Intentional error examples use only our newly created key or an unwritten sibling key.
            demonstrateFailureHandling(client, address, key, document);

            // Retrieve revision 1, then check both the returned application payload and the
            // reconstructed Order. Successful retrieval alone does not prove correct mapping.
            Map<String, Object> returned = awaitRead(client, address, key, 1);
            show("SDK read Map (including provider fields)", returned);

            // Strip only known provider-owned fields before restoring the application's Order.
            Map<String, Object> payload = ExpectedDocuments.payload(returned);
            checkEquals("read vs independent expected", expected, payload);

            Order restored = MAPPER.convertValue(payload, Order.class);
            System.out.println("Restored POJO: " + restored);
            checkEquals("restored POJO vs independent expected", expected, MAPPER.convertValue(restored, MAP_TYPE));

            // In this pinned SDK, update replaces the body; omitted customer/items must disappear.
            // Revision 2 intentionally contains too little data for a complete Order, so compare
            // it as a Map instead of trying to restore fields that were deliberately removed.
            Map<String, Object> replacement = Map.of("orderId", original.orderId(),
                    "runId", runId, "revision", 2, "status", "PAID");
            show("update input: deliberately incomplete body", replacement);
            client.update(address, key, replacement);

            Map<String, Object> afterUpdate = awaitRead(client, address, key, 2);
            show("SDK read after update", afterUpdate);
            checkEquals("update REPLACES body on Cosmos/Dynamo (customer/items must be absent)",
                    replacement, ExpectedDocuments.payload(afterUpdate));

            // Restore the complete document after the deliberately incomplete replacement.
            // Revision 3 lets the read helper distinguish this write from an older visible version.
            // The input and expectation are rebuilt explicitly rather than reusing the partial Map.
            Order restoredOrder = OrderFixtures.create(runId, 3);
            Map<String, Object> upsertDocument = MAPPER.convertValue(restoredOrder, MAP_TYPE);
            show("upsert input: complete POJO mapped again", upsertDocument);
            client.upsert(address, key, upsertDocument);

            expected = ExpectedDocuments.load(runId, 3);
            Map<String, Object> afterUpsert = awaitRead(client, address, key, 3);
            show("SDK read after upsert", afterUpsert);
            checkEquals("upsert vs independent expected", expected, ExpectedDocuments.payload(afterUpsert));

            Order upserted = MAPPER.convertValue(ExpectedDocuments.payload(afterUpsert), Order.class);
            System.out.println("Restored POJO: " + upserted);
            checkEquals("restored POJO vs independent expected", expected, MAPPER.convertValue(upserted, MAP_TYPE));

            // Query is a separate public SDK path from point-read. Restrict it to this run,
            // expect exactly one matching document, and restore each returned payload to Order.
            // Compare against revision 3 because the full upsert was the most recent write.
            List<Map<String, Object>> rows = awaitQuery(client, address, runId, key.sortKey());
            checkEquals("query row count in this run/case", 1, rows.size());

            for (Map<String, Object> row : rows) {
                show("SDK query row", row);
                Map<String, Object> queryPayload = ExpectedDocuments.payload(row);
                checkEquals("query vs independent expected", expected, queryPayload);

                Order queried = MAPPER.convertValue(queryPayload, Order.class);
                System.out.println("Restored POJO: " + queried);
                checkEquals("restored POJO vs independent expected", expected, MAPPER.convertValue(queried, MAP_TYPE));
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
        check(missing == null, "Missing read returned a document; no write/delete attempted on that key");
        System.out.println("PASS missing read: null; application chooses its not-found response, no exception");

        // Reuse the confirmed-created key to provoke CONFLICT without creating another document.
        try {
            client.create(address, ownedKey, document);
            throw new AssertionError("Duplicate create unexpectedly succeeded");
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
            throw new AssertionError("Unsupported Map value unexpectedly accepted");
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
        System.out.println("CLEANUP exact key: " + key.partitionKey() + "/" + key.sortKey());
        client.delete(address, key);

        for (int attempt = 0; attempt < 5; attempt++) {
            if (client.read(address, key) == null) {
                System.out.println("PASS cleanup: deleted own key " + key.partitionKey() + "/" + key.sortKey());
                return;
            }
            Thread.sleep(250);
        }

        throw new AssertionError("Deleted document still visible after bounded reads");
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
        throw new AssertionError("Expected revision not visible after bounded reads");
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
                    throw new AssertionError("Query page budget exceeded; no unbounded scan");
                }
            } while (token != null);

            if (!rows.isEmpty() && rows.stream().allMatch(row ->
                    ExpectedDocuments.differences(3, row.get("revision")).isEmpty())) {
                return rows;
            }

            System.out.println("WAIT query: missing/stale rows, attempt " + (attempt + 1));
            Thread.sleep(250);
        }
        throw new AssertionError("Expected query rows not visible after bounded queries");
    }

    // JSON makes the document readable; the additional leaf types reveal Java representations
    // that JSON alone cannot distinguish (for example Integer, Long and BigDecimal).
    private static void show(String label, Map<String, Object> map) throws JsonProcessingException {
        System.out.println(label + ":\n" + MAPPER
                .writerWithDefaultPrettyPrinter().writeValueAsString(map));
        ExpectedDocuments.printTypes("$", map);
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
