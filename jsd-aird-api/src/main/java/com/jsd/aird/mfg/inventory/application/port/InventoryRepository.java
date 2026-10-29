package com.jsd.aird.mfg.inventory.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository {
    void lockTransaction(UUID org,UUID id);

    boolean productExists(UUID org,UUID productId,Scope scope);
    java.util.List<ProductOption> productOptions(UUID org,Scope scope,String keyword);
    ProductOption createProduct(UUID org,String name,Scope scope,UUID actor);
    java.util.List<ProductOption> productOptionsExact(UUID org,Scope scope,String name);
    BalancePage balances(UUID org,String keyword,Scope scope,AlertStatus alert,int page,int size,String sort);
    Optional<Balance> balance(UUID org,UUID productId,Scope scope);
    void upsertPolicy(UUID org,UUID productId,Scope scope,BigDecimal packageKg,BigDecimal lowStockKg,UUID actor);
    BigDecimal lockBalance(UUID org,UUID product,Scope scope,UUID actor);
    void updateBalance(UUID org,UUID product,Scope scope,BigDecimal quantity);
    void insertTransaction(UUID org,Transaction tx,String idempotencyKey);
    int updateTransaction(UUID org,UUID id,LocalDate businessDate,String documentNo,String reason,String note,String actorName);
    java.util.List<String> transactionReasons(UUID org,Scope scope);
    Optional<Transaction> findTransaction(UUID org,UUID id);
    Optional<Transaction> findByIdempotency(UUID org,String key);
    Optional<Transaction> findByDocumentNo(UUID org,String documentNo,String businessType);
    Page<Transaction> transactions(UUID org,Scope scope,String keyword,String direction,String reason,int page,int size);
    void insertSample(UUID org,Sample sample,UUID actor);
    void insertShipment(UUID org,Shipment shipment,UUID actor);
    Optional<String> activeCustomerName(UUID customerId);
    record RecipientPreference(String recipientName,String recipientPhone,String sampleAddress){}
    Optional<RecipientPreference> recipientPreference(UUID customerId);
    Optional<RecipientPreference> latestSampleRecipient(UUID customerId);
    void upsertRecipientPreference(UUID customerId,String recipientName,String recipientPhone,String sampleAddress);
    Page<Sample> samples(UUID org,String keyword,String status,LocalDate startDate,LocalDate endDate,int page,int size);
    Page<Shipment> shipments(UUID org,String keyword,int page,int size);
    Page<Shipment> shipmentDetails(UUID org,String shipmentNo,int page,int size);
    java.util.List<UUID> pendingShipmentIds(UUID org,String shipmentNo);
    Page<ShipmentHeader> shipmentHeaders(UUID org,String keyword,String status,LocalDate startDate,LocalDate endDate,int page,int size);
    Optional<ShipmentHeader> shipmentHeader(UUID org,String shipmentNo);
    ShipmentHeader insertShipmentHeader(UUID org,String shipmentNo,LocalDate businessDate,String note,UUID actor);
    int updateShipmentHeaderDate(UUID org,String shipmentNo,LocalDate businessDate);
    int updateShipmentHeader(UUID org,String shipmentNo,LocalDate businessDate,String note);
    int deletePendingShipmentDetailsByHeader(UUID org,String shipmentNo);
    int deleteShipmentHeader(UUID org,String shipmentNo);
    int deletePendingSample(UUID org,UUID id);
    Optional<Sample> sampleByTransaction(UUID org,UUID tx);
    Optional<Shipment> shipmentByTransaction(UUID org,UUID tx);
    Optional<Sample> findSample(UUID org,UUID id);
    Optional<Sample> lockSample(UUID org,UUID id);
    Optional<Sample> findSampleByDispatchNo(UUID org,String dispatchNo);
    int completeSample(UUID org,UUID id,UUID transactionId);
    Optional<Shipment> findShipment(UUID org,UUID id);
    Optional<Shipment> lockShipment(UUID org,UUID id);
    Optional<Shipment> findShipmentByNo(UUID org,String shipmentNo);
    int completeShipment(UUID org,UUID id,UUID transactionId);
    int deletePendingShipment(UUID org,UUID id);
    int updateSample(UUID org,UUID id,LocalDate businessDate,String dispatchNo,String specification,java.math.BigDecimal packageQuantity,UUID customerId,String customerName,
                     String recipientName,String recipientPhone,String sampleAddress,String courierCompany,String trackingNo,
                     String clientContact,String clientContactPhone,String senderName,
                     String customerRequirement,String customerFeedback,String status,java.math.BigDecimal quantityKg);
    int updateShipment(UUID org,UUID id,Shipment shipment);
    Optional<UUID> productIdByCode(UUID org,String code);
    void insertImport(UUID id,UUID org,String status,JsonNode rows,JsonNode errors,UUID actor);
    Optional<ImportData> findImport(UUID org,UUID id);
    int markImportCommitted(UUID org,UUID id);
    record ImportData(UUID id,String status,String rowsJson,String errorsJson,Instant createdAt,Instant committedAt){}
}
