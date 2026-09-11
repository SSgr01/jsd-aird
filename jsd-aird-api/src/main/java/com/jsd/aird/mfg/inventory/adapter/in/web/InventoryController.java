package com.jsd.aird.mfg.inventory.adapter.in.web;

import com.jsd.aird.mfg.inventory.application.InitialStockImportService;
import com.jsd.aird.mfg.inventory.application.InventoryService;
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
    private final InventoryService service; private final InitialStockImportService imports;
    public InventoryController(InventoryService service,InitialStockImportService imports){this.service=service;this.imports=imports;}
    @GetMapping("/products") ApiResponse<?> products(@RequestParam(required=false)Scope scope,@RequestParam(required=false)String keyword){return ok(service.productOptions(scope,keyword));}
    @PostMapping("/products") ApiResponse<?> createProduct(@RequestBody ProductInput request){return ok(service.createProduct(request));}
    @GetMapping("/balances") ApiResponse<?> balances(@RequestParam(required=false)String keyword,@RequestParam(required=false)Scope scope,@RequestParam(required=false)AlertStatus alert,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.balances(keyword,scope,alert,page,size));}
    @GetMapping("/balances/{productId}") ApiResponse<?> balance(@PathVariable UUID productId,@RequestParam Scope scope){return ok(service.balance(productId,scope));}
    @PutMapping("/balances/{productId}/policy") ApiResponse<?> policy(@PathVariable UUID productId,@RequestParam Scope scope,@RequestBody PolicyInput request){return ok(service.updatePolicy(productId,scope,request));}
    @GetMapping("/transactions") ApiResponse<?> transactions(@RequestParam(required=false)Scope scope,@RequestParam(required=false)String keyword,@RequestParam(required=false)String direction,@RequestParam(required=false)String reason,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.transactions(scope,keyword,direction,reason,page,size));}
    @GetMapping("/transactions/{id}") ApiResponse<?> transaction(@PathVariable UUID id){return ok(service.transaction(id));}
    @PostMapping("/transactions") ApiResponse<?> move(@RequestBody Movement request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(service.move(request,key));}
    @PutMapping("/transactions/{id}") ApiResponse<?> updateTransaction(@PathVariable UUID id,@RequestBody TransactionUpdateInput request){return ok(service.updateTransaction(id,request));}
    @PostMapping("/transactions/{id}/reverse") ApiResponse<?> reverse(@PathVariable UUID id,@RequestBody(required=false)ReverseRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(service.reverse(id,request==null?null:request.note(),key));}
    @GetMapping("/samples") ApiResponse<?> samples(@RequestParam(required=false)String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.samples(keyword,page,size));}
    @PostMapping("/samples") ApiResponse<?> sample(@RequestBody SampleInput request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(service.createSample(request,key));}
    @PutMapping("/samples/{id}") ApiResponse<?> updateSample(@PathVariable UUID id,@RequestBody SampleUpdateInput request){return ok(service.updateSample(id,request));}
    @GetMapping("/shipments") ApiResponse<?> shipments(@RequestParam(required=false)String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.shipments(keyword,page,size));}
    @PostMapping("/shipments") ApiResponse<?> shipment(@RequestBody ShipmentInput request,@RequestHeader(value="Idempotency-Key",required=false)String key){return ok(service.createShipment(request,key));}
    @PutMapping("/shipments/{id}") ApiResponse<?> updateShipment(@PathVariable UUID id,@RequestBody ShipmentUpdateInput request){return ok(service.updateShipment(id,request));}
    @PostMapping(value="/initial-stock/imports",consumes="multipart/form-data") ApiResponse<?> upload(@RequestPart("file")MultipartFile file){return ok(imports.upload(file));}
    @GetMapping("/initial-stock/imports/{id}") ApiResponse<?> getImport(@PathVariable UUID id){return ok(imports.get(id));}
    @PostMapping("/initial-stock/imports/{id}/commit") ApiResponse<?> commit(@PathVariable UUID id){return ok(imports.commit(id));}
    private static <T>ApiResponse<T> ok(T data){return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());}
    public record ReverseRequest(String note){}
}
