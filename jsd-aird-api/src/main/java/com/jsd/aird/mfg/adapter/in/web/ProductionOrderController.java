package com.jsd.aird.mfg.adapter.in.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.mfg.application.ProductionOrderService;
import com.jsd.aird.mfg.application.ProductionOfficeExportService;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v2/production-orders")
public class ProductionOrderController {

    private final ProductionOrderService service;
    private final ProductionOfficeExportService officeExportService;

    public ProductionOrderController(ProductionOrderService service, ProductionOfficeExportService officeExportService) {
        this.service = service;
        this.officeExportService = officeExportService;
    }

    @GetMapping
    public ApiResponse<List<ProductionOrderRepository.ProductionOrderListItem>> list() {
        return success(service.list());
    }

    @PostMapping
    public ApiResponse<ProductionOrderRepository.ProductionWorkspace> create(
            @Valid @RequestBody CreateRequest request
    ) {
        return success(service.create(new ProductionOrderService.CreateCommand(
                request.orderNo(),
                request.templateVersionId(),
                request.productId(),
                request.quantity(),
                request.unitCode(),
                request.plannedDate(),
                request.ownerId()
        )));
    }

    @GetMapping("/{orderId}/edit-model")
    public ApiResponse<ProductionOrderRepository.ProductionWorkspace> editModel(
            @PathVariable UUID orderId
    ) {
        return success(service.get(orderId));
    }

    @GetMapping("/{orderId}/revisions")
    public ApiResponse<List<ProductionOrderRepository.RevisionSummary>> revisions(@PathVariable UUID orderId) {
        return success(officeExportService.revisions(orderId));
    }

    @GetMapping("/{orderId}/export/check")
    public ApiResponse<ProductionOfficeExportService.Check> checkExport(
            @PathVariable UUID orderId,
            @RequestParam String format,
            @RequestParam(required = false) UUID revisionId
    ) {
        return success(officeExportService.check(orderId, format, revisionId));
    }

    @GetMapping("/{orderId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable UUID orderId,
            @RequestParam String format,
            @RequestParam(required = false) UUID revisionId
    ) {
        var file = officeExportService.export(orderId, format, revisionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Export-Warning-Count", Integer.toString(file.warnings().size()))
                .body(file.content());
    }

    @PutMapping("/{orderId}/draft")
    public ApiResponse<ProductionOrderService.SaveResult> save(
            @PathVariable UUID orderId,
            @Valid @RequestBody SaveRequest request
    ) {
        var bindingValues = request.bindingValues() == null
                ? List.<ProductionOrderService.BindingValuePair>of()
                : request.bindingValues().stream()
                        .map(item -> new ProductionOrderService.BindingValuePair(
                                item.dataPath(),
                                item.dataValue(),
                                item.editorValue()
                        ))
                        .toList();
        return success(service.save(orderId, new ProductionOrderService.SaveCommand(
                request.lockVersion(),
                request.baseWorkspaceHash(),
                request.schema(),
                request.mapping(),
                request.data(),
                request.snapshotFileId(),
                request.snapshotHash(),
                request.editorAppVersion(),
                request.pluginManifest(),
                request.snapshotFormatVersion(),
                bindingValues
        )));
    }

    @PostMapping("/{orderId}/submit")
    public ApiResponse<SubmitResponse> submit(@PathVariable UUID orderId) {
        return success(new SubmitResponse(service.submit(orderId)));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable UUID orderId) {
        service.cancel(orderId);
        return success(null);
    }

    @DeleteMapping("/{orderId}")
    public ApiResponse<Void> delete(@PathVariable UUID orderId) {
        service.delete(orderId);
        return success(null);
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record CreateRequest(
            @NotBlank String orderNo,
            @NotNull UUID templateVersionId,
            UUID productId,
            @Positive BigDecimal quantity,
            String unitCode,
            LocalDate plannedDate,
            UUID ownerId
    ) {
    }

    public record BindingValueRequest(
            @NotBlank String dataPath,
            @NotNull JsonNode dataValue,
            @NotNull JsonNode editorValue
    ) {
    }

    public record SaveRequest(
            @Min(0) long lockVersion,
            @NotBlank String baseWorkspaceHash,
            @NotNull JsonNode schema,
            @NotNull JsonNode mapping,
            @NotNull JsonNode data,
            @NotNull UUID snapshotFileId,
            @NotBlank String snapshotHash,
            @NotBlank String editorAppVersion,
            @NotBlank String pluginManifest,
            @Min(1) int snapshotFormatVersion,
            List<@Valid BindingValueRequest> bindingValues
    ) {
    }

    public record SubmitResponse(UUID revisionId) {
    }
}
