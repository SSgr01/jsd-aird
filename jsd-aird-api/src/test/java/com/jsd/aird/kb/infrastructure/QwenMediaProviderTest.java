package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = objectMapper.createObjectNode();
            response.putArray("choices").addObject().putObject("message").putArray("content")
                    .addObject().put("type", "text").put("ocr_result", "设备编号 A-102\n\n压力 1.25 MPa");
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenOcrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "qwen3.5-ocr",
                    "/chat/completions", Duration.ofSeconds(3), 1024 * 1024,
                    new QwenDocumentParsingConverter());
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {1, 2, 3}), "sample.png",
                    new MediaExtractionProvider.ExtractionContext(null, "image/png", 3, null));

            assertThat(parsed.blocks()).extracting(block -> block.content())
                    .containsExactly("设备编号 A-102", "压力 1.25 MPa");
            assertThat(requests.getFirst()).contains("qwen3.5-ocr", "data:image/png;base64,AQID");
            assertThat(requests.getFirst()).contains("document_parsing");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void convertsOfficialDocumentParsingLatexWithoutInventingCoordinates() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putObject("output").putArray("choices").addObject().putObject("message")
                    .putArray("content").addObject().put("ocr_result", "\\section{检测报告}\n\n批号 LOT-1");
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenOcrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "qwen3.5-ocr",
                    "/chat/completions", Duration.ofSeconds(3), 1024 * 1024,
                    new QwenDocumentParsingConverter());
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {1}), "coa.png",
                    new MediaExtractionProvider.ExtractionContext(null, "image/png", 1, null));

            assertThat(parsed.blocks()).extracting(block -> block.content())
                    .containsExactly("检测报告", "批号 LOT-1");
            assertThat(parsed.blocks()).allSatisfy(block -> assertThat(block.bbox()).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void extractsTextFromNativeDashScopeLayoutObjectsInsteadOfPreviewMarkdown() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/aigc/multimodal-generation/generation", exchange -> {
            var response = objectMapper.createObjectNode();
            var content = response.putObject("output").putArray("choices").addObject()
                    .putObject("message").putArray("content").addObject();
            content.putObject("ocr_result").putArray("layouts").addObject()
                    .put("markdownContent", "![preview](https://example.com/page.png)")
                    .put("text", "物料名称\n\nTEST-TPL-丙烯酸树脂");
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenOcrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "qwen3.5-ocr",
                    "/services/aigc/multimodal-generation/generation", Duration.ofSeconds(3), 1024 * 1024,
                    new QwenDocumentParsingConverter());
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {1}), "layout.png",
                    new MediaExtractionProvider.ExtractionContext(null, "image/png", 1, null));

            assertThat(parsed.blocks()).extracting(block -> block.content())
                    .containsExactly("物料名称", "TEST-TPL-丙烯酸树脂");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsNativeDashScopeOcrOptionsUnderParameters() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/aigc/multimodal-generation/generation", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = objectMapper.createObjectNode();
            response.putObject("output").putArray("choices").addObject().putObject("message").putArray("content")
                    .addObject().put("text", "\\section{原生协议}\n\n批号 LOT-NATIVE");
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenOcrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "qwen3.5-ocr",
                    "/services/aigc/multimodal-generation/generation", Duration.ofSeconds(3), 1024 * 1024,
                    new QwenDocumentParsingConverter());
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {1, 2, 3}), "native.png",
                    new MediaExtractionProvider.ExtractionContext(null, "image/png", 3, null));

            assertThat(parsed.blocks()).extracting(block -> block.content())
                    .containsExactly("原生协议", "批号 LOT-NATIVE");
            assertThat(requests.getFirst()).contains("\"parameters\"", "\"ocr_options\"", "document_parsing", "\"image\"");
            assertThat(requests.getFirst()).doesNotContain("\"type\":\"image_url\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsImageOnlyMarkdownReturnedAsOcrContent() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/aigc/multimodal-generation/generation", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putArray("choices").addObject().putObject("message").putArray("content")
                    .addObject().put("text", "![preview](https://example.com/preview.png?token=abc123)");
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenOcrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "qwen3.5-ocr",
                    "/services/aigc/multimodal-generation/generation", Duration.ofSeconds(3), 1024 * 1024,
                    new QwenDocumentParsingConverter());

            assertThatThrownBy(() -> provider.extract(new ByteArrayInputStream(new byte[] {1}), "preview.png",
                    new MediaExtractionProvider.ExtractionContext(null, "image/png", 1, null)))
                    .isInstanceOf(MediaExtractionException.class)
                    .hasMessageContaining("图片链接而非识别内容");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToOfficialTableParsingForRealMaterialSpreadsheetScreenshot() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            var task = objectMapper.readTree(request).at("/ocr_options/task").asText();
            var text = "table_parsing".equals(task) ? materialSpreadsheetHtml() : """
                    A
                    B
                    C
                    D
                    1
                    2
                    3
                    4
                    5
                    6
                    7
                    8
                    9
                    10
                    材料基础信息
                    物料名称
                    TEST-TPL-丙烯酸树脂
                    牌号
                    A-2026
                    供应商
                    杰事达供应商
                    密度
                    1.05
                    检验日期
                    2026-08-11
                    备注
                    适用温度
                    25 ℃
                    状态
                    合格
                    """;
            var response = objectMapper.createObjectNode();
            response.putArray("choices").addObject().putObject("message").putArray("content")
                    .addObject().put("type", "text").put("text", text);
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenOcrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "qwen3.5-ocr",
                    "/chat/completions", Duration.ofSeconds(3), 1024 * 1024,
                    new QwenDocumentParsingConverter());
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {1, 2, 3}), "材料基础信息.png",
                    new MediaExtractionProvider.ExtractionContext(null, "image/png", 1, null));

            assertThat(requests).hasSize(2);
            assertThat(requests.get(0)).contains("document_parsing");
            assertThat(requests.get(1)).contains("table_parsing");
            assertThat(parsed.blocks()).hasSize(10);
            assertThat(parsed.blocks()).extracting(block -> block.content())
                    .startsWith("材料基础信息", "物料名称 | TEST-TPL-丙烯酸树脂")
                    .doesNotContain("A", "B", "C", "D", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
            assertThat(parsed.blocks()).allSatisfy(block ->
                    assertThat(block.attributes()).containsEntry("spreadsheetAxesRemoved", true));
            assertThat(cells(parsed.blocks().getFirst()).getFirst())
                    .containsEntry("text", "材料基础信息").containsEntry("columnSpan", 4);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitsAndPollsFiletransThenDownloadsTranscriptionResult() throws Exception {
        var postBody = new AtomicReference<String>();
        var transcriptQuery = new AtomicReference<String>();
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
                        .put("transcription_url", "http://127.0.0.1:" + server.getAddress().getPort()
                                + "/transcript.json?response-content-disposition=attachment%3Bfilename%3Dresult.json&token=a%2Fb");
            }
            sendJson(exchange, response);
        });
        server.createContext("/transcript.json", exchange -> {
            transcriptQuery.set(exchange.getRequestURI().getRawQuery());
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
                    "zh", true, true, Duration.ofSeconds(3), Duration.ofMillis(1));
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {9}), "sample.wav",
                    new MediaExtractionProvider.ExtractionContext(null, "audio/wav", 1,
                            "http://minio-public/presigned.wav"));

            assertThat(parsed.providerTaskId()).isEqualTo("task-1");
            assertThat(parsed.blocks()).hasSize(1);
            assertThat(parsed.blocks().getFirst().content()).isEqualTo("这是一个 ASR 测试。");
            assertThat(parsed.blocks().getFirst().startTimeMs()).isEqualTo(120);
            assertThat(parsed.blocks().getFirst().endTimeMs()).isEqualTo(1680);
            JsonNode sent = objectMapper.readTree(postBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("qwen3-asr-flash-filetrans");
            assertThat(sent.path("input").path("file_url").asText()).contains("presigned.wav");
            assertThat(sent.path("parameters").path("language").asText()).isEqualTo("zh");
            assertThat(sent.path("parameters").path("channel_id").get(0).asInt()).isZero();
            assertThat(polls).hasValue(2);
            assertThat(transcriptQuery.get()).contains("%3Bfilename%3Dresult.json", "token=a%2Fb")
                    .doesNotContain("%253B", "%252F");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadsLocalAudioToDashScopeTemporaryStorageBeforeFiletrans() throws Exception {
        var uploadBody = new AtomicReference<String>();
        var submitBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/uploads", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putObject("data")
                    .put("upload_host", "http://127.0.0.1:" + server.getAddress().getPort() + "/upload")
                    .put("upload_dir", "temporary")
                    .put("oss_access_key_id", "oss-key")
                    .put("policy", "policy")
                    .put("signature", "signature")
                    .put("x_oss_object_acl", "private")
                    .put("x_oss_forbid_overwrite", "true");
            sendJson(exchange, response);
        });
        server.createContext("/upload", exchange -> {
            uploadBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/api/v1/services/audio/asr/transcription", exchange -> {
            submitBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = objectMapper.createObjectNode();
            response.putObject("output").put("task_id", "task-local-upload").put("task_status", "PENDING");
            sendJson(exchange, response);
        });
        server.createContext("/api/v1/tasks/task-local-upload", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putObject("output").put("task_status", "SUCCEEDED")
                    .putArray("results").addObject().put("transcription_url",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/local-transcript.json");
            sendJson(exchange, response);
        });
        server.createContext("/local-transcript.json", exchange -> {
            var response = objectMapper.createObjectNode();
            response.putArray("transcripts").addObject().putArray("sentences").addObject()
                    .put("text", "本地音频临时上传成功").put("begin_time", 0).put("end_time", 500);
            sendJson(exchange, response);
        });
        server.start();
        try {
            var provider = new QwenAsrProvider(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", "test-key",
                    "qwen3-asr-flash-filetrans", "/services/audio/asr/transcription", "/tasks",
                    "zh", true, false, Duration.ofSeconds(3), Duration.ofMillis(1));
            var parsed = provider.extract(new ByteArrayInputStream(new byte[] {9, 8, 7}), "中文录音.wav",
                    new MediaExtractionProvider.ExtractionContext(null, "audio/wav", 1, null));

            assertThat(parsed.providerTaskId()).isEqualTo("task-local-upload");
            assertThat(parsed.blocks()).extracting(block -> block.content()).contains("本地音频临时上传成功");
            assertThat(uploadBody.get()).contains("name=\"OSSAccessKeyId\"", "name=\"key\"", "name=\"file\"");
            assertThat(objectMapper.readTree(submitBody.get()).path("input").path("file_url").asText())
                    .startsWith("oss://temporary/");
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
                 "zh", true, true, timeout, Duration.ofMillis(1));
    }

    private String materialSpreadsheetHtml() {
        return """
                ```html
                <table>
                  <tr><td></td><td>A</td><td>B</td><td>C</td><td>D</td></tr>
                  <tr><td>1</td><th colspan="4">材料基础信息</th></tr>
                  <tr><td>2</td><th>物料名称</th><td colspan="3">TEST-TPL-丙烯酸树脂</td></tr>
                  <tr><td>3</td><th>牌号</th><td colspan="3">A-2026</td></tr>
                  <tr><td>4</td><th>供应商</th><td colspan="3">杰事达供应商</td></tr>
                  <tr><td>5</td><th>密度</th><td colspan="3">1.05</td></tr>
                  <tr><td>6</td><th>检验日期</th><td colspan="3">2026-08-11</td></tr>
                  <tr><td>7</td><td colspan="4"></td></tr>
                  <tr><td>8</td><th colspan="4">备注</th></tr>
                  <tr><td>9</td><th>适用温度</th><td colspan="3">25 ℃</td></tr>
                  <tr><td>10</td><th>状态</th><td colspan="3">合格</td></tr>
                </table>
                ```
                """;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cells(com.jsd.aird.kb.domain.DocumentParser.TextBlock block) {
        return (List<Map<String, Object>>) block.attributes().get("cells");
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
