package com.jsd.aird.spc.infrastructure;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.port.SpectrumPromptPort;
import com.jsd.aird.spc.application.port.SpectrumVisionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiCompatibleSpectrumVisionClient implements SpectrumVisionClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleSpectrumVisionClient.class);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final SpectrumPromptProvider prompts;

    public OpenAiCompatibleSpectrumVisionClient(ObjectMapper objectMapper,
                                                SpectrumPromptProvider prompts,
                                                @Value("${app.model.base-url:}") String baseUrl,
                                                @Value("${app.model.api-key:}") String apiKey,
                                                @Value("${app.model.model:}") String model,
                                                @Value("${app.model.max-completion-tokens:12000}") int maxTokens) {
        this.objectMapper = objectMapper;
        this.prompts = prompts;
        this.baseUrl = strip(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.maxTokens = Math.max(1000, maxTokens);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @Override
    public VisionResult analyze(VisionRequest request) {
        if (!isConfigured()) throw new IllegalStateException("图谱视觉模型尚未配置");
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.0);
        body.put("max_tokens", maxTokens);
        body.set("response_format", objectMapper.createObjectNode().put("type", "json_object"));
        var messages = body.putArray("messages");
        messages.add(objectMapper.createObjectNode().put("role", "system").put("content",
                "请严格执行用户消息中的图谱分析规则，输出 JSON 对象。"));
        var user = objectMapper.createObjectNode().put("role", "user");
        var content = user.putArray("content");
        content.add(objectMapper.createObjectNode().put("type", "text").put("text", request.prompt()));
        for (var image : request.images()) {
            var imageNode = objectMapper.createObjectNode().put("type", "image_url");
            imageNode.set("image_url", objectMapper.createObjectNode().put("url", image.dataUri()));
            content.add(imageNode);
        }
        messages.add(user);
        var response = restClient.post().uri(baseUrl + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
        .retrieve().body(JsonNode.class);
        if (response == null) throw new IllegalStateException("视觉模型返回为空");
        var choice = response.path("choices").path(0);
        var message = choice.path("message");
        var contentText = textContent(message.path("content"));
        if (contentText.isBlank()) {
            var finishReason = choice.path("finish_reason").asText("");
            var reasoningPresent = !textContent(message.path("reasoning_content")).isBlank();
            var errorMessage = response.path("error").path("message").asText("");
            log.warn("Spectrum model returned no message content: model={}, finishReason={}, choices={}, "
                            + "contentType={}, reasoningPresent={}, providerError={}",
                    model, finishReason, response.path("choices").isArray() ? response.path("choices").size() : 0,
                    message.path("content").getNodeType(), reasoningPresent, errorMessage);
            throw new IllegalStateException("视觉模型响应内容为空（finish_reason=" + finishReason
                    + "，reasoning_content=" + (reasoningPresent ? "有" : "无") + "）");
        }
        return new VisionResult(parseJson(contentText), response, model,
                "COMPETITOR_DECOMPOSITION".equals(request.scenarioTemplate())
                        ? SpectrumPromptPort.COMPETITOR_VERSION : SpectrumPromptPort.GENERIC_VERSION);
    }

    private JsonNode parseJson(String value) {
        var normalized = value.strip();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            normalized = normalized.substring(3, normalized.length() - 3).strip();
            if (normalized.startsWith("json")) normalized = normalized.substring(4).strip();
        }
        try { return objectMapper.readTree(normalized); }
        catch (Exception exception) {
            var result = prompts.emptyResult("视觉模型未返回合法 JSON，分析任务未完成。", "模型未返回合法 JSON");
            result.put("analysisStatus", "FAILED");
            return result;
        }
    }

    private String textContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText("");
        if (node.isArray()) {
            var result = new StringBuilder();
            node.forEach(item -> result.append(textContent(item)));
            return result.toString();
        }
        if (node.isObject()) {
            if (node.path("text").isTextual()) return node.path("text").asText("");
            if (node.path("content").isTextual()) return node.path("content").asText("");
        }
        return "";
    }

    private String strip(String value) {
        if (value == null) return "";
        var result = value.strip();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
