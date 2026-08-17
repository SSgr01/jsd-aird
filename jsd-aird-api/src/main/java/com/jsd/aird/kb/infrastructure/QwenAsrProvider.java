package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import com.jsd.aird.kb.domain.MediaExtractionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QwenAsrProvider implements MediaExtractionProvider {

    private static final Logger log = LoggerFactory.getLogger(QwenAsrProvider.class);

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String transcriptionPath;
    private final String taskPath;
    private final String language;
    private final boolean enableItn;
    private final boolean enableWords;
    private final Duration timeout;
    private final Duration pollInterval;
    private final RestClient client;

    public QwenAsrProvider(
            @Value("${app.ai.asr.enabled:false}") boolean enabled,
            @Value("${app.ai.asr.base-url:https://dashscope.aliyuncs.com/api/v1}") String baseUrl,
            @Value("${app.ai.asr.api-key:}") String apiKey,
            @Value("${app.ai.asr.model:qwen3-asr-flash-filetrans}") String model,
            @Value("${app.ai.asr.transcription-path:/services/audio/asr/transcription}") String transcriptionPath,
            @Value("${app.ai.asr.task-path:/tasks}") String taskPath,
            @Value("${app.ai.asr.language:zh}") String language,
            @Value("${app.ai.asr.enable-itn:true}") boolean enableItn,
            @Value("${app.ai.asr.enable-words:true}") boolean enableWords,
            @Value("${app.ai.asr.timeout:10m}") Duration timeout,
            @Value("${app.ai.asr.poll-interval:2s}") Duration pollInterval
    ) {
        this.enabled = enabled;
        this.baseUrl = strip(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.transcriptionPath = normalizePath(transcriptionPath, "/services/audio/asr/transcription");
        this.taskPath = normalizePath(taskPath, "/tasks");
        this.language = language == null ? "" : language.strip();
        this.enableItn = enableItn;
        this.enableWords = enableWords;
        this.timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        this.pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setReadTimeout(this.timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        log.debug("Qwen ASR config enabled={}, baseUrlPresent={}, apiKeyPresent={}, modelPresent={}",
                enabled, StringUtils.hasText(this.baseUrl), StringUtils.hasText(this.apiKey),
                StringUtils.hasText(this.model));
    }

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("audio/") || name.endsWith(".wav") || name.endsWith(".mp3")
                || name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".flac")
                || name.endsWith(".ogg") || name.endsWith(".opus");
    }

    @Override
    public boolean isConfigured() {
        // DashScope accepts a short-lived oss:// URL obtained from its upload
        // policy endpoint, so local object storage can still be used for
        // development and end-to-end tests. A public storage endpoint remains
        // preferred when one is configured.
        return enabled && StringUtils.hasText(baseUrl)
                && StringUtils.hasText(apiKey) && StringUtils.hasText(model);
    }

    @Override
    public String unavailableReason() {
        return "ASR 服务暂不可用";
    }

    @Override
    public DocumentParser.ParsedDocument extract(InputStream source, String fileName, ExtractionContext context) {
        if (!isConfigured()) {
            throw new IllegalStateException("ASR 服务尚未配置");
        }
        var publicUrl = context == null ? null : context.publicUrl();
        String taskId = null;
        try {
            if (!StringUtils.hasText(publicUrl)) {
                publicUrl = uploadToTemporaryStorage(source.readAllBytes(), fileName);
            }
            taskId = submit(publicUrl);
            var result = poll(taskId);
            var blocks = transcriptBlocks(result);
            if (blocks.isEmpty()) throw new IllegalStateException("ASR 返回结果为空");
            return new DocumentParser.ParsedDocument(blocks, model, taskId,
                    Map.of("model", model, "timestampLevel", enableWords ? "SENTENCE_AND_WORD" : "SENTENCE"));
        } catch (IOException exception) {
            throw new MediaExtractionException("ASR 文件读取失败", taskId, model, exception);
        } catch (RestClientResponseException exception) {
            throw new MediaExtractionException("ASR 服务调用失败：HTTP " + exception.getStatusCode().value()
                    + " " + exception.getResponseBodyAsString(), taskId, model, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaExtractionException("ASR 轮询被中断", taskId, model, exception);
        } catch (RuntimeException exception) {
            if (exception instanceof MediaExtractionException media) throw media;
            throw new MediaExtractionException(exception.getMessage() == null ? "ASR任务失败" : exception.getMessage(),
                    taskId, model, exception);
        }
    }

    private String submit(String publicUrl) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("channel_id", List.of(0));
        if (StringUtils.hasText(language)) {
            parameters.put("language", language);
        }
        parameters.put("enable_itn", enableItn);
        parameters.put("enable_words", enableWords);
        var input = Map.of("file_url", publicUrl);
        var body = Map.of(
                "model", model,
                "input", input,
                "parameters", parameters
        );
        var response = withRetry(() -> {
            var request = client.post().uri(baseUrl + transcriptionPath)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header("X-DashScope-Async", "enable");
            if (publicUrl.startsWith("oss://")) {
                request.header("X-DashScope-OssResourceResolve", "enable");
            }
            return request.body(body).retrieve().body(JsonNode.class);
        });
        var taskId = firstText(response == null ? null : response.path("output"), "task_id", "taskId");
        if (!StringUtils.hasText(taskId)) throw new IllegalStateException("ASR 未返回 task_id");
        return taskId;
    }

    private String uploadToTemporaryStorage(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) throw new IllegalStateException("ASR 音频内容为空");
        var modelQuery = URLEncoder.encode(model, StandardCharsets.UTF_8);
        var policyResponse = withRetry(() -> client.get()
                .uri(baseUrl + "/uploads?action=getPolicy&model=" + modelQuery)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .retrieve()
                .body(JsonNode.class));
        var data = policyResponse == null ? null : policyResponse.path("data");
        var uploadHost = required(data, "upload_host", "uploadHost");
        var uploadDir = required(data, "upload_dir", "uploadDir");
        var key = uploadDir + "/" + safeFileName(fileName);
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("OSSAccessKeyId", required(data, "oss_access_key_id", "ossAccessKeyId"));
        form.add("policy", required(data, "policy"));
        form.add("Signature", required(data, "signature"));
        form.add("x-oss-object-acl", required(data, "x_oss_object_acl", "xOssObjectAcl"));
        form.add("x-oss-forbid-overwrite", required(data, "x_oss_forbid_overwrite", "xOssForbidOverwrite"));
        form.add("key", key);
        form.add("success_action_status", "200");
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return safeFileName(fileName);
            }
        });
        withRetry(() -> {
            client.post().uri(uploadHost).contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form).retrieve().toBodilessEntity();
            return Boolean.TRUE;
        });
        return "oss://" + key;
    }

    private String required(JsonNode node, String... names) {
        var value = firstText(node, names);
        if (!StringUtils.hasText(value)) throw new IllegalStateException("ASR 临时上传凭证不完整");
        return value;
    }

    private String safeFileName(String fileName) {
        var value = StringUtils.hasText(fileName) ? fileName : "audio.bin";
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return UUID.randomUUID() + "-" + value;
    }

    private JsonNode poll(String taskId) throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            var response = withRetry(() -> client.get().uri(baseUrl + taskPath + "/" + taskId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class));
            var output = response == null ? null : response.path("output");
            var status = firstText(output, "task_status", "taskStatus");
            if (!StringUtils.hasText(status)) status = response == null ? "" : response.path("status").asText("");
            if ("SUCCEEDED".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status)
                    || "COMPLETED".equalsIgnoreCase(status)) return response;
            if ("FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status)
                    || "CANCELLED".equalsIgnoreCase(status)) {
                var message = firstText(output, "message", "error_message", "errorMessage");
                throw new IllegalStateException("ASR 任务失败" + (StringUtils.hasText(message) ? "：" + message : ""));
            }
            if ("UNKNOWN".equalsIgnoreCase(status)) {
                throw new IllegalStateException("ASR 任务不存在或状态未知");
            }
            Thread.sleep(Math.max(250, pollInterval.toMillis()));
        }
        throw new IllegalStateException("ASR 任务超过轮询超时：" + timeout);
    }

    private List<DocumentParser.TextBlock> transcriptBlocks(JsonNode response) {
        var result = new ArrayList<DocumentParser.TextBlock>();
        var output = response == null ? null : response.path("output");
        collectTranscripts(output == null || output.isMissingNode() ? null : output.path("transcripts"), result);
        if (result.isEmpty()) collectTranscripts(response == null ? null : response.path("transcripts"), result);
        if (result.isEmpty()) collectTranscripts(output == null ? null : output.path("result"), result);
        if (result.isEmpty() && output != null && output.isObject()) {
            var resultItems = output.path("results");
            if (resultItems.isArray()) {
                resultItems.forEach(item -> downloadTranscript(item, result));
            }
        }
        if (result.isEmpty()) {
            downloadTranscript(output == null ? null : output.path("result"), result);
        }
        if (result.isEmpty()) {
            var text = firstText(output, "text", "result");
            if (StringUtils.hasText(text)) result.add(new DocumentParser.TextBlock(null, "ASR", text.strip()));
        }
        return result;
    }

    private void downloadTranscript(JsonNode resultNode, List<DocumentParser.TextBlock> result) {
        var transcriptionUrl = firstText(resultNode, "transcription_url", "transcriptionUrl");
        if (!StringUtils.hasText(transcriptionUrl)) return;
        // DashScope returns a pre-signed OSS URL. Passing it as a String lets
        // RestClient treat the percent-encoded query as a URI template and
        // encode it again, invalidating the OSS signature. URI preserves the
        // provider's raw query exactly.
        var transcriptFile = withRetry(() -> client.get().uri(java.net.URI.create(transcriptionUrl))
                .retrieve().body(JsonNode.class));
        collectTranscripts(transcriptFile, result);
    }

    private void collectTranscripts(JsonNode node, List<DocumentParser.TextBlock> result) {
        collectTranscripts(node, result, "0");
    }

    private void collectTranscripts(JsonNode node, List<DocumentParser.TextBlock> result, String inheritedChannel) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(item -> collectTranscripts(item, result, inheritedChannel));
            return;
        }
        if (!node.isObject()) return;
        var channel = node.hasNonNull("channel_id") ? node.path("channel_id").asText(inheritedChannel)
                : node.path("channelId").asText(inheritedChannel);
        var sentences = node.path("sentences");
        if (sentences.isArray() && !sentences.isEmpty()) {
            sentences.forEach(sentence -> collectTranscripts(sentence, result, channel));
            return;
        }
        var text = firstText(node, "text", "transcript", "sentence");
        if (StringUtils.hasText(text)) {
            var start = firstLong(node, "begin_time", "start_time", "startTime", "start_ms", "beginTime");
            var end = firstLong(node, "end_time", "endTime", "end_ms", "stop_time");
            result.add(new DocumentParser.TextBlock(null, "ASR-SENTENCE-CHANNEL-" + channel, text.strip(),
                    null, null, null, List.of(), start, end, confidence(node)));
            // Word timestamps are useful provider metadata, but exposing every
            // word as a separate review block duplicates each sentence and
            // makes the correction editor unreadable. The sentence block keeps
            // the stable audio anchor; word-level data remains available in the
            // provider response diagnostics when needed.
            return;
        }
        collectTranscripts(node.path("transcripts"), result, channel);
        collectTranscripts(node.path("results"), result, channel);
    }

    private String firstText(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        for (var name : names) {
            var value = node.path(name);
            if (value.isTextual() && StringUtils.hasText(value.asText())) return value.asText();
        }
        return "";
    }

    private Long firstLong(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        for (var name : names) {
            var value = node.path(name);
            if (value.isNumber()) return value.longValue();
            if (value.isTextual()) {
                try { return Long.parseLong(value.asText()); } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private Double confidence(JsonNode node) {
        var value = node == null ? null : node.path("confidence");
        return value != null && value.isNumber() ? Math.max(0, Math.min(1, value.doubleValue())) : null;
    }

    private <T> T withRetry(Supplier<T> request) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return request.get();
            } catch (RestClientResponseException exception) {
                last = exception;
                var status = exception.getStatusCode().value();
                if (status != 429 && status < 500 || attempt == 2) throw exception;
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt == 2) throw exception;
            }
            try { Thread.sleep(250L * (1L << attempt)); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ASR重试被中断", exception);
            }
        }
        throw last == null ? new IllegalStateException("ASR调用失败") : last;
    }

    private String strip(String value) {
        return value == null ? "" : value.strip().replaceAll("/+$", "");
    }

    private String normalizePath(String value, String fallback) {
        var path = StringUtils.hasText(value) ? value.strip() : fallback;
        return path.startsWith("/") ? path : "/" + path;
    }
}
