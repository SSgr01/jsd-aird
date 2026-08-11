package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
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

            assertThat(parsed.blocks()).singleElement().extracting(block -> block.content())
                    .isEqualTo("设备编号 A-102\n压力 1.25 MPa");
            assertThat(requestBody.get()).contains("qwen3.5-ocr", "data:image/png;base64,AQID");
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
            response.putArray("transcripts").addObject().put("channel_id", 0).putArray("sentences")
                    .addObject().put("text", "这是一个 ASR 测试。");
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenAsrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", "test-key",
                    "qwen-audio-3.0-asr-flash-filetrans", "/services/audio/asr/transcription", "/tasks",
                    "zh", true, true, Duration.ofSeconds(3), Duration.ofMillis(1), "http://minio-public");
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {9}), "sample.wav",
                    new MediaExtractionProvider.ExtractionContext(null, "audio/wav", 1,
                            "http://minio-public/presigned.wav"));

            assertThat(parsed.blocks()).singleElement().extracting(block -> block.content())
                    .isEqualTo("这是一个 ASR 测试。");
            JsonNode sent = objectMapper.readTree(postBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("qwen-audio-3.0-asr-flash-filetrans");
            assertThat(sent.path("input").path("file_urls").get(0).asText()).contains("presigned.wav");
            assertThat(sent.path("parameters").path("language_hints").get(0).asText()).isEqualTo("zh");
            assertThat(sent.path("parameters").path("channel_id").get(0).asInt()).isZero();
            assertThat(polls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, JsonNode body) throws java.io.IOException {
        var bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
