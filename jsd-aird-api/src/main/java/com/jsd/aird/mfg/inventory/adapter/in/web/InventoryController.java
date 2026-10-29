package com.jsd.aird.mfg.inventory.adapter.in.web;

import com.jsd.aird.mfg.inventory.application.InitialStockImportService;
import com.jsd.aird.mfg.inventory.application.InventoryService;
import com.jsd.aird.mfg.inventory.application.ShipmentWorkbookImportService;
import com.jsd.aird.mfg.inventory.application.NewCustomerSampleService;
import com.jsd.aird.mfg.inventory.application.NewCustomerShipmentService;
import com.jsd.aird.mfg.inventory.application.InventoryService.SampleInput;
import com.jsd.aird.mfg.inventory.application.InventoryService.ShipmentInput;
import com.jsd.aird.mfg.inventory.application.InventoryService.PolicyInput;
import com.jsd.aird.mfg.inventory.application.InventoryService.ProductInput;
import com.jsd.aird.mfg.inventory.application.InventoryService.TransactionUpdateInput;
import com.jsd.aird.mfg.inventory.application.InventoryService.SampleUpdateInput;
import com.jsd.aird.mfg.inventory.application.InventoryService.ShipmentUpdateInput;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.*;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    private final InventoryService service; private final InitialStockImportService imports; private final NewCustomerSampleService newCustomerSamples; private final NewCustomerShipmentService newCustomerShipments; private final ShipmentWorkbookImportService shipmentWorkbookImports;
    public InventoryController(InventoryService service,InitialStockImportService imports,NewCustomerSampleService newCustomerSamples,NewCustomerShipmentService newCustomerShipments,ShipmentWorkbookImportService shipmentWorkbookImports){this.service=service;this.imports=imports;this.newCustomerSamples=newCustomerSamples;this.newCustomerShipments=newCustomerShipments;this.shipmentWorkbookImports=shipmentWorkbookImports;}
    @GetMapping("/products") ApiResponse<?> products(@RequestParam(required=false)Scope scope,@RequestParam(required=false)String keyword){return ok(service.productOptions(scope,keyword));}
    @PostMapping("/products") ApiResponse<?> createProduct(@RequestBody ProductInput request){return ok(service.createProduct(request));}
    @GetMapping("/balances") ApiResponse<?> balances(@RequestParam(required=false)String keyword,@RequestParam(required=false)Scope scope,@RequestParam(required=false)AlertStatus alert,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String sort){return ok(service.balances(keyword,scope,alert,page,size,sort));}
    @GetMapping("/balances/{productId}") ApiResponse<?> balance(@PathVariable UUID productId,@RequestParam Scope scope){return ok(service.balance(productId,scope));}
    @PutMapping("/balances/{productId}/policy") ApiResponse<?> policy(@PathVariable UUID productId,@RequestParam Scope scope,@jakarta.validation.Valid @RequestBody PolicyInput request){return ok(service.updatePolicy(productId,scope,request));}
    @GetMapping("/transactions") ApiResponse<?> transactions(@RequestParam(required=false)Scope scope,@RequestParam(required=false)String keyword,@RequestParam(required=false)String direction,@RequestParam(required=false)String reason,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.transactions(scope,keyword,direction,reason,page,size));}
    @GetMapping("/transaction-reasons") ApiResponse<?> transactionReasons(@RequestParam(required=false)Scope scope){return ok(service.transactionReasons(scope));}
    @GetMapping("/transactions/{id}") ApiResponse<?> transaction(@PathVariable UUID id){return ok(service.transaction(id));}
    @PostMapping("/transactions") ApiResponse<?> move(@RequestBody Movement request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(service.move(request,key));}
    @PutMapping("/transactions/{id}") ApiResponse<?> updateTransaction(@PathVariable UUID id,@jakarta.validation.Valid @RequestBody TransactionUpdateInput request){return ok(service.updateTransaction(id,request));}
    @PostMapping("/transactions/{id}/reverse") ApiResponse<?> reverse(@PathVariable UUID id,@RequestBody(required=false)ReverseRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(service.reverse(id,request==null?null:request.note(),key,request==null?null:request.revision()));}
    @GetMapping("/samples") ApiResponse<?> samples(@RequestParam(required=false)String keyword,@RequestParam(required=false)String status,@RequestParam(required=false)@org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE)java.time.LocalDate startDate,@RequestParam(required=false)@org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE)java.time.LocalDate endDate,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.samples(keyword,status,startDate,endDate,page,size));}
    @DeleteMapping("/samples") ApiResponse<?> deleteSamples(@RequestBody java.util.List<UUID> ids){return ok(service.deleteSamples(ids));}
    @GetMapping("/samples/recipient-preference/{customerId}") ApiResponse<?> sampleRecipientPreference(@PathVariable UUID customerId){return ok(service.recipientPreference(customerId).orElse(null));}
    @PostMapping("/samples") ApiResponse<?> sample(@RequestBody SampleInput request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(service.createSample(request,key));}
    @PostMapping("/samples/new-customer") ApiResponse<?> newCustomerSample(@RequestBody NewCustomerSampleService.Input request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(newCustomerSamples.create(request,key));}
    @PutMapping("/samples/status") ApiResponse<?> updateSampleStatuses(@RequestBody SampleStatusRequest request){return ok(service.updateSampleStatuses(request.ids(),request.status()));}
    @PutMapping("/samples/{id}") ApiResponse<?> updateSample(@PathVariable UUID id,@jakarta.validation.Valid @RequestBody SampleUpdateInput request){return ok(service.updateSample(id,request));}
    @PutMapping("/samples/{id}/new-customer") ApiResponse<?> updateSampleNewCustomer(@PathVariable UUID id,@RequestBody NewCustomerSampleService.UpdateInput request){return ok(newCustomerSamples.update(id,request));}
    @GetMapping("/shipments") ApiResponse<?> shipments(@RequestParam(required=false)String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.shipments(keyword,page,size));}
    @GetMapping("/shipment-headers") ApiResponse<?> shipmentHeaders(@RequestParam(required=false)String keyword,@RequestParam(required=false)String status,@RequestParam(required=false)@org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE)java.time.LocalDate startDate,@RequestParam(required=false)@org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE)java.time.LocalDate endDate,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.shipmentHeaders(keyword,status,startDate,endDate,page,size));}
    @GetMapping("/shipment-headers/{shipmentNo}") ApiResponse<?> shipmentHeader(@PathVariable String shipmentNo){return ok(service.shipmentHeader(shipmentNo));}
    @GetMapping("/shipment-headers/{shipmentNo}/details") ApiResponse<?> shipmentDetails(@PathVariable String shipmentNo,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.shipmentDetails(shipmentNo,page,size));}
    @DeleteMapping("/shipment-headers/{shipmentNo}/details") ApiResponse<?> deleteShipmentDetails(@PathVariable String shipmentNo,@RequestBody java.util.List<UUID> ids){return ok(service.deleteShipmentDetails(shipmentNo,ids));}
    @DeleteMapping("/shipment-headers") ApiResponse<?> deleteShipmentHeaders(@RequestBody java.util.List<String> shipmentNos){return ok(service.deleteShipmentHeaders(shipmentNos));}
    @DeleteMapping("/shipment-headers/{shipmentNo}") ApiResponse<?> deleteShipmentHeader(@PathVariable String shipmentNo){return ok(service.deleteShipmentHeader(shipmentNo));}
    @PutMapping("/shipment-headers/{shipmentNo}/status") ApiResponse<?> completeShipmentHeader(@PathVariable String shipmentNo){return ok(service.completeShipmentHeader(shipmentNo));}
    @PutMapping("/shipment-headers/status") ApiResponse<?> completeShipmentHeaders(@RequestBody ShipmentHeaderStatusRequest request){return ok(service.completeShipmentHeaders(request.shipmentNos()));}
    @PostMapping("/shipment-headers") ApiResponse<?> createShipmentHeader(@RequestBody ShipmentHeaderInput request){return ok(service.createShipmentHeader(request.shipmentNo(),request.businessDate(),request.note()));}
    @PutMapping("/shipment-headers/{shipmentNo}") ApiResponse<?> updateShipmentHeader(@PathVariable String shipmentNo,@RequestBody ShipmentHeaderInput request){return ok(service.updateShipmentHeader(shipmentNo,request.businessDate(),request.note()));}
    @PostMapping("/shipment-headers/{shipmentNo}/details/batch") ApiResponse<?> createShipmentBatch(@PathVariable String shipmentNo,@RequestBody java.util.List<ShipmentInput> request){return ok(service.createShipmentBatch(shipmentNo,request));}
    @PostMapping("/shipment-headers/{shipmentNo}/import") ApiResponse<?> importShipment(@PathVariable String shipmentNo,@RequestBody java.util.List<ShipmentWorkbookImportService.Detail> request){return ok(shipmentWorkbookImports.importWorkbook(shipmentNo,request));}
    @PutMapping("/shipments/status") ApiResponse<?> updateShipmentStatuses(@RequestBody SampleStatusRequest request){return ok(service.updateShipmentStatuses(request.ids(),request.status()));}
    @PostMapping("/shipments") ApiResponse<?> shipment(@RequestBody ShipmentInput request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(service.createShipment(request,key));}
    @PostMapping("/shipments/new-customer") ApiResponse<?> newCustomerShipment(@RequestBody NewCustomerShipmentService.Input request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(newCustomerShipments.create(request,key));}
    @PutMapping("/shipments/{id}") ApiResponse<?> updateShipment(@PathVariable UUID id,@jakarta.validation.Valid @RequestBody ShipmentUpdateInput request){return ok(service.updateShipment(id,request));}
    @PutMapping("/shipments/{id}/new-customer") ApiResponse<?> updateShipmentNewCustomer(@PathVariable UUID id,@RequestBody NewCustomerShipmentService.UpdateInput request){return ok(newCustomerShipments.update(id,request));}
    @PostMapping(value="/initial-stock/imports",consumes="multipart/form-data") ApiResponse<?> upload(@RequestPart("file")MultipartFile file){return ok(imports.upload(file));}
    @GetMapping("/initial-stock/imports/{id}") ApiResponse<?> getImport(@PathVariable UUID id){return ok(imports.get(id));}
    @PostMapping("/initial-stock/imports/{id}/commit") ApiResponse<?> commit(@PathVariable UUID id){return ok(imports.commit(id));}
    private static <T>ApiResponse<T> ok(T data){return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());}
    public record ReverseRequest(@jakarta.validation.constraints.NotBlank String revision,String note){}
    public record SampleStatusRequest(java.util.List<UUID> ids,String status){}
    public record ShipmentHeaderInput(String shipmentNo,java.time.LocalDate businessDate,String note){}
    public record ShipmentHeaderStatusRequest(java.util.List<String> shipmentNos){}
}
