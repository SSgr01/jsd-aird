package com.jsd.aird.mfg.upload.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.mfg.upload.application.ProductionUploadService;
import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/production-orders/records")
public class ProductionOrderRecordController {

    private final ProductionUploadService service;

    public ProductionOrderRecordController(ProductionUploadService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductionUploadRepository.UploadView> get(@PathVariable UUID id) {
        return success(service.get(id));
    }

    @PutMapping("/batch")
    public ApiResponse<Void> saveBatch(@Valid @RequestBody SaveBatchRequest request) {
        service.saveBatch(request.records().stream()
                .map(item -> new ProductionUploadService.SaveDraftCommand(
                        item.id(), item.workbookSnapshot(), item.lockVersion(),
                        item.productionName(), item.orderNo(), item.productName(),
                        item.category(), item.manufactureDate()))
                .toList());
        return success(null);
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<ProductionUploadRepository.VersionView>> versions(@PathVariable UUID id) {
        return success(service.versions(id));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<ProductionUploadRepository.VersionView> publish(@PathVariable UUID id) {
        return success(service.publish(id));
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record SaveBatchRequest(@NotNull List<@Valid SaveRecordRequest> records) {
    }

    public record SaveRecordRequest(
            @NotNull UUID id,
            JsonNode workbookSnapshot,
            @Min(0) long lockVersion,
            String productionName,
            String orderNo,
            String productName,
            String category,
            java.time.LocalDate manufactureDate
    ) {
    }
}
