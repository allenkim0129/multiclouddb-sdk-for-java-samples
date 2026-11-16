// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Edit these synthetic inputs and the independent expected JSON together. */
public final class OrderFixtures {
    public static final List<String> CASES = List.of("baseline", "precision", "large-integer");

    private OrderFixtures() { }

    public static Order create(String scenario, String runId, int revision) {
        if (!CASES.contains(scenario)) {
            throw new IllegalArgumentException("Unknown fixture");
        }
        long sequence = scenario.equals("precision") ? 9007199254740993L : 9007199254740991L;
        BigDecimal total = new BigDecimal(scenario.equals("precision") ? "12345.67890123456789" : "125.50");
        BigInteger externalNumber = new BigInteger(
                scenario.equals("large-integer") ? "18446744073709551617" : "42");
        Map<String, Object> optionalFields = new LinkedHashMap<>();
        optionalFields.put("giftMessage", null);
        // "coupon" is deliberately absent; null and missing are checked separately.
        return new Order("synthetic-order-" + scenario, runId, revision, Order.Status.NEW,
                new Order.Customer("Synthetic Customer",
                        new Order.Address("Example City", "00123")),
                List.of(new Order.LineItem("SYNTHETIC-A", 2, new BigDecimal("50.25"), List.of("demo", "fragile")),
                        new Order.LineItem("SYNTHETIC-B", 1, new BigDecimal("25.00"), List.of())),
                Map.of("channel", "sample", "region", "test"), optionalFields,
                Instant.parse("2026-01-02T03:04:05.123456789Z"), LocalDate.parse("2026-01-05"),
                null, sequence, externalNumber, total, Long.toString(sequence), total.toPlainString());
    }
}
