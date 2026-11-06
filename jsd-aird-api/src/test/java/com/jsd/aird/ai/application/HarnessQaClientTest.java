package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.error.ApiException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HarnessQaClientTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingHttpStreamStopsTheSameNativeRunEvenDuringAdmission(boolean duringAdmission) throws Exception {
        var mapper = new ObjectMapper();
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "synthetic", "USER");
        var delegation = mock(AgentDelegation.class);
        when(delegation.issue(actor)).thenReturn("delegated-test-ticket");
        var run = UUID.randomUUID();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var admission = new java.util.concurrent.CountDownLatch(duringAdmission ? 1 : 0);
        var stopped = new java.util.concurrent.CountDownLatch(1);
        var submissions = new java.util.concurrent.atomic.AtomicInteger();
        var cancellations = new java.util.concurrent.atomic.AtomicInteger();
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/internal/v1/harness", exchange -> {
            try {
                assertThat(exchange.getRequestHeaders().getFirst("X-Jsd-Agent-Ticket")).isEqualTo("delegated-test-ticket");
                var path = exchange.getRequestURI().getPath();
                String body;
                if (path.endsWith("/tasks")) {
                    submissions.incrementAndGet();
                    if (duringAdmission) ready.countDown();
                    if (!admission.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("admission timeout");
                    body = "{\"id\":\"" + run + "\"}";
                } else if (path.endsWith("/cancel")) {
                    assertThat(path).endsWith("/runs/" + run + "/cancel");
                    cancellations.incrementAndGet(); stopped.countDown(); body = "{\"accepted\":true}";
                } else if (path.endsWith("/events")) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush(); ready.countDown();
                    stopped.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return;
                } else { throw new java.io.IOException("unexpected path: " + path); }
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new HarnessQaClient(delegation, mapper, "http://127.0.0.1:" + server.getAddress().getPort(), true);
            var subscription = client.stream(actor, UUID.randomUUID(), UUID.randomUUID(), "客户问题", List.of(), List.of(), false).subscribe();
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            subscription.dispose(); admission.countDown();
            assertThat(stopped.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(submissions.get()).isEqualTo(1);
            assertThat(cancellations.get()).isEqualTo(1);
        } finally { admission.countDown(); stopped.countDown(); server.stop(0); executor.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsQuotaFailureFromStreamAndRecoveredSnapshotWithoutReplaying(boolean snapshotFailure) throws Exception {
        var mapper = new ObjectMapper();
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "synthetic", "USER");
        var delegation = mock(AgentDelegation.class);
        when(delegation.issue(actor)).thenReturn("delegated-test-ticket");
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var run = UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/harness", exchange -> {
            var path = exchange.getRequestURI().getPath();
            String body;
            if (path.endsWith("/tasks")) {
                submitted.incrementAndGet();
                body = "{\"id\":\"" + run + "\"}";
            } else if (path.endsWith("/events")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                body = snapshotFailure ? "\n" : "data: {\"seq\":1,\"type\":\"run.failed\",\"payload\":{\"code\":\"MODEL_QUOTA_UNAVAILABLE\"}}\n\n";
            } else body = "{\"status\":\"FAILED\",\"error\":\"MODEL_QUOTA_UNAVAILABLE\"}";
            var data = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        try {
            var client = new HarnessQaClient(delegation, mapper, "http://127.0.0.1:" + server.getAddress().getPort(), true);
            assertThatThrownBy(() -> client.stream(actor, UUID.randomUUID(), UUID.randomUUID(), "客户问题",
                List.of(), List.of(), false)
                .collectList().block(Duration.ofSeconds(10)))
                .isInstanceOf(ApiException.class)
                .hasMessage("模型服务额度暂不可用，请联系管理员检查账户。");
            assertThat(submitted.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test
    void preservesCanonicalIdsAndResumesSseWithoutDuplicatingText() throws Exception {
        var mapper = new ObjectMapper();
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "synthetic", "USER");
        var delegation = mock(AgentDelegation.class);
        when(delegation.issue(actor)).thenReturn("delegated-test-ticket");
        var conversation = UUID.randomUUID(); var message = UUID.randomUUID(); var run = UUID.randomUUID();
        var submitted = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var cursor = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/harness", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Jsd-Agent-Ticket")).isEqualTo("delegated-test-ticket");
            var path = exchange.getRequestURI().getPath();
            String body;
            if (path.endsWith("/tasks")) {
                submitted.set(mapper.readTree(exchange.getRequestBody()));
                body = "{\"id\":\"" + run + "\"}";
            } else if (path.endsWith("/events")) {
                var after = exchange.getRequestHeaders().getFirst("Last-Event-ID"); cursor.set(after);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                body = "data: {\"seq\":1,\"type\":\"message.delta\",\"payload\":{\"delta\":\"12 \"}}\n\n";
                if ("1".equals(after)) body += "data: {\"seq\":2,\"type\":\"message.delta\",\"payload\":{\"delta\":\"kPa [[K1]]\"}}\n\n"
                    + "data: {\"seq\":3,\"type\":\"run.succeeded\",\"payload\":{\"text\":\"12 kPa [[K1]]\"}}\n\n";
            } else body = "{\"status\":\"RUNNING\"}";
            var data = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        try {
            var client = new HarnessQaClient(delegation, mapper, "http://127.0.0.1:" + server.getAddress().getPort(), true);
            var chunks = client.stream(actor, conversation, message, "Synthetic question", List.of(), List.of(), false).collectList().block(Duration.ofSeconds(10));
            var result = new StringBuilder();
            for (var chunk : chunks) { if (chunk.reset()) result.setLength(0); result.append(chunk.text()); }
            assertThat(result.toString()).isEqualTo("12 kPa [[K1]]");
            assertThat(cursor.get()).isEqualTo("1");
            assertThat(submitted.get().path("canonical_conversation_id").asText()).isEqualTo(conversation.toString());
            assertThat(submitted.get().path("canonical_user_message_id").asText()).isEqualTo(message.toString());
            assertThat(submitted.get().has("instructions")).isFalse();
            assertThat(submitted.get().has("prompt")).isFalse();
            assertThat(submitted.get().has("candidates")).isFalse();
        } finally { server.stop(0); }
    }
}
