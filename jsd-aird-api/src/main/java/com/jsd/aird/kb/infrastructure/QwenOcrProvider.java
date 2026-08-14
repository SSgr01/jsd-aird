package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

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
        var pdf = "application/pdf".equals(type) || name.endsWith(".pdf");
        return type.startsWith("image/") || name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".webp")
                || name.endsWith(".bmp") || name.endsWith(".tif") || name.endsWith(".tiff") || pdf;
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
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) return true;
        try (var document = Loader.loadPDF(source.readAllBytes())) {
            var stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                if (!hasSufficientNativeText(document, page, stripper.getText(document))) return true;
            }
            return false;
        } catch (Exception exception) {
            return true;
        }
    }

    @Override
    public DocumentParser.ParsedDocument extract(InputStream source, String fileName, ExtractionContext context) {
        if (!isConfigured()) throw new IllegalStateException("OCR 服务尚未配置");
        try {
            var bytes = source.readAllBytes();
            if (isPdf(fileName, context)) return extractPdf(bytes);
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

    private DocumentParser.ParsedDocument extractPdf(byte[] bytes) {
        var blocks = new ArrayList<DocumentParser.TextBlock>();
        var ocrPages = new ArrayList<Integer>();
        try (var document = Loader.loadPDF(bytes)) {
            var stripper = new PDFTextStripper();
            var renderer = new PDFRenderer(document);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                var nativeText = stripper.getText(document).strip();
                if (hasSufficientNativeText(document, page, nativeText)) {
                    blocks.add(new DocumentParser.TextBlock(page, "PDF-NATIVE", nativeText));
                    continue;
                }
                var image = renderer.renderImageWithDPI(page - 1, 200, ImageType.RGB);
                var output = new java.io.ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                blocks.addAll(recognize(output.toByteArray(), "image/png", page));
                ocrPages.add(page);
            }
            return new DocumentParser.ParsedDocument(blocks, model + "+pdfbox-3", null,
                    Map.of("model", model, "task", "document_parsing", "pageCount", document.getNumberOfPages(),
                            "ocrPages", ocrPages, "mode", ocrPages.size() == document.getNumberOfPages() ? "SCANNED" : "MIXED"));
        } catch (IOException exception) {
            throw new IllegalStateException("PDF OCR解析失败", exception);
        }
    }

    private List<DocumentParser.TextBlock> recognize(byte[] bytes, String contentType, Integer pageNo) {
        if (bytes.length > maxBytes) throw new IllegalStateException("OCR图片超过限制：" + maxBytes + " bytes");
        var dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        var request = new LinkedHashMap<String, Object>();
        request.put("model", model);
        request.put("stream", false);
        request.put("ocr_options", Map.of("task", "document_parsing"));
        request.put("messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))))));
        var response = callWithRetry(request);
        var text = content(response == null ? null : response.at("/choices/0/message/content"));
        if (!StringUtils.hasText(text)) text = content(response == null ? null : response.at("/output/choices/0/message/content"));
        if (!StringUtils.hasText(text)) throw new IllegalStateException("OCR返回内容为空");
        var blocks = new ArrayList<>(converter.convert(text, pageNo));
        if (blocks.isEmpty()) blocks.add(new DocumentParser.TextBlock(pageNo, "paragraph", text.strip()));
        return blocks;
    }

    private JsonNode callWithRetry(Object request) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return client.post().uri(baseUrl + completionsPath)
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

    private boolean hasSufficientNativeText(org.apache.pdfbox.pdmodel.PDDocument document,
                                            int pageNo, String text) {
        var characterCount = text == null ? 0 : text.replaceAll("\\s+", "").length();
        if (characterCount < 24) return false;
        var box = document.getPage(pageNo - 1).getMediaBox();
        var squareInches = Math.max(1.0, (box.getWidth() / 72.0) * (box.getHeight() / 72.0));
        return characterCount / squareInches >= 0.4;
    }

    private void backoff(int attempt) {
        try { Thread.sleep(250L * (1L << attempt)); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("OCR重试被中断", exception); }
    }

    private boolean isPdf(String fileName, ExtractionContext context) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                || context != null && "application/pdf".equalsIgnoreCase(context.contentType());
    }

    private String content(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            var result = new StringBuilder();
            node.forEach(item -> {
                var text = item.path("ocr_result").asText(item.path("text").asText(""));
                if (!text.isBlank()) result.append(text).append('\n');
            });
            return result.toString().strip();
        }
        return node.path("ocr_result").asText(node.path("text").asText(""));
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
