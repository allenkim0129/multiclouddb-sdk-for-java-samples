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

/**
 * Visible synthetic input for the walkthrough; edit alongside resources/fixtures/baseline.json.
 * runId isolates each execution; revision identifies the create/update/upsert version being read.
 */
public final class OrderFixtures {
    private OrderFixtures() { }

    public static Order create(String runId, int revision) {
        // Keep numeric values ordinary: this walkthrough tests object structure, not number limits.
        // Decimal string constructors also make the intended sample values explicit.
        long sequence = 12345L;
        BigDecimal total = new BigDecimal("125.50");
        BigInteger externalNumber = new BigInteger("42");

        // Map.of rejects null values; this map intentionally tests an explicit JSON null.
        Map<String, Object> optionalFields = new LinkedHashMap<>();
        optionalFields.put("giftMessage", null);

        // "coupon" is deliberately absent; null and missing are checked separately.
        // The leading-zero postal code is text, tags include both populated and empty lists,
        // and dates exercise Instant plus LocalDate. All values are synthetic and editable here.
        return new Order("synthetic-order-baseline", runId, revision, Order.Status.NEW,
                new Order.Customer("Synthetic Customer",
                        new Order.Address("Example City", "00123")),
                List.of(new Order.LineItem("SYNTHETIC-A", 2, new BigDecimal("50.25"), List.of("demo", "fragile")),
                        new Order.LineItem("SYNTHETIC-B", 1, new BigDecimal("25.00"), List.of())),
                Map.of("channel", "sample", "region", "test"), optionalFields,
                Instant.parse("2026-01-02T03:04:05.123456789Z"), LocalDate.parse("2026-01-05"),
                null, sequence, externalNumber, total);
    }
}
