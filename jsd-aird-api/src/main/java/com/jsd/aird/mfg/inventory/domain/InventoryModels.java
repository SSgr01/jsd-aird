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
                          AlertStatus alertStatus, long version, Instant updatedAt) {}
    public record Summary(BigDecimal totalKg, long productCount, long lowStockCount, long outOfStockCount) {}
    public record ProductOption(UUID id,String code,String name,String category,String status) {}
    public record BalancePage(List<Balance> items, long page, long size, long total, long totalPages, Summary summary) {}
    public record Transaction(UUID id, UUID productId, String productCode, String productName, String category,
                              Scope scope, Direction direction, String reason, BigDecimal quantityKg,
                              BigDecimal beforeKg, BigDecimal afterKg, LocalDate businessDate, String documentNo,
                              String businessType, UUID businessId, UUID reversalOf, String note, UUID actorId,
                              String actorName, Instant createdAt, boolean reversed) {}
    public record Page<T>(List<T> items, long page, long size, long total, long totalPages) {}
    public record Movement(UUID productId, Scope scope, Direction direction, String reason, BigDecimal quantityKg,
                           LocalDate businessDate, String documentNo, String businessType, UUID businessId, String note) {}
    public record Sample(UUID id, String dispatchNo, LocalDate businessDate, UUID productId, String productName,
                         BigDecimal quantityKg, UUID customerId, String customerName, String recipientName, String sampleAddress,
                         String courierCompany, String trackingNo, String clientContact, String customerRequirement,
                         String customerFeedback, UUID transactionId, String operator, Instant createdAt) {}
    public record Shipment(UUID id, String shipmentNo, LocalDate businessDate, UUID productId, String productName,
                           BigDecimal quantityKg, UUID customerId, String customerName, String note, UUID transactionId,
                           String operator, Instant createdAt) {}
}
