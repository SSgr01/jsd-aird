package com.jsd.aird.rnd.adapter.in.web;

import java.util.UUID;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.ProjectDocumentService;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Create;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Detail;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Summary;
import com.jsd.aird.rnd.domain.ProjectDocumentFormat;
import com.jsd.aird.rnd.domain.ProjectDocumentSource;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/documents")
public class ProjectDocumentController {

    private final ProjectDocumentService service;

    public ProjectDocumentController(ProjectDocumentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId) {
        return ok(service.list(projectId));
    }

    @GetMapping("/{documentId}")
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

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }
}
