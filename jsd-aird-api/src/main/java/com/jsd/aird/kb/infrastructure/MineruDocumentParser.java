package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.SystemParsingPolicy;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.OcrMode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** MinerU precise PDF parser. The worker owns the asynchronous ingestion boundary. */
@Component
public final class MineruDocumentParser implements DocumentParser {

    static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    private static final int MAX_REQUEST_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(MineruDocumentParser.class);

    private final boolean enabled;
    private final String baseUrl;
    private final String token;
    private final String model;
    private final Duration pollInterval;
    private final Duration maxWait;
    private final SystemParsingPolicy systemParsingPolicy;
    private final RestClient client;
    private final HttpClient transferClient;
    private final ObjectMapper mapper;
    private final FileStorageFacade storage;
    private final MineruDocumentAdapter adapter;
    private final PdfOcrDecider ocrDecider = new PdfOcrDecider();

    public MineruDocumentParser(
            @Value("${app.ai.mineru.enabled:true}") boolean enabled,
            @Value("${app.ai.mineru.base-url:https://mineru.net}") String baseUrl,
            @Value("${app.ai.mineru.token:}") String token,
            @Value("${app.ai.mineru.model:vlm}") String model,
            @Value("${app.ai.mineru.poll-interval:3s}") Duration pollInterval,
            @Value("${app.ai.mineru.max-wait:15m}") Duration maxWait,
            @Value("${app.ai.mineru.http-timeout:60s}") Duration httpTimeout,
            SystemParsingPolicy systemParsingPolicy,
            ObjectMapper mapper,
            FileStorageFacade storage
    ) {
        this.enabled = enabled;
        this.baseUrl = strip(baseUrl);
        this.token = token == null ? "" : token.strip();
        this.model = StringUtils.hasText(model) ? model.strip() : "vlm";
        this.pollInterval = pollInterval == null ? Duration.ofSeconds(3) : pollInterval;
        this.maxWait = maxWait == null ? Duration.ofMinutes(15) : maxWait;
        this.systemParsingPolicy = systemParsingPolicy;
        this.mapper = mapper;
        this.storage = storage;
        this.adapter = new MineruDocumentAdapter(mapper);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(httpTimeout);
        factory.setReadTimeout(httpTimeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.transferClient = HttpClient.newBuilder().connectTimeout(httpTimeout).build();
    }

    @PostConstruct
    void logConfiguration() {
        log.info("MinerU configuration: enabled={}, preciseConfigured={}, fallbackCapability={}, defaultOcrMode={}",
                enabled, StringUtils.hasText(token), systemParsingPolicy.agentFallbackEnabled(),
                systemParsingPolicy.defaultOcrMode());
    }

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || "application/pdf".equals(type);
    }

    @Override
    public boolean isConfigured() {
        return enabled && (StringUtils.hasText(token) || systemParsingPolicy.agentFallbackEnabled());
    }

    @Override
    public String unavailableReason() {
        return "MinerU 未配置精准接口 Token，且未启用 Agent 降级能力";
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        return parse(source, fileName, null);
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName, ParseContext context) {
        if (!enabled) throw MineruException.contract("MinerU 解析已禁用", null);
        var policy = context == null ? OcrMode.AUTO : context.ocrMode();
        var allowFallback = context != null && context.allowAgentFallback();
        Path sourceFile = null;
        try {
            sourceFile = Files.createTempFile("mineru-source-", ".pdf");
            Files.copy(source, sourceFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var decision = ocrDecider.decide(sourceFile, policy);
            MineruException preciseFailure = null;
            if (StringUtils.hasText(token)) {
                try {
                    return parsePrecise(sourceFile, fileName, context, policy, decision);
                } catch (MineruException exception) {
                    preciseFailure = exception;
                    if (!(allowFallback && systemParsingPolicy.agentFallbackEnabled()
                            && exception.fallbackEligible())) throw exception;
                    log.warn("MinerU precise provider unavailable; explicit Agent fallback enabled: fileName={} taskId={} reason={}",
                            fileName, exception.taskId(), safeMessage(exception));
                }
            } else if (!(allowFallback && systemParsingPolicy.agentFallbackEnabled())) {
                throw MineruException.contract("MinerU 精准接口 Token 未配置，当前版本也未允许 Agent 降级", null);
            }
            if (allowFallback && systemParsingPolicy.agentFallbackEnabled()) {
                try {
                    return parseAgent(sourceFile, fileName, context, policy, decision, preciseFailure);
                } catch (MineruException fallbackFailure) {
                    if (preciseFailure != null) fallbackFailure.addSuppressed(preciseFailure);
                    throw fallbackFailure;
                }
            }
            throw preciseFailure == null ? MineruException.contract("MinerU 精准解析不可用", null) : preciseFailure;
        } catch (IOException exception) {
            throw new MineruException("PDF 临时文件读取失败", null, "FILE_IO", null,
                    false, false, exception);
        } finally {
            deleteQuietly(sourceFile);
        }
    }

    private ParsedDocument parsePrecise(Path sourceFile, String fileName, ParseContext context,
                                        OcrMode requestedMode, PdfOcrDecider.Decision decision) {
        var request = new LinkedHashMap<String, Object>();
        request.put("files", List.of(Map.of(
                "name", safeFileName(fileName),
                "data_id", "jsd-aird-" + UUID.randomUUID(),
                "is_ocr", decision.useOcr())));
        request.put("model_version", model);
        request.put("language", "ch");
        request.put("enable_formula", true);
        request.put("enable_table", true);
        var submitted = withRetry(() -> {
            var response = post("/api/v4/file-urls/batch", request, true, null);
            ensureOk(response, "MinerU 精准接口提交失败", null);
            return response;
        });
        var data = submitted.path("data");
        var batchId = data.path("batch_id").asText(null);
        var uploadUrl = data.path("file_urls").isArray() && !data.path("file_urls").isEmpty()
                ? data.path("file_urls").get(0).asText(null) : null;
        if (!StringUtils.hasText(batchId) || !StringUtils.hasText(uploadUrl)) {
            throw MineruException.contract("MinerU 精准接口未返回上传地址", batchId);
        }
        withRetry(() -> { upload(uploadUrl, sourceFile, batchId); return null; });
        var result = pollPrecise(batchId);
        var zipUrl = result.path("full_zip_url").asText(null);
        if (!StringUtils.hasText(zipUrl)) throw MineruException.contract("MinerU 未返回结果 ZIP 地址", batchId);
        Path resultFile = null;
        try {
            resultFile = Files.createTempFile("mineru-result-", ".zip");
            var target = resultFile;
            withRetry(() -> { download(zipUrl, target, batchId); return null; });
            var resultFileId = persistResult(context, fileName, ".mineru.zip", "application/zip",
                    "KB_MINERU_RESULT", resultFile);
            var adapted = adapter.parsePrecise(resultFile, fileName, resultFileId, Map.of());
            var artifacts = persistZipArtifacts(context, fileName, resultFile);
            var blocks = attachAssetFileIds(adapted.blocks(), artifacts.assetFileIds());
            var metadata = baseMetadata(adapted.metadata(), requestedMode, decision);
            metadata.put("taskId", batchId);
            metadata.put("modelVersion", model);
            metadata.put("mode", "PRECISE");
            if (resultFileId != null) metadata.put("resultFileId", resultFileId.toString());
            if (artifacts.markdownFileId() != null) metadata.put("markdownFileId", artifacts.markdownFileId().toString());
            metadata.put("resultAssets", artifacts.assets());
            return new ParsedDocument(blocks, "mineru-precision-v2", batchId, metadata, List.of());
        } catch (IOException exception) {
            throw MineruException.adapter("MinerU 结果临时文件处理失败", exception);
        } finally {
            deleteQuietly(resultFile);
        }
    }

    private ParsedDocument parseAgent(Path sourceFile, String fileName, ParseContext context,
                                      OcrMode requestedMode, PdfOcrDecider.Decision decision,
                                      MineruException preciseFailure) {
        var request = new LinkedHashMap<String, Object>();
        request.put("file_name", safeFileName(fileName));
        request.put("language", "ch");
        request.put("enable_table", true);
        request.put("enable_formula", true);
        request.put("is_ocr", decision.useOcr());
        var submitted = withRetry(() -> {
            var response = post("/api/v1/agent/parse/file", request, false, null);
            ensureOk(response, "MinerU Agent 提交失败", null);
            return response;
        });
        var data = submitted.path("data");
        var taskId = data.path("task_id").asText(null);
        var uploadUrl = data.path("file_url").asText(null);
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(uploadUrl)) {
            throw MineruException.contract("MinerU Agent 未返回上传地址", taskId);
        }
        withRetry(() -> { upload(uploadUrl, sourceFile, taskId); return null; });
        var result = pollAgent(taskId);
        var markdownUrl = result.path("markdown_url").asText(null);
        if (!StringUtils.hasText(markdownUrl)) throw MineruException.contract("MinerU Agent 未返回 Markdown", taskId);
        Path resultFile = null;
        try {
            resultFile = Files.createTempFile("mineru-agent-result-", ".md");
            var target = resultFile;
            withRetry(() -> { download(markdownUrl, target, taskId); return null; });
            if (Files.size(resultFile) > MineruDocumentAdapter.MAX_ENTRY_BYTES) {
                throw MineruException.contract("MinerU Agent Markdown 超过 128 MiB", taskId);
            }
            var markdown = Files.readString(resultFile, StandardCharsets.UTF_8);
            var adapted = adapter.parseAgent(markdown, fileName);
            var resultFileId = persistResult(context, fileName, ".mineru.agent.md", "text/markdown",
                    "KB_MINERU_AGENT_RESULT", resultFile);
            var metadata = baseMetadata(adapted.metadata(), requestedMode, decision);
            metadata.put("taskId", taskId);
            metadata.put("mode", "AGENT_FALLBACK");
            metadata.put("degraded", true);
            metadata.put("reviewWarning", "该版本使用 MinerU Agent 降级解析，版面和坐标质量较低，请重点审核");
            if (preciseFailure != null) {
                metadata.put("fallbackReason", preciseFailure.getMessage());
                if (preciseFailure.taskId() != null) metadata.put("preciseTaskId", preciseFailure.taskId());
            }
            if (resultFileId != null) metadata.put("resultFileId", resultFileId.toString());
            return new ParsedDocument(adapted.blocks(), "mineru-agent-v2", taskId, metadata, List.of());
        } catch (IOException exception) {
            throw MineruException.adapter("MinerU Agent 结果临时文件处理失败", exception);
        } finally {
            deleteQuietly(resultFile);
        }
    }

    private LinkedHashMap<String, Object> baseMetadata(Map<String, Object> source, OcrMode requestedMode,
                                                        PdfOcrDecider.Decision decision) {
        var metadata = new LinkedHashMap<>(source);
        metadata.put("requestedOcrMode", requestedMode.name());
        metadata.put("effectiveOcr", decision.useOcr());
        metadata.put("ocrDecisionReason", decision.reason());
        metadata.put("ocrSampledPages", decision.sampledPages());
        metadata.put("ocrQualifiedPages", decision.qualifiedPages());
        metadata.put("ocrValidCharacters", decision.validCharacters());
        return metadata;
    }

    private UUID persistResult(ParseContext context, String fileName, String suffix, String contentType,
                               String kind, Path path) {
        if (context == null || context.organizationId() == null || context.actorId() == null) return null;
        try (var input = Files.newInputStream(path)) {
            var staged = storage.stageDerived(context.organizationId(), context.actorId(),
                    safeFileName(fileName) + suffix, contentType, kind, input);
            storage.activate(staged.fileId());
            return staged.fileId();
        } catch (IOException exception) {
            throw MineruException.adapter("MinerU 结果文件持久化失败", exception);
        }
    }

    private ZipArtifacts persistZipArtifacts(ParseContext context, String fileName, Path zipPath) {
        if (context == null || context.organizationId() == null || context.actorId() == null) {
            return new ZipArtifacts(Map.of(), null, List.of());
        }
        var assetIds = new HashMap<String, UUID>();
        var assets = new ArrayList<Map<String, Object>>();
        var seenPaths = new HashSet<String>();
        var totalBytes = 0L;
        var entryCount = 0;
        UUID markdownFileId = null;
        try (var archive = new ZipFile(zipPath.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (++entryCount > MineruDocumentAdapter.MAX_ENTRIES) {
                    throw MineruException.contract("MinerU 结果 entry 数量超过 4096", null);
                }
                var path = normalizeEntryPath(entry.getName());
                if (path.isBlank() || path.contains("../") || path.startsWith("/")) {
                    throw MineruException.contract("MinerU 结果包含非法资产路径", null);
                }
                if (!seenPaths.add(path)) throw MineruException.contract("MinerU 结果包含重复 entry", null);
                var size = Math.max(0L, entry.getSize());
                totalBytes += size;
                if (totalBytes > MineruDocumentAdapter.MAX_TOTAL_BYTES) {
                    throw MineruException.contract("MinerU 结果解压总大小超过 1 GiB", null);
                }
                var compressed = entry.getCompressedSize();
                if (compressed > 0 && size / (double) compressed > MineruDocumentAdapter.MAX_COMPRESSION_RATIO) {
                    throw MineruException.contract("MinerU 结果压缩比超过安全限制", null);
                }
                var lower = path.toLowerCase(Locale.ROOT);
                if (markdownFileId == null && (lower.equals("full.md") || lower.endsWith("/full.md"))) {
                    try (var input = archive.getInputStream(entry)) {
                        markdownFileId = persistDerivedStream(context, safeFileName(fileName) + ".mineru.full.md",
                                "text/markdown", "KB_MINERU_MARKDOWN_RESULT", input).fileId();
                    }
                    continue;
                }
                var contentType = imageContentType(lower);
                if (contentType == null) continue;
                if (size > MineruDocumentAdapter.MAX_ENTRY_BYTES) {
                    throw MineruException.contract("MinerU 图片 entry 超过 128 MiB", null);
                }
                try (var input = archive.getInputStream(entry)) {
                    var staged = persistDerivedStream(context,
                            safeFileName(fileName) + ".mineru." + basename(path), contentType,
                            "KB_MINERU_RESULT_ASSET", input);
                    var assetId = staged.fileId();
                    assetIds.put(path, assetId);
                    assets.add(Map.of("assetFileId", assetId.toString(), "entryPath", path,
                            "contentType", contentType, "size", staged.size()));
                }
            }
            return new ZipArtifacts(Map.copyOf(assetIds), markdownFileId, List.copyOf(assets));
        } catch (IOException exception) {
            throw MineruException.adapter("MinerU 结果图片/Markdown 资产持久化失败", exception);
        }
    }

    private List<DocumentParser.TextBlock> attachAssetFileIds(List<DocumentParser.TextBlock> blocks,
                                                               Map<String, UUID> assetFileIds) {
        if (assetFileIds == null || assetFileIds.isEmpty()) return blocks == null ? List.of() : List.copyOf(blocks);
        var result = new ArrayList<DocumentParser.TextBlock>();
        for (var block : blocks == null ? List.<DocumentParser.TextBlock>of() : blocks) {
            var path = block.attributes().get("resultEntryPath");
            var assetId = path == null ? null : assetFileIds.get(normalizeEntryPath(String.valueOf(path)));
            if (assetId == null) {
                result.add(block);
                continue;
            }
            var attributes = new LinkedHashMap<>(block.attributes());
            attributes.put("assetFileId", assetId.toString());
            result.add(new DocumentParser.TextBlock(block.pageNo(), block.section(), block.content(), block.sheetName(),
                    block.cellRange(), block.paragraphId(), block.bbox(), block.startTimeMs(), block.endTimeMs(),
                    block.confidence(), attributes));
        }
        return List.copyOf(result);
    }

    private FileStorageFacade.StagedFile persistDerivedStream(ParseContext context, String name, String contentType,
                                                              String kind, InputStream input) {
        var staged = storage.stageDerived(context.organizationId(), context.actorId(), name, contentType, kind, input);
        storage.activate(staged.fileId());
        return staged;
    }

    private String normalizeEntryPath(String value) {
        var normalized = value == null ? "" : value.replace('\\', '/').strip();
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    private String basename(String path) {
        var index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    private String imageContentType(String path) {
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".bmp")) return "image/bmp";
        return null;
    }

    private record ZipArtifacts(Map<String, UUID> assetFileIds, UUID markdownFileId,
                                List<Map<String, Object>> assets) { }

    private JsonNode pollPrecise(String batchId) {
        var deadline = System.nanoTime() + maxWait.toNanos();
        while (System.nanoTime() < deadline) {
            var response = withRetry(() -> {
                var value = get("/api/v4/extract-results/batch/" + batchId, true, batchId);
                ensureOk(value, "MinerU 精准结果查询失败", batchId);
                return value;
            });
            for (var item : response.path("data").path("extract_result")) {
                var state = item.path("state").asText("").toLowerCase(Locale.ROOT);
                if ("done".equals(state)) return item;
                if ("failed".equals(state) || "error".equals(state)) {
                    throw taskFailure("MinerU 精准任务失败：" + item.path("err_msg").asText("未知错误"), batchId);
                }
            }
            pause();
        }
        throw new MineruException("MinerU 精准任务等待超时", null, "TIMEOUT", batchId, true, true, null);
    }

    private JsonNode pollAgent(String taskId) {
        var deadline = System.nanoTime() + maxWait.toNanos();
        while (System.nanoTime() < deadline) {
            var response = withRetry(() -> {
                var value = get("/api/v1/agent/parse/" + taskId, false, taskId);
                ensureOk(value, "MinerU Agent 结果查询失败", taskId);
                return value;
            });
            var state = response.path("data").path("state").asText("").toLowerCase(Locale.ROOT);
            if ("done".equals(state)) return response.path("data");
            if ("failed".equals(state) || "error".equals(state)) {
                throw taskFailure("MinerU Agent 任务失败：" + response.path("data").path("err_msg").asText("未知错误"), taskId);
            }
            pause();
        }
        throw new MineruException("MinerU Agent 任务等待超时", null, "TIMEOUT", taskId, true, false, null);
    }

    private MineruException taskFailure(String message, String taskId) {
        var badInput = message.matches("(?is).*(auth|token|参数|格式|文件损坏|password|encrypted|unsupported).*?");
        return new MineruException(message, null, "TASK_FAILED", taskId, false, !badInput, null);
    }

    private void pause() {
        try { Thread.sleep(Math.max(100L, pollInterval.toMillis())); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MineruException("MinerU 任务等待被中断", null, "INTERRUPTED", null,
                    false, false, exception);
        }
    }

    private void upload(String url, Path path, String taskId) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                    .PUT(HttpRequest.BodyPublishers.ofFile(path)).build();
            var response = transferClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw httpFailure(
                    "MinerU 文件上传失败", response.statusCode(), taskId, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MineruException("MinerU 文件上传被中断", null, "INTERRUPTED", taskId,
                    false, false, exception);
        } catch (IOException exception) {
            throw new MineruException("MinerU 文件上传网络故障", null, "NETWORK", taskId,
                    true, true, exception);
        }
    }

    private void download(String url, Path target, String taskId) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
            var response = transferClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw httpFailure("MinerU 结果下载失败", response.statusCode(), taskId, null);
            }
            try (var input = response.body();
                 var output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = new byte[64 * 1024];
                long total = 0;
                for (int read; (read = input.read(buffer)) >= 0;) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw MineruException.contract("MinerU 结果下载超过 512 MiB", taskId);
                    }
                    output.write(buffer, 0, read);
                }
            }
        } catch (MineruException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MineruException("MinerU 结果下载被中断", null, "INTERRUPTED", taskId,
                    false, false, exception);
        } catch (IOException exception) {
            throw new MineruException("MinerU 结果下载网络故障", null, "NETWORK", taskId,
                    true, true, exception);
        }
    }

    private JsonNode post(String path, Object body, boolean precise, String taskId) {
        try {
            var request = client.post().uri(url(path)).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            if (precise) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request.body(writeJson(body)).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw httpFailure("MinerU API 请求失败", exception.getStatusCode().value(), taskId, exception);
        } catch (RestClientException exception) {
            throw new MineruException("MinerU API 网络故障", null, "NETWORK", taskId, true, true, exception);
        }
    }

    private JsonNode get(String path, boolean precise, String taskId) {
        try {
            var request = client.get().uri(url(path));
            if (precise) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request.retrieve().body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw httpFailure("MinerU API 请求失败", exception.getStatusCode().value(), taskId, exception);
        } catch (RestClientException exception) {
            throw new MineruException("MinerU API 网络故障", null, "NETWORK", taskId, true, true, exception);
        }
    }

    private MineruException httpFailure(String message, int status, String taskId, Throwable cause) {
        var transientFailure = status == 429 || status >= 500;
        return new MineruException(message + "：HTTP " + status, status, "HTTP_" + status, taskId,
                transientFailure, transientFailure, cause);
    }

    private void ensureOk(JsonNode response, String message, String taskId) {
        if (response != null && response.path("code").asInt(-1) == 0) return;
        var code = response == null ? "EMPTY_RESPONSE" : response.path("code").asText("UNKNOWN");
        var detail = response == null ? "空响应" : response.path("msg").asText("未知错误");
        var transientFailure = "429".equals(code) || code.startsWith("5");
        var badInput = detail.matches("(?is).*(auth|token|参数|文件|格式|permission|unauthorized).*?");
        throw new MineruException(message + "：" + detail, null, code, taskId,
                transientFailure, transientFailure && !badInput, null);
    }

    private <T> T withRetry(ProviderCall<T> call) {
        MineruException last = null;
        for (var attempt = 1; attempt <= MAX_REQUEST_ATTEMPTS; attempt++) {
            try {
                return call.call();
            } catch (MineruException exception) {
                last = exception;
                if (!exception.retryable() || attempt == MAX_REQUEST_ATTEMPTS) throw exception;
                pause();
            }
        }
        throw last;
    }

    private String writeJson(Object body) {
        try { return mapper.writeValueAsString(body); }
        catch (Exception exception) { throw MineruException.contract("MinerU 请求参数序列化失败", null); }
    }

    private String url(String path) { return baseUrl + "/" + path.replaceFirst("^/", ""); }

    private String strip(String value) {
        if (!StringUtils.hasText(value)) return "https://mineru.net";
        return value.strip().replaceAll("/+$", "");
    }

    private String safeFileName(String value) { return StringUtils.hasText(value) ? value : "document.pdf"; }

    private void deleteQuietly(Path value) {
        if (value != null) try { Files.deleteIfExists(value); } catch (IOException ignored) { }
    }

    private String safeMessage(Exception exception) {
        var message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName()
                : message.replaceAll("(?i)bearer\\s+\\S+", "Bearer [redacted]");
    }

    @FunctionalInterface
    private interface ProviderCall<T> { T call(); }
}
