package com.jsd.aird.mfg.inventory.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository {
    boolean productExists(UUID org,UUID productId,Scope scope);
    java.util.List<ProductOption> productOptions(UUID org,Scope scope,String keyword);
    ProductOption createProduct(UUID org,String name,Scope scope,UUID actor);
    BalancePage balances(UUID org,String keyword,Scope scope,AlertStatus alert,int page,int size);
    Optional<Balance> balance(UUID org,UUID productId,Scope scope);
    void upsertPolicy(UUID org,UUID productId,Scope scope,BigDecimal packageKg,BigDecimal lowStockKg,UUID actor);
    BigDecimal lockBalance(UUID org,UUID product,Scope scope,UUID actor);
    void updateBalance(UUID org,UUID product,Scope scope,BigDecimal quantity);
    void insertTransaction(UUID org,Transaction tx,String idempotencyKey);
    int updateTransaction(UUID org,UUID id,LocalDate businessDate,String documentNo,String reason,String note);
    Optional<Transaction> findTransaction(UUID org,UUID id);
    Optional<Transaction> findByIdempotency(UUID org,String key);
    Page<Transaction> transactions(UUID org,Scope scope,String keyword,String direction,String reason,int page,int size);
    void insertSample(UUID org,Sample sample,UUID actor);
    void insertShipment(UUID org,Shipment shipment,UUID actor);
    Optional<String> activeCustomerName(UUID customerId);
    Page<Sample> samples(UUID org,int page,int size);
    Page<Shipment> shipments(UUID org,int page,int size);
    Optional<Sample> sampleByTransaction(UUID org,UUID tx);
    Optional<Shipment> shipmentByTransaction(UUID org,UUID tx);
    Optional<Sample> findSample(UUID org,UUID id);
    Optional<Shipment> findShipment(UUID org,UUID id);
    int updateSample(UUID org,UUID id,LocalDate businessDate,String dispatchNo,UUID customerId,String customerName,
                     String recipientName,String sampleAddress,String courierCompany,String trackingNo,String clientContact,
                     String customerRequirement,String customerFeedback);
    int updateShipment(UUID org,UUID id,LocalDate businessDate,String shipmentNo,UUID customerId,String customerName,String note);
    Optional<UUID> productIdByCode(UUID org,String code);
    void insertImport(UUID id,UUID org,String status,JsonNode rows,JsonNode errors,UUID actor);
    Optional<ImportData> findImport(UUID org,UUID id);
    int markImportCommitted(UUID org,UUID id);
    record ImportData(UUID id,String status,String rowsJson,String errorsJson,Instant createdAt,Instant committedAt){}
}
