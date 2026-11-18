// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ComplexPojoConfigTest {
    private static final String CONFIG = """
            multiclouddb.provider=cosmos
            multiclouddb.connection.endpoint=https://synthetic.invalid/
            multiclouddb.connection.key=synthetic-private-key
            multiclouddb.database=synthetic-db
            multiclouddb.collection=synthetic-container
            multiclouddb.runPrefix=pojo-test
            """;

    @TempDir
    Path directory;

    @Test
    void requiresOneSupportedProviderBeforeLoadingProperties() throws Exception {
        for (List<String> args : List.of(
                List.<String>of(), List.of("preview"), List.of("spanner"),
                List.of("cosmos", "--allow-live"), List.of("dynamo", "--unknown"),
                List.of("live", "cosmos"))) {
            String output = rejected(args, Map.of());
            assertTrue(output.contains("IllegalStateException: FAIL Usage: <cosmos|dynamo>"), output);
        }
    }

    @Test
    void missingResourceDoesNotUseLegacyEnvironmentTargets() throws Exception {
        String output = rejected(List.of("cosmos"), Map.of());
        assertTrue(output.contains("\u2014 using system properties only"), output);
        assertTrue(output.contains("Missing required property: multiclouddb.provider"), output);
    }

    @Test
    void classpathPropertiesRequireValuesAndMatchingProviderWithoutLeakingSecrets() throws Exception {
        Map<String, String> cases = Map.of(
                CONFIG.replace("synthetic-private-key", ""), "Missing required property: multiclouddb.connection.key",
                CONFIG.replace("https://synthetic.invalid/", ""),
                "Missing required property: multiclouddb.connection.endpoint",
                CONFIG.replace("multiclouddb.database=synthetic-db", ""), "Missing required property: multiclouddb.database",
                CONFIG.replace("provider=cosmos", "provider=dynamo"), "Config provider must match");
        for (var entry : cases.entrySet()) {
            Files.writeString(directory.resolve("serialization-cosmos-cloud.properties"), entry.getKey());
            String output = rejected(List.of("cosmos"), Map.of());
            assertTrue(output.contains(entry.getValue()), output);
        }
    }

    @Test
    void sharedLoaderAcceptsResourceSelectionAndSystemPropertyOverrides() throws Exception {
        Files.writeString(directory.resolve("chosen.properties"),
                CONFIG.replace("synthetic-db", "").replace("synthetic-private-key", ""));
        String output = rejected(List.of("cosmos"), Map.of(
                "multiclouddb.config", "chosen.properties", "multiclouddb.database", "overridden-db"));
        assertTrue(output.contains("Missing required property: multiclouddb.connection.key"), output);
        assertFalse(output.contains("Missing required property: multiclouddb.database"), output);
    }

    @Test
    void sampleDoesNotRestrictNamesPrefixOrEndpointFormat() throws Exception {
        // A missing key prevents any connection; the other values must pass the sample's checks.
        Files.writeString(directory.resolve("serialization-cosmos-cloud.properties"), CONFIG
                .replace("synthetic-db", "database.with.dots." + "a".repeat(64))
                .replace("synthetic-container", "orders.with.dots")
                .replace("pojo-test", "review_prefix_" + "b".repeat(32))
                .replace("https://synthetic.invalid/", "not-a-url")
                .replace("synthetic-private-key", ""));
        String output = rejected(List.of("cosmos"), Map.of());
        assertTrue(output.contains("Missing required property: multiclouddb.connection.key"), output);
    }

    @Test
    void dynamoUsesItsPropertiesResourceRatherThanLegacyRegionEnvironment() throws Exception {
        Files.writeString(directory.resolve("serialization-dynamo-cloud.properties"),
                CONFIG.replace("provider=cosmos", "provider=dynamo")
                        + "multiclouddb.connection.region=\n");
        String output = rejected(List.of("dynamo"), Map.of());
        assertTrue(output.contains("Missing required property: multiclouddb.connection.region"), output);
    }

    // Every invocation deliberately fails validation before creating any SDK client.
    private String rejected(List<String> args, Map<String, String> overrides) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        // Match Files.readString below, including on Windows JDK 17 with a non-UTF-8 default.
        command.add("-Dfile.encoding=UTF-8");
        overrides.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                + File.pathSeparator + directory);
        command.add(ComplexPojoSample.class.getName());
        command.addAll(args);
        Path log = Files.createTempFile(directory, "config-check-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("MCDB_DATABASE", "stale-db", "MCDB_COLLECTION", "stale-container",
                "MCDB_RUN_PREFIX", "stale", "MCDB_COSMOS_ENDPOINT", "https://stale.invalid/",
                "MCDB_COSMOS_KEY", "synthetic-private-key", "MCDB_AWS_REGION", "us-east-1"));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Config validation timed out");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertEquals(1, process.exitValue(), output);
        assertFalse(output.contains("LIVE provider="), output);
        assertFalse(output.contains("synthetic-private-key"), output);
        return output;
    }
}
