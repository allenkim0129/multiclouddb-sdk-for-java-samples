// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.changefeed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import java.io.UncheckedIOException;

/** The PR requires the application to supply cursor JSON serialization, too. */
final class CursorSerialization {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CursorSerialization() { }

    static String toToken(ChangeFeedCursor cursor) {
        return cursor.toToken(value -> {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException failure) {
                throw new UncheckedIOException("Could not serialize change-feed cursor", failure);
            }
        });
    }

    static ChangeFeedCursor fromToken(String token) {
        return ChangeFeedCursor.fromToken(token, (json, type) -> {
            try {
                return MAPPER.readValue(json, type);
            } catch (JsonProcessingException failure) {
                throw new UncheckedIOException("Could not deserialize change-feed cursor", failure);
            }
        });
    }
}
