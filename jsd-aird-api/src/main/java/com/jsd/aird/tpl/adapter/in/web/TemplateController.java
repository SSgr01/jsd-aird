package com.jsd.aird.tpl.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.tpl.application.TemplateWorkspaceService;
import com.jsd.aird.tpl.application.TemplateRecognitionReviewService;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public class TemplateController {

    private final TemplateWorkspaceService service;
    private final TemplateRecognitionReviewService recognitionReviewService;

    public TemplateController(
            TemplateWorkspaceService service,
            TemplateRecognitionReviewService recognitionReviewService
    ) {
        this.service = service;
        this.recognitionReviewService = recognitionReviewService;
    }

    @GetMapping("/templates")
    public ApiResponse<PageResponse<TemplateRepository.TemplateListItem>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) TemplateFormat format,
            @RequestParam(required = false) TemplateStatus status
    ) {
        var items = service.list(keyword, format, status);
        return success(new PageResponse<>(items, 1, items.size(), items.size(), 1));
    }

    @PostMapping("/templates")
    public ApiResponse<TemplateRepository.TemplateWorkspace> create(
            @Valid @RequestBody CreateTemplateRequest request
    ) {
        return success(service.createBlank(new TemplateWorkspaceService.CreateBlankCommand(
                request.name(),
                request.purpose(),
                request.category(),
                request.format(),
                request.importJobId()
        )));
    }

    @GetMapping("/template-versions/{versionId}/edit-model")
    public ApiResponse<TemplateRepository.TemplateWorkspace> editModel(@PathVariable UUID versionId) {
        return success(service.get(versionId));
    }

    @GetMapping("/template-versions/{versionId}/recognition-review")
    public ApiResponse<TemplateRecognitionReviewService.RecognitionReview> recognitionReview(
            @PathVariable UUID versionId
    ) {
        return success(recognitionReviewService.get(versionId));
    }

    @PostMapping("/template-versions/{versionId}/recognition-runs")
    public ApiResponse<com.jsd.aird.tpl.application.port.TemplateImportRepository.ImportJobView> restartRecognition(
            @PathVariable UUID versionId,
            @Valid @RequestBody RecognitionRunRequest request
    ) {
        return success(recognitionReviewService.start(
                versionId, request.scope(), request.sheetId(), request.address(), request.snapshotFragment()
        ));
    }

    @GetMapping("/templates/{templateId}/versions")
    public ApiResponse<List<TemplateRepository.TemplateVersionHistoryItem>> versions(
            @PathVariable UUID templateId
    ) {
        return success(service.versionHistory(templateId));
    }

    @PutMapping("/template-versions/{versionId}/draft")
    public ApiResponse<TemplateWorkspaceService.SaveResult> saveDraft(
            @PathVariable UUID versionId,
            @Valid @RequestBody SaveDraftRequest request
    ) {
        var bindingValues = request.bindingValues() == null
                ? List.<TemplateWorkspaceService.BindingValuePair>of()
                : request.bindingValues().stream()
                        .map(item -> new TemplateWorkspaceService.BindingValuePair(
                                item.dataPath(),
                                item.dataValue(),
                                item.editorValue()
                        ))
                        .toList();
        var recognitionActions = request.recognitionActions() == null
                ? List.<TemplateRecognitionReviewService.RecognitionAction>of()
                : request.recognitionActions().stream()
                        .map(item -> new TemplateRecognitionReviewService.RecognitionAction(
                                item.recognitionItemId(), item.action()
                        ))
                        .toList();
        var qualityActions = request.qualityActions() == null
                ? List.<TemplateRecognitionReviewService.QualityAction>of()
                : request.qualityActions().stream()
                        .map(item -> new TemplateRecognitionReviewService.QualityAction(
                                item.issueId(), item.action()
                        ))
                        .toList();
        var structureOperations = request.structureOperations() == null
                ? List.<TemplateWorkspaceService.StructureOperation>of()
                : request.structureOperations().stream()
                        .map(item -> new TemplateWorkspaceService.StructureOperation(
                                item.operationId(), item.type(), item.sheetId(), item.sheetName(),
                                item.index(), item.count(), item.previousSheetName(),
                                item.nextSheetName(), item.source()
                        ))
                        .toList();
        return success(service.saveDraft(versionId, new TemplateWorkspaceService.SaveDraftCommand(
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
                request.clientCommandSummary(),
                request.idempotencyKey(),
                bindingValues,
                recognitionActions,
                qualityActions,
                structureOperations
        )));
    }

    @PostMapping("/template-versions/{versionId}/publish")
    public ApiResponse<Void> publish(@PathVariable UUID versionId) {
        service.publish(versionId);
        return success(null);
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record CreateTemplateRequest(
            @NotBlank String name,
            String purpose,
            String category,
            @NotNull TemplateFormat format,
            UUID importJobId
    ) {
    }

    public record BindingValueRequest(
            @NotBlank String dataPath,
            @NotNull JsonNode dataValue,
            @NotNull JsonNode editorValue
    ) {
    }

    public record RecognitionActionRequest(
            @NotNull UUID recognitionItemId,
            @NotBlank String action
    ) {
    }

    public record QualityActionRequest(
            @NotNull UUID issueId,
            @NotBlank String action
    ) {
    }

    @PostMapping("/template-versions/{versionId}/revisions")
    public ApiResponse<TemplateRepository.TemplateWorkspace> createRevision(@PathVariable UUID versionId) {
        return success(service.createRevision(versionId));
    }

    @DeleteMapping("/template-versions/{versionId}")
    public ApiResponse<Void> deleteDraft(@PathVariable UUID versionId) {
        service.deleteDraft(versionId);
        return success(null);
    }

    @PostMapping("/templates/{templateId}/retire")
    public ApiResponse<Void> retire(@PathVariable UUID templateId) {
        service.retire(templateId);
        return success(null);
    }

    public record StructureOperationRequest(
            @NotNull UUID operationId,
            @NotBlank String type,
            @NotBlank String sheetId,
            String sheetName,
            Integer index,
            Integer count,
            String previousSheetName,
            String nextSheetName,
            @NotBlank String source
    ) {
    }

    public record RecognitionRunRequest(
            @NotBlank String scope,
            String sheetId,
            String address,
            JsonNode snapshotFragment
    ) {
    }

    public record SaveDraftRequest(
            @Min(0) long lockVersion,
            @NotBlank String baseWorkspaceHash,
            @NotNull JsonNode schema,
            @NotNull ArrayNode mapping,
            @NotNull JsonNode data,
            @NotNull UUID snapshotFileId,
            @NotBlank String snapshotHash,
            @NotBlank String editorAppVersion,
            @NotBlank String pluginManifest,
            @Min(1) int snapshotFormatVersion,
            String clientCommandSummary,
            @NotBlank String idempotencyKey,
            List<@Valid BindingValueRequest> bindingValues,
            List<@Valid RecognitionActionRequest> recognitionActions,
            List<@Valid QualityActionRequest> qualityActions,
            List<@Valid StructureOperationRequest> structureOperations
    ) {
    }
}
