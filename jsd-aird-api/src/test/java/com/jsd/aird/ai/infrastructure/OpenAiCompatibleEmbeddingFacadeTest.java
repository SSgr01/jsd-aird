package com.jsd.aird.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.sun.net.httpserver.HttpServer;

class OpenAiCompatibleEmbeddingFacadeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsConfiguredDimensionAndAcceptsMatchingResponse() throws Exception {
        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = objectMapper.createObjectNode();
            var vector = objectMapper.createArrayNode();
            for (int index = 0; index < 1024; index++) vector.add(index / 1024.0);
            response.putArray("data").addObject().set("embedding", vector);
            var bytes = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var springModels = mock(ObjectProvider.class);
            when(springModels.getIfAvailable()).thenReturn(null);
            var facade = new OpenAiCompatibleEmbeddingFacade(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key", "text-embedding-v4", 1024,
                    java.time.Duration.ofSeconds(3), springModels
            );

            var vector = facade.embedVector("测试文本");

            assertThat(vector).isPresent();
            assertThat(vector.get().substring(1, vector.get().length() - 1).split(", ")).hasSize(1024);
            var sent = objectMapper.readTree(requestBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("text-embedding-v4");
            assertThat(sent.path("dimensions").asInt()).isEqualTo(1024);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsResponseWithWrongDimension() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            var response = objectMapper.createObjectNode();
            var vector = objectMapper.createArrayNode();
            vector.add(0.1).add(0.2).add(0.3);
            response.putArray("data").addObject().set("embedding", vector);
            var bytes = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var springModels = mock(ObjectProvider.class);
            when(springModels.getIfAvailable()).thenReturn(null);
            var facade = new OpenAiCompatibleEmbeddingFacade(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key", "text-embedding-v4", 1024,
                    java.time.Duration.ofSeconds(3), springModels
            );

            assertThat(facade.embedVector("wrong dimension")).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
