package com.jsd.aird.kb.adapter.in.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.jsd.aird.kb.application.KnowledgeService;
import com.jsd.aird.kb.api.KnowledgeScopeFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
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
    private final KnowledgeScopeFacade scopes;

    public KnowledgeController(KnowledgeService service, FileStorageFacade storage, KnowledgeScopeFacade scopes) {
        this.service = service;
        this.storage = storage;
        this.scopes = scopes;
    }

    @PostMapping("/documents")
    public ApiResponse<KnowledgeService.DocumentView> create(
    @RequestPart MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String libraryScope,
            @RequestParam(required = false) UUID categoryId
    ) throws IOException {
        var staged = storage.stageFile(
                file.getOriginalFilename(),
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                "KNOWLEDGE",
                file.getInputStream()
        );
        return success(service.create(new KnowledgeService.CreateCommand(staged.fileId(), title, documentType,
                libraryScope, categoryId)));
    }

    @GetMapping("/documents")
    public ApiResponse<?> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String aiStatus,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return success(service.list(keyword, status, aiStatus, scope, categoryId, page, size));
    }

    @GetMapping("/categories")
    public ApiResponse<List<com.jsd.aird.kb.application.port.KnowledgeRepository.CategoryRow>> categories(
            @RequestParam(required = false) String scope) {
        return success(service.categories(scope));
    }

    @PostMapping("/categories")
    public ApiResponse<com.jsd.aird.kb.application.port.KnowledgeRepository.CategoryRow> createCategory(
            @Valid @RequestBody CategoryRequest request) {
        return success(service.createCategory(request.scope(), request.name()));
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<com.jsd.aird.kb.application.port.KnowledgeRepository.CategoryRow> renameCategory(
            @PathVariable UUID categoryId, @Valid @RequestBody RenameCategoryRequest request) {
        return success(service.renameCategory(categoryId, request.name()));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID categoryId,
                                            @RequestParam(required = false) UUID replacementCategoryId) {
        service.deleteCategory(categoryId, replacementCategoryId);
        return success(null);
    }

    @PutMapping("/documents/{id}/category")
    public ApiResponse<Void> assignCategory(@PathVariable UUID id, @Valid @RequestBody AssignCategoryRequest request) {
        service.assignCategory(id, request.categoryId());
        return success(null);
    }

    @PutMapping("/documents/{id}")
    public ApiResponse<KnowledgeService.DocumentView> renameDocument(@PathVariable UUID id,
                                                                       @Valid @RequestBody RenameDocumentRequest request) {
        return success(service.renameDocument(id, request.title()));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> deleteDocument(@PathVariable UUID id) {
        service.deleteDocument(id);
        return success(null);
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<KnowledgeService.DocumentView> get(@PathVariable UUID id) {
        return success(service.get(id));
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
            @RequestBody GrantRequest request
    ) {
        return success(service.updateAiGrant(id, new KnowledgeService.GrantCommand(request.action(), request.reason())));
    }

    @PostMapping("/documents/{id}/reindex")
    public ApiResponse<KnowledgeService.DocumentView> reindex(@PathVariable UUID id) {
        return success(service.reindex(id));
    }

    @PostMapping("/documents/{id}/scopes")
    public ApiResponse<Void> attachScope(@PathVariable UUID id, @RequestBody ScopeRequest request) {
        var actor = com.jsd.aird.shared.security.ActorContext.required();
        service.get(id);
        scopes.attach(actor.organizationId(), request.scopeId(),
                new KnowledgeScopeFacade.AttachResource("KNOWLEDGE_DOCUMENT", id, "IN_SCOPE"));
        return success(null);
    }

    @GetMapping("/documents/{id}/content")
    public void content(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        var file = service.openContent(id);
        response.setContentType(file.contentType());
        response.setContentLengthLong(file.size());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + file.originalName().replace("\"", "") + "\"");
        try (file) {
            file.stream().transferTo(response.getOutputStream());
        } catch (Exception exception) {
            throw new IOException("知识文件读取失败", exception);
        }
    }

    @PostMapping(value = "/documents/export", produces = "application/zip")
    public void export(@Valid @RequestBody DocumentExportRequest request, HttpServletResponse response) throws IOException {
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

    public record GrantRequest(@Size(max = 20) String action, @Size(max = 500) String reason) { }
    public record SearchRequest(@Size(min = 1, max = 1000) String query, int limit) {
        public int limit() { return limit <= 0 ? 20 : Math.min(limit, 50); }
    }
    public record ScopeRequest(UUID scopeId) { }
    public record CategoryRequest(@NotBlank String name, @NotBlank String scope) { }
    public record RenameCategoryRequest(@NotBlank String name) { }
    public record AssignCategoryRequest(@NotNull UUID categoryId) { }
    public record RenameDocumentRequest(@NotBlank @Size(max = 260) String title) { }
    public record DocumentExportRequest(@NotNull @Size(min = 1, max = 200) List<UUID> documentIds) { }
}
