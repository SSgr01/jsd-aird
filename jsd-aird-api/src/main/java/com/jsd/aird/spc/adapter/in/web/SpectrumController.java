package com.jsd.aird.spc.adapter.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.spc.application.SpectrumService;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
                                                                     @Valid @RequestBody CategoryRequest request) {
        return success(service.updateCategory(categoryId, request.command()));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID categoryId) {
        service.deleteCategory(categoryId);
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
        return success(service.createChart(request.command()));
    }

    @GetMapping("/charts/{chartId}")
    public ApiResponse<SpectrumService.ChartView> chart(@PathVariable UUID chartId) {
        return success(service.chart(chartId));
    }

    @PutMapping("/charts/{chartId}")
    public ApiResponse<SpectrumService.ChartView> updateChart(@PathVariable UUID chartId,
                                                               @Valid @RequestBody UpdateChartRequest request) {
        return success(service.updateChart(chartId, request.command()));
    }

    @DeleteMapping("/charts/{chartId}")
    public ApiResponse<Void> deleteChart(@PathVariable UUID chartId) {
        service.deleteChart(chartId);
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
                                   JsonNode fields) {
        SpectrumService.CategoryCommand command() {
            return new SpectrumService.CategoryCommand(code, name, description, analysisHint, fields);
        }
    }

    public record SessionTitleRequest(@NotBlank @Size(max = 80) String title) { }

    public record CreateChartRequest(@NotBlank String fileId,
                                     @Size(max = 260) String title,
                                     @NotNull UUID categoryId,
                                     @Size(max = 260) String sampleName,
                                     @Size(max = 160) String batchNo,
                                     @Size(max = 10000) String testConditions,
                                     JsonNode metadata) {
        SpectrumService.CreateChartCommand command() {
            return new SpectrumService.CreateChartCommand(fileId, title, categoryId, sampleName, batchNo,
                    testConditions, metadata);
        }
    }

    public record UpdateChartRequest(@NotBlank @Size(max = 260) String title,
                                     @Size(max = 260) String sampleName,
                                     @Size(max = 160) String batchNo,
                                     @Size(max = 10000) String testConditions,
                                     JsonNode metadata) {
        SpectrumService.UpdateChartCommand command() {
            return new SpectrumService.UpdateChartCommand(title, sampleName, batchNo, testConditions, metadata);
        }
    }
}
