package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QwenOcrProvider implements MediaExtractionProvider {

    private static final String DEFAULT_PROMPT = "Extract all visible text from this image. Return only the recognized text, preserving the original reading order. Do not add explanations.";

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String completionsPath;
    private final long maxBytes;
    private final RestClient client;

    public QwenOcrProvider(
            @Value("${app.ai.ocr.enabled:false}") boolean enabled,
            @Value("${app.ai.ocr.base-url:}") String baseUrl,
            @Value("${app.ai.ocr.api-key:}") String apiKey,
            @Value("${app.ai.ocr.model:qwen3.5-ocr}") String model,
            @Value("${app.ai.ocr.completions-path:/chat/completions}") String completionsPath,
            @Value("${app.ai.ocr.timeout:120s}") Duration timeout,
            @Value("${app.ai.ocr.max-bytes:20971520}") long maxBytes
    ) {
        this.enabled = enabled;
        this.baseUrl = strip(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.completionsPath = normalizePath(completionsPath, "/chat/completions");
        this.maxBytes = Math.max(1, maxBytes);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("image/") || name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".webp")
                || name.endsWith(".bmp") || name.endsWith(".tif") || name.endsWith(".tiff");
    }

    @Override
    public boolean isConfigured() {
        return enabled && StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey) && StringUtils.hasText(model);
    }

    @Override
    public String unavailableReason() {
        return "OCR 服务尚未配置";
    }

    @Override
    public DocumentParser.ParsedDocument extract(InputStream source, String fileName, ExtractionContext context) {
        if (!isConfigured()) throw new IllegalStateException("OCR 服务尚未配置");
        try {
            var bytes = source.readAllBytes();
            if (bytes.length > maxBytes) {
                throw new IllegalStateException("OCR 图片超过限制：" + maxBytes + " bytes");
            }
            var contentType = StringUtils.hasText(context == null ? null : context.contentType())
                    ? context.contentType() : mimeType(fileName);
            var dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
            var request = Map.of(
                    "model", model,
                    "stream", false,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "text", "text", DEFAULT_PROMPT),
                                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                            )
                    ))
            );
            var response = client.post().uri(baseUrl + completionsPath)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            var text = content(response == null ? null : response.at("/choices/0/message/content"));
            if (!StringUtils.hasText(text)) {
                text = content(response == null ? null : response.at("/output/choices/0/message/content"));
            }
            if (!StringUtils.hasText(text)) throw new IllegalStateException("OCR 返回内容为空");
            return new DocumentParser.ParsedDocument(List.of(new DocumentParser.TextBlock(null, "OCR", text.strip())),
                    model);
        } catch (IOException exception) {
            throw new IllegalStateException("OCR 文件读取失败", exception);
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("OCR 服务调用失败：HTTP " + exception.getStatusCode().value()
                    + " " + exception.getResponseBodyAsString(), exception);
        }
    }

    private String content(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            var result = new StringBuilder();
            node.forEach(item -> {
                var text = item.path("text").asText("");
                if (!text.isBlank()) result.append(text).append('\n');
            });
            return result.toString().strip();
        }
        return node.path("text").asText(node.path("ocr_result").asText(""));
    }

    private String mimeType(String fileName) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".tif") || name.endsWith(".tiff")) return "image/tiff";
        return "image/jpeg";
    }

    private String strip(String value) {
        return value == null ? "" : value.strip().replaceAll("/+$", "");
    }

    private String normalizePath(String value, String fallback) {
        var path = StringUtils.hasText(value) ? value.strip() : fallback;
        return path.startsWith("/") ? path : "/" + path;
    }
}
