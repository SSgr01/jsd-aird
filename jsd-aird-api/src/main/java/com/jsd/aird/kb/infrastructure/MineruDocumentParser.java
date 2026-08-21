package com.jsd.aird.kb.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** MinerU precise PDF parser. The worker owns the asynchronous ingestion boundary. */
@Component
public final class MineruDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MineruDocumentParser.class);

    private final boolean enabled;
    private final String baseUrl;
    private final String token;
    private final String model;
    private final Duration pollInterval;
    private final Duration maxWait;
    private final boolean agentFallbackEnabled;
    private final RestClient client;
    private final HttpClient signedUploadClient;
    private final ObjectMapper mapper;
    private final FileStorageFacade storage;
    private final MineruDocumentAdapter adapter;

    public MineruDocumentParser(
            @Value("${app.ai.mineru.enabled:true}") boolean enabled,
            @Value("${app.ai.mineru.base-url:https://mineru.net}") String baseUrl,
            @Value("${app.ai.mineru.token:}") String token,
            @Value("${app.ai.mineru.model:vlm}") String model,
            @Value("${app.ai.mineru.poll-interval:3s}") Duration pollInterval,
            @Value("${app.ai.mineru.max-wait:15m}") Duration maxWait,
            @Value("${app.ai.mineru.http-timeout:60s}") Duration httpTimeout,
            @Value("${app.ai.mineru.agent-fallback-enabled:true}") boolean agentFallbackEnabled,
            ObjectMapper mapper,
            FileStorageFacade storage
    ) {
        this.enabled = enabled;
        this.baseUrl = strip(baseUrl);
        this.token = token == null ? "" : token.strip();
        this.model = StringUtils.hasText(model) ? model.strip() : "vlm";
        this.pollInterval = pollInterval == null ? Duration.ofSeconds(3) : pollInterval;
        this.maxWait = maxWait == null ? Duration.ofMinutes(15) : maxWait;
        this.agentFallbackEnabled = agentFallbackEnabled;
        this.mapper = mapper;
        this.storage = storage;
        this.adapter = new MineruDocumentAdapter(mapper);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(httpTimeout);
        factory.setReadTimeout(httpTimeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.signedUploadClient = HttpClient.newBuilder().connectTimeout(httpTimeout).build();
    }

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || "application/pdf".equals(type);
    }

    @Override
    public boolean isConfigured() {
        return enabled && (StringUtils.hasText(token) || agentFallbackEnabled);
    }

    @Override
    public String unavailableReason() {
        return "MinerU 未配置精准接口 Token，且未启用轻量降级解析";
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        return parse(source, fileName, null);
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName, ParseContext context) {
        if (!isConfigured()) throw new IllegalStateException(unavailableReason());
        final byte[] bytes;
        try {
            bytes = source.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("PDF 文件读取失败", exception);
        }
        Exception preciseFailure = null;
        if (StringUtils.hasText(token)) {
            try {
                return parsePrecise(bytes, fileName, context);
            } catch (Exception exception) {
                preciseFailure = exception;
                log.warn("MinerU 精准解析失败，将尝试轻量降级：fileName={} reason={}", fileName, safeMessage(exception));
            }
        }
        if (agentFallbackEnabled) {
            try {
                return parseAgent(bytes, fileName, context);
            } catch (Exception fallbackFailure) {
                if (preciseFailure != null) fallbackFailure.addSuppressed(preciseFailure);
                throw new IllegalStateException("MinerU 解析失败：精准接口和轻量降级均失败", fallbackFailure);
            }
        }
        throw new IllegalStateException("MinerU 精准解析失败", preciseFailure);
    }

    private ParsedDocument parsePrecise(byte[] bytes, String fileName, ParseContext context) {
        var request = new LinkedHashMap<String, Object>();
        request.put("files", List.of(Map.of("name", fileName == null ? "document.pdf" : fileName,
                "data_id", "jsd-aird-" + UUID.randomUUID())));
        request.put("model_version", model);
        request.put("language", "ch");
        request.put("enable_formula", true);
        request.put("enable_table", true);
        request.put("is_ocr", false);
        var submitted = post("/api/v4/file-urls/batch", request, true);
        ensureOk(submitted, "MinerU 精准接口提交失败");
        var data = submitted.path("data");
        var batchId = data.path("batch_id").asText(null);
        var uploadUrl = data.path("file_urls").isArray() && data.path("file_urls").size() > 0
                ? data.path("file_urls").get(0).asText(null) : null;
        if (!StringUtils.hasText(batchId) || !StringUtils.hasText(uploadUrl)) {
            throw new IllegalStateException("MinerU 精准接口未返回上传地址");
        }
        upload(uploadUrl, bytes, fileName);
        var result = pollPrecise(batchId);
        var zipUrl = result.path("full_zip_url").asText(null);
        if (!StringUtils.hasText(zipUrl)) throw new IllegalStateException("MinerU 未返回结果 ZIP 地址");
        var zip = download(zipUrl);
        var adapted = adapter.parsePrecise(zip, fileName);
        var metadata = new LinkedHashMap<>(adapted.metadata());
        metadata.put("taskId", batchId);
        metadata.put("modelVersion", model);
        metadata.put("mode", "PRECISION");
        var resultFileId = persistResult(context, fileName, ".mineru.zip", "application/zip", "KB_MINERU_RESULT", zip);
        if (resultFileId != null) metadata.put("resultFileId", resultFileId.toString());
        return new ParsedDocument(adapted.blocks(), "mineru-precision-v1", batchId, metadata, List.of());
    }

    private ParsedDocument parseAgent(byte[] bytes, String fileName, ParseContext context) {
        var request = new LinkedHashMap<String, Object>();
        request.put("file_name", fileName == null ? "document.pdf" : fileName);
        request.put("language", "ch");
        request.put("enable_table", true);
        request.put("enable_formula", true);
        request.put("is_ocr", false);
        var submitted = post("/api/v1/agent/parse/file", request, false);
        ensureOk(submitted, "MinerU 轻量解析提交失败");
        var data = submitted.path("data");
        var taskId = data.path("task_id").asText(null);
        var uploadUrl = data.path("file_url").asText(null);
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(uploadUrl)) {
            throw new IllegalStateException("MinerU 轻量解析未返回上传地址");
        }
        upload(uploadUrl, bytes, fileName);
        var result = pollAgent(taskId);
        var markdownUrl = result.path("markdown_url").asText(null);
        if (!StringUtils.hasText(markdownUrl)) throw new IllegalStateException("MinerU 轻量解析未返回 Markdown");
        var markdown = new String(download(markdownUrl), java.nio.charset.StandardCharsets.UTF_8);
        var adapted = adapter.parseAgent(markdown, fileName);
        var metadata = new LinkedHashMap<>(adapted.metadata());
        metadata.put("taskId", taskId);
        metadata.put("mode", "AGENT_FALLBACK");
        var resultFileId = persistResult(context, fileName, ".mineru.agent.md", "text/markdown", "KB_MINERU_AGENT_RESULT",
                markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (resultFileId != null) metadata.put("resultFileId", resultFileId.toString());
        return new ParsedDocument(adapted.blocks(), "mineru-agent-v1", taskId, metadata, List.of());
    }

    private UUID persistResult(ParseContext context, String fileName, String suffix, String contentType, String kind,
                               byte[] bytes) {
        if (context == null || context.organizationId() == null || context.actorId() == null) return null;
        var base = StringUtils.hasText(fileName) ? fileName : "document.pdf";
        var name = base + suffix;
        var staged = storage.stageDerived(context.organizationId(), context.actorId(), name, contentType, kind,
                new ByteArrayInputStream(bytes));
        storage.activate(staged.fileId());
        return staged.fileId();
    }

    private JsonNode pollPrecise(String batchId) {
        var deadline = System.nanoTime() + maxWait.toNanos();
        while (System.nanoTime() < deadline) {
            var response = get("/api/v4/extract-results/batch/" + batchId, true);
            ensureOk(response, "MinerU 精准结果查询失败");
            for (var item : response.path("data").path("extract_result")) {
                var state = item.path("state").asText("").toLowerCase(Locale.ROOT);
                if ("done".equals(state)) return item;
                if ("failed".equals(state) || "error".equals(state)) {
                    throw new IllegalStateException("MinerU 精准任务失败：" + item.path("err_msg").asText("未知错误"));
                }
            }
            pause();
        }
        throw new IllegalStateException("MinerU 精准任务等待超时");
    }

    private JsonNode pollAgent(String taskId) {
        var deadline = System.nanoTime() + maxWait.toNanos();
        while (System.nanoTime() < deadline) {
            var response = get("/api/v1/agent/parse/" + taskId, false);
            ensureOk(response, "MinerU 轻量结果查询失败");
            var state = response.path("data").path("state").asText("").toLowerCase(Locale.ROOT);
            if ("done".equals(state)) return response.path("data");
            if ("failed".equals(state) || "error".equals(state)) {
                throw new IllegalStateException("MinerU 轻量任务失败：" + response.path("data").path("err_msg").asText("未知错误"));
            }
            pause();
        }
        throw new IllegalStateException("MinerU 轻量任务等待超时");
    }

    private void pause() {
        try { Thread.sleep(Math.max(100L, pollInterval.toMillis())); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MinerU 任务等待被中断", exception);
        }
    }

    private void upload(String url, byte[] bytes, String fileName) {
        try {
            // The signed object-store URL is signed with an empty Content-Type. Spring's
            // byte[] converter adds application/octet-stream automatically, which changes
            // the OSS canonical string and yields SignatureDoesNotMatch. Use JDK HttpClient
            // so the PUT contains only the signed URL and raw bytes.
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            var response = signedUploadClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MinerU 文件上传失败：HTTP " + response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MinerU 文件上传失败", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("MinerU 文件上传失败", exception);
        }
    }

    private byte[] download(String url) {
        try {
            return client.get().uri(url).retrieve().body(byte[].class);
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("MinerU 结果下载失败：HTTP " + exception.getStatusCode().value(), exception);
        }
    }

    private JsonNode post(String path, Object body, boolean precise) {
        var request = client.post().uri(url(path))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        if (precise) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request.body(writeJson(body)).retrieve().body(JsonNode.class);
    }

    private JsonNode get(String path, boolean precise) {
        var request = client.get().uri(url(path));
        if (precise) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request.retrieve().body(JsonNode.class);
    }

    private void ensureOk(JsonNode response, String message) {
        if (response == null || response.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(message + "：" + (response == null ? "空响应" : response.path("msg").asText("未知错误")));
        }
    }

    private String writeJson(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException("MinerU 请求参数序列化失败", exception);
        }
    }

    private String url(String path) {
        return baseUrl + "/" + path.replaceFirst("^/", "");
    }

    private String strip(String value) {
        if (!StringUtils.hasText(value)) return "https://mineru.net";
        return value.strip().replaceAll("/+$", "");
    }

    private String safeMessage(Exception exception) {
        var message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.replaceAll("(?i)bearer\\s+\\S+", "Bearer [redacted]");
    }
}
