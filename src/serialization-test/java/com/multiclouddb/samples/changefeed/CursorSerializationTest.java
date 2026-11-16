// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.changefeed;

import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import com.multiclouddb.api.MulticloudDbKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CursorSerializationTest {
    @Test
    void migratedCallbacksRoundTripPublicCursorWithoutAccount() {
        ChangeFeedCursor cursor = ChangeFeedCursor.now();
        ChangeFeedCursor restored = CursorSerialization.fromToken(CursorSerialization.toToken(cursor));
        assertTrue(restored.isUnhydratedSentinel());
        assertEquals(CursorSerialization.toToken(cursor), CursorSerialization.toToken(restored));
    }

    @Test
    void malformedCursorUsesCurrentPublicFailureContract() {
        assertThrows(CursorExpiredException.class, () -> CursorSerialization.fromToken("not-a-valid-token"));
    }

    @Test
    void watcherRendersNeutralNestedPayloadAndPreservesEmptyVersusMissingData() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note", null);
        payload.put("items", List.of(Map.of("sku", "synthetic")));
        String nested = ChangeFeedWatcherSample.formatEvent(event(payload), 0);
        assertTrue(nested.contains("\"note\":null"));
        assertTrue(nested.contains("\"items\":[{\"sku\":\"synthetic\"}]"));
        assertTrue(ChangeFeedWatcherSample.formatEvent(event(Map.of()), 0).endsWith("  {}"));
        assertFalse(ChangeFeedWatcherSample.formatEvent(event(null), 0).contains("{}"));
    }

    private static ChangeEvent event(Object data) {
        return new ChangeEvent(MulticloudDbKey.of("synthetic", "event"), ChangeType.CREATE,
                Instant.parse("2026-01-02T03:04:05Z"), data, "synthetic-event-id");
    }
}
