package com.jsd.aird.mfg.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InventoryModels {
    private InventoryModels() {}
    public enum Scope { RND, PRODUCTION }
    public enum Direction { INBOUND, OUTBOUND }
    public enum AlertStatus { NORMAL, LOW_STOCK, OUT_OF_STOCK }

    public record Balance(UUID productId, String productCode, String productName, String category,
                          Scope scope, BigDecimal quantityKg, BigDecimal packageKg, BigDecimal lowStockKg,
                          AlertStatus alertStatus, long version, Instant updatedAt) { public String getPolicyRevision(){return com.jsd.aird.shared.json.RevisionToken.of(productId,scope,packageKg,lowStockKg);} }
    public record Summary(BigDecimal totalKg, long productCount, long lowStockCount, long outOfStockCount) {}
    public record ProductOption(UUID id,String code,String name,String category,String status) {}
    public record BalancePage(List<Balance> items, long page, long size, long total, long totalPages, Summary summary) {}
    public record Transaction(UUID id, UUID productId, String productCode, String productName, String category,
                              Scope scope, Direction direction, String reason, BigDecimal quantityKg,
                              BigDecimal beforeKg, BigDecimal afterKg, LocalDate businessDate, String documentNo,
                              String businessType, UUID businessId, UUID reversalOf, String note, UUID actorId,
                              String actorName, Instant createdAt, boolean reversed) { public String getRevision(){return com.jsd.aird.shared.json.RevisionToken.ofRecord(this);} }
    public record Page<T>(List<T> items, long page, long size, long total, long totalPages) {}
    public record Movement(UUID productId, Scope scope, Direction direction, String reason, BigDecimal quantityKg,
                           LocalDate businessDate, String documentNo, String businessType, UUID businessId, String note,
                           String actorName) {}
    public record Sample(UUID id, String dispatchNo, LocalDate businessDate, UUID productId, String productName, String specification,
                         Scope scope, BigDecimal quantityKg, BigDecimal packageQuantity, UUID customerId, String customerName, String recipientName,
                         String recipientPhone, String sampleAddress, String courierCompany, String trackingNo,
                         String clientContact, String clientContactPhone, String senderName, String customerRequirement,
                         String customerFeedback, String status, UUID transactionId, String operator, Instant createdAt) { public String getRevision(){return com.jsd.aird.shared.json.RevisionToken.ofRecord(this);} }
    public record Shipment(UUID id, String shipmentNo, LocalDate businessDate, UUID productId, String productName,
                           Scope scope, BigDecimal quantityKg, UUID customerId, String customerName, String senderName,
                           String batchNo, String specification, BigDecimal packageQuantity, BigDecimal totalQuantityKg,
                           String recipientName, String recipientPhone, String shippingAddress,
                           String courierCompany, String trackingNo, String note, String status,
                           UUID transactionId, String operator, Instant createdAt, String coaNo) {
        public Shipment(UUID id, String shipmentNo, LocalDate businessDate, UUID productId, String productName,
                        Scope scope, BigDecimal quantityKg, UUID customerId, String customerName, String senderName,
                        String batchNo, String specification, BigDecimal packageQuantity, BigDecimal totalQuantityKg,
                        String recipientName, String recipientPhone, String shippingAddress,
                        String courierCompany, String trackingNo, String note, String status,
                        UUID transactionId, String operator, Instant createdAt) {
            this(id, shipmentNo, businessDate, productId, productName, scope, quantityKg, customerId, customerName,
                    senderName, batchNo, specification, packageQuantity, totalQuantityKg, recipientName,
                    recipientPhone, shippingAddress, courierCompany, trackingNo, note, status, transactionId,
                    operator, createdAt, null);
        }
        public String getRevision(){return com.jsd.aird.shared.json.RevisionToken.ofRecord(this);}
    }
    public record ShipmentHeader(UUID id, String shipmentNo, LocalDate businessDate, String note,
                                 long detailCount, long completedCount, BigDecimal totalQuantityKg) {}
}
