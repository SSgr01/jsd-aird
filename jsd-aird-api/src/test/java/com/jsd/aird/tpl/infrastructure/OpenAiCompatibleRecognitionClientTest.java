package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleRecognitionClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsRemovedGlobalSemanticPhase() {
        var client = new OpenAiCompatibleRecognitionClient(
                objectMapper, new JsonCanonicalizer(objectMapper),
                "https://example.test/v1", "test-key", "test-model", 0.0);

        assertThatThrownBy(() -> client.recognize(new RecognitionModelClient.RecognitionRequest(
                UUID.randomUUID(), null, TemplateFormat.XLSX, "模板.xlsx", "legacy",
                physicalFacts(), null, "REGION_INFERENCE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持旧的全局语义识别阶段");
    }

    @Test
    void sendsTheStrictStructureProtocolWithoutLegacyEnvelope() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var response = modelResponse(objectMapper.readTree(
                    "{\"recognitionProtocolVersion\":2,\"proposals\":[],\"qualityIssues\":[]}"));
            var bytes = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiCompatibleRecognitionClient(
                    objectMapper, new JsonCanonicalizer(objectMapper),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key", "test-model", 0.0);
            var batch = client.recognize(new RecognitionModelClient.RecognitionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), TemplateFormat.XLSX,
                    "模板.xlsx", "workbook-structure", physicalFacts(), null,
                    "STRUCTURE_DISCOVERY"));
            assertThat(batch.suggestions()).extracting(RecognitionModelClient.ModelSuggestion::suggestionType)
                    .containsExactly("SEMANTIC_MODEL");
            assertThat(batch.promptVersion()).isEqualTo("structure-three-region-v3");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsOnlyCandidateSemanticBatchProtocol() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var response = modelResponse(objectMapper.readTree(
                    "{\"recognitionProtocolVersion\":3,\"regions\":[],\"qualityIssues\":[]}"));
            var bytes = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiCompatibleRecognitionClient(
                    objectMapper, new JsonCanonicalizer(objectMapper),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key", "test-model", 0.0);
            var context = physicalFacts();
            context.putArray("semanticRegions");
            var batch = client.recognize(new RecognitionModelClient.RecognitionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), TemplateFormat.XLSX,
                    "模板.xlsx", "workbook-regions", context, null, "REGION_FIELDS"));
            assertThat(batch.suggestions()).extracting(RecognitionModelClient.ModelSuggestion::suggestionType)
                    .containsExactly("SEMANTIC_MODEL");
            assertThat(batch.promptVersion()).isEqualTo("region-semantics-three-region-v3");
        } finally {
            server.stop(0);
        }
    }

    private ObjectNode modelResponse(JsonNode content) {
        var response = objectMapper.createObjectNode();
        response.set("choices", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .set("message", objectMapper.createObjectNode()
                        .put("content", content.toString()))));
        response.set("usage", objectMapper.createObjectNode()
                .put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2));
        return response;
    }

    private ObjectNode physicalFacts() {
        var facts = objectMapper.createObjectNode().put("structureVersion", 6);
        facts.putArray("sheets").addObject().put("id", "sheet-1")
                .put("name", "测试").put("usedRange", "A1:L30");
        return facts;
    }
}
