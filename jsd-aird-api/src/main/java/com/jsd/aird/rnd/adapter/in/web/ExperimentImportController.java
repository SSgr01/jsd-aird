package com.jsd.aird.rnd.adapter.in.web;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.ExperimentImportService;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.tpl.domain.TemplateFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/experiment-imports")
public class ExperimentImportController {
    private final ExperimentImportService service;

    public ExperimentImportController(ExperimentImportService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<?> create(@Valid @RequestBody ImportRequest request) {
        return ResponseFactory.success(service.importFile(new ExperimentImportService.Command(
                request.fileId(), request.fileName(), request.sha256(), request.format(),
                request.categoryId(), request.categoryName(), request.projectId(), request.stageId(),
                request.taskId(), request.experimentDate(), request.visibility())), RequestIdHolder.currentOrUnknown());
    }

    @GetMapping
    public ApiResponse<?> list() {
        return ResponseFactory.success(service.list(), RequestIdHolder.currentOrUnknown());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseFactory.success(null, RequestIdHolder.currentOrUnknown());
    }

    public record ImportRequest(@NotNull UUID fileId, @NotBlank String fileName,
                                @NotBlank String sha256, @NotNull TemplateFormat format,
                                UUID categoryId, String categoryName, UUID projectId,
                                UUID stageId, UUID taskId, LocalDate experimentDate, String visibility) {}
}
