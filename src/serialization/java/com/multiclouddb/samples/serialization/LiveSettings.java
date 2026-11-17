// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;

import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

record LiveSettings(ProviderId provider, ResourceAddress address,
                    MulticloudDbClientConfig config, String prefix) {
    static final Path DEFAULT_COSMOS_CONFIG =
            Path.of("src/main/resources/serialization-cosmos-cloud.properties");

    static boolean preview(String[] args) {
        if (includeNumericLimits(args)) {
            args = Arrays.copyOf(args, args.length - 1);
            if (args.length == 0) {
                throw new IllegalArgumentException("Specify preview or live before --include-numeric-limits");
            }
        }
        if (args.length == 0 || Arrays.equals(args, new String[]{"preview"})) {
            return true;
        }
        if (args.length != 3 || !args[0].equals("live")
                || !(args[1].equals("cosmos") || args[1].equals("dynamo"))
                || !args[2].equals("--allow-live")) {
            throw new IllegalArgumentException(
                    "Usage: preview [--include-numeric-limits]"
                            + " | live <cosmos|dynamo> --allow-live [--include-numeric-limits]");
        }
        return false;
    }

    static boolean includeNumericLimits(String[] args) {
        return args.length > 0 && args[args.length - 1].equals("--include-numeric-limits");
    }

    static LiveSettings load(String[] args, Map<String, String> env) {
        if (preview(args)) {
            throw new IllegalArgumentException("Preview must not load account configuration");
        }
        String database = identifier(env, "MCDB_DATABASE");
        String collection = identifier(env, "MCDB_COLLECTION");
        String prefix = required(env, "MCDB_RUN_PREFIX");
        if (!prefix.matches("[A-Za-z0-9-]{1,32}")) {
            throw new IllegalArgumentException("MCDB_RUN_PREFIX must be 1-32 letters, digits or hyphens");
        }
        ProviderId provider = args[1].equals("cosmos") ? ProviderId.COSMOS : ProviderId.DYNAMO;
        var config = MulticloudDbClientConfig.builder().provider(provider);
        if (provider.equals(ProviderId.COSMOS)) {
            String endpoint = required(env, "MCDB_COSMOS_ENDPOINT");
            URI uri;
            try {
                uri = URI.create(endpoint);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("MCDB_COSMOS_ENDPOINT must be an HTTPS account URL");
            }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                throw new IllegalArgumentException("MCDB_COSMOS_ENDPOINT must be an HTTPS account URL");
            }
            config.connection("endpoint", endpoint);
            String key = env.get("MCDB_COSMOS_KEY");
            if (key != null && !key.isBlank()) {
                config.connection("key", key);
            }
        } else {
            String region = required(env, "MCDB_AWS_REGION");
            if (!region.matches("[a-z0-9-]+")) {
                throw new IllegalArgumentException("MCDB_AWS_REGION must be an AWS region identifier");
            }
            config.connection("region", region);
        }
        return new LiveSettings(provider, new ResourceAddress(database, collection), config.build(), prefix);
    }

    static LiveSettings load(String[] args, Map<String, String> env, String configFile) {
        if (preview(args)) {
            throw new IllegalArgumentException("Preview must not load account configuration");
        }
        if (configFile != null) {
            if (configFile.isBlank()) {
                throw new IllegalArgumentException("multiclouddb.config must be a non-empty filesystem path");
            }
            return fromProperties(args, Path.of(configFile));
        }
        if (args[1].equals("cosmos") && Files.exists(DEFAULT_COSMOS_CONFIG)) {
            return fromProperties(args, DEFAULT_COSMOS_CONFIG);
        }
        return load(args, env);
    }

    private static LiveSettings fromProperties(String[] args, Path path) {
        if (!args[1].equals("cosmos")) {
            throw new IllegalArgumentException("The file-based sample configuration supports live cosmos only");
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            // Accept both BOM-free UTF-8 and files saved by Windows PowerShell editors.
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            properties.load(reader);
        } catch (IOException | IllegalArgumentException failure) {
            // Do not attach parser exceptions: malformed input may contain the primary key.
            throw new IllegalArgumentException(
                    "Cannot read Cosmos properties file; check its path, access and syntax locally");
        }
        if (!"cosmos".equals(property(properties, "multiclouddb.provider"))) {
            throw new IllegalArgumentException("multiclouddb.provider must be cosmos for this configuration");
        }
        Map<String, String> values = Map.of(
                "MCDB_DATABASE", property(properties, "multiclouddb.database"),
                "MCDB_COLLECTION", property(properties, "multiclouddb.collection"),
                "MCDB_RUN_PREFIX", property(properties, "multiclouddb.runPrefix"),
                "MCDB_COSMOS_ENDPOINT", property(properties, "multiclouddb.connection.endpoint"),
                "MCDB_COSMOS_KEY", property(properties, "multiclouddb.connection.key"));
        // A selected file is authoritative; never combine it with stale environment credentials/targets.
        return load(args, values);
    }

    private static String property(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + name);
        }
        return value;
    }

    private static String identifier(Map<String, String> env, String name) {
        String value = required(env, name);
        if (!value.matches("[A-Za-z0-9_-]{1,63}")) {
            throw new IllegalArgumentException(name + " must be 1-63 letters, digits, underscores or hyphens");
        }
        return value;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable: " + name);
        }
        return value;
    }
}
