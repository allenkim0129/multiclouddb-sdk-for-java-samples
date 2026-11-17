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
        String orderId, String runId, int revision, Status status, Customer customer,
        List<LineItem> items, Map<String, String> labels, Map<String, Object> optionalFields,
        Instant placedAt, LocalDate deliveryDate, String note,
        long sequence, BigInteger externalNumber, BigDecimal total) {
    public enum Status { NEW, PAID }
    public record Customer(String name, Address address) { }
    public record Address(String city, String postalCode) { }
    public record LineItem(String sku, int quantity, BigDecimal unitPrice, List<String> tags) { }
}
