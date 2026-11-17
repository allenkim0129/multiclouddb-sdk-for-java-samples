// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ExpectedDocuments {
    private static final Set<String> PROVIDER_FIELDS =
            Set.of("id", "partitionKey", "sortKey", "_rid", "_self", "_etag", "_attachments", "_ts");

    private ExpectedDocuments() { }

    static Map<String, Object> load(String runId, int revision) throws IOException {
        try (var input = ExpectedDocuments.class.getResourceAsStream("/fixtures/baseline.json")) {
            if (input == null) {
                throw new IOException("Missing expected fixture: baseline.json");
            }
            Map<String, Object> expected = CustomerSerializers.jackson()
                    .readValue(input, CustomerSerializers.MAP_TYPE);
            expected.put("runId", runId);
            expected.put("revision", revision);
            return expected;
        }
    }

    static Map<String, Object> payload(Map<String, Object> returned) {
        Map<String, Object> result = new LinkedHashMap<>(returned);
        PROVIDER_FIELDS.forEach(result::remove);
        return result;
    }

    static List<String> differences(Object expected, Object actual) {
        List<String> result = new ArrayList<>();
        compare("$", expected, actual, result);
        return result;
    }

    private static void compare(String path, Object expected, Object actual, List<String> errors) {
        if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
            if (!left.keySet().equals(right.keySet())) {
                errors.add(path + " keys expected=" + left.keySet() + " actual=" + right.keySet());
            }
            left.forEach((key, value) -> {
                if (right.containsKey(key)) {
                    compare(path + "." + key, value, right.get(key), errors);
                }
            });
        } else if (expected instanceof List<?> left && actual instanceof List<?> right) {
            if (left.size() != right.size()) {
                errors.add(path + " list size expected=" + left.size() + " actual=" + right.size());
            }
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                compare(path + "[" + i + "]", left.get(i), right.get(i), errors);
            }
        } else if (expected instanceof Number left && actual instanceof Number right) {
            // Exact decimal comparison, never a double conversion or epsilon.
            if (new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) != 0) {
                errors.add(path + " value mismatch expected=" + left + " actual=" + right);
            }
        } else if (!Objects.equals(expected, actual)) {
            errors.add(path + " expected=" + expected + " actual=" + actual);
        }
    }

    static void printTypes(String path, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> printTypes(path + "." + key, child));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                printTypes(path + "[" + i + "]", list.get(i));
            }
        } else {
            System.out.println("  " + path + " = " + value + " ["
                    + (value == null ? "null" : value.getClass().getSimpleName()) + "]");
        }
    }
}
