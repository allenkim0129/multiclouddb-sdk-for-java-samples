// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Application-owned data model: no SDK or serializer annotations.
 * Records generate constructors, accessors (such as order.customer()), and value equality.
 * Fields cannot be reassigned, but referenced lists/maps are not automatically immutable.
 */
public record Order(
        // orderId is business data; runId/revision let the sample isolate and observe its writes.
        String orderId, String runId, int revision, Status status, Customer customer,
        // Nested objects, typed lists and Maps exercise different shapes in the same document.
        List<LineItem> items, Map<String, String> labels, Map<String, Object> optionalFields,
        // Jackson maps Java dates to ISO text; note intentionally demonstrates a nullable field.
        Instant placedAt, LocalDate deliveryDate, String note,
        long sequence, BigInteger externalNumber, BigDecimal total) {
    public enum Status { NEW, PAID }

    public record Customer(String name, Address address) { }

    public record Address(String city, String postalCode) { }

    public record LineItem(String sku, int quantity, BigDecimal unitPrice, List<String> tags) { }
}
