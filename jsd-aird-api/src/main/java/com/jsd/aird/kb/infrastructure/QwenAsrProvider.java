package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
public class QwenAsrProvider implements MediaExtractionProvider {

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
    private final boolean publicStorageConfigured;
    private final RestClient client;

    public QwenAsrProvider(
            @Value("${app.ai.asr.enabled:false}") boolean enabled,
            @Value("${app.ai.asr.base-url:https://dashscope.aliyuncs.com/api/v1}") String baseUrl,
            @Value("${app.ai.asr.api-key:}") String apiKey,
            @Value("${app.ai.asr.model:qwen-audio-3.0-asr-flash-filetrans}") String model,
            @Value("${app.ai.asr.transcription-path:/services/audio/asr/transcription}") String transcriptionPath,
            @Value("${app.ai.asr.task-path:/tasks}") String taskPath,
            @Value("${app.ai.asr.language:zh}") String language,
            @Value("${app.ai.asr.enable-itn:true}") boolean enableItn,
            @Value("${app.ai.asr.enable-words:true}") boolean enableWords,
            @Value("${app.ai.asr.timeout:10m}") Duration timeout,
            @Value("${app.ai.asr.poll-interval:2s}") Duration pollInterval,
            @Value("${app.storage.public-endpoint:}") String publicEndpoint
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
        this.publicStorageConfigured = StringUtils.hasText(publicEndpoint);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setReadTimeout(this.timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
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
        return enabled && publicStorageConfigured && StringUtils.hasText(baseUrl)
                && StringUtils.hasText(apiKey) && StringUtils.hasText(model);
    }

    @Override
    public String unavailableReason() {
        return "ASR 服务未配置，需配置可被 DashScope 访问的 MinIO 公网 endpoint";
    }

    @Override
    public DocumentParser.ParsedDocument extract(InputStream source, String fileName, ExtractionContext context) {
        if (!isConfigured()) {
            throw new IllegalStateException("ASR 服务未配置，需配置可被 DashScope 访问的 MinIO 公网 endpoint");
        }
        var publicUrl = context == null ? null : context.publicUrl();
        if (!StringUtils.hasText(publicUrl)) {
            throw new IllegalStateException("ASR 文件缺少 MinIO 公网预签名 URL");
        }
        try {
            var taskId = submit(publicUrl);
            var result = poll(taskId);
            var blocks = transcriptBlocks(result);
            if (blocks.isEmpty()) throw new IllegalStateException("ASR 返回结果为空");
            return new DocumentParser.ParsedDocument(blocks, model);
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("ASR 服务调用失败：HTTP " + exception.getStatusCode().value()
                    + " " + exception.getResponseBodyAsString(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR 轮询被中断", exception);
        }
    }

    private String submit(String publicUrl) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("channel_id", List.of(0));
        if (StringUtils.hasText(language)) {
            if (model.toLowerCase(Locale.ROOT).contains("qwen-audio-3.0")) {
                parameters.put("language_hints", List.of(language));
            } else {
                parameters.put("language", language);
            }
        }
        parameters.put("enable_itn", enableItn);
        parameters.put("enable_words", enableWords);
        var input = model.toLowerCase(Locale.ROOT).contains("qwen-audio-3.0")
                ? Map.of("file_urls", List.of(publicUrl))
                : Map.of("file_url", publicUrl);
        var body = Map.of(
                "model", model,
                "input", input,
                "parameters", parameters
        );
        var response = client.post().uri(baseUrl + transcriptionPath)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header("X-DashScope-Async", "enable")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        var taskId = firstText(response == null ? null : response.path("output"), "task_id", "taskId");
        if (!StringUtils.hasText(taskId)) throw new IllegalStateException("ASR 未返回 task_id");
        return taskId;
    }

    private JsonNode poll(String taskId) throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            var response = client.get().uri(baseUrl + taskPath + "/" + taskId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);
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
        var transcriptFile = client.get().uri(transcriptionUrl).retrieve().body(JsonNode.class);
        collectTranscripts(transcriptFile, result);
    }

    private void collectTranscripts(JsonNode node, List<DocumentParser.TextBlock> result) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(item -> collectTranscripts(item, result));
            return;
        }
        if (!node.isObject()) return;
        var text = firstText(node, "text", "transcript", "sentence");
        if (StringUtils.hasText(text)) {
            var channel = node.path("channel_id").asText(node.path("channelId").asText("0"));
            result.add(new DocumentParser.TextBlock(null, "ASR-CHANNEL-" + channel, text.strip()));
            return;
        }
        collectTranscripts(node.path("transcripts"), result);
        collectTranscripts(node.path("sentences"), result);
        collectTranscripts(node.path("results"), result);
    }

    private String firstText(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        for (var name : names) {
            var value = node.path(name);
            if (value.isTextual() && StringUtils.hasText(value.asText())) return value.asText();
        }
        return "";
    }

    private String strip(String value) {
        return value == null ? "" : value.strip().replaceAll("/+$", "");
    }

    private String normalizePath(String value, String fallback) {
        var path = StringUtils.hasText(value) ? value.strip() : fallback;
        return path.startsWith("/") ? path : "/" + path;
    }
}
