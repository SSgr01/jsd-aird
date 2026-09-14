package com.jsd.aird.spc.infrastructure;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
    private final int maxOutputTokens;
    private final Duration readTimeout;

    public OpenAiCompatibleSpectrumVisionClient(
            ObjectMapper objectMapper,
            @Value("${app.spectrum.model.base-url:}") String baseUrl,
            @Value("${app.spectrum.model.api-key:}") String apiKey,
            @Value("${app.spectrum.model.name:}") String model,
            @Value("${app.spectrum.model.responses-path:/responses}") String responsesPath,
            @Value("${app.spectrum.model.reasoning-effort:high}") String reasoningEffort,
            @Value("${app.spectrum.model.max-output-tokens:12000}") int maxOutputTokens,
            @Value("${app.spectrum.model.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.spectrum.model.read-timeout:10m}") Duration readTimeout) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.responsesPath = normalizePath(responsesPath);
        this.reasoningEffort = blankDefault(reasoningEffort, "high");
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
            var result = parseJson(parsed.outputText());
            var returnedModel = parsed.response().path("model").asText(model);
            return new VisionResult(result, parsed.response(), returnedModel,
                    "COMPETITOR_DECOMPOSITION".equals(request.scenarioTemplate())
                            ? SpectrumPromptPort.COMPETITOR_VERSION : SpectrumPromptPort.GENERIC_VERSION);
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
        body.put("instructions", "请严格执行输入中的图谱分析规则，只输出一个 JSON 对象。"
                + "所有面向用户的文本必须使用简体中文，不得输出内部 ID。");
        body.put("max_output_tokens", maxOutputTokens);
        body.put("stream", true);
        var reasoning = body.putObject("reasoning");
        reasoning.put("effort", reasoningEffort);
        var input = body.putArray("input");
        var user = input.addObject().put("role", "user");
        var content = user.putArray("content");
        content.addObject().put("type", "input_text").put("text", request.prompt());
        for (var image : request.images()) {
            content.addObject().put("type", "input_image").put("image_url", image.dataUri()).put("detail", "high");
        }
        return body;
    }

    private JsonNode parseJson(String value) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            normalized = normalized.substring(3, normalized.length() - 3).strip();
            if (normalized.startsWith("json")) normalized = normalized.substring(4).strip();
        }
        try {
            var parsed = objectMapper.readTree(normalized);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("根节点不是 JSON 对象");
            return parsed;
        } catch (Exception exception) {
            throw new IllegalStateException("图谱模型未返回合法 JSON", exception);
        }
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
}
