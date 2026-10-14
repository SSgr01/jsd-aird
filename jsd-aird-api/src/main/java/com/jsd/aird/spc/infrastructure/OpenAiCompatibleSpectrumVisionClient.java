package com.jsd.aird.spc.infrastructure;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.spc.application.port.SpectrumPromptPort;
import com.jsd.aird.spc.application.port.SpectrumVisionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** OpenAI Responses API client used only by spectrum analysis. */
@Component
public class OpenAiCompatibleSpectrumVisionClient implements SpectrumVisionClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String responsesPath;
    private final String reasoningEffort;
    private final String textVerbosity;
    private final int maxOutputTokens;
    private final Duration readTimeout;

    public OpenAiCompatibleSpectrumVisionClient(
            ObjectMapper objectMapper,
            @Value("${app.spectrum.model.base-url:}") String baseUrl,
            @Value("${app.spectrum.model.api-key:}") String apiKey,
            @Value("${app.spectrum.model.name:}") String model,
            @Value("${app.spectrum.model.responses-path:/responses}") String responsesPath,
            @Value("${app.spectrum.model.reasoning-effort:high}") String reasoningEffort,
            @Value("${app.spectrum.model.text-verbosity:high}") String textVerbosity,
            @Value("${app.spectrum.model.max-output-tokens:12000}") int maxOutputTokens,
            @Value("${app.spectrum.model.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.spectrum.model.read-timeout:10m}") Duration readTimeout) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.responsesPath = normalizePath(responsesPath);
        this.reasoningEffort = blankDefault(reasoningEffort, "high");
        this.textVerbosity = normalizeVerbosity(textVerbosity);
        this.maxOutputTokens = Math.max(1000, maxOutputTokens);
        this.readTimeout = readTimeout == null ? Duration.ofMinutes(10) : readTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout)
                .build();
    }

    @Override
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @Override
    public VisionResult analyze(VisionRequest request, StreamObserver observer) {
        if (!isConfigured()) throw new IllegalStateException("图谱 GPT-5.6 模型尚未配置");
        var safeObserver = observer == null ? StreamObserver.NOOP : observer;
        var body = requestBody(request);
        var httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + responsesPath))
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (var stream = response.body()) {
                    var error = new String(stream.readNBytes(16_384), StandardCharsets.UTF_8);
                    throw new IllegalStateException("图谱模型调用失败（HTTP " + response.statusCode() + "）："
                            + providerError(error));
                }
            }
            SpectrumResponsesStreamParser.ParsedStream parsed;
            try (InputStream stream = response.body()) {
                parsed = new SpectrumResponsesStreamParser(objectMapper).parse(stream, safeObserver);
            }
            var result = parseOutput(parsed.outputText());
            var returnedModel = parsed.response().path("model").asText(model);
            return new VisionResult(result, parsed.response(), returnedModel, SpectrumPromptPort.currentVersion());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图谱模型调用被中断", exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("图谱模型流式调用失败：" + safeMessage(exception), exception);
        }
    }

    ObjectNode requestBody(VisionRequest request) {
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("instructions", "请根据用户问题和图谱资料直接用自然、专业的简体中文回答。"
                + "按内容需要使用 Markdown 标题、表格或列表，不要求固定结构；不要输出 JSON、claims、内部字段或思维过程。");
        body.put("max_output_tokens", maxOutputTokens);
        body.put("stream", true);
        var reasoning = body.putObject("reasoning");
        reasoning.put("effort", reasoningEffort);
        body.putObject("text").put("verbosity", textVerbosity);
        var input = body.putArray("input");
        var user = input.addObject().put("role", "user");
        var content = user.putArray("content");
        content.addObject().put("type", "input_text").put("text", request.prompt());
        for (var image : request.images()) {
            content.addObject().put("type", "input_text").put("text", image.caption());
            content.addObject().put("type", "input_image").put("image_url", image.dataUri()).put("detail", "high");
        }
        return body;
    }

    JsonNode parseOutput(String value) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            var firstLineEnd = normalized.indexOf('\n');
            if (firstLineEnd >= 0) normalized = normalized.substring(firstLineEnd + 1, normalized.length() - 3).strip();
            else normalized = normalized.substring(3, normalized.length() - 3).strip();
        }
        if (normalized.isBlank()) throw new IllegalStateException("图谱模型未返回可显示的分析内容");
        var result = objectMapper.createObjectNode();
        result.put("schemaVersion", 4);
        result.put("analysisStatus", "SUCCEEDED");
        result.put("answerMarkdown", normalized);
        return result;
    }

    private String providerError(String responseBody) {
        try {
            var root = objectMapper.readTree(responseBody);
            var message = root.path("error").path("message").asText("");
            if (!message.isBlank()) return message.substring(0, Math.min(500, message.length()));
        } catch (Exception ignored) { }
        var value = responseBody == null ? "" : responseBody.replaceAll("\\s+", " ").strip();
        return value.isBlank() ? "上游未返回错误详情" : value.substring(0, Math.min(500, value.length()));
    }

    private String safeMessage(Exception exception) {
        var message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(500, message.length()));
    }

    private String stripTrailingSlash(String value) {
        var result = value == null ? "" : value.strip();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String normalizePath(String value) {
        var result = blankDefault(value, "/responses");
        return result.startsWith("/") ? result : "/" + result;
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private String normalizeVerbosity(String value) {
        var normalized = blankDefault(value, "high").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "medium", "high" -> normalized;
            default -> "high";
        };
    }
}
