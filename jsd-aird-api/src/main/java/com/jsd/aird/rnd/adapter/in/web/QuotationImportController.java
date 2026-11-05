package com.jsd.aird.rnd.adapter.in.web;

import java.util.UUID;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.QuotationImportService;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
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

/** 报价记录文件上传任务入口。 */
@RestController
@RequestMapping("/api/v1/quotation-imports")
public class QuotationImportController {
    private final QuotationImportService service;

    public QuotationImportController(QuotationImportService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<?> create(@Valid @RequestBody ImportRequest request) {
        return ok(service.importFile(new QuotationImportService.Command(
                request.fileId(), request.fileName(), request.sha256(), request.format(),
                request.templateVersionId(), request.projectId(), request.projectName(),
                request.stageId(), request.stageName(), request.taskId(), request.taskName(),
                request.visibility())));
    }

    @GetMapping
    public ApiResponse<?> list() {
        return ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable UUID id) {
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
                                @NotNull UUID templateVersionId, UUID projectId, String projectName,
                                UUID stageId, String stageName, UUID taskId, String taskName,
                                @NotBlank String visibility) { }
}
