package com.jsd.aird.rnd.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.ProjectDocumentExportService;
import com.jsd.aird.rnd.application.ProjectDocumentService;
import com.jsd.aird.rnd.domain.ProjectDocumentFormat;
import com.jsd.aird.rnd.domain.ProjectDocumentSource;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/documents")
public class ProjectDocumentController {

    private final ProjectDocumentService service;
    private final ProjectDocumentExportService exportService;

    public ProjectDocumentController(ProjectDocumentService service, ProjectDocumentExportService exportService) {
        this.service = service;
        this.exportService = exportService;
    }

    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId) {
        return ok(service.list(projectId));
    }

    /**
     * Keep the static export route separate from /{documentId}; otherwise the literal
     * "export" is sent to Spring's UUID converter and results in a type-mismatch error.
     * The query form is retained for callers that already use /documents/export.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID projectId,
                                         @RequestParam(required = false) UUID documentId,
                                         @RequestParam(required = false) UUID id) {
        var target = documentId != null ? documentId : id;
        if (target == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "导出项目文档时必须提供 documentId");
        }
        return download(exportService.export(projectId, target));
    }

    @GetMapping("/{documentId}/export")
    public ResponseEntity<byte[]> exportByDocument(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        return download(exportService.export(projectId, documentId));
    }

    @GetMapping("/{documentId:[0-9a-fA-F-]+}")
    public ApiResponse<?> get(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        return ok(service.get(documentId));
    }

    @PostMapping
    public ApiResponse<?> create(@PathVariable UUID projectId, @Valid @RequestBody CreateRequest request) {
        var id = service.create(
                projectId,
                request.title(),
                request.format(),
                request.source(),
                request.templateId(),
                request.templateVersionId(),
                request.fileObjectId()
        );
        return ok(id);
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse<?> delete(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        service.delete(documentId);
        return ok(null);
    }

    @PostMapping("/import")
    public ApiResponse<?> importDocument(@PathVariable UUID projectId, @Valid @RequestBody ImportRequest request) {
        return ok(service.importDocument(projectId, request.title(), request.format(), request.fileObjectId()));
    }

    public record ImportRequest(@NotBlank @Size(max = 260) String title,
                                @NotNull ProjectDocumentFormat format,
                                @NotNull UUID fileObjectId) {}

    @PutMapping("/{documentId}/content")
    public ApiResponse<?> saveContent(@PathVariable UUID projectId, @PathVariable UUID documentId,
                                      @Valid @RequestBody SaveContentRequest request) {
        var structure = service.saveContent(documentId, request.snapshot(), request.schema(), request.mapping(), request.data());
        return ok(structure);
    }

    @PostMapping("/{documentId}/publish")
    public ApiResponse<?> publish(@PathVariable UUID projectId, @PathVariable UUID documentId,
                                  @Valid @RequestBody SaveContentRequest request) {
        return ok(service.publish(documentId, request.snapshot(), request.schema(), request.mapping(), request.data()));
    }

    public record SaveContentRequest(@NotNull JsonNode snapshot, @NotNull JsonNode schema,
                                     @NotNull JsonNode mapping, @NotNull JsonNode data) {}

    public record CreateRequest(
            @NotBlank @Size(max = 260) String title,
            @NotNull ProjectDocumentFormat format,
            @NotNull ProjectDocumentSource source,
            UUID templateId,
            UUID templateVersionId,
            UUID fileObjectId
    ) {
    }

    @GetMapping("/{documentId}/versions")
    public ApiResponse<?> listVersions(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        return ok(service.listVersions(documentId));
    }

    @GetMapping("/{documentId}/versions/compare")
    public ApiResponse<?> compareVersions(@PathVariable UUID projectId, @PathVariable UUID documentId,
                                          @RequestParam long from, @RequestParam long to) {
        return ok(service.compareVersions(documentId, from, to));
    }

    @GetMapping("/{documentId}/audits")
    public ApiResponse<?> listAudits(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        return ok(service.listAudits(documentId));
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    private static ResponseEntity<byte[]> download(ProjectDocumentExportService.Download file) {
        var disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(file.content().length)
                .body(file.content());
    }
}
