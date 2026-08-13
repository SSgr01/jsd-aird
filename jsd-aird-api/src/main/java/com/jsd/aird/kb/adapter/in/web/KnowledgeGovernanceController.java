package com.jsd.aird.kb.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.kb.application.KnowledgeGovernanceService;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeGovernanceController {

    private final KnowledgeGovernanceService service;

    public KnowledgeGovernanceController(KnowledgeGovernanceService service) {
        this.service = service;
    }

    @PostMapping("/uploads/preflight")
    public ApiResponse<KnowledgeGovernanceService.PreflightResult> preflight(@Valid @RequestBody PreflightRequest request) {
        return success(service.preflight(new KnowledgeGovernanceService.PreflightCommand(
                request.fileId(), request.documentType(), request.objectRefIds())));
    }

    @PostMapping(value = "/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> create(@Valid @RequestBody CreateRequest request) {
        return success(service.create(new KnowledgeGovernanceService.CreateCommand(request.fileId(), request.title(),
                request.documentType(), request.libraryScope(), request.categoryId(), request.tags(),
                request.objectRefIds(), request.mediaProcessingConsent(), request.resolution(), request.targetDocumentId(),
                request.sourceInfo())));
    }

    @GetMapping("/review-queue")
    public ApiResponse<List<KnowledgeGovernanceRepository.ReviewQueueItem>> reviewQueue(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return success(service.reviewQueue(status, limit));
    }

    @GetMapping("/documents/{documentId}/versions/{versionId}/review")
    public ApiResponse<KnowledgeGovernanceRepository.ReviewView> review(
            @PathVariable UUID documentId, @PathVariable UUID versionId) {
        return success(service.review(documentId, versionId));
    }

    @PutMapping("/documents/{documentId}/versions/{versionId}/review")
    public ApiResponse<KnowledgeGovernanceRepository.ReviewView> saveReview(
            @PathVariable UUID documentId, @PathVariable UUID versionId,
            @Valid @RequestBody ReviewRequest request) {
        return success(service.saveReview(documentId, versionId, new KnowledgeGovernanceService.ReviewCommand(
                request.documentId(), request.versionId(), request.reviewRevision(), request.title(),
                request.documentType(), request.libraryScope(), request.categoryId(), request.tags(),
                request.objectRefIds(), request.blocks(), request.fields())));
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/publish")
    public ApiResponse<KnowledgeGovernanceRepository.PublicationRow> publish(
            @PathVariable UUID documentId, @PathVariable UUID versionId,
            @Valid @RequestBody RevisionRequest request) {
        return success(service.publish(documentId, versionId, request.reviewRevision()));
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/reject")
    public ApiResponse<Void> reject(@PathVariable UUID documentId, @PathVariable UUID versionId,
                                    @Valid @RequestBody RejectRequest request) {
        service.reject(documentId, versionId, request.reviewRevision(), request.reason());
        return success(null);
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/reparse")
    public ApiResponse<?> reparse(@PathVariable UUID documentId, @PathVariable UUID versionId,
                                  @Valid @RequestBody ReparseRequest request) {
        return success(service.reparse(documentId, versionId, request.reviewRevision(), request.mediaProcessingConsent()));
    }

    @PostMapping("/documents/{documentId}/disable")
    public ApiResponse<Void> disable(@PathVariable UUID documentId, @Valid @RequestBody ReasonRequest request) {
        service.lifecycle(documentId, "DISABLED", request.reason());
        return success(null);
    }

    @PostMapping("/documents/{documentId}/restore")
    public ApiResponse<Void> restore(@PathVariable UUID documentId) {
        service.lifecycle(documentId, "ACTIVE", null);
        return success(null);
    }

    @PutMapping("/publications/{publicationId}/ai-usage")
    public ApiResponse<KnowledgeGovernanceRepository.PublicationRow> aiUsage(
            @PathVariable UUID publicationId, @Valid @RequestBody AiUsageRequest request) {
        return success(service.aiUsage(publicationId, request.action(), request.reason()));
    }

    @PostMapping("/documents/batch/move")
    public ApiResponse<List<KnowledgeGovernanceService.BatchResult>> batchMove(@Valid @RequestBody BatchMoveRequest request) {
        return success(service.batchMove(request.documentIds(), request.categoryId()));
    }

    @PostMapping("/documents/batch/tags")
    public ApiResponse<List<KnowledgeGovernanceService.BatchResult>> batchTags(@Valid @RequestBody BatchTagsRequest request) {
        return success(service.batchTags(request.documentIds(), request.add(), request.remove()));
    }

    @PostMapping("/documents/batch/ai-usage")
    public ApiResponse<List<KnowledgeGovernanceService.BatchResult>> batchAiUsage(@Valid @RequestBody BatchAiRequest request) {
        return success(service.batchAiUsage(request.documentIds(), request.action(), request.reason()));
    }

    @GetMapping("/pages")
    public ApiResponse<List<KnowledgeGovernanceRepository.PageListItem>> pages() {
        return success(service.pages());
    }

    @GetMapping("/documents/{documentId}/audit")
    public ApiResponse<List<com.jsd.aird.ops.application.port.AuditLogFacade.AuditEntry>> audit(
            @PathVariable UUID documentId) {
        return success(service.auditTrail(documentId));
    }

    @GetMapping("/documents/{documentId}/publications")
    public ApiResponse<List<KnowledgeGovernanceRepository.PublicationRow>> publications(
            @PathVariable UUID documentId) {
        return success(service.publications(documentId));
    }

    @GetMapping("/pages/{pageId}")
    public ApiResponse<KnowledgeGovernanceRepository.PageView> page(@PathVariable UUID pageId) {
        return success(service.page(pageId));
    }

    @PutMapping("/pages/{pageId}/draft")
    public ApiResponse<KnowledgeGovernanceRepository.PageView> savePage(
            @PathVariable UUID pageId, @Valid @RequestBody PageDraftRequest request) {
        return success(service.savePageDraft(pageId, request.title(), request.summary(), request.draftRevision()));
    }

    @PostMapping("/pages/{pageId}/publish")
    public ApiResponse<KnowledgeGovernanceRepository.PageVersionRow> publishPage(
            @PathVariable UUID pageId, @Valid @RequestBody PagePublishRequest request) {
        return success(service.publishPage(pageId, request.draftRevision()));
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record PreflightRequest(@NotNull UUID fileId, @NotBlank String documentType,
                                   @Size(max = 100) List<UUID> objectRefIds) { }
    public record CreateRequest(@NotNull UUID fileId, @Size(max = 260) String title, @NotBlank String documentType,
                                String libraryScope, @NotNull UUID categoryId, @Size(max = 50) List<String> tags,
                                @Size(max = 100) List<UUID> objectRefIds, boolean mediaProcessingConsent,
                                String resolution, UUID targetDocumentId, com.fasterxml.jackson.databind.JsonNode sourceInfo) { }
    public record ReviewRequest(UUID documentId, UUID versionId, int reviewRevision, @NotBlank String title,
                                @NotBlank String documentType, @NotBlank String libraryScope, @NotNull UUID categoryId,
                                @Size(max = 50) List<String> tags, @Size(max = 100) List<UUID> objectRefIds,
                                List<KnowledgeGovernanceRepository.BlockUpdate> blocks,
                                List<KnowledgeGovernanceRepository.FieldUpdate> fields) { }
    public record RevisionRequest(int reviewRevision) { }
    public record RejectRequest(int reviewRevision, @NotBlank @Size(max = 1000) String reason) { }
    public record ReparseRequest(int reviewRevision, Boolean mediaProcessingConsent) { }
    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) { }
    public record AiUsageRequest(@NotBlank String action, @Size(max = 500) String reason) { }
    public record BatchMoveRequest(@NotNull @Size(min = 1, max = 200) List<UUID> documentIds,
                                   @NotNull UUID categoryId) { }
    public record BatchTagsRequest(@NotNull @Size(min = 1, max = 200) List<UUID> documentIds,
                                   @Size(max = 50) List<String> add, @Size(max = 50) List<String> remove) { }
    public record BatchAiRequest(@NotNull @Size(min = 1, max = 200) List<UUID> documentIds,
                                 @NotBlank String action, @Size(max = 500) String reason) { }
    public record PageDraftRequest(@NotBlank @Size(max = 260) String title, @Size(max = 20000) String summary,
                                   int draftRevision) { }
    public record PagePublishRequest(int draftRevision) { }
}
