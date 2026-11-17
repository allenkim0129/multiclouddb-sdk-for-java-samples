// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiveSettingsTest {
    private static final String[] LIVE = {"live", "cosmos", "--allow-live"};
    private static final String CONFIG = """
            multiclouddb.provider=cosmos
            multiclouddb.connection.endpoint=https\\://synthetic.documents.azure.com/
            multiclouddb.connection.key=synthetic+/key\\=\\=
            multiclouddb.database=synthetic-db
            multiclouddb.collection=synthetic-container
            multiclouddb.runPrefix=pojo-test
            """;

    @TempDir
    Path directory;

    @Test
    void loadsPropertiesFromDiskWithEscapingAndWithoutEnvironment() throws Exception {
        Path config = write(CONFIG);
        LiveSettings settings = LiveSettings.load(LIVE, Map.of(), config.toString());
        assertEquals("synthetic-db", settings.address().database());
        assertEquals("synthetic-container", settings.address().collection());
        assertEquals("https://synthetic.documents.azure.com/", settings.config().connection().get("endpoint"));
        assertEquals("synthetic+/key==", settings.config().connection().get("key"));
        assertEquals("pojo-test", settings.prefix());
    }

    @Test
    void selectedFileOverridesAllStaleEnvironmentValuesAndReloadsEdits() throws Exception {
        Path config = write(CONFIG);
        Map<String, String> stale = Map.of("MCDB_DATABASE", "stale-db",
                "MCDB_COSMOS_KEY", "stale-key", "MCDB_COSMOS_ENDPOINT", "https://stale.example/");
        assertEquals("synthetic-db", LiveSettings.load(LIVE, stale, config.toString()).address().database());
        Files.writeString(config, CONFIG.replace("synthetic-db", "edited-db"), StandardCharsets.UTF_8);
        assertEquals("edited-db", LiveSettings.load(LIVE, stale, config.toString()).address().database());
    }

    @Test
    void selectedFileMustExistAndContainPrimaryKeyWithoutFallback() throws Exception {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () ->
                LiveSettings.load(LIVE, Map.of(), directory.resolve("absent.properties").toString()));
        assertTrue(missing.getMessage().contains("Cannot read Cosmos properties file"));
        Path config = write(CONFIG.replace("multiclouddb.connection.key=synthetic+/key\\=\\=", ""));
        IllegalArgumentException key = assertThrows(IllegalArgumentException.class, () ->
                LiveSettings.load(LIVE, Map.of("MCDB_COSMOS_KEY", "stale-key"), config.toString()));
        assertEquals("Missing required property: multiclouddb.connection.key", key.getMessage());
    }

    @Test
    void malformedFileDoesNotExposeSecretOrParserCause() throws Exception {
        Path config = write(CONFIG + "private=synthetic-secret-\\uINVALID\n");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                LiveSettings.load(LIVE, Map.of(), config.toString()));
        assertFalse(failure.getMessage().contains("synthetic-secret"));
        assertNull(failure.getCause());
    }

    @Test
    void acceptsUtf8BomAndRequiresMatchingProviderAndOptIn() throws Exception {
        Path config = write("\uFEFF" + CONFIG);
        assertEquals("synthetic-db", LiveSettings.load(LIVE, Map.of(), config.toString()).address().database());
        assertThrows(IllegalArgumentException.class, () ->
                LiveSettings.load(new String[]{"live", "cosmos"}, Map.of(), config.toString()));
        assertThrows(IllegalArgumentException.class, () ->
                LiveSettings.load(new String[]{"live", "dynamo", "--allow-live"}, Map.of(), config.toString()));
        Files.writeString(config, CONFIG.replace("provider=cosmos", "provider=dynamo"));
        assertThrows(IllegalArgumentException.class, () -> LiveSettings.load(LIVE, Map.of(), config.toString()));
    }

    @Test
    void missingOrRemovedModeFailsWithUsageBeforeLoadingAccountConfig() throws Exception {
        String old = System.getProperty("multiclouddb.config");
        PrintStream originalError = System.err;
        var output = new ByteArrayOutputStream();
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            System.setProperty("multiclouddb.config", directory.resolve("absent.properties").toString());
            assertEquals(1, new ComplexPojoSample().run(new String[0]));
            assertEquals(1, new ComplexPojoSample().run(new String[]{"preview"}));
        } finally {
            System.setErr(originalError);
            if (old == null) {
                System.clearProperty("multiclouddb.config");
            } else {
                System.setProperty("multiclouddb.config", old);
            }
        }
        assertEquals(2, output.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.equals("FAIL configuration: Usage: live <cosmos|dynamo> --allow-live")).count());
    }

    private Path write(String content) throws Exception {
        return Files.writeString(directory.resolve("cosmos-cloud.properties"), content, StandardCharsets.UTF_8);
    }
}
