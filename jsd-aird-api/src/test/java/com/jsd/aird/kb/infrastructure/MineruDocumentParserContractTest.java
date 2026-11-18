package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.SystemParsingPolicy;
import com.jsd.aird.kb.api.DocumentParser;
import com.jsd.aird.kb.api.OcrMode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class MineruDocumentParserContractTest {

    @Test
    void reusesPartitionBatchesEvenWhenPdfBoxSerializesPartsAgain() throws Exception {
        var submissions = new AtomicInteger();
        var checkpoints = new java.util.concurrent.ConcurrentHashMap<String, com.jsd.aird.kb.application.port.MineruTaskStore.Task>();
        var store = new com.jsd.aird.kb.application.port.MineruTaskStore() {
            public java.util.Optional<Task> find(java.util.UUID org, java.util.UUID version, String key) { return java.util.Optional.ofNullable(checkpoints.get(key)); }
            public void save(java.util.UUID org, java.util.UUID version, String key, Task task) { checkpoints.put(key, task); }
            public java.util.Optional<Task> latest(java.util.UUID org, java.util.UUID version) { return checkpoints.values().stream().findFirst(); }
        };
        var output = new ByteArrayOutputStream();
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i < 191; i++) pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            pdf.save(output);
        }
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v4/file-urls/batch", exchange -> json(exchange, 200,
                "{\"code\":0,\"data\":{\"batch_id\":\"batch-" + submissions.incrementAndGet() + "\",\"file_urls\":[\"" + base + "/upload\"]}}"));
        server.createContext("/upload", exchange -> { exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream()); json(exchange, 200, "{}"); });
        server.createContext("/api/v4/extract-results/batch/", exchange -> json(exchange, 200,
                "{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"done\",\"full_zip_url\":\"" + base + "/result.zip\"}]}}"));
        server.createContext("/result.zip", exchange -> bytes(exchange, 200, resultZip()));
        server.start();
        var context = new DocumentParser.ParseContext(java.util.UUID.randomUUID(), null, null, "application/pdf", output.size(),
                OcrMode.OFF, false, java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        var first = parser(base, false); var second = parser(base, false);
        first.setTaskStore(store); second.setTaskStore(store);
        try {
            first.parse(new ByteArrayInputStream(output.toByteArray()), "large.pdf", context);
            second.parse(new ByteArrayInputStream(output.toByteArray()), "large.pdf", context);
            assertThat(submissions).hasValue(2);
            assertThat(checkpoints).hasSize(2);
        } finally { first.shutdownPartExecutor(); second.shutdownPartExecutor(); server.stop(0); }
    }

    @Test
    void resumesUploadedBatchAfterTimeoutAndParserRestartWithoutSubmittingAgain() throws Exception {
        var submissions = new AtomicInteger();
        var uploads = new AtomicInteger();
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        var checkpoints = new java.util.HashMap<String, com.jsd.aird.kb.application.port.MineruTaskStore.Task>();
        var store = new com.jsd.aird.kb.application.port.MineruTaskStore() {
            public java.util.Optional<Task> find(java.util.UUID org, java.util.UUID version, String key) { return java.util.Optional.ofNullable(checkpoints.get(key)); }
            public void save(java.util.UUID org, java.util.UUID version, String key, Task task) { checkpoints.put(key, task); }
            public java.util.Optional<Task> latest(java.util.UUID org, java.util.UUID version) { return checkpoints.values().stream().findFirst(); }
        };
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            submissions.incrementAndGet();
            json(exchange, 200, "{\"code\":0,\"data\":{\"batch_id\":\"batch-1\",\"file_urls\":[\"" + base + "/upload\"]}}");
        });
        server.createContext("/upload", exchange -> {
            uploads.incrementAndGet(); exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream()); json(exchange, 200, "{}");
        });
        server.createContext("/api/v4/extract-results/batch/batch-1", exchange -> json(exchange, 200,
                "{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"" + (done.get() ? "done" : "pending")
                        + "\",\"full_zip_url\":\"" + base + "/result.zip\"}]}}"));
        server.createContext("/result.zip", exchange -> bytes(exchange, 200, resultZip()));
        server.start();
        var context = new DocumentParser.ParseContext(java.util.UUID.randomUUID(), null, null, "application/pdf", 3,
                OcrMode.OFF, false, java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        var first = new MineruDocumentParser(true, base, "token", "vlm", Duration.ofMillis(1), Duration.ofMillis(20),
                Duration.ofSeconds(2), new SystemParsingPolicy("AUTO", false), new ObjectMapper(), mock(FileStorageFacade.class));
        first.setTaskStore(store);
        var restarted = parser(base, false);
        restarted.setTaskStore(store);
        try {
            assertThatThrownBy(() -> first.parse(new ByteArrayInputStream(new byte[]{1}), "test.pdf", context))
                    .isInstanceOf(MineruException.class).hasMessageContaining("等待超时");
            assertThat(checkpoints.values()).singleElement().satisfies(task -> assertThat(task.uploaded()).isTrue());
            done.set(true);
            var result = restarted.parse(new ByteArrayInputStream(new byte[]{1}), "test.pdf", context);
            assertThat(result.providerTaskId()).isEqualTo("batch-1");
            assertThat(submissions).hasValue(1);
            assertThat(uploads).hasValue(1);
            restarted.parse(new ByteArrayInputStream(new byte[]{2}), "test.pdf", context);
            assertThat(submissions).hasValue(2); // Changed source bytes cannot consume the old result.
        } finally { first.shutdownPartExecutor(); restarted.shutdownPartExecutor(); server.stop(0); }
    }

    @Test
    void resumesHistoricalTimeoutWithoutCreatingOrUploadingAnotherBatch() throws Exception {
        var store = mock(com.jsd.aird.kb.application.port.MineruTaskStore.class);
        org.mockito.Mockito.when(store.previousTimeout(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(new com.jsd.aird.kb.application.port.MineruTaskStore.Task("batch-1", null, true, "pending")));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        successfulPreciseEndpoints(server, base);
        server.removeContext("/upload"); // Any new submission or upload fails this test.
        server.start();
        var parser = parser(base, false);
        parser.setTaskStore(store);
        try {
            var parsed = parser.parse(new ByteArrayInputStream(new byte[]{1}), "test.pdf",
                    new DocumentParser.ParseContext(java.util.UUID.randomUUID(), null, null, "application/pdf", 1,
                            OcrMode.OFF, false, java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
            assertThat(parsed.providerTaskId()).isEqualTo("batch-1");
        } finally { parser.shutdownPartExecutor(); server.stop(0); }
    }

    @Test
    void boundsDerivedNamesByUnicodeCharactersAndPreservesSuffix() {
        var name = "😀".repeat(260) + ".pdf";
        var derived = MineruDocumentParser.derivedFileName(name, ".mineru.full.md");
        assertThat(derived.codePointCount(0, derived.length())).isEqualTo(260);
        assertThat(derived).endsWith(".mineru.full.md");
        var asset = MineruDocumentParser.derivedFileName(name, ".mineru." + "a".repeat(300) + ".png");
        assertThat(asset.codePointCount(0, asset.length())).isLessThanOrEqualTo(260);
        assertThat(asset).endsWith(".png");
        assertThat(asset).isNotEqualTo(MineruDocumentParser.derivedFileName(name, ".mineru." + "b".repeat(300) + ".png"));
    }

    @Test
    void sendsOcrDecisionInsideEachPreciseFileRequest() throws Exception {
        var mapper = new ObjectMapper();
        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"code\":0,\"data\":{\"batch_id\":\"batch-1\",\"file_urls\":[\"" + base + "/upload\"]}}");
        });
        server.createContext("/upload", exchange -> { exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream()); json(exchange, 200, "{}"); });
        server.createContext("/api/v4/extract-results/batch/batch-1", exchange -> json(exchange, 200,
                "{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"done\",\"full_zip_url\":\"" + base + "/result.zip\"}]}}"));
        server.createContext("/result.zip", exchange -> bytes(exchange, 200, resultZip()));
        server.start();
        try {
            var parser = parser(base, true);
            var parsed = parser.parse(new ByteArrayInputStream("pdf".getBytes(StandardCharsets.UTF_8)), "test.pdf",
                    new DocumentParser.ParseContext(null, null, null, "application/pdf", 3, OcrMode.OFF, false));

            var submitted = mapper.readTree(requestBody.get());
            assertThat(submitted.has("is_ocr")).isFalse();
            assertThat(submitted.path("files").get(0).path("is_ocr").asBoolean()).isFalse();
            assertThat(parsed.metadata()).containsEntry("mode", "PRECISE").containsEntry("effectiveOcr", false);
        } finally { server.stop(0); }
    }

    @Test
    void neverFallsBackForAuthenticationErrorsEvenWhenVersionAllowsIt() throws Exception {
        var preciseCalls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            preciseCalls.incrementAndGet();
            json(exchange, 401, "{\"code\":401,\"msg\":\"unauthorized\"}");
        });
        server.start();
        try {
            assertThatThrownBy(() -> parser(base, true).parse(new ByteArrayInputStream(new byte[]{1}), "test.pdf",
                    new DocumentParser.ParseContext(null, null, null, "application/pdf", 1, OcrMode.ON, true)))
                    .isInstanceOf(MineruException.class)
                    .satisfies(error -> assertThat(((MineruException) error).fallbackEligible()).isFalse());
            assertThat(preciseCalls).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test
    void retriesTransientFailuresThreeTimesInsideTheSameTask() throws Exception {
        var preciseCalls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            if (preciseCalls.incrementAndGet() < 3) {
                json(exchange, 503, "{\"code\":503,\"msg\":\"busy\"}");
            } else {
                json(exchange, 200, "{\"code\":0,\"data\":{\"batch_id\":\"batch-1\",\"file_urls\":[\""
                        + base + "/upload\"]}}");
            }
        });
        successfulPreciseEndpoints(server, base);
        server.start();
        try {
            var parsed = parser(base, false).parse(new ByteArrayInputStream(new byte[]{1}), "retry.pdf",
                    new DocumentParser.ParseContext(null, null, null, "application/pdf", 1, OcrMode.ON, false));

            assertThat(preciseCalls).hasValue(3);
            assertThat(parsed.metadata()).containsEntry("mode", "PRECISE");
        } finally { server.stop(0); }
    }

    @Test
    void usesAgentOnlyAfterExplicitFallbackForEligibleProviderFailure() throws Exception {
        var preciseCalls = new AtomicInteger();
        var agentCalls = new AtomicInteger();
        var agentRequest = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            preciseCalls.incrementAndGet();
            json(exchange, 503, "{\"code\":503,\"msg\":\"busy\"}");
        });
        server.createContext("/api/v1/agent/parse/file", exchange -> {
            agentCalls.incrementAndGet();
            agentRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"code\":0,\"data\":{\"task_id\":\"agent-1\",\"file_url\":\""
                    + base + "/agent-upload\"}}");
        });
        server.createContext("/agent-upload", exchange -> {
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            json(exchange, 200, "{}");
        });
        server.createContext("/api/v1/agent/parse/agent-1", exchange -> json(exchange, 200,
                "{\"code\":0,\"data\":{\"state\":\"done\",\"markdown_url\":\"" + base + "/result.md\"}}"));
        server.createContext("/result.md", exchange -> bytes(exchange, 200,
                "# 参数\n\n粘度：400-700cps".getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            var parsed = parser(base, true).parse(new ByteArrayInputStream(new byte[]{1}), "fallback.pdf",
                    new DocumentParser.ParseContext(null, null, null, "application/pdf", 1, OcrMode.ON, true));

            assertThat(preciseCalls).hasValue(3);
            assertThat(agentCalls).hasValue(1);
            assertThat(new ObjectMapper().readTree(agentRequest.get()).path("is_ocr").asBoolean()).isTrue();
            assertThat(parsed.metadata()).containsEntry("mode", "AGENT_FALLBACK")
                    .containsEntry("degraded", true).containsKey("reviewWarning");
        } finally { server.stop(0); }
    }

    @Test
    void neverFallsBackForAnEmptyPreciseResult() throws Exception {
        var agentCalls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v4/file-urls/batch", exchange -> json(exchange, 200,
                "{\"code\":0,\"data\":{\"batch_id\":\"batch-1\",\"file_urls\":[\"" + base + "/upload\"]}}"));
        server.createContext("/upload", exchange -> {
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            json(exchange, 200, "{}");
        });
        server.createContext("/api/v4/extract-results/batch/batch-1", exchange -> json(exchange, 200,
                "{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"done\",\"full_zip_url\":\""
                        + base + "/empty.zip\"}]}}"));
        server.createContext("/empty.zip", exchange -> bytes(exchange, 200, emptyResultZip()));
        server.createContext("/api/v1/agent/parse/file", exchange -> {
            agentCalls.incrementAndGet(); json(exchange, 500, "{}");
        });
        server.start();
        try {
            assertThatThrownBy(() -> parser(base, true).parse(new ByteArrayInputStream(new byte[]{1}), "empty.pdf",
                    new DocumentParser.ParseContext(null, null, null, "application/pdf", 1, OcrMode.ON, true)))
                    .isInstanceOf(MineruException.class).hasMessageContaining("非空有效内容")
                    .satisfies(error -> assertThat(((MineruException) error).fallbackEligible()).isFalse());
            assertThat(agentCalls).hasValue(0);
        } finally { server.stop(0); }
    }

    private MineruDocumentParser parser(String base, boolean fallbackCapability) {
        return new MineruDocumentParser(true, base, "test-token", "vlm", Duration.ofMillis(1),
                Duration.ofSeconds(2), Duration.ofSeconds(2),
                new SystemParsingPolicy("AUTO", fallbackCapability), new ObjectMapper(),
                mock(FileStorageFacade.class));
    }

    private void successfulPreciseEndpoints(HttpServer server, String base) {
        server.createContext("/upload", exchange -> {
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            json(exchange, 200, "{}");
        });
        server.createContext("/api/v4/extract-results/batch/batch-1", exchange -> json(exchange, 200,
                "{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"done\",\"full_zip_url\":\""
                        + base + "/result.zip\"}]}}"));
        server.createContext("/result.zip", exchange -> bytes(exchange, 200, resultZip()));
    }

    private byte[] resultZip() {
        try {
            var output = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("content_list.json"));
                zip.write("[{\"type\":\"text\",\"text\":\"UA-1117\",\"page_idx\":0,\"bbox\":[0,0,100,100]}]".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private byte[] emptyResultZip() {
        try {
            var output = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("content_list.json"));
                zip.write("[]".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void json(HttpExchange exchange, int status, String value) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        bytes(exchange, status, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(HttpExchange exchange, int status, byte[] value) throws java.io.IOException {
        exchange.sendResponseHeaders(status, value.length);
        try (var output = exchange.getResponseBody()) { output.write(value); }
    }
}
