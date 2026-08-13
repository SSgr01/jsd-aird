package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

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
            @Value("${app.ai.asr.model:qwen3-asr-flash-filetrans}") String model,
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
        String taskId = null;
        try {
            taskId = submit(publicUrl);
            var result = poll(taskId);
            var blocks = transcriptBlocks(result);
            if (blocks.isEmpty()) throw new IllegalStateException("ASR 返回结果为空");
            return new DocumentParser.ParsedDocument(blocks, model, taskId,
                    Map.of("model", model, "timestampLevel", enableWords ? "SENTENCE_AND_WORD" : "SENTENCE"));
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
        var response = withRetry(() -> client.post().uri(baseUrl + transcriptionPath)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header("X-DashScope-Async", "enable")
                .body(body)
                .retrieve()
                .body(JsonNode.class));
        var taskId = firstText(response == null ? null : response.path("output"), "task_id", "taskId");
        if (!StringUtils.hasText(taskId)) throw new IllegalStateException("ASR 未返回 task_id");
        return taskId;
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
        var transcriptFile = withRetry(() -> client.get().uri(transcriptionUrl).retrieve().body(JsonNode.class));
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
            var words = node.path("words");
            if (enableWords && words.isArray()) {
                words.forEach(word -> {
                    var wordText = firstText(word, "text", "word");
                    if (StringUtils.hasText(wordText)) {
                        var punctuation = firstText(word, "punctuation");
                        result.add(new DocumentParser.TextBlock(null, "ASR-WORD-CHANNEL-" + channel,
                                wordText + punctuation,
                                null, null, null, List.of(),
                                firstLong(word, "begin_time", "start_time", "startTime", "start_ms", "beginTime"),
                                firstLong(word, "end_time", "endTime", "end_ms", "stop_time"), confidence(word)));
                    }
                });
            }
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
