package com.jsd.aird.rnd.adapter.in.web;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.ExperimentImportService;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.domain.TemplateFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** RND entry point for the original free-upload flow. */
@RestController
@RequestMapping("/api/v1/experiment-imports")
public class ExperimentImportController {
    private final ExperimentImportService service;

    public ExperimentImportController(ExperimentImportService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<?> create(@Valid @RequestBody ImportRequest request) {
        if (request.templateVersionId() != null
                || "TEMPLATE_GUIDED".equalsIgnoreCase(request.recognitionMode())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "按模板导入请使用数据中心导入任务接口");
        }
        return ok(service.importFile(new ExperimentImportService.Command(
                request.fileId(), request.fileName(), request.sha256(), request.format(),
                request.categoryId(), request.categoryName(), request.projectId(), request.stageId(),
                request.taskId(), request.experimentDate(), request.visibility(), request.duplicateOverride())));
    }

    @GetMapping
    public ApiResponse<?> list() {
        return ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable UUID id) {
        return ok(service.get(id));
    }

    @GetMapping("/{id}/workspace")
    public ApiResponse<?> workspace(@PathVariable UUID id) {
        return ok(service.get(id));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<?> retry(@PathVariable UUID id) {
        return ok(service.retry(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable UUID id) {
        service.delete(id);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record ImportRequest(@NotNull UUID fileId, @NotBlank String fileName,
                                @NotBlank String sha256, @NotNull TemplateFormat format,
                                UUID categoryId, String categoryName, UUID projectId,
                                UUID stageId, UUID taskId, LocalDate experimentDate,
                                String visibility, String recognitionMode,
                                UUID templateVersionId, boolean duplicateOverride) {}
}
