package com.jsd.aird.ai.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade.QueryTask;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EmbeddingQueryInstructionsTest {
    @ParameterizedTest
    @ValueSource(strings = {"none", "generic", "materials"})
    void wrapsOnlyQueriesAndUsesSeparateManualInstructionAcrossHttpBatches(String profile) throws Exception {
        var mapper = new ObjectMapper();
        var inputs = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            var input = mapper.readTree(exchange.getRequestBody()).path("input");
            var values = input.isArray() ? input : mapper.createArrayNode().add(input);
            var response = mapper.createObjectNode();
            var data = response.putArray("data");
            for (int i = 0; i < values.size(); i++) {
                inputs.add(values.get(i).asText());
                data.addObject().put("index", i).putArray("embedding").add(1).add(0);
            }
            var bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var policy = new EmbeddingQueryInstructions(profile, "eval-v1", "", "");
            var facade = new OpenAiCompatibleEmbeddingFacade("http://127.0.0.1:" + server.getAddress().getPort(),
                    "", "qwen3", 2, Duration.ofSeconds(3), mock(ObjectProvider.class));
            facade.queryInstructions(policy);
            assertThat(facade.embedVector("文档原文")).isPresent();
            assertThat(facade.embedVectors(List.of("文档一", "文档二"))).allMatch(java.util.Optional::isPresent);
            var queries = IntStream.range(0, 11).mapToObj(i -> "树脂性能 " + i).toList();
            assertThat(facade.embedQueryVectors(queries, QueryTask.KNOWLEDGE)).hasSize(11).allMatch(java.util.Optional::isPresent);
            assertThat(facade.embedQueryVector("怎样导入文件", QueryTask.OPERATION_MANUAL)).isPresent();
            assertThat(inputs.subList(0, 3)).containsExactly("文档原文", "文档一", "文档二");
            assertThat(inputs.subList(3, 14)).containsExactlyElementsOf(queries.stream()
                    .map(q -> policy.format(q, QueryTask.KNOWLEDGE)).toList());
            assertThat(inputs.getLast()).isEqualTo(policy.format("怎样导入文件", QueryTask.OPERATION_MANUAL));
            if (!"none".equals(profile)) {
                assertThat(inputs.get(3)).startsWith("Instruct: ").contains("\nQuery:树脂性能 0");
                assertThat(inputs.getLast()).contains("operating instructions", "\nQuery:怎样导入文件")
                        .doesNotContain("materials research");
            }
            assertThat(queries.getFirst()).isEqualTo("树脂性能 0");
            assertThat(facade.queryPolicy()).isEqualTo(policy.describe());
        } finally { server.stop(0); }
    }

    @Test void customInstructionsAndBlankQueriesHaveExplicitBehavior() {
        var policy = new EmbeddingQueryInstructions("materials", "custom-v2", "Find evidence", "Find steps");
        assertThat(policy.format("问题", QueryTask.KNOWLEDGE)).isEqualTo("Instruct: Find evidence\nQuery:问题");
        assertThat(policy.format("问题", QueryTask.OPERATION_MANUAL)).isEqualTo("Instruct: Find steps\nQuery:问题");
        assertThat(policy.format("  ", QueryTask.KNOWLEDGE)).isEqualTo("  ");
        assertThat(policy.format(null, QueryTask.KNOWLEDGE)).isNull();
        assertThat(new EmbeddingQueryInstructions("none", "", "custom", "custom").format("问题", QueryTask.KNOWLEDGE))
                .isEqualTo("问题");
        assertThatThrownBy(() -> new EmbeddingQueryInstructions("typo", "", "", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
