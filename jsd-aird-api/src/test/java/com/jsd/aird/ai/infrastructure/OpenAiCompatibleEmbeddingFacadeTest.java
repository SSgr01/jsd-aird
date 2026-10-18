package com.jsd.aird.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            var springModel = mock(org.springframework.ai.embedding.EmbeddingModel.class);
            when(springModels.getIfAvailable()).thenReturn(springModel);
            var facade = new OpenAiCompatibleEmbeddingFacade(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key", "text-embedding-v4", 1024,
                    java.time.Duration.ofSeconds(3), springModels
            );

            var vector = facade.embedVector("测试文本");

            assertThat(vector).isPresent();
            assertThat(vector.get().substring(1, vector.get().length() - 1).split(", ")).hasSize(1024);
            verify(springModel, never()).embed("测试文本");
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

    @ParameterizedTest
    @ValueSource(ints = {21, 25})
    void respectsProviderBatchLimitAndPreservesOrderAcrossReorderedResponses(int count) throws Exception {
        var batchSizes = new ArrayList<Integer>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            var request = objectMapper.readTree(exchange.getRequestBody());
            var input = request.path("input");
            var items = input.isArray() ? input : objectMapper.createArrayNode().add(input);
            batchSizes.add(items.size());
            if (items.size() > 10) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            var response = objectMapper.createObjectNode();
            var data = response.putArray("data");
            for (int index = items.size() - 1; index >= 0; index--) {
                var ordinal = Integer.parseInt(items.get(index).asText().substring(4));
                data.addObject().put("index", index).putArray("embedding").add(ordinal + 1).add(0.5).add(0.25);
            }
            var bytes = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var facade = new OpenAiCompatibleEmbeddingFacade(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "text-embedding-v4", 3,
                    java.time.Duration.ofSeconds(3), mock(ObjectProvider.class));
            var vectors = facade.embedVectors(IntStream.range(0, count).mapToObj(index -> "row-" + index).toList());
            assertThat(batchSizes).containsExactly(10, 10, count - 20);
            assertThat(vectors).hasSize(count);
            for (int index = 0; index < count; index++) {
                assertThat(objectMapper.readTree(vectors.get(index).orElseThrow()).get(0).asInt()).isEqualTo(index + 1);
            }
        } finally {
            server.stop(0);
        }
    }
}
