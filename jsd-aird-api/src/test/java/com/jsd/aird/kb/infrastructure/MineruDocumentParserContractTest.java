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
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.OcrMode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class MineruDocumentParserContractTest {

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
                Duration.ofSeconds(2), Duration.ofSeconds(2), fallbackCapability, new ObjectMapper(),
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
