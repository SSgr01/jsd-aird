package com.jsd.aird.mfg.ingest.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.mfg.ingest.application.InstanceIngestService;
import com.jsd.aird.mfg.ingest.application.port.InstanceIngestRepository;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/production-orders/{orderId}/ingest-jobs")
public class InstanceIngestController {

    private final InstanceIngestService service;

    public InstanceIngestController(InstanceIngestService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<InstanceIngestRepository.Job> create(
            @PathVariable UUID orderId, @Valid @RequestBody CreateRequest request
    ) {
        return success(service.create(orderId, new InstanceIngestService.CreateCommand(
                request.sourceType(), request.sourceFileIds(), request.requestedTemplateVersionId())));
    }

    @GetMapping("/{jobId}")
    public ApiResponse<InstanceIngestRepository.Job> get(
            @PathVariable UUID orderId, @PathVariable UUID jobId
    ) {
        return success(service.get(orderId, jobId));
    }

    @PostMapping("/{jobId}/confirm")
    public ApiResponse<InstanceIngestService.ConfirmResult> confirm(
            @PathVariable UUID orderId, @PathVariable UUID jobId,
            @Valid @RequestBody ConfirmRequest request
    ) {
        return success(service.confirm(orderId, jobId, new InstanceIngestService.ConfirmCommand(
                request.baseWorkspaceHash(), request.lockVersion(), request.resultVersion(),
                request.selectedTemplateVersionId(), request.correctedData())));
    }

    @PostMapping("/{jobId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable UUID orderId, @PathVariable UUID jobId) {
        service.cancel(orderId, jobId);
        return success(null);
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record CreateRequest(
            @NotBlank String sourceType,
            @NotEmpty List<UUID> sourceFileIds,
            UUID requestedTemplateVersionId
    ) {
    }

    public record ConfirmRequest(
            @NotBlank String baseWorkspaceHash,
            @Min(0) long lockVersion,
            @Min(1) int resultVersion,
            UUID selectedTemplateVersionId,
            JsonNode correctedData
    ) {
    }
}
