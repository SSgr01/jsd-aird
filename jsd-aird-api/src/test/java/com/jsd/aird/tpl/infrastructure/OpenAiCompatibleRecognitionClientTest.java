package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

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
            objectMapper,
            new JsonCanonicalizer(objectMapper),
            "https://example.test/v1",
            "test-key",
            "test-model",
            0.5
    );

    @Test
    void keepsOnlyValidXlsxSuggestions() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "suggestions": [
                    {
                      "fieldCode": "PROCESS.TEMPERATURE",
                      "fieldName": "反应温度",
                      "dataPath": "/process/temperature",
                      "valueType": "number",
                      "required": true,
                      "role": "FIELD",
                      "locatorType": "CELL_RANGE",
                      "locator": {"sheetId": "sheet-1", "sheetName": "测试", "address": "B6"},
                      "confidence": 0.91,
                      "evidence": [{"label": "反应温度"}],
                      "reason": "标签明确"
                    },
                    {
                      "fieldCode": "INVALID FIELD",
                      "fieldName": "无效字段",
                      "dataPath": "not-a-pointer",
                      "valueType": "string",
                      "locatorType": "CELL_RANGE",
                      "locator": {"address": "unknown"},
                      "confidence": 2
                    }
                  ]
                }
                """);

        var suggestions = client.parseSuggestions(response, TemplateFormat.XLSX);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.getFirst().payload().path("fieldCode").asText())
                .isEqualTo("PROCESS.TEMPERATURE");
        assertThat(suggestions.getFirst().confidence()).isEqualTo(0.91);
    }

    @Test
    void wordSuggestionRequiresAnExistingTextQuoteInsteadOfInventedMarker() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "suggestions": [
                    {
                      "fieldCode": "PRODUCT.NAME",
                      "fieldName": "产品名称",
                      "dataPath": "/product/name",
                      "valueType": "string",
                      "role": "FIELD",
                      "locatorType": "TEXT_QUOTE",
                      "locator": {"quote": "产品名称"},
                      "confidence": 0.8
                    },
                    {
                      "fieldCode": "PRODUCT.CODE",
                      "fieldName": "产品编码",
                      "dataPath": "/product/code",
                      "valueType": "string",
                      "role": "FIELD",
                      "locatorType": "CONTENT_CONTROL_TAG",
                      "locator": {"markerId": "invented"},
                      "confidence": 0.9
                    }
                  ]
                }
                """);

        assertThat(client.parseSuggestions(response, TemplateFormat.DOCX))
                .singleElement()
                .satisfies(suggestion -> assertThat(suggestion.payload().path("locatorType").asText())
                        .isEqualTo("TEXT_QUOTE"));
    }

    @Test
    void sendsAndAuditsTheSameSanitizedPayloadWithUsageMetrics() throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = """
                    {"choices":[{"message":{"content":"{\\"suggestions\\":[]}"}}],
                     "usage":{"prompt_tokens":120,"completion_tokens":8,"total_tokens":128}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var localClient = new OpenAiCompatibleRecognitionClient(
                    objectMapper, new JsonCanonicalizer(objectMapper),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "sk-should-never-be-audited", "qwen-plus", 0.5
            );
            var structure = objectMapper.readTree("""
                    {"structureVersion":4,"contact":"13812345678","sheets":[],
                     "region":{"regionId":"region-1","address":"A1:B2"},"regions":[]}
                    """);
            var batch = localClient.recognize(new RecognitionModelClient.RecognitionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), TemplateFormat.XLSX,
                    "测试.xlsx", "region-1", structure
            ));

            assertThat(captured.get()).doesNotContain("13812345678", "sk-should-never-be-audited")
                    .contains("[REDACTED_PHONE]");
            assertThat(objectMapper.readTree(captured.get())).isEqualTo(batch.callTrace().requestPayload());
            assertThat(batch.callTrace().promptTokens()).isEqualTo(120);
            assertThat(batch.callTrace().completionTokens()).isEqualTo(8);
            assertThat(batch.callTrace().totalTokens()).isEqualTo(128);
        } finally {
            server.stop(0);
        }
    }
}
