// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.changefeed;

import com.multiclouddb.api.changefeed.ChangeFeedCursor;

/** Released SDK compatibility; the serialization-pr profile uses customer-owned JSON instead. */
final class CursorSerialization {
    private CursorSerialization() { }

    static String toToken(ChangeFeedCursor cursor) {
        return cursor.toToken();
    }

    static ChangeFeedCursor fromToken(String token) {
        return ChangeFeedCursor.fromToken(token);
    }
}
