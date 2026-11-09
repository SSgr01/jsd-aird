package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.jsd.aird.shared.security.Actor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HarnessQaClientTest {
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
            if (path.endsWith("/prepared-qa")) {
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
            var chunks = client.stream(actor, conversation, message, "Synthetic question", "instructions", "prompt", List.of("K1"),
                mapper.createArrayNode(), List.of(), List.of(), false).collectList().block(Duration.ofSeconds(10));
            var result = new StringBuilder();
            for (var chunk : chunks) { if (chunk.reset()) result.setLength(0); result.append(chunk.text()); }
            assertThat(result.toString()).isEqualTo("12 kPa [[K1]]");
            assertThat(cursor.get()).isEqualTo("1");
            assertThat(submitted.get().path("canonical_conversation_id").asText()).isEqualTo(conversation.toString());
            assertThat(submitted.get().path("canonical_user_message_id").asText()).isEqualTo(message.toString());
            assertThat(submitted.get().path("allowed_refs").get(0).asText()).isEqualTo("K1");
        } finally { server.stop(0); }
    }
}
