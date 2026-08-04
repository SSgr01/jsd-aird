package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    void rejectsUnknownEnumsExtraFieldsAndInventedCoordinates() throws Exception {
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
        assertThatThrownBy(() -> client.validateResponse(invented, facts))
                .hasMessageContaining("超出工作表使用范围");
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
