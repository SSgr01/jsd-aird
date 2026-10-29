package com.jsd.aird.spc.adapter.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.platform.web.TiffPreview;
import com.jsd.aird.spc.application.SpectrumService;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;

@RestController
@RequestMapping("/api/v1/spc")
public class SpectrumController {

    private final SpectrumService service;

    public SpectrumController(SpectrumService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public ApiResponse<List<SpectrumService.CategoryView>> categories() {
        return success(service.categories());
    }

    @PostMapping("/categories")
    public ApiResponse<SpectrumService.CategoryView> createCategory(@Valid @RequestBody CategoryRequest request) {
        return success(service.createCategory(request.command()));
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<SpectrumService.CategoryView> updateCategory(@PathVariable UUID categoryId,
                                                                     @Valid @RequestBody UpdateCategoryRequest request) {
        return success(service.updateCategory(categoryId, request.command(), request.revision()));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID categoryId,@RequestParam String revision) {
        service.deleteCategory(categoryId,revision);
        return success(null);
    }

    @GetMapping("/charts")
    public ApiResponse<?> charts(@RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) UUID categoryId,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return success(service.listCharts(keyword, categoryId, status, page, size));
    }

    @PostMapping("/charts")
    public ApiResponse<SpectrumService.ChartView> createChart(@Valid @RequestBody CreateChartRequest request) {
        try {
            return success(service.createChart(request.command()));
        } catch (RuntimeException exception) {
            if (request.requestId() != null) {
                var apiException = exception instanceof ApiException error ? error : null;
                var code = apiException == null ? "UPLOAD_FAILED" : apiException.errorCode().code();
                var status = apiException != null && apiException.errorCode() == ApiErrorCode.RESOURCE_CONFLICT
                        ? "DUPLICATE" : "FAILED";
                UUID stagedFileId = null;
                try { stagedFileId = UUID.fromString(request.fileId()); } catch (RuntimeException ignored) { }
                try {
                    service.recordUploadAttempt(new SpectrumService.UploadAttemptCommand(request.requestId(),
                            request.categoryId(), stagedFileId,
                            request.originalName() == null || request.originalName().isBlank()
                                    ? "未知图谱文件" : request.originalName(),
                            request.size(), status, code,
                            exception.getMessage() == null ? "图谱保存失败" : exception.getMessage(),
                            request.uploadContext(), request.parentRequestId()));
                } catch (RuntimeException ignored) {
                    // Preserve the original create failure; recording is best-effort for malformed requests.
                }
            }
            throw exception;
        }
    }

    @GetMapping("/uploads/attempts")
    public ApiResponse<List<SpectrumService.UploadAttemptView>> uploadAttempts(
            @RequestParam(defaultValue = "100") int limit) {
        return success(service.uploadAttempts(limit));
    }

    @PostMapping("/uploads/attempts")
    public ApiResponse<Void> recordUploadAttempt(@Valid @RequestBody UploadAttemptRequest request) {
        service.recordUploadAttempt(new SpectrumService.UploadAttemptCommand(request.requestId(),
                request.categoryId(), request.fileObjectId(), request.originalName(), request.size(),
                request.status(), request.errorCode(), request.errorMessage(),
                request.uploadContext(), request.parentRequestId()));
        return success(null);
    }

    @DeleteMapping("/uploads/attempts/{requestId}")
    public ApiResponse<Void> hideUploadAttempt(@PathVariable UUID requestId) {
        service.hideUploadAttempt(requestId);
        return success(null);
    }

    @PostMapping("/charts/export")
    public void exportCharts(@Valid @RequestBody ExportChartsRequest request, HttpServletResponse response)
            throws IOException {
        if (request.chartIds().size() == 1) {
            var file = service.openChart(request.chartIds().getFirst()).file();
            try (var stream = file.stream()) {
                response.setContentType(file.contentType());
                response.setContentLengthLong(file.size());
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.originalName(), StandardCharsets.UTF_8).build().toString());
                stream.transferTo(response.getOutputStream());
            }
            return;
        }
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("图谱文件.zip", StandardCharsets.UTF_8).build().toString());
        service.writeExport(request.chartIds(), response.getOutputStream());
    }

    @GetMapping("/charts/{chartId}")
    public ApiResponse<SpectrumService.ChartView> chart(@PathVariable UUID chartId) {
        return success(service.chart(chartId));
    }

    @PutMapping("/charts/{chartId}")
    public ApiResponse<SpectrumService.ChartView> updateChart(@PathVariable UUID chartId,
                                                               @Valid @RequestBody UpdateChartRequest request) {
        return success(service.updateChart(chartId, request.command(), request.revision()));
    }

    @PutMapping("/charts/{chartId}/category")
    public ApiResponse<SpectrumService.ChartView> assignChartCategory(@PathVariable UUID chartId,
                                                                       @Valid @RequestBody AssignChartCategoryRequest request) {
        return success(service.assignChartCategory(chartId, request.categoryId(), request.revision()));
    }

    @DeleteMapping("/charts/{chartId}")
    public ApiResponse<Void> deleteChart(@PathVariable UUID chartId,@RequestParam String revision) {
        service.deleteChart(chartId,revision);
        return success(null);
    }

    @GetMapping("/charts/{chartId}/pages")
    public ApiResponse<List<SpectrumService.PageView>> pages(@PathVariable UUID chartId) {
        return success(service.pages(chartId));
    }

    @GetMapping("/charts/{chartId}/content")
    public void content(@PathVariable UUID chartId, HttpServletResponse response) throws IOException {
        var stored = service.openChart(chartId);
        var file = stored.file();
        writeInline(file, response);
    }

    /**
     * Return a browser-friendly rendering for formats that are accepted by the
     * spectrum module but are not consistently rendered by mobile browsers.
     * The original file remains available from /content for downloads.
     */
    @GetMapping("/charts/{chartId}/preview")
    public void preview(@PathVariable UUID chartId, HttpServletResponse response) throws IOException {
        var stored = service.openChart(chartId);
        var file = stored.file();
        if (!TiffPreview.isTiff(file.originalName(), file.contentType())) {
            writeInline(file, response);
            return;
        }

        byte[] png;
        try (file) {
            png = TiffPreview.toPng(file.stream());
        } catch (Exception exception) {
            throw new IOException("TIFF 图谱预览生成失败", exception);
        }

        response.setContentType("image/png");
        response.setContentLengthLong(png.length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(TiffPreview.previewName(file.originalName()), StandardCharsets.UTF_8).build().toString());
        response.getOutputStream().write(png);
    }

    private void writeInline(com.jsd.aird.ops.application.port.FileStorageFacade.StoredFile file,
                             HttpServletResponse response) throws IOException {
        response.setContentType(file.contentType());
        response.setContentLengthLong(file.size());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(file.originalName(), StandardCharsets.UTF_8).build().toString());
        try (file) {
            file.stream().transferTo(response.getOutputStream());
        } catch (Exception exception) {
            throw new IOException("图谱文件读取失败", exception);
        }
    }

    @PostMapping("/chat/sessions")
    public ApiResponse<SpectrumService.SessionView> createSession() {
        return success(service.createSession());
    }

    @GetMapping("/chat/sessions")
    public ApiResponse<List<SpectrumService.SessionView>> sessions(@RequestParam(defaultValue = "50") int limit) {
        return success(service.sessions(limit));
    }

    @GetMapping("/chat/sessions/{sessionId}")
    public ApiResponse<SpectrumService.SessionView> session(@PathVariable UUID sessionId) {
        return success(service.session(sessionId));
    }

    @PatchMapping("/chat/sessions/{sessionId}")
    public ApiResponse<Void> renameSession(@PathVariable UUID sessionId, @Valid @RequestBody SessionTitleRequest request) {
        service.renameSession(sessionId, request.title());
        return success(null);
    }

    @DeleteMapping("/chat/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable UUID sessionId) {
        service.deleteSession(sessionId);
        return success(null);
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record CategoryRequest(@NotBlank @Size(max = 120) String name,
                                  @Size(max = 64) String code,
                                  @Size(max = 500) String description,
                                  @Size(max = 4000) String analysisHint,
                                   JsonNode fields, String revision) {
        SpectrumService.CategoryCommand command() {
            return new SpectrumService.CategoryCommand(code, name, description, analysisHint, fields);
        }
    }

    public record SessionTitleRequest(@NotBlank @Size(max = 80) String title) { }

    public record UpdateCategoryRequest(@NotBlank @Size(max = 120) String name,
                                         @Size(max = 64) String code,
                                         @Size(max = 500) String description,
                                         @Size(max = 4000) String analysisHint,
                                         JsonNode fields, @NotBlank String revision) {
        SpectrumService.CategoryCommand command() {
            return new SpectrumService.CategoryCommand(code, name, description, analysisHint, fields);
        }
    }

    public record CreateChartRequest(@NotBlank String fileId,
                                     @Size(max = 260) String title,
                                     @NotNull UUID categoryId,
                                     @Size(max = 260) String sampleName,
                                     @Size(max = 160) String batchNo,
                                     @Size(max = 10000) String testConditions,
                                     JsonNode metadata,
                                     @NotNull UUID requestId,
                                     @Size(max = 500) String originalName,
                                     @Min(0) long size,
                                     boolean duplicateOverride,
                                     JsonNode uploadContext,
                                     UUID parentRequestId) {
        SpectrumService.CreateChartCommand command() {
            return new SpectrumService.CreateChartCommand(fileId, title, categoryId, sampleName, batchNo,
                    testConditions, metadata, requestId, duplicateOverride, uploadContext, parentRequestId);
        }
    }

    public record UploadAttemptRequest(@NotNull UUID requestId, UUID categoryId, UUID fileObjectId,
                                       @NotBlank @Size(max = 500) String originalName, @Min(0) long size,
                                       @NotBlank String status, @Size(max = 80) String errorCode,
                                       @Size(max = 2000) String errorMessage, JsonNode uploadContext,
                                       UUID parentRequestId) { }

    public record ExportChartsRequest(@NotNull @Size(min = 1, max = 50) List<@NotNull UUID> chartIds) { }

    public record UpdateChartRequest(@NotBlank @Size(max = 260) String title,
                                     @Size(max = 260) String sampleName,
                                     @Size(max = 160) String batchNo,
                                     @Size(max = 10000) String testConditions,
                                     JsonNode metadata, UUID categoryId,@NotBlank String revision) {
        SpectrumService.UpdateChartCommand command() {
            return new SpectrumService.UpdateChartCommand(title, sampleName, batchNo, testConditions, metadata, categoryId);
        }
    }

    public record AssignChartCategoryRequest(@NotNull UUID categoryId,@NotBlank String revision) { }
}
