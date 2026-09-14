package com.jsd.aird.spc.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.port.SpectrumVisionClient;

/** Stateful parser for OpenAI Responses API server-sent events. */
final class SpectrumResponsesStreamParser {

    private final ObjectMapper objectMapper;

    SpectrumResponsesStreamParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ParsedStream parse(InputStream input, SpectrumVisionClient.StreamObserver observer) throws IOException {
        var output = new StringBuilder();
        JsonNode completedResponse = null;
        var completed = false;
        var eventData = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!eventData.isEmpty()) {
                        var event = eventData.toString();
                        eventData.setLength(0);
                        if ("[DONE]".equals(event.strip())) continue;
                        var parsed = parseEvent(event);
                        var outcome = accept(parsed, output, observer);
                        if (outcome.completedResponse() != null) completedResponse = outcome.completedResponse();
                        completed = completed || outcome.completed();
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!eventData.isEmpty()) eventData.append('\n');
                    eventData.append(line.substring(5).stripLeading());
                }
            }
        }
        if (!eventData.isEmpty() && !"[DONE]".equals(eventData.toString().strip())) {
            var outcome = accept(parseEvent(eventData.toString()), output, observer);
            if (outcome.completedResponse() != null) completedResponse = outcome.completedResponse();
            completed = completed || outcome.completed();
        }
        if (!completed || completedResponse == null) {
            throw new IllegalStateException("图谱模型流式响应未完整结束");
        }
        if (!"completed".equals(completedResponse.path("status").asText())) {
            throw providerFailure("未完整完成", completedResponse);
        }
        var finalOutput = responseOutputText(completedResponse);
        if (finalOutput.isBlank()) finalOutput = output.toString();
        return new ParsedStream(finalOutput, completedResponse);
    }

    private JsonNode parseEvent(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception exception) {
            throw new IllegalStateException("图谱模型返回了无法解析的流式事件", exception);
        }
    }

    private EventOutcome accept(JsonNode event, StringBuilder output, SpectrumVisionClient.StreamObserver observer) {
        var type = event.path("type").asText("");
        return switch (type) {
            case "response.output_text.delta" -> {
                var delta = event.path("delta").asText("");
                output.append(delta);
                if (!delta.isEmpty()) observer.onOutputTextDelta(delta);
                yield EventOutcome.NONE;
            }
            case "response.output_text.done" -> {
                if (output.isEmpty()) output.append(event.path("text").asText(""));
                yield EventOutcome.NONE;
            }
            case "response.completed" -> new EventOutcome(true, event.path("response"));
            case "response.failed" -> throw providerFailure("失败", event.path("response"));
            case "response.incomplete" -> throw providerFailure("未完整完成", event.path("response"));
            case "error" -> throw providerFailure("返回错误", event);
            default -> EventOutcome.NONE;
        };
    }

    private IllegalStateException providerFailure(String status, JsonNode source) {
        var message = source.path("error").path("message").asText("");
        if (message.isBlank()) message = source.path("incomplete_details").path("reason").asText("");
        if (message.isBlank()) message = source.path("message").asText("");
        return new IllegalStateException("图谱模型响应" + status + (message.isBlank() ? "" : "：" + message));
    }

    private String responseOutputText(JsonNode response) {
        var result = new StringBuilder();
        for (var item : response.path("output")) {
            if (!"message".equals(item.path("type").asText())) continue;
            for (var content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText())) result.append(content.path("text").asText(""));
            }
        }
        return result.toString();
    }

    record ParsedStream(String outputText, JsonNode response) { }

    private record EventOutcome(boolean completed, JsonNode completedResponse) {
        private static final EventOutcome NONE = new EventOutcome(false, null);
    }
}
