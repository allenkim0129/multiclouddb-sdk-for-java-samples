// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;

record LiveSettings(ProviderId provider, ResourceAddress address,
                    MulticloudDbClientConfig config, String prefix) {
    static boolean preview(String[] args) {
        if (args.length == 0 || Arrays.equals(args, new String[]{"preview"})) {
            return true;
        }
        if (args.length != 3 || !args[0].equals("live")
                || !(args[1].equals("cosmos") || args[1].equals("dynamo"))
                || !args[2].equals("--allow-live")) {
            throw new IllegalArgumentException(
                    "Usage: preview | live <cosmos|dynamo> --allow-live");
        }
        return false;
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
