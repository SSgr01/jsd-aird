package com.jsd.aird.ai.adapter.in.web;

import com.jsd.aird.ai.application.operation.OperationAssistantService;
import com.jsd.aird.ai.application.operation.OperationManualRetrievalService.HistoryMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/operation-assistant")
public class OperationAssistantController {
    private final OperationAssistantService service;
    public OperationAssistantController(OperationAssistantService service) { this.service = service; }

    @PostMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody QaRequest request) {
        return service.stream(new OperationAssistantService.AskCommand(request.question(), request.currentPageId(),
                request.history() == null ? List.of() : request.history().stream()
                        .map(message -> new HistoryMessage(message.role(), message.content())).toList()));
    }

    public record QaRequest(@NotBlank @Size(max = 3000) String question, @Size(max = 100) String currentPageId,
                            @Size(max = 12) List<@NotNull @Valid HistoryRequest> history) { }
    public record HistoryRequest(@NotBlank @Pattern(regexp = "USER|ASSISTANT") String role,
                                 @NotBlank @Size(max = 6000) String content) { }
}
