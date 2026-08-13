package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import com.jsd.aird.kb.domain.MediaExtractionException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class QwenMediaProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsImageAsDataUrlAndParsesMultipartTextContent() throws Exception {
        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = objectMapper.createObjectNode();
            response.putArray("choices").addObject().putObject("message").putArray("content")
                    .addObject().put("type", "text").put("text", "设备编号 A-102\n压力 1.25 MPa");
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenOcrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "qwen3.5-ocr",
                    "/chat/completions", Duration.ofSeconds(3), 1024 * 1024);
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {1, 2, 3}), "sample.png",
                    new MediaExtractionProvider.ExtractionContext(null, "image/png", 3, null));

            assertThat(parsed.blocks()).extracting(block -> block.content())
                    .containsExactly("设备编号 A-102", "压力 1.25 MPa");
            assertThat(requestBody.get()).contains("qwen3.5-ocr", "data:image/png;base64,AQID");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesOfficialAdvancedRecognitionLocationPayload() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putObject("output").putArray("choices").addObject().putObject("message")
                    .putArray("content").addObject().putObject("ocr_result").putArray("words_info")
                    .addObject().put("text", "批号 LOT-1").putArray("location")
                    .add(10).add(20).add(110).add(20).add(110).add(42).add(10).add(42);
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenOcrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "qwen3.5-ocr",
                    "/chat/completions", Duration.ofSeconds(3), 1024 * 1024);
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {1}), "coa.png",
                    new MediaExtractionProvider.ExtractionContext(null, "image/png", 1, null));

            assertThat(parsed.blocks()).singleElement().satisfies(block -> {
                assertThat(block.content()).isEqualTo("批号 LOT-1");
                assertThat(block.bbox()).containsExactly(10d, 20d, 110d, 20d, 110d, 42d, 10d, 42d);
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitsAndPollsFiletransThenDownloadsTranscriptionResult() throws Exception {
        var postBody = new AtomicReference<String>();
        var polls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/audio/asr/transcription", exchange -> {
            postBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = objectMapper.createObjectNode();
            response.putObject("output").put("task_id", "task-1").put("task_status", "PENDING");
            sendJson(exchange, response);
        });
        server.createContext("/api/v1/tasks/task-1", exchange -> {
            var response = objectMapper.createObjectNode();
            var output = response.putObject("output").put("task_id", "task-1");
            if (polls.incrementAndGet() == 1) {
                output.put("task_status", "RUNNING");
            } else {
                output.put("task_status", "SUCCEEDED").putArray("results").addObject()
                        .put("subtask_status", "SUCCEEDED")
                        .put("transcription_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/transcript.json");
            }
            sendJson(exchange, response);
        });
        server.createContext("/transcript.json", exchange -> {
            var response = objectMapper.createObjectNode();
            var sentence = response.putArray("transcripts").addObject().put("channel_id", 0)
                    .putArray("sentences").addObject().put("text", "这是一个 ASR 测试。")
                    .put("begin_time", 120).put("end_time", 1680);
            var words = sentence.putArray("words");
            words.addObject().put("text", "这是").put("begin_time", 120).put("end_time", 520);
            words.addObject().put("text", "一个测试").put("punctuation", "。").put("begin_time", 520).put("end_time", 1680);
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenAsrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", "test-key",
                    "qwen3-asr-flash-filetrans", "/services/audio/asr/transcription", "/tasks",
                    "zh", true, true, Duration.ofSeconds(3), Duration.ofMillis(1), "http://minio-public");
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {9}), "sample.wav",
                    new MediaExtractionProvider.ExtractionContext(null, "audio/wav", 1,
                            "http://minio-public/presigned.wav"));

            assertThat(parsed.providerTaskId()).isEqualTo("task-1");
            assertThat(parsed.blocks()).hasSize(3);
            assertThat(parsed.blocks().getFirst().content()).isEqualTo("这是一个 ASR 测试。");
            assertThat(parsed.blocks().getFirst().startTimeMs()).isEqualTo(120);
            assertThat(parsed.blocks().getFirst().endTimeMs()).isEqualTo(1680);
            assertThat(parsed.blocks().get(2).content()).isEqualTo("一个测试。");
            assertThat(parsed.blocks().get(2).startTimeMs()).isEqualTo(520);
            JsonNode sent = objectMapper.readTree(postBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("qwen3-asr-flash-filetrans");
            assertThat(sent.path("input").path("file_url").asText()).contains("presigned.wav");
            assertThat(sent.path("parameters").path("language").asText()).isEqualTo("zh");
            assertThat(sent.path("parameters").path("channel_id").get(0).asInt()).isZero();
            assertThat(polls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransientAsrSubmissionThreeTimes() throws Exception {
        var attempts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/audio/asr/transcription", exchange -> {
            if (attempts.incrementAndGet() < 3) {
                sendJson(exchange, objectMapper.createObjectNode().put("message", "busy"), 429);
                return;
            }
            var response = objectMapper.createObjectNode();
            response.putObject("output").put("task_id", "task-retry").put("task_status", "PENDING");
            sendJson(exchange, response);
        });
        server.createContext("/api/v1/tasks/task-retry", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putObject("output").put("task_status", "SUCCEEDED")
                    .putArray("results").addObject().put("transcription_url",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/retry.json");
            sendJson(exchange, response);
        });
        server.createContext("/retry.json", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putArray("transcripts").addObject().putArray("sentences").addObject()
                    .put("text", "重试成功").put("begin_time", 0).put("end_time", 300);
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = asrProvider(server, Duration.ofSeconds(3));
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {1}), "retry.wav",
                    new MediaExtractionProvider.ExtractionContext(null, "audio/wav", 1,
                            "http://minio-public/retry.wav"));

            assertThat(attempts).hasValue(3);
            assertThat(parsed.blocks()).extracting(block -> block.content()).contains("重试成功");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesTaskIdWhenAsrPollingTimesOut() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/audio/asr/transcription", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putObject("output").put("task_id", "task-timeout").put("task_status", "PENDING");
            sendJson(exchange, response);
        });
        server.createContext("/api/v1/tasks/task-timeout", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putObject("output").put("task_status", "RUNNING");
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = asrProvider(server, Duration.ofMillis(40));
            assertThatThrownBy(() -> provider.extract(new ByteArrayInputStream(new byte[] {1}), "timeout.wav",
                    new MediaExtractionProvider.ExtractionContext(null, "audio/wav", 1,
                            "http://minio-public/timeout.wav")))
                    .isInstanceOf(MediaExtractionException.class)
                    .hasMessageContaining("超过轮询超时")
                    .extracting(error -> ((MediaExtractionException) error).providerTaskId())
                    .isEqualTo("task-timeout");
        } finally {
            server.stop(0);
        }
    }

    private QwenAsrProvider asrProvider(HttpServer server, Duration timeout) {
        return new QwenAsrProvider(true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", "test-key",
                "qwen3-asr-flash-filetrans", "/services/audio/asr/transcription", "/tasks",
                "zh", true, true, timeout, Duration.ofMillis(1), "http://minio-public");
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, JsonNode body) throws java.io.IOException {
        sendJson(exchange, body, 200);
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, JsonNode body, int status) throws java.io.IOException {
        var bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
