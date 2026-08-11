package com.jsd.aird.data.adapter.in.web;

import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.data.application.DataAssetExportService;
import com.jsd.aird.data.application.DataImportService;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v2/data")
public class DataController {

    private final DataImportService service;
    private final com.jsd.aird.data.application.DataProjectionService projectionService;
    private final DataAssetExportService assetExportService;
    private final com.jsd.aird.data.application.DataCategoryService categoryService;

    public DataController(DataImportService service, DataAssetExportService assetExportService,
                          com.jsd.aird.data.application.DataCategoryService categoryService,
                          com.jsd.aird.data.application.DataProjectionService projectionService) {
        this.service = service;
        this.assetExportService = assetExportService;
        this.categoryService = categoryService;
        this.projectionService = projectionService;
    }

    @GetMapping("/templates")
    public ApiResponse<List<com.jsd.aird.tpl.api.TemplateDataImportFacade.DataTemplateOption>> templates(
            @RequestParam(required = false) String targetDataType) {
        return success(service.listTemplates(targetDataType));
    }

    @PostMapping("/import-jobs")
    public ApiResponse<DataRepository.Job> create(@Valid @RequestBody CreateRequest request) {
        return success(service.create(new DataImportService.CreateCommand(
                request.sourceFileId(), request.templateVersionId(), request.targetDataType(), request.categoryId(),
                request.duplicateOverride())));
    }

    @GetMapping("/import-jobs/{id}")
    public ApiResponse<DataRepository.Job> get(@PathVariable UUID id) { return success(service.get(id)); }

    @GetMapping("/import-jobs")
    public ApiResponse<PageResponse<DataRepository.Job>> jobs(
            @RequestParam(required = false) String targetDataType,
            @RequestParam(required = false) UUID templateVersionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(service.listJobs(targetDataType, templateVersionId, status, keyword, page, size));
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
    public ApiResponse<Void> resolve(@PathVariable UUID issueId, @Valid @RequestBody IssueRequest request) {
        service.resolveIssue(issueId, request.status());
        return success(null);
    }

    @GetMapping("/import-jobs/{id}/preview")
    public ApiResponse<DataImportService.Preview> preview(@PathVariable UUID id) {
        var preview = service.preview(id);
        try {
            var dataset = projectionService.latest(id);
            preview = new DataImportService.Preview(preview.job(), preview.sheets(), preview.mappings(), preview.rows(),
                    preview.issues(), preview.templateContract(), new DataImportService.ProjectionSummary(
                    dataset.id(), dataset.status(), dataset.recordCount(),
                    dataset.qualitySummary().path("longValueCount").asInt(0), dataset.eligibleRecordCount()));
        } catch (com.jsd.aird.shared.error.ApiException ignored) {
            // A job may legitimately have no committed projection yet.
        }
        return success(preview);
    }

    @GetMapping("/import-jobs/{id}/long-table-preview")
    public ApiResponse<com.jsd.aird.data.application.DataProjectionService.LongTablePreview> longTablePreview(
            @PathVariable UUID id, @RequestParam(defaultValue = "20") int limit) {
        return success(projectionService.longTablePreview(id, limit));
    }

    @GetMapping("/import-jobs/{id}/training-dataset")
    public ApiResponse<com.jsd.aird.data.application.port.DataProjectionRepository.TrainingDataset> trainingDatasetForJob(
            @PathVariable UUID id) {
        return success(projectionService.latest(id));
    }

    @PostMapping("/import-jobs/{id}/commit")
    public ApiResponse<DataRepository.Job> commit(@PathVariable UUID id) {
        service.commit(id);
        return success(service.get(id));
    }

    @GetMapping("/training-datasets/{id}")
    public ApiResponse<com.jsd.aird.data.application.port.DataProjectionRepository.TrainingDataset> trainingDataset(
            @PathVariable UUID id) {
        return success(projectionService.dataset(id));
    }

    @PostMapping("/training-datasets/{id}/approve")
    public ApiResponse<Void> approveTrainingDataset(@PathVariable UUID id) {
        projectionService.updateStatus(id, "APPROVED");
        return success(null);
    }

    @PostMapping("/training-datasets/{id}/rebuild")
    public ApiResponse<DataImportService.ProjectionSummary> rebuildTrainingDataset(@PathVariable UUID id) {
        var dataset = projectionService.dataset(id);
        return success(projectionService.project(dataset.importJobId()));
    }

    @GetMapping("/assets")
    public ApiResponse<PageResponse<DataRepository.Asset>> assets(
            @RequestParam(required = false) String targetDataType,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(service.assets(targetDataType, categoryId, status, keyword, page, size));
    }

    @GetMapping("/categories")
    public ApiResponse<List<com.jsd.aird.data.application.port.DataCategoryRepository.Category>> categories() {
        return success(categoryService.list());
    }

    @PostMapping("/categories")
    public ApiResponse<com.jsd.aird.data.application.port.DataCategoryRepository.Category> createCategory(
            @Valid @RequestBody CategoryRequest request) {
        return success(categoryService.create(request.name(), request.targetDataType()));
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<com.jsd.aird.data.application.port.DataCategoryRepository.Category> renameCategory(
            @PathVariable UUID categoryId, @Valid @RequestBody RenameCategoryRequest request) {
        return success(categoryService.rename(categoryId, request.name()));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID categoryId,
                                            @RequestParam(required = false) UUID replacementCategoryId) {
        categoryService.delete(categoryId, replacementCategoryId);
        return success(null);
    }

    @PutMapping("/assets/{id}/category")
    public ApiResponse<Void> assignCategory(@PathVariable UUID id, @Valid @RequestBody AssignCategoryRequest request) {
        categoryService.assignAsset(id, request.categoryId());
        return success(null);
    }

    @PostMapping(value = "/assets/export", produces = "application/zip")
    public ResponseEntity<byte[]> export(
            @Valid @RequestBody ExportRequest request) {
        var result = assetExportService.export(new DataAssetExportService.ExportCommand(
                request.targetDataType(), request.templateVersionId(), request.assetIds()));
        var headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(result.fileName(), StandardCharsets.UTF_8).build());
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        return ResponseEntity.ok().headers(headers).body(result.content());
    }

    @GetMapping("/assets/{id}")
    public ApiResponse<DataRepository.AssetDetail> asset(@PathVariable UUID id) { return success(service.asset(id)); }

    @GetMapping("/assets/{id}/revisions")
    public ApiResponse<List<DataRepository.Revision>> revisions(@PathVariable UUID id) { return success(service.revisions(id)); }

    @GetMapping("/assets/{id}/source")
    public ApiResponse<List<DataRepository.SourceAnchor>> source(@PathVariable UUID id) { return success(service.sources(id)); }

    private <T> ApiResponse<T> success(T value) { return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown()); }

    public record CreateRequest(@NotNull UUID sourceFileId, @NotNull UUID templateVersionId,
                                @NotBlank String targetDataType, UUID categoryId, boolean duplicateOverride) {}

    public record CategoryRequest(@NotBlank String name, String targetDataType) {}
    public record RenameCategoryRequest(@NotBlank String name) {}
    public record AssignCategoryRequest(@NotNull UUID categoryId) {}

    public record ExportRequest(@NotBlank String targetDataType, @NotNull UUID templateVersionId,
                                @NotNull List<@NotNull UUID> assetIds) {}

    public record SheetRequest(@NotNull List<@Valid SheetItem> items) {}
    public record SheetItem(@NotBlank String sheetId, boolean selected, List<Integer> headerRows,
                            Integer dataStartRow, Integer dataEndRow, @NotBlank String confirmationStatus) {}

    public record MappingRequest(@NotNull List<@Valid MappingItem> items) {}
    public record MappingItem(@NotBlank String sheetId, @NotBlank String sourceColumn, String sourceHeader,
                              String fieldCode, String fieldName, @NotBlank String action, String valueType,
                              String sourceUnit, String standardUnit, JsonNode detail) {}

    public record IssueRequest(@NotBlank String status) {}
    public record FieldRequest(String fieldId, @NotBlank String displayName, String valueType,
                               String uiType, String groupCode, String description) {}
}
