package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleRecognitionClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiCompatibleRecognitionClient client = new OpenAiCompatibleRecognitionClient(
            objectMapper, new JsonCanonicalizer(objectMapper),
            "https://example.test/v1", "test-key", "test-model", 1.5
    );

    @Test
    void rejectsInvalidRootAndBlocksButIsolatesAnInventedFieldCoordinate() throws Exception {
        var facts = physicalFacts();
        var unknownEnum = validResponse().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknownEnum.path("businessBlocks").get(0))
                .put("type", "FIELD_GROUP");
        assertThatThrownBy(() -> client.validateResponse(unknownEnum, facts))
                .isInstanceOf(GlobalSemanticRecognitionProtocol.ProtocolViolationException.class)
                .hasMessageContaining("未知枚举");

        var extra = validResponse().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) extra).put("fieldCode", "MODEL.MUST.NOT.SET");
        assertThatThrownBy(() -> client.validateResponse(extra, facts))
                .hasMessageContaining("协议未定义字段");

        var invented = validResponse().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invented.path("fieldRelations").get(0))
                .put("valueRange", "Z99");
        var recovered = client.validateResponse(invented, facts);
        assertThat(recovered.path("fieldRelations")).isEmpty();
        assertThat(recovered.path("qualityIssues")).singleElement().satisfies(issue ->
                assertThat(issue.path("category").asText()).isEqualTo("FIELD_RELATION_UNCLEAR"));
    }

    @Test
    void repairsMissingRelationBlockOnlyWhenOneBlockContainsBothRanges() throws Exception {
        var response = validResponse().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("fieldRelations").get(0))
                .put("blockTemporaryId", "missing-block");

        var validated = client.validateResponse(response, physicalFacts());

        assertThat(validated.path("fieldRelations").get(0).path("blockTemporaryId").asText())
                .isEqualTo("b1");
        assertThat(response.path("fieldRelations").get(0).path("blockTemporaryId").asText())
                .isEqualTo("missing-block");
    }

    @Test
    void isolatesAFieldRelationThatOverlapsItsOwnLabelAndValue() throws Exception {
        var response = validResponse().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("fieldRelations").get(0))
                .put("valueRange", "A5");

        var recovered = client.validateResponse(response, physicalFacts());
        assertThat(recovered.path("fieldRelations")).isEmpty();
        assertThat(recovered.path("qualityIssues")).hasSize(1);
    }

    @Test
    void isolatesAFieldRelationInsideAnInstructionBlock() throws Exception {
        var response = validResponse().deepCopy();
        var block = (com.fasterxml.jackson.databind.node.ObjectNode) response.path("businessBlocks").get(0);
        block.put("type", "INSTRUCTION_LIST");

        var recovered = client.validateResponse(response, physicalFacts());
        assertThat(recovered.path("fieldRelations")).isEmpty();
        assertThat(recovered.path("businessBlocks")).hasSize(1);
    }

    @Test
    void sendsJsonSchemaAtZeroTemperatureAndAuditsSanitizedUsage() throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var content = objectMapper.writeValueAsString(validResponse());
            var response = objectMapper.createObjectNode();
            response.set("choices", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                    .set("message", objectMapper.createObjectNode().put("content", content))));
            response.set("usage", objectMapper.createObjectNode()
                    .put("prompt_tokens", 120).put("completion_tokens", 80).put("total_tokens", 200));
            var bytes = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var localClient = new OpenAiCompatibleRecognitionClient(
                    objectMapper, new JsonCanonicalizer(objectMapper),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "sk-should-never-be-audited", "qwen-plus", 1.9
            );
            var facts = physicalFacts();
            ((com.fasterxml.jackson.databind.node.ObjectNode) facts).put("contact", "13812345678");
            var batch = localClient.recognize(new RecognitionModelClient.RecognitionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), TemplateFormat.XLSX,
                    "测试.xlsx", "workbook-global", facts
            ));

            var sent = objectMapper.readTree(captured.get());
            assertThat(sent.path("temperature").asDouble()).isZero();
            assertThat(sent.path("enable_thinking").asBoolean()).isFalse();
            assertThat(sent.path("max_tokens").asInt()).isEqualTo(12000);
            assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_schema");
            var schema = sent.path("response_format").path("json_schema").path("schema");
            for (var definition : java.util.List.of(
                    "annotation", "block", "relation", "column", "table", "issue"
            )) {
                assertThat(schema.path("$defs").path(definition).path("required").size())
                        .isEqualTo(schema.path("$defs").path(definition).path("properties").size());
            }
            assertThat(captured.get()).doesNotContain("13812345678", "sk-should-never-be-audited")
                    .contains("[REDACTED_PHONE]");
            assertThat(batch.suggestions()).extracting(RecognitionModelClient.ModelSuggestion::suggestionType)
                    .contains("SEMANTIC_MODEL", "SCALAR_FIELD");
            assertThat(batch.callTrace().totalTokens()).isEqualTo(200);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnEmptySemanticResultWhenPhysicalFactsContainValues() throws Exception {
        var facts = physicalFacts();
        facts.putArray("semanticCells").addObject()
                .put("factType", "VALUE").put("address", "A1").put("value", "产品名称");
        var empty = objectMapper.readTree("""
                {"recognitionProtocolVersion":1,"semanticAnnotations":[],"businessBlocks":[],
                 "fieldRelations":[],"tables":[],"qualityIssues":[]}
                """);

        assertThatThrownBy(() -> new SemanticResultValidator().validateMeaningfulResult(empty, facts))
                .isInstanceOf(SemanticResultValidator.EmptySemanticResultException.class)
                .hasMessageContaining("空语义结果");
    }

    @Test
    void auditsHttp200EmptySemanticResultAsFailedCall() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var response = modelResponse(objectMapper.readTree("""
                    {"recognitionProtocolVersion":1,"semanticAnnotations":[],"businessBlocks":[],
                     "fieldRelations":[],"tables":[],"qualityIssues":[]}
                    """), 46767, 4914, 51681);
            var bytes = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var localClient = new OpenAiCompatibleRecognitionClient(
                    objectMapper, new JsonCanonicalizer(objectMapper),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key", "qwen3.7-max", 0.0
            );
            var facts = physicalFacts();
            facts.putArray("semanticCells").addObject().put("factType", "VALUE").put("address", "A1");
            assertThatThrownBy(() -> localClient.recognize(new RecognitionModelClient.RecognitionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), TemplateFormat.XLSX,
                    "测试.xlsx", "workbook-global", facts
            ))).isInstanceOf(RecognitionModelClient.RecognitionCallException.class)
                    .satisfies(error -> {
                        var exception = (RecognitionModelClient.RecognitionCallException) error;
                        assertThat(exception.traces()).singleElement().satisfies(trace -> {
                            assertThat(trace.status()).isEqualTo("FAILED");
                            assertThat(trace.httpStatus()).isEqualTo(200);
                            assertThat(trace.errorType()).isEqualTo("EMPTY_SEMANTIC_RESULT");
                            assertThat(trace.responsePayload().path("usage").path("prompt_tokens").asInt())
                                    .isEqualTo(46767);
                        });
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void locallyRemovesABrokenOptionalAnnotationReferenceWithoutASecondCall() throws Exception {
        var requests = new ArrayList<String>();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var semantic = validResponse();
            var call = calls.incrementAndGet();
            if (call == 1) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) semantic.path("semanticAnnotations").get(0))
                        .put("temporaryBlockRef", "missing-block");
            }
            var response = modelResponse(semantic, call == 1 ? 91 : 120,
                    call == 1 ? 42 : 80, call == 1 ? 133 : 200);
            var bytes = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var localClient = new OpenAiCompatibleRecognitionClient(
                    objectMapper, new JsonCanonicalizer(objectMapper),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key", "qwen-plus", 0.0
            );

            var batch = localClient.recognize(new RecognitionModelClient.RecognitionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), TemplateFormat.XLSX,
                    "测试.xlsx", "workbook-global", physicalFacts()
            ));

            assertThat(calls).hasValue(1);
            assertThat(requests).hasSize(1);
            assertThat(batch.callTraces()).singleElement().satisfies(trace -> {
                assertThat(trace.status()).isEqualTo("SUCCEEDED");
                assertThat(trace.phase()).isEqualTo("REGION_INFERENCE");
                assertThat(trace.totalTokens()).isEqualTo(133);
                assertThat(trace.responsePayload().path("choices")).isNotEmpty();
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void isolatesAFieldOutsideItsBusinessBlockWithoutASecondCall() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var semantic = validResponse();
            if (calls.incrementAndGet() == 1) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) semantic.path("businessBlocks").get(0))
                        .put("range", "A1:A4");
            }
            var bytes = objectMapper.writeValueAsBytes(modelResponse(semantic, 90, 40, 130));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var localClient = new OpenAiCompatibleRecognitionClient(
                    objectMapper, new JsonCanonicalizer(objectMapper),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key", "qwen-plus", 0.0
            );

            var batch = localClient.recognize(new RecognitionModelClient.RecognitionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), TemplateFormat.XLSX,
                    "测试.xlsx", "workbook-global", physicalFacts()
            ));

            assertThat(calls).hasValue(1);
            assertThat(batch.callTraces()).singleElement().satisfies(trace -> {
                assertThat(trace.status()).isEqualTo("SUCCEEDED");
                assertThat(trace.phase()).isEqualTo("REGION_INFERENCE");
            });
            assertThat(batch.suggestions()).extracting(RecognitionModelClient.ModelSuggestion::suggestionType)
                    .containsExactly("SEMANTIC_MODEL");
            assertThat(batch.qualityIssues()).singleElement().satisfies(issue ->
                    assertThat(issue.issueType()).isEqualTo("FIELD_RELATION_UNCLEAR"));
        } finally {
            server.stop(0);
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode modelResponse(
            JsonNode semantic, int promptTokens, int completionTokens, int totalTokens
    ) throws IOException {
        var content = objectMapper.writeValueAsString(semantic);
        var response = objectMapper.createObjectNode();
        response.set("choices", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .set("message", objectMapper.createObjectNode().put("content", content))));
        response.set("usage", objectMapper.createObjectNode()
                .put("prompt_tokens", promptTokens)
                .put("completion_tokens", completionTokens)
                .put("total_tokens", totalTokens));
        return response;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode physicalFacts() throws IOException {
        return (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"structureVersion":6,"sheets":[{"id":"sheet-1","name":"生产单","usedRange":"A1:L30"}]}
                """);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode validResponse() throws IOException {
        return (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":1,
                  "semanticAnnotations":[
                    {"sheetId":"sheet-1","range":"A1:L2","role":"DOCUMENT_TITLE","temporaryBlockRef":"b1"},
                    {"sheetId":"sheet-1","range":"A5","role":"FIELD_LABEL","temporaryRelationRef":"r1","temporaryBlockRef":"b1"},
                    {"sheetId":"sheet-1","range":"B5:D5","role":"FIELD_VALUE","temporaryRelationRef":"r1","temporaryBlockRef":"b1"}
                  ],
                  "businessBlocks":[
                    {"temporaryId":"b1","sheetId":"sheet-1","range":"A1:L6","type":"FORM_FIELDS","businessName":"基础信息","groupNameSuggestion":"基础信息"}
                  ],
                  "fieldRelations":[
                    {"temporaryId":"r1","sheetId":"sheet-1","labelRange":"A5","valueRange":"B5:D5","relationType":"LABEL_VALUE","businessName":"产品名称","blockTemporaryId":"b1","groupNameSuggestion":"基础信息","valueType":"string","required":true,"editability":"EDITABLE","valueSource":"USER_INPUT"}
                  ],
                  "tables":[],
                  "qualityIssues":[]
                }
                """);
    }
}
