package com.jsd.aird.mfg.upload.adapter.in.web;

import java.time.LocalDate;
import java.util.UUID;

import com.jsd.aird.mfg.upload.application.ProductionUploadService;
import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/production-uploads")
public class ProductionUploadController {

    private final ProductionUploadService service;

    public ProductionUploadController(ProductionUploadService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<ProductionUploadRepository.UploadView> create(
            @Valid @RequestBody CreateRequest request) {
        return success(service.create(new ProductionUploadService.CreateCommand(
                request.fileId(), request.productionName(), request.orderNo(), request.productName(),
                request.category(), request.manufactureDate(), request.projectId(), request.projectName(),
                request.stageId(), request.stageName(), request.taskId(), request.taskName(), request.visibility(),
                request.sourceType(), request.templateVersionId())));
    }

    @GetMapping
    public ApiResponse<ProductionUploadRepository.PageResult<ProductionUploadRepository.UploadView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(service.list(keyword, status, projectId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductionUploadRepository.UploadView> get(@PathVariable UUID id) {
        return success(service.get(id));
    }

    @GetMapping("/{id}/fields")
    public ApiResponse<java.util.List<ProductionUploadRepository.RecognitionFieldView>> fields(
            @PathVariable UUID id) {
        return success(service.fields(id));
    }

    @PostMapping("/{id}/select-template")
    public ApiResponse<ProductionUploadRepository.UploadView> selectTemplate(
            @PathVariable UUID id, @Valid @RequestBody SelectTemplateRequest request) {
        return success(service.selectTemplate(id, request.templateVersionId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return success(null);
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record CreateRequest(
            @NotNull UUID fileId,
            @Size(max = 200) String productionName,
            @Size(max = 80) String orderNo,
            @Size(max = 200) String productName,
            @Size(max = 120) String category,
            LocalDate manufactureDate,
            UUID projectId,
            String projectName,
            UUID stageId,
            String stageName,
            UUID taskId,
            String taskName,
            @NotBlank String visibility,
            String sourceType,
            UUID templateVersionId
    ) {
    }

    public record SelectTemplateRequest(@NotNull UUID templateVersionId) {
    }

}
