package com.jsd.aird.tpl.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.tpl.application.TemplateImportService;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/template-imports")
public class TemplateImportController {

    private final TemplateImportService service;

    public TemplateImportController(TemplateImportService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<TemplateImportRepository.ImportJobView>> list() {
        return success(service.list());
    }

    @GetMapping("/{importJobId}")
    public ApiResponse<TemplateImportRepository.ImportJobView> get(@PathVariable UUID importJobId) {
        return success(service.get(importJobId));
    }

    @GetMapping("/{importJobId}/suggestions")
    public ApiResponse<List<TemplateImportRepository.RecognitionSuggestionView>> suggestions(
            @PathVariable UUID importJobId
    ) {
        return success(service.listSuggestions(importJobId));
    }

    @GetMapping("/{importJobId}/recognition-calls")
    public ApiResponse<List<TemplateImportRepository.RecognitionCallView>> recognitionCalls(
            @PathVariable UUID importJobId
    ) {
        return success(service.listRecognitionCalls(importJobId));
    }

    /** Internal read-only entry point for optional visual rendering. */
    @GetMapping("/{importJobId}/render-context")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> renderContext(@PathVariable UUID importJobId) {
        return success(service.renderContext(importJobId));
    }

    @GetMapping("/{importJobId}/document-structure")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> documentStructure(@PathVariable UUID importJobId) {
        return success(service.documentStructure(importJobId));
    }

    @DeleteMapping("/{importJobId}")
    public ApiResponse<Void> delete(@PathVariable UUID importJobId) {
        service.delete(importJobId);
        return success(null);
    }

    @PostMapping("/{importJobId}/suggestions/{suggestionId}/decision")
    public ApiResponse<TemplateImportRepository.RecognitionSuggestionView> decideSuggestion(
            @PathVariable UUID importJobId,
            @PathVariable UUID suggestionId,
            @Valid @RequestBody DecisionRequest request
    ) {
        return success(service.decideSuggestion(importJobId, suggestionId, request.decision()));
    }

    @PostMapping("/{importJobId}/suggestions/confirm-all")
    public ApiResponse<List<TemplateImportRepository.RecognitionSuggestionView>> confirmAll(
            @PathVariable UUID importJobId
    ) {
        return success(service.confirmAll(importJobId));
    }

    @PostMapping
    public ApiResponse<TemplateImportRepository.ImportJobView> create(
            @Valid @RequestBody CreateRequest request
    ) {
        return success(service.create(request.fileId(), request.format()));
    }

    @PostMapping("/{importJobId}/retry")
    public ApiResponse<TemplateImportRepository.ImportJobView> retry(
            @PathVariable UUID importJobId,
            @Valid @RequestBody RetryRequest request
    ) {
        return success(service.retryCurrentDraft(
                importJobId, request.source(), request.baseWorkspaceHash()
        ));
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record CreateRequest(@NotNull UUID fileId, @NotNull TemplateFormat format) {
    }

    public record DecisionRequest(@NotNull String decision) {
    }

    public record RetryRequest(@NotNull String source, @NotNull String baseWorkspaceHash) {
    }
}
