package com.jsd.aird.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import com.sun.net.httpserver.HttpServer;

class OpenAiCompatibleEmbeddingFacadeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsConfiguredDimensionAndAcceptsMatchingResponse() throws Exception {
        var requestBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void usesExplicitLocalEndpointWithoutAuthentication(String apiKey) throws Exception {
        var authorization = new AtomicReference<String>("not received");
        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var springModels = mock(ObjectProvider.class);
            var facade = new OpenAiCompatibleEmbeddingFacade(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                    apiKey, "Qwen3-Embedding-0.6B", 3, java.time.Duration.ofSeconds(3), springModels);

            assertThat(facade.embedVector("材料研发检索")).contains("[0.1, 0.2, 0.3]");
            assertThat(authorization.get()).isNull();
            var sent = objectMapper.readTree(requestBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("Qwen3-Embedding-0.6B");
            assertThat(sent.path("input").asText()).isEqualTo("材料研发检索");
            verifyNoInteractions(springModels);
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
    @MethodSource("batchRequests")
    void respectsProviderBatchLimitAndPreservesOrderAcrossReorderedResponses(int count, String apiKey) throws Exception {
        var batchSizes = new ArrayList<Integer>();
        var authorizations = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
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
            var springModels = mock(ObjectProvider.class);
            var facade = new OpenAiCompatibleEmbeddingFacade(
                    "http://127.0.0.1:" + server.getAddress().getPort(), apiKey, "text-embedding-v4", 3,
                    java.time.Duration.ofSeconds(3), springModels);
            var vectors = facade.embedVectors(IntStream.range(0, count).mapToObj(index -> "row-" + index).toList());
            assertThat(batchSizes).containsExactly(10, 10, count - 20);
            assertThat(vectors).hasSize(count);
            for (int index = 0; index < count; index++) {
                assertThat(objectMapper.readTree(vectors.get(index).orElseThrow()).get(0).asInt()).isEqualTo(index + 1);
            }
            assertThat(authorizations).hasSize(3).allSatisfy(header ->
                    assertThat(header).isEqualTo(apiKey == null || apiKey.isBlank() ? null : "Bearer " + apiKey.strip()));
            verifyNoInteractions(springModels);
        } finally {
            server.stop(0);
        }
    }

    static Stream<Arguments> batchRequests() {
        return Stream.of(Arguments.of(21, "test-key"), Arguments.of(25, "test-key"),
                Arguments.of(21, null), Arguments.of(25, ""), Arguments.of(21, " \t "));
    }
}
