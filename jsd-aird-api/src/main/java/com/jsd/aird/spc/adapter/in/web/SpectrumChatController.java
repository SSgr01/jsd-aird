package com.jsd.aird.spc.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.spc.application.SpectrumChatService;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/spc")
public class SpectrumChatController {

    private final SpectrumChatService service;

    public SpectrumChatController(SpectrumChatService service) {
        this.service = service;
    }

    @PostMapping("/chat/messages")
    public ApiResponse<SpectrumChatService.SubmitView> submit(@Valid @RequestBody ChatRequest request) {
        return success(service.submit(new SpectrumChatService.ChatCommand(request.sessionId(), request.question(),
                request.chartIds(), request.pageSelections(), request.scenarioTemplate())));
    }

    @GetMapping("/analysis-runs/{analysisId}")
    public ApiResponse<SpectrumChatService.AnalysisView> analysis(@PathVariable UUID analysisId) {
        return success(service.analysis(analysisId));
    }

    @GetMapping(value = "/analysis-runs/{analysisId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID analysisId) {
        return service.stream(analysisId);
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record ChatRequest(UUID sessionId, @NotBlank @Size(max = 3000) String question,
                              @NotEmpty @Size(max = 12) List<UUID> chartIds,
                              JsonNode pageSelections, @Size(max = 80) String scenarioTemplate) { }
}
