package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import com.jsd.aird.kb.domain.MediaExtractionException;
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

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String completionsPath;
    private final long maxBytes;
    private final RestClient client;
    private final QwenDocumentParsingConverter converter;

    public QwenOcrProvider(
            @Value("${app.ai.ocr.enabled:false}") boolean enabled,
            @Value("${app.ai.ocr.base-url:}") String baseUrl,
            @Value("${app.ai.ocr.api-key:}") String apiKey,
            @Value("${app.ai.ocr.model:qwen3.5-ocr}") String model,
            @Value("${app.ai.ocr.completions-path:/chat/completions}") String completionsPath,
            @Value("${app.ai.ocr.timeout:120s}") Duration timeout,
            @Value("${app.ai.ocr.max-bytes:20971520}") long maxBytes,
            QwenDocumentParsingConverter converter
    ) {
        this.enabled = enabled;
        this.baseUrl = strip(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.completionsPath = normalizePath(completionsPath, "/chat/completions");
        this.maxBytes = Math.max(1, maxBytes);
        this.converter = converter;
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
    public boolean requiresExternalExtraction(InputStream source, String fileName) {
        return true;
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
            var blocks = recognize(bytes, contentType, null);
            return new DocumentParser.ParsedDocument(blocks, model, null,
                    Map.of("model", model, "task", "document_parsing"));
        } catch (IOException exception) {
            throw new IllegalStateException("OCR 文件读取失败", exception);
        } catch (RestClientResponseException exception) {
            throw new MediaExtractionException("OCR 服务调用失败：HTTP " + exception.getStatusCode().value()
                    + " " + exception.getResponseBodyAsString(), null, model, exception);
        } catch (RuntimeException exception) {
            if (exception instanceof MediaExtractionException media) throw media;
            throw new MediaExtractionException(exception.getMessage() == null ? "OCR 服务调用失败" : exception.getMessage(),
                    null, model, exception);
        }
    }

    private List<DocumentParser.TextBlock> recognize(byte[] bytes, String contentType, Integer pageNo) {
        if (bytes.length > maxBytes) throw new IllegalStateException("OCR图片超过限制：" + maxBytes + " bytes");
        var documentText = recognizeTask(bytes, contentType, "document_parsing");
        var document = converter.convertDetailed(documentText, pageNo);
        // A document-parsing response can contain readable text while losing
        // the table geometry completely. In that case the page still needs a
        // dedicated table_parsing pass; only accept it when the HTML parser
        // produces a reliable table, otherwise retain the document result.
        if (!document.reliableTable()) {
            var tableText = recognizeTask(bytes, contentType, "table_parsing");
            var table = converter.convertTableHtml(tableText, pageNo);
            if (table.reliableTable()) return mergeTableFallback(document.blocks(), table.blocks());
        }
        if (!document.blocks().isEmpty()) return document.blocks();
        return List.of(new DocumentParser.TextBlock(pageNo, "paragraph", documentText.strip()));
    }

    private String recognizeTask(byte[] bytes, String contentType, String task) {
        var dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        var request = new LinkedHashMap<String, Object>();
        request.put("model", model);
        request.put("stream", false);
        if (isDashScopeGenerationEndpoint()) {
            // Native DashScope HTTP puts the built-in OCR task under
            // parameters.ocr_options and uses input.messages.content.image.
            // Sending the option at the top level silently falls back to the
            // generic vision response (often a Markdown image link).
            request.put("input", Map.of("messages", List.of(Map.of("role", "user", "content", List.of(
                    Map.of("image", dataUrl, "min_pixels", 3072, "max_pixels", 8388608,
                            "enable_rotate", false))))));
            request.put("parameters", Map.of("ocr_options", Map.of("task", task)));
        } else {
            request.put("ocr_options", Map.of("task", task));
            request.put("messages", List.of(Map.of("role", "user", "content", List.of(
                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))))));
        }
        var response = callWithRetry(request);
        var text = responseText(response);
        if (looksLikeImageOnlyMarkdown(text) && !isDashScopeGenerationEndpoint()) {
            response = callWithRetry(nativeRequest(dataUrl, task), nativeCompletionsPath());
            text = responseText(response);
        }
        if (!StringUtils.hasText(text)) throw new IllegalStateException("OCR返回内容为空");
        if (looksLikeImageOnlyMarkdown(text)) throw new IllegalStateException("OCR返回图片链接而非识别内容");
        return text;
    }

    private Map<String, Object> nativeRequest(String dataUrl, String task) {
        var request = new LinkedHashMap<String, Object>();
        request.put("model", model);
        request.put("input", Map.of("messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("image", dataUrl, "min_pixels", 3072, "max_pixels", 8388608,
                        "enable_rotate", false))))));
        request.put("parameters", Map.of("ocr_options", Map.of("task", task)));
        return request;
    }

    private String responseText(JsonNode response) {
        var text = content(response == null ? null : response.at("/choices/0/message/content"));
        if (!StringUtils.hasText(text)) text = content(response == null ? null : response.at("/output/choices/0/message/content"));
        return text;
    }

    private boolean looksLikeImageOnlyMarkdown(String text) {
        if (!StringUtils.hasText(text)) return false;
        var candidate = text.strip();
        // DashScope may wrap the image URL in a Markdown block or return a
        // long URL whose delimiters make a single-line regex unreliable.
        // Remove image nodes first, then reject the response when nothing
        // human-readable remains. Such a response is a rendered preview, not
        // OCR text, and must never become a confirmed source node.
        var withoutImages = candidate
                .replaceAll("(?s)!\\[[^\\]]*\\]\\(.*?\\)", "")
                .replaceAll("(?s)```(?:markdown|md)?", "")
                .replace("```", "")
                .replaceAll("\\s+", "")
                .strip();
        return candidate.contains("![") && candidate.contains("](") && withoutImages.isBlank();
    }

    private boolean isDashScopeGenerationEndpoint() {
        return completionsPath.contains("/services/aigc/multimodal-generation/generation");
    }

    private String nativeCompletionsPath() {
        return "/services/aigc/multimodal-generation/generation";
    }

    private List<DocumentParser.TextBlock> mergeTableFallback(List<DocumentParser.TextBlock> documentBlocks,
                                                               List<DocumentParser.TextBlock> tableBlocks) {
        var tableText = normalizeForOverlap(tableBlocks.stream().map(DocumentParser.TextBlock::content)
                .reduce((left, right) -> left + right).orElse(""));
        var spreadsheetAxesRemoved = tableBlocks.stream()
                .anyMatch(block -> Boolean.TRUE.equals(block.attributes().get("spreadsheetAxesRemoved")));
        var cleanedDocumentBlocks = spreadsheetAxesRemoved ? removeSpreadsheetAxisRuns(documentBlocks) : documentBlocks;
        var result = new ArrayList<DocumentParser.TextBlock>(tableBlocks);
        var seenTextBlocks = new LinkedHashSet<String>();
        cleanedDocumentBlocks.stream().filter(block -> !coveredByTable(block.content(), tableText, spreadsheetAxesRemoved))
                .filter(block -> seenTextBlocks.add(textBlockKey(block)))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private String textBlockKey(DocumentParser.TextBlock block) {
        return (block.pageNo() == null ? "" : block.pageNo()) + "|"
                + (block.section() == null ? "" : block.section()) + "|"
                + normalizeForOverlap(block.content());
    }

    private List<DocumentParser.TextBlock> removeSpreadsheetAxisRuns(List<DocumentParser.TextBlock> blocks) {
        var hasColumns = blocks.stream().anyMatch(block -> converter.isSpreadsheetColumnSequence(block.content()));
        var hasRows = blocks.stream().anyMatch(block -> converter.isSpreadsheetRowSequence(block.content()));
        if (!hasColumns || !hasRows) return blocks;
        var result = new ArrayList<DocumentParser.TextBlock>();
        for (var block : blocks) {
            var cleaned = converter.removeSpreadsheetAxisRuns(block.content());
            if (cleaned.isBlank()) continue;
            if (cleaned.equals(block.content())) {
                result.add(block);
            } else {
                result.add(new DocumentParser.TextBlock(block.pageNo(), block.section(), cleaned, block.sheetName(),
                        block.cellRange(), block.paragraphId(), block.bbox(), block.startTimeMs(), block.endTimeMs(),
                        block.confidence(), block.attributes()));
            }
        }
        return List.copyOf(result);
    }

    private boolean coveredByTable(String source, String tableText, boolean spreadsheetAxesRemoved) {
        var value = normalizeForOverlap(source);
        if (value.isBlank()) return true;
        if (tableText.contains(value) || value.contains(tableText)) return true;
        if (spreadsheetAxesRemoved && (value.matches("[a-z]{1,3}") || value.matches("\\d{1,7}"))) return true;
        if (value.codePointCount(0, value.length()) < 8) return false;
        var sourceCharacters = value.codePoints().collect(LinkedHashSet<Integer>::new, Set::add, Set::addAll);
        var tableCharacters = tableText.codePoints().collect(LinkedHashSet<Integer>::new, Set::add, Set::addAll);
        if (sourceCharacters.isEmpty()) return true;
        var covered = sourceCharacters.stream().filter(tableCharacters::contains).count();
        return covered / (double) sourceCharacters.size() >= 0.85;
    }

    private String normalizeForOverlap(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]", "");
    }

    private JsonNode callWithRetry(Object request) {
        return callWithRetry(request, completionsPath);
    }

    private JsonNode callWithRetry(Object request, String path) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return client.post().uri(baseUrlFor(path) + path)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .body(request).retrieve().body(JsonNode.class);
            } catch (RestClientResponseException exception) {
                last = exception;
                var status = exception.getStatusCode().value();
                if (status != 429 && status < 500 || attempt == 2) throw exception;
                backoff(attempt);
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt == 2) throw exception;
                backoff(attempt);
            }
        }
        throw last == null ? new IllegalStateException("OCR调用失败") : last;
    }

    private String baseUrlFor(String path) {
        if (!path.contains("/services/aigc/multimodal-generation/generation")) return baseUrl;
        return baseUrl.replaceFirst("/compatible-mode/v1$", "/api/v1");
    }

    private void backoff(int attempt) {
        try { Thread.sleep(250L * (1L << attempt)); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("OCR重试被中断", exception); }
    }

    private String content(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            var result = new StringBuilder();
            node.forEach(item -> {
                var text = content(item);
                if (!text.isBlank()) result.append(text).append('\n');
            });
            return result.toString().strip();
        }
        // Native DashScope document_parsing returns the human-readable OCR
        // text under ocr_result.layouts[].text. markdownContent is only a
        // rendered page preview (usually a temporary Markdown image URL).
        var ocrResult = node.path("ocr_result");
        if (!ocrResult.isMissingNode() && !ocrResult.isNull()) {
            var extracted = content(ocrResult);
            if (StringUtils.hasText(extracted)) return extracted;
        }
        var layouts = node.path("layouts");
        if (layouts.isArray()) {
            var result = new StringBuilder();
            layouts.forEach(layout -> {
                var text = layout.path("text").asText("");
                if (!StringUtils.hasText(text)) text = layout.path("markdownContent").asText("");
                if (StringUtils.hasText(text)) result.append(text).append('\n');
            });
            if (StringUtils.hasText(result)) return result.toString().strip();
        }
        for (var key : List.of("text", "content", "result", "markdownContent")) {
            var value = node.path(key);
            if (value.isTextual() && StringUtils.hasText(value.asText())) return value.asText();
            var extracted = content(value);
            if (StringUtils.hasText(extracted)) return extracted;
        }
        return "";
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
