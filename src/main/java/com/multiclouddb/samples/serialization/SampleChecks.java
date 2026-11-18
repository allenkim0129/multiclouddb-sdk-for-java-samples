// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import com.multiclouddb.samples.ConfigLoader;

import java.util.ArrayList;
import java.util.List;

/** Fail-fast sample assertions. Messages describe our checks, never native exception details. */
final class SampleChecks {
    private SampleChecks() { }

    static void checkArguments(String[] args) {
        check(args.length == 1 && (args[0].equals("cosmos") || args[0].equals("dynamo")),
                "Usage: <cosmos|dynamo>");
    }

    static void checkRequiredProperties(ConfigLoader.AppConfig config, String provider) {
        // The shared loader permits a missing resource; this live sample requires explicit targets.
        // Leave property formats and provider-specific naming rules to the SDK/service.
        List<String> names = new ArrayList<>(List.of("multiclouddb.provider",
                "multiclouddb.database", "multiclouddb.collection", "multiclouddb.runPrefix"));
        names.addAll(provider.equals("cosmos")
                ? List.of("multiclouddb.connection.endpoint", "multiclouddb.connection.key")
                : List.of("multiclouddb.connection.region"));

        for (String name : names) {
            check(config.property(name) != null && !config.property(name).isBlank(),
                    "Missing required property: " + name + ". Include the config directory on the runtime classpath.");
        }

        check(config.sdk().provider().id().equals(provider),
                "Config provider must match the cosmos/dynamo argument");
    }

    static void checkEquals(String label, Object expected, Object actual) {
        List<String> differences = ExpectedDocuments.differences(expected, actual);
        check(differences.isEmpty(), label + "\n  " + String.join("\n  ", differences));

        System.out.println("PASS " + label);
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            // Unlike Java's assert keyword, this is always active without a JVM -ea flag.
            throw new AssertionError(message);
        }
    }
}
