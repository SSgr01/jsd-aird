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
        return success(service.preflight(new KnowledgeGovernanceService.PreflightCommand(request.fileId(), request.categoryId())));
    }

    @PostMapping(value = "/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> create(@Valid @RequestBody CreateRequest request) {
        return success(service.create(new KnowledgeGovernanceService.CreateCommand(request.fileId(), request.title(),
                request.libraryScope(), request.categoryId(), request.tags(), request.resolution(),
                request.targetDocumentId(), request.sourceInfo())));
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
                request.documentId(), request.versionId(), request.reviewRevisionId(), request.lockVersion(),
                request.basePublicationId(), request.title(), request.libraryScope(), request.categoryId(),
                request.tags(), request.confirmedDocument(), request.excludedReviewNodeIds(), request.issueActions())));
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/publish")
    public ApiResponse<KnowledgeGovernanceService.IndexBuildView> publish(
            @PathVariable UUID documentId, @PathVariable UUID versionId,
            @Valid @RequestBody RevisionRequest request) {
        return success(service.publish(documentId, versionId, request.reviewRevisionId(), request.lockVersion(),
                request.basePublicationId()));
    }

    @PostMapping("/documents/{documentId}/revisions")
    public ApiResponse<KnowledgeGovernanceRepository.ReviewView> revise(
            @PathVariable UUID documentId, @Valid @RequestBody PublishedRevisionRequest request) {
        return success(service.createRevision(documentId,
                new KnowledgeGovernanceService.RevisionCommand(request.basePublicationId())));
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/reject")
    public ApiResponse<Void> reject(@PathVariable UUID documentId, @PathVariable UUID versionId,
                                    @Valid @RequestBody RejectRequest request) {
        service.reject(documentId, versionId, request.reviewRevisionId(), request.lockVersion(), request.reason());
        return success(null);
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/reparse")
    public ApiResponse<?> reparse(@PathVariable UUID documentId, @PathVariable UUID versionId,
                                  @Valid @RequestBody RevisionRequest request) {
        return success(service.reparse(documentId, versionId, request.reviewRevisionId(), request.lockVersion()));
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

    @GetMapping("/documents/{documentId}/publications")
    public ApiResponse<List<KnowledgeGovernanceRepository.PublicationRow>> publications(@PathVariable UUID documentId) {
        return success(service.publications(documentId));
    }

    @GetMapping("/documents/{documentId}/published-content")
    public ApiResponse<KnowledgeGovernanceRepository.PublishedContentView> publishedContent(
            @PathVariable UUID documentId,
            @RequestParam(required = false) UUID publicationId) {
        return success(service.publishedContent(documentId, publicationId));
    }

    @GetMapping("/documents/{documentId}/versions/{versionId}/review/{reviewRevisionId}/tables/{sourceTableId}")
    public ApiResponse<KnowledgeGovernanceRepository.TableWindow> reviewTable(
            @PathVariable UUID documentId, @PathVariable UUID versionId,
            @PathVariable UUID reviewRevisionId, @PathVariable UUID sourceTableId,
            @RequestParam(defaultValue = "0") int rowOffset,
            @RequestParam(defaultValue = "200") int rowLimit,
            @RequestParam(defaultValue = "0") int columnOffset,
            @RequestParam(defaultValue = "100") int columnLimit) {
        return success(service.reviewTable(documentId, versionId, reviewRevisionId, sourceTableId,
                rowOffset, rowLimit, columnOffset, columnLimit));
    }

    @PutMapping("/documents/{documentId}/versions/{versionId}/review/{reviewRevisionId}/tables/{sourceTableId}")
    public ApiResponse<KnowledgeGovernanceRepository.TableWindow> saveReviewTable(
            @PathVariable UUID documentId, @PathVariable UUID versionId,
            @PathVariable UUID reviewRevisionId, @PathVariable UUID sourceTableId,
            @Valid @RequestBody TableReviewRequest request) {
        return success(service.saveReviewTable(documentId, versionId, reviewRevisionId, sourceTableId,
                new KnowledgeGovernanceService.TableReviewCommand(request.lockVersion(), request.patches(),
                        request.rows(), request.rowOffset(), request.rowLimit(),
                        request.columnOffset(), request.columnLimit())));
    }

    @GetMapping("/documents/{documentId}/publications/{publicationId}/tables/{sourceTableId}")
    public ApiResponse<KnowledgeGovernanceRepository.TableWindow> publishedTable(
            @PathVariable UUID documentId, @PathVariable UUID publicationId, @PathVariable UUID sourceTableId,
            @RequestParam(defaultValue = "0") int rowOffset,
            @RequestParam(defaultValue = "200") int rowLimit,
            @RequestParam(defaultValue = "0") int columnOffset,
            @RequestParam(defaultValue = "100") int columnLimit) {
        return success(service.publishedTable(documentId, publicationId, sourceTableId,
                rowOffset, rowLimit, columnOffset, columnLimit));
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record PreflightRequest(@NotNull UUID fileId, @NotNull UUID categoryId) { }
    public record CreateRequest(@NotNull UUID fileId, @Size(max = 260) String title,
                                String libraryScope, @NotNull UUID categoryId, @Size(max = 50) List<String> tags,
                                String resolution, UUID targetDocumentId,
                                com.fasterxml.jackson.databind.JsonNode sourceInfo) { }
    public record ReviewRequest(UUID documentId, UUID versionId, @NotNull UUID reviewRevisionId,
                                int lockVersion, UUID basePublicationId, @NotBlank String title,
                                @NotBlank String libraryScope, @NotNull UUID categoryId,
                                @Size(max = 50) List<String> tags,
                                @NotNull com.fasterxml.jackson.databind.JsonNode confirmedDocument,
                                List<UUID> excludedReviewNodeIds,
                                List<KnowledgeGovernanceRepository.IssueAction> issueActions) { }
    public record PublishedRevisionRequest(@NotNull UUID basePublicationId) { }
    public record RevisionRequest(@NotNull UUID reviewRevisionId, int lockVersion, UUID basePublicationId) { }
    public record RejectRequest(@NotNull UUID reviewRevisionId, int lockVersion,
                                @NotBlank @Size(max = 1000) String reason) { }
    public record TableReviewRequest(int lockVersion,
                                     List<KnowledgeGovernanceRepository.CellPatch> patches,
                                     List<KnowledgeGovernanceRepository.RowState> rows,
                                     int rowOffset, int rowLimit, int columnOffset, int columnLimit) { }
    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) { }
    public record BatchMoveRequest(@NotNull @Size(min = 1, max = 200) List<UUID> documentIds,
                                   @NotNull UUID categoryId) { }
    public record BatchTagsRequest(@NotNull @Size(min = 1, max = 200) List<UUID> documentIds,
                                   @Size(max = 50) List<String> add, @Size(max = 50) List<String> remove) { }
    public record BatchAiRequest(@NotNull @Size(min = 1, max = 200) List<UUID> documentIds,
                                 @NotBlank String action, @Size(max = 500) String reason) { }
}
