// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Mutable application-owned POJOs with no SDK or serializer annotations. */
public class Order {
    // runId/revision let the sample isolate and observe its writes.
    private String orderId;
    private String runId;
    private int revision;
    private Status status;
    private Customer customer;
    private List<LineItem> items;
    private Map<String, String> labels;
    private Map<String, Object> optionalFields;
    private Instant placedAt;
    private LocalDate deliveryDate;
    private String note;
    private long sequence;
    private BigInteger externalNumber;
    private BigDecimal total;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Customer getCustomer() { return customer; }
    public void setCustomer(Customer customer) { this.customer = customer; }

    public List<LineItem> getItems() { return items; }
    public void setItems(List<LineItem> items) { this.items = items; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public Map<String, Object> getOptionalFields() { return optionalFields; }
    public void setOptionalFields(Map<String, Object> optionalFields) { this.optionalFields = optionalFields; }

    public Instant getPlacedAt() { return placedAt; }
    public void setPlacedAt(Instant placedAt) { this.placedAt = placedAt; }

    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public BigInteger getExternalNumber() { return externalNumber; }
    public void setExternalNumber(BigInteger externalNumber) { this.externalNumber = externalNumber; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public enum Status { NEW, PAID }

    public static class Customer {
        private String name;
        private Address address;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Address getAddress() { return address; }
        public void setAddress(Address address) { this.address = address; }
    }

    public static class Address {
        private String city;
        private String postalCode;

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }

        public String getPostalCode() { return postalCode; }
        public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    }

    public static class LineItem {
        private String sku;
        private int quantity;
        private BigDecimal unitPrice;
        private List<String> tags;

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }

        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }

        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }
}
