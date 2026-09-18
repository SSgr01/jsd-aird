package com.jsd.aird.data.adapter.in.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.core.api.ProjectResourceFacade.ProjectRelationTarget;
import com.jsd.aird.data.application.DataImportService;
import com.jsd.aird.data.application.DataWorkbookService;
import com.jsd.aird.data.application.ExperimentAssemblyService;
import com.jsd.aird.recognition.application.SourceRecognitionService;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/v1/data")
public class DataController {

    private final DataImportService service;
    private final DataWorkbookService workbookService;
    private final com.jsd.aird.data.application.DataCategoryService categoryService;
    private final ExperimentAssemblyService experimentAssemblyService;
    private final SourceRecognitionService recognitionService;

    public DataController(DataImportService service,
                          com.jsd.aird.data.application.DataCategoryService categoryService,
                          DataWorkbookService workbookService,
                          ExperimentAssemblyService experimentAssemblyService,
                          SourceRecognitionService recognitionService) {
        this.service = service;
        this.categoryService = categoryService;
        this.workbookService = workbookService;
        this.experimentAssemblyService = experimentAssemblyService;
        this.recognitionService = recognitionService;
    }

    @GetMapping("/templates")
    public ApiResponse<List<com.jsd.aird.tpl.api.TemplateDataImportFacade.DataTemplateOption>> templates() {
        return success(service.listTemplates());
    }

    @PostMapping("/import-jobs")
    public ApiResponse<DataRepository.Job> create(@Valid @RequestBody CreateRequest request) {
        if ("FREEFORM".equalsIgnoreCase(request.recognitionMode())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "自由识别请使用 /api/v1/data/recognition-jobs");
        }
        return success(service.create(new DataImportService.CreateCommand(
                request.sourceFileId(), request.templateVersionId(), request.categoryId(),
                request.duplicateOverride(), targets(request.projectRelations()),
                request.importPurpose(), request.targetExperimentCategoryId())));
    }

    /** Template-guided uploads started from the experiment notebook keep the
     * same Data mapping workspace, but the source owner remains EXPERIMENT. */
    @PostMapping("/experiment-import-jobs")
    public ApiResponse<DataRepository.Job> createExperimentImport(@Valid @RequestBody CreateRequest request) {
        if (!"EXPERIMENT_DRAFT".equalsIgnoreCase(request.importPurpose())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "实验记录本模板导入必须使用EXPERIMENT_DRAFT");
        }
        return success(service.createExperiment(new DataImportService.CreateCommand(
                request.sourceFileId(), request.templateVersionId(), request.categoryId(),
                request.duplicateOverride(), targets(request.projectRelations()),
                request.importPurpose(), request.targetExperimentCategoryId())));
    }

    @GetMapping("/experiment-import-jobs")
    public ApiResponse<PageResponse<DataRepository.Job>> experimentImportJobs(
            @RequestParam(required = false) UUID templateVersionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(service.listExperimentJobs(templateVersionId, status, keyword, page, size));
    }

    @PostMapping("/recognition-jobs")
    public ApiResponse<?> createRecognition(@Valid @RequestBody RecognitionCreateRequest request) {
        var relation = request.projectRelations() == null ? null
                : request.projectRelations().stream().findFirst().orElse(null);
        return success(recognitionService.create(SourceRecognitionService.DATA_CENTER,
                new SourceRecognitionService.CreateCommand(request.sourceFileId(), request.sourceFormat(),
                        request.recognitionMode(), request.templateVersionId(), request.categoryId(),
                        request.importPurpose(), request.targetExperimentCategoryId(),
                        relation == null ? null : relation.projectId(), relation == null ? null : relation.stageId(),
                        relation == null ? null : relation.taskId(), request.experimentDate(), "ALL",
                        request.duplicateOverride())));
    }

    @GetMapping("/recognition-jobs/{id}/workspace")
    public ApiResponse<?> recognitionWorkspace(@PathVariable UUID id) {
        return success(recognitionService.get(SourceRecognitionService.DATA_CENTER, id));
    }

    @PutMapping("/recognition-jobs/{id}/boundaries")
    public ApiResponse<?> recognitionBoundaries(@PathVariable UUID id,
            @Valid @RequestBody RecognitionBoundariesRequest request) {
        return success(recognitionService.updateBoundaries(SourceRecognitionService.DATA_CENTER, id,
                request.expectedRevision(), request.items()));
    }

    @PutMapping("/recognition-jobs/{id}/mappings")
    public ApiResponse<?> recognitionMappings(@PathVariable UUID id,
            @Valid @RequestBody RecognitionMappingsRequest request) {
        return success(recognitionService.updateMappings(SourceRecognitionService.DATA_CENTER, id,
                request.expectedRevision(), request.candidates(), request.unrecognizedFragments(), request.issues()));
    }

    @PostMapping("/recognition-jobs/{id}/recognition-profiles")
    public ApiResponse<?> recognitionProfile(@PathVariable UUID id,
            @Valid @RequestBody RecognitionProfileRequest request) {
        return success(recognitionService.saveProfile(SourceRecognitionService.DATA_CENTER, id,
                request.name(), request.expectedRevision()));
    }

    @PostMapping("/recognition-jobs/{id}/retry")
    public ApiResponse<?> retryRecognition(@PathVariable UUID id) {
        return success(recognitionService.retry(SourceRecognitionService.DATA_CENTER, id));
    }

    @PostMapping("/recognition-jobs/{id}/finalize")
    public ApiResponse<?> finalizeRecognition(@PathVariable UUID id,
            @Valid @RequestBody RecognitionRevisionRequest request) {
        return success(recognitionService.finalizeWorkspace(SourceRecognitionService.DATA_CENTER, id,
                request.expectedRevision()));
    }

    @GetMapping("/import-jobs/{id}")
    public ApiResponse<DataRepository.Job> get(@PathVariable UUID id) { return success(service.get(id)); }

    @GetMapping("/import-jobs")
    public ApiResponse<PageResponse<DataRepository.Job>> jobs(
            @RequestParam(required = false) UUID templateVersionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(service.listJobs(templateVersionId, status, keyword, page, size));
    }

    @PostMapping("/import-jobs/{id}/parse")
    public ApiResponse<DataRepository.Job> parse(@PathVariable UUID id) {
        service.parse(id);
        return success(service.get(id));
    }

    @PostMapping("/import-jobs/{id}/re-extract")
    public ApiResponse<DataRepository.Job> reExtract(@PathVariable UUID id) {
        service.reExtract(id);
        return success(service.get(id));
    }

    @PutMapping("/import-jobs/{id}/sheets")
    public ApiResponse<DataRepository.Job> sheets(@PathVariable UUID id, @Valid @RequestBody SheetRequest request) {
        service.confirmSheets(id, request.items().stream().map(item -> new DataRepository.SheetUpdate(
                id, item.sheetId(), item.selected(), item.headerRows(), item.dataStartRow(), item.dataEndRow(), item.confirmationStatus()
        )).toList());
        return success(service.get(id));
    }

    @PutMapping("/import-jobs/{id}/mappings")
    public ApiResponse<DataRepository.Job> mappings(@PathVariable UUID id, @Valid @RequestBody MappingRequest request) {
        service.saveMappings(id, request.items().stream().map(item -> new DataImportService.MappingCommand(
                item.sheetId(), item.sourceColumn(), item.sourceHeader(), item.fieldCode(), item.fieldName(), item.action(),
                item.valueType(), item.sourceUnit(), item.standardUnit(), item.detail()
        )).toList());
        return success(service.get(id));
    }

    @GetMapping("/import-jobs/{id}/issues")
    public ApiResponse<List<DataRepository.Issue>> issues(@PathVariable UUID id) { return success(service.issues(id)); }

    @PostMapping("/import-jobs/{id}/field-requests")
    public ApiResponse<com.jsd.aird.tpl.api.TemplateDataImportFacade.FieldRequest> requestField(
            @PathVariable UUID id, @Valid @RequestBody FieldRequest request) {
        return success(service.requestField(id, new DataImportService.FieldRequestCommand(
                request.fieldId(), request.displayName(), request.valueType(), request.uiType(),
                request.groupCode(), request.description())));
    }

    @PutMapping("/import-jobs/{id}/issues/{issueId}")
    public ApiResponse<Void> resolve(@PathVariable UUID id, @PathVariable UUID issueId, @Valid @RequestBody IssueRequest request) {
        service.resolveIssue(id, issueId, request.status(), request.reason());
        return success(null);
    }

    @PutMapping("/import-jobs/{id}/records/{recordId}/values")
    public ApiResponse<Void> correctValue(@PathVariable UUID id, @PathVariable UUID recordId,
                                          @Valid @RequestBody CorrectValueRequest request) {
        service.correctValue(id, recordId, request.bindingId(), request.valuePath(),
                request.correctedValue(), request.reason());
        return success(null);
    }

    @PutMapping("/import-jobs/{id}/records/{recordId}/exclusion")
    public ApiResponse<Void> excludeRecord(@PathVariable UUID id, @PathVariable UUID recordId,
                                           @Valid @RequestBody ExcludeRecordRequest request) {
        service.excludeRow(id, recordId, request.excluded(), request.reason());
        return success(null);
    }

    @GetMapping("/import-jobs/{id}/preview")
    public ApiResponse<DataImportService.Preview> preview(@PathVariable UUID id) {
        return success(service.preview(id));
    }

    @GetMapping("/import-jobs/{id}/workbook-snapshot")
    public ApiResponse<DataWorkbookService.WorkbookContext> importWorkbook(@PathVariable UUID id) {
        return success(workbookService.importJob(id));
    }

    @PutMapping("/import-jobs/{id}/components/{componentId}/anchor")
    public ApiResponse<DataRepository.Job> reanchorComponent(
            @PathVariable UUID id, @PathVariable String componentId,
            @Valid @RequestBody ComponentAnchorRequest request) {
        service.reanchorComponent(id, componentId, request.sheetId(), request.sourceRange(), request.reason());
        return success(service.get(id));
    }

    @PostMapping("/import-jobs/{id}/commit")
    public ApiResponse<DataRepository.Job> commit(@PathVariable UUID id) {
        service.commit(id);
        var job = service.get(id);
        if ("EXPERIMENT_DRAFT".equals(job.importPurpose())) {
            var plan = experimentAssemblyService.preview(id);
            if (plan.readyCount() > 0 && plan.reviewCount() == 0 && plan.blockedCount() == 0) {
                experimentAssemblyService.sync(id, job.targetExperimentCategoryId(), null);
            }
        }
        return success(job);
    }

    @GetMapping("/import-jobs/{id}/experiments")
    public ApiResponse<com.jsd.aird.data.application.ExperimentImportAssembler.AssemblyPlan> experimentPlan(
            @PathVariable UUID id) {
        return success(experimentAssemblyService.preview(id));
    }

    @PostMapping("/import-jobs/{id}/experiment-sync")
    public ApiResponse<ExperimentAssemblyService.SyncResult> syncExperiments(
            @PathVariable UUID id, @Valid @RequestBody ExperimentSyncRequest request) {
        return success(experimentAssemblyService.sync(id, request.categoryId(), request.assemblyKeys()));
    }

    @GetMapping("/import-jobs/{id}/experiment-sync-status")
    public ApiResponse<ExperimentAssemblyService.SyncStatus> experimentSyncStatus(@PathVariable UUID id) {
        return success(experimentAssemblyService.status(id));
    }

    /** The data-center list is one card per source file/import batch, never one card per field. */
    @GetMapping("/sources")
    public ApiResponse<PageResponse<DataRepository.SourceFile>> sources(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(service.sourceFiles(categoryId, status, keyword, projectId, page, size));
    }

    @GetMapping("/categories")
    public ApiResponse<List<com.jsd.aird.data.application.port.DataCategoryRepository.Category>> categories() {
        return success(categoryService.list());
    }

    @PostMapping("/categories")
    public ApiResponse<com.jsd.aird.data.application.port.DataCategoryRepository.Category> createCategory(
            @Valid @RequestBody CategoryRequest request) {
        return success(categoryService.create(request.name(), request.description()));
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<com.jsd.aird.data.application.port.DataCategoryRepository.Category> renameCategory(
            @PathVariable UUID categoryId, @Valid @RequestBody RenameCategoryRequest request) {
        return success(categoryService.rename(categoryId, request.name(), request.description()));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID categoryId,
                                            @RequestParam(required = false) UUID replacementCategoryId) {
        categoryService.delete(categoryId, replacementCategoryId);
        return success(null);
    }

    @PutMapping("/sources/{id}/category")
    public ApiResponse<Void> assignSourceCategory(@PathVariable UUID id, @Valid @RequestBody AssignCategoryRequest request) {
        service.assignSourceCategory(id, request.categoryId());
        return success(null);
    }

    private <T> ApiResponse<T> success(T value) { return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown()); }

    private List<ProjectRelationTarget> targets(List<ProjectRelationRequest> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> new ProjectRelationTarget(
                value.projectId(), value.stageId(), value.taskId())).toList();
    }

    public record CreateRequest(@NotNull UUID sourceFileId, @NotNull UUID templateVersionId,
                                UUID categoryId, boolean duplicateOverride,
                                List<@Valid ProjectRelationRequest> projectRelations,
                                String importPurpose, UUID targetExperimentCategoryId,
                                String recognitionMode) {}
    public record RecognitionCreateRequest(@NotNull UUID sourceFileId, String sourceFormat,
                                           String recognitionMode, UUID templateVersionId, UUID categoryId,
                                           String importPurpose, UUID targetExperimentCategoryId,
                                           List<@Valid ProjectRelationRequest> projectRelations,
                                           LocalDate experimentDate, boolean duplicateOverride) {}
    public record RecognitionBoundariesRequest(long expectedRevision, @NotNull JsonNode items) {}
    public record RecognitionMappingsRequest(long expectedRevision, @NotNull JsonNode candidates,
                                             @NotNull JsonNode unrecognizedFragments, @NotNull JsonNode issues) {}
    public record RecognitionProfileRequest(@NotBlank String name, long expectedRevision) {}
    public record RecognitionRevisionRequest(long expectedRevision) {}
    public record ProjectRelationRequest(@NotNull UUID projectId, UUID stageId, UUID taskId) {}

    public record CategoryRequest(@NotBlank String name, @Size(max = 240) String description) {}
    public record RenameCategoryRequest(@NotBlank String name, @Size(max = 240) String description) {}
    public record AssignCategoryRequest(@NotNull UUID categoryId) {}

    public record SheetRequest(@NotNull List<@Valid SheetItem> items) {}
    public record SheetItem(@NotBlank String sheetId, boolean selected, List<Integer> headerRows,
                            Integer dataStartRow, Integer dataEndRow, @NotBlank String confirmationStatus) {}

    public record MappingRequest(@NotNull List<@Valid MappingItem> items) {}
    public record MappingItem(@NotBlank String sheetId, @NotBlank String sourceColumn, String sourceHeader,
                              String fieldCode, String fieldName, @NotBlank String action, String valueType,
                              String sourceUnit, String standardUnit, JsonNode detail) {}

    public record IssueRequest(@NotBlank String status, String reason) {}
    public record CorrectValueRequest(@NotBlank String bindingId, @NotNull String valuePath,
                                      @NotNull JsonNode correctedValue, String reason) {}
    public record ExcludeRecordRequest(boolean excluded, String reason) {}
    public record ComponentAnchorRequest(@NotBlank String sheetId, @NotBlank String sourceRange,
                                         @NotBlank @Size(max = 500) String reason) {}
    public record ExperimentSyncRequest(@NotNull UUID categoryId, List<String> assemblyKeys) {}
    public record FieldRequest(String fieldId, @NotBlank String displayName, String valueType,
                               String uiType, String groupCode, String description) {}
}
