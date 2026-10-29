package com.jsd.aird.kb.adapter.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.jsd.aird.kb.application.KnowledgeService;
import com.jsd.aird.kb.application.KnowledgeUploadTaskService;
import com.jsd.aird.kb.domain.FileSafetyScanner;
import com.jsd.aird.platform.web.HttpContentDisposition;
import com.jsd.aird.kb.application.KnowledgeGovernanceService;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService service;
    private final KnowledgeGovernanceService governance;
    private final KnowledgeUploadTaskService uploadTasks;
    private final FileSafetyScanner fileSafetyScanner;
    private final OpsAsyncFacade async;

    public KnowledgeController(KnowledgeService service, FileStorageFacade storage,
                               KnowledgeGovernanceService governance, KnowledgeUploadTaskService uploadTasks,
                               FileSafetyScanner fileSafetyScanner, OpsAsyncFacade async) {
        this.service = service;
        this.storage = storage;
        this.governance = governance;
        this.uploadTasks = uploadTasks;
        this.fileSafetyScanner = fileSafetyScanner;
        this.async = async;
    }

    @PostMapping(value = "/documents", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeService.DocumentView> create(
    @RequestPart MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String libraryScope,
            @RequestParam(required = false) UUID categoryId
    ) throws IOException {
        var staged = storage.stageFile(
                file.getOriginalFilename(),
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                "KNOWLEDGE",
                file.getInputStream()
        );
        return success(governance.create(new KnowledgeGovernanceService.CreateCommand(staged.fileId(), title,
                libraryScope, categoryId, List.of(), null, null, null)));
    }

    @GetMapping("/documents")
    public ApiResponse<?> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String lifecycleStatus,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return success(service.list(keyword, status, scope, categoryId, lifecycleStatus, reviewStatus,
                projectId, page, size));
    }

    @GetMapping("/upload-capabilities")
    public ApiResponse<UploadCapabilities> uploadCapabilities() {
        var actor = com.jsd.aird.shared.security.ActorContext.required();
        var extensions = fileSafetyScanner.supportedExtensions().stream().sorted()
                .map(extension -> "." + extension).toList();
        return success(new UploadCapabilities(fileSafetyScanner.maxFileBytes(), extensions,
                async.queueCapacity(actor.organizationId())));
    }

    @PostMapping("/upload-tasks")
    public ApiResponse<KnowledgeUploadTaskService.UploadTaskView> createUploadTask(
            @Valid @RequestBody UploadTaskCreateRequest request) {
        return success(uploadTasks.create(new KnowledgeUploadTaskService.CreateCommand(
                request.fileName(), request.size(), request.uploadContext())));
    }

    @GetMapping("/upload-tasks")
    public ApiResponse<List<KnowledgeUploadTaskService.UploadTaskView>> uploadTasks(
            @RequestParam(defaultValue = "100") int limit) {
        return success(uploadTasks.list(limit));
    }

    @PutMapping("/upload-tasks/{id}")
    public ApiResponse<KnowledgeUploadTaskService.UploadTaskView> updateUploadTask(
            @PathVariable UUID id, @Valid @RequestBody UploadTaskUpdateRequest request) {
        return success(uploadTasks.update(id, new KnowledgeUploadTaskService.UpdateCommand(
                request.status(), request.progress(), request.sourceFileId(), request.uploadContext(),
                request.documentId(), request.documentVersionId(), request.message())));
    }

    @DeleteMapping("/upload-tasks/{id}")
    public ApiResponse<Void> deleteUploadTask(@PathVariable UUID id) {
        uploadTasks.delete(id);
        return success(null);
    }

    @GetMapping("/categories")
    public ApiResponse<List<com.jsd.aird.kb.application.port.KnowledgeRepository.CategoryRow>> categories(
            @RequestParam(required = false) String scope) {
        return success(service.categories(scope));
    }

    @PostMapping("/categories")
    public ApiResponse<com.jsd.aird.kb.application.port.KnowledgeRepository.CategoryRow> createCategory(
            @Valid @RequestBody CategoryRequest request) {
        return success(service.createCategory(request.scope(), request.name(), request.description()));
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<com.jsd.aird.kb.application.port.KnowledgeRepository.CategoryRow> renameCategory(
            @PathVariable UUID categoryId, @Valid @RequestBody RenameCategoryRequest request) {
        return success(service.renameCategory(categoryId, request.name(), request.description(),request.revision()));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID categoryId,
                                            @RequestParam(required = false) UUID replacementCategoryId,@RequestParam String revision) {
        service.deleteCategory(categoryId, replacementCategoryId,revision);
        return success(null);
    }

    @PutMapping("/documents/{id}/category")
    public ApiResponse<Void> assignCategory(@PathVariable UUID id, @Valid @RequestBody AssignCategoryRequest request) {
        service.assignCategory(id, request.categoryId(), request.revision());
        return success(null);
    }

    @PutMapping("/documents/{id}")
    public ApiResponse<KnowledgeService.DocumentView> renameDocument(@PathVariable UUID id,
                                                                       @Valid @RequestBody RenameDocumentRequest request) {
        return success(service.renameDocument(id, request.title(), request.revision()));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> deleteDocument(@PathVariable UUID id,@RequestParam String revision) {
        service.deleteDocument(id, revision);
        return success(null);
    }

    @PutMapping("/documents/{id}/metadata")
    public ApiResponse<KnowledgeService.DocumentView> updateMetadata(@PathVariable UUID id,
            @Valid @RequestBody UpdateMetadataRequest request) {
        return success(service.updateDocument(id, new KnowledgeService.UpdateDocumentCommand(request.revision(), request.title(),
                request.libraryScope(), request.categoryId(), request.tags(), request.sourceInfo(), request.projectRelations())));
    }

    public record UpdateMetadataRequest(@NotBlank String revision, @NotBlank @Size(max = 260) String title,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "INTERNAL|EXTERNAL") String libraryScope,
            @NotNull UUID categoryId, @Size(max = 50) List<@Size(max = 100) String> tags,
            com.fasterxml.jackson.databind.JsonNode sourceInfo,
            List<com.jsd.aird.core.api.ProjectResourceFacade.ProjectRelationTarget> projectRelations) { }

    @GetMapping("/documents/{id}")
    public ApiResponse<KnowledgeService.DocumentView> get(@PathVariable UUID id) {
        return success(service.get(id));
    }

    @GetMapping("/documents/{id}/processing")
    public ApiResponse<KnowledgeService.ProcessingView> processing(@PathVariable UUID id) {
        return success(service.processing(id));
    }

    @GetMapping("/documents/{id}/versions")
    public ApiResponse<?> versions(@PathVariable UUID id) {
        return success(service.versions(id));
    }

    @PostMapping("/documents/{id}/versions")
    public ApiResponse<KnowledgeService.DocumentView> createVersion(
            @PathVariable UUID id,
            @RequestPart MultipartFile file
    ) throws IOException {
        var staged = storage.stageFile(
                file.getOriginalFilename(),
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                "KNOWLEDGE",
                file.getInputStream()
        );
        return success(service.createVersion(id, new KnowledgeService.CreateVersionCommand(staged.fileId())));
    }

    @PostMapping("/documents/{id}/ai-grant")
    public ApiResponse<KnowledgeService.DocumentView> aiGrant(
            @PathVariable UUID id,
            @Valid @RequestBody GrantRequest request
    ) {
        return success(service.updateAiGrant(id, new KnowledgeService.GrantCommand(request.revision(),request.action(), request.reason())));
    }

    @GetMapping("/documents/{id}/content")
    public void content(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        var file = service.openContent(id);
        response.setContentType(file.contentType());
        response.setContentLengthLong(file.size());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                HttpContentDisposition.inline(file.originalName()));
        try (file) {
            file.stream().transferTo(response.getOutputStream());
        } catch (Exception exception) {
            throw new IOException("知识文件读取失败", exception);
        }
    }

    @GetMapping("/documents/{documentId}/versions/{versionId}/content")
    public void versionContent(
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            HttpServletResponse response
    ) throws IOException {
        var file = service.openVersionContent(documentId, versionId);
        response.setContentType(file.contentType());
        response.setContentLengthLong(file.size());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                HttpContentDisposition.inline(file.originalName()));
        try (file) {
            file.stream().transferTo(response.getOutputStream());
        } catch (Exception exception) {
            throw new IOException("知识文件版本读取失败", exception);
        }
    }

    @PostMapping("/documents/export")
    public void export(@Valid @RequestBody DocumentExportRequest request, HttpServletResponse response) throws IOException {
        var documentIds = request.documentIds().stream().distinct().toList();
        if (documentIds.size() == 1) {
            var document = service.get(documentIds.getFirst());
            var file = service.openVersionContent(document.id(), document.currentVersionId());
            try (var stream = file.stream()) {
                response.setContentType(file.contentType());
                response.setContentLengthLong(file.size());
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.originalName(), StandardCharsets.UTF_8).build().toString());
                stream.transferTo(response.getOutputStream());
            }
            return;
        }
        var content = service.exportDocuments(request.documentIds());
        response.setContentType("application/zip");
        response.setContentLength(content.length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=knowledge-documents.zip");
        response.getOutputStream().write(content);
    }

    @PostMapping("/search")
    public ApiResponse<?> search(
            @RequestBody SearchRequest request
    ) {
        return success(service.search(com.jsd.aird.shared.security.ActorContext.required().organizationId(),
                request.query(), false, request.limit()));
    }

    private final FileStorageFacade storage;

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record GrantRequest(@NotBlank String revision,@Size(max = 20) String action, @Size(max = 500) String reason) { }
    public record UploadCapabilities(long maxFileBytes, List<String> supportedExtensions,
                                     OpsAsyncFacade.QueueCapacityView queue) { }
    public record SearchRequest(@Size(min = 1, max = 1000) String query, int limit) {
        public int limit() { return limit <= 0 ? 20 : Math.min(limit, 50); }
    }
    public record CategoryRequest(@NotBlank String name, @NotBlank String scope, @Size(max = 240) String description) { }
    public record RenameCategoryRequest(@NotBlank String revision,@NotBlank String name, @Size(max = 240) String description) { }
    public record AssignCategoryRequest(@NotBlank String revision,@NotNull UUID categoryId) { }
    public record RenameDocumentRequest(@NotBlank String revision,@NotBlank @Size(max = 260) String title) { }
    public record DocumentExportRequest(@NotNull @Size(min = 1, max = 200) List<@NotNull UUID> documentIds) { }
    public record UploadTaskCreateRequest(@NotBlank @Size(max = 500) String fileName,
                                         @jakarta.validation.constraints.PositiveOrZero long size,
                                         com.fasterxml.jackson.databind.JsonNode uploadContext) { }
    public record UploadTaskUpdateRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "STAGING|CHECKING|SUBMITTING|QUEUED|DUPLICATE|FAILED") String status,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) int progress,
            UUID sourceFileId, com.fasterxml.jackson.databind.JsonNode uploadContext,
            UUID documentId, UUID documentVersionId, @Size(max = 2000) String message) { }
}
