package com.jsd.aird.tpl.application;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Component;

/**
 * Version 6 intentionally has no rule-based business-field recognizer.
 * Physical rules may produce generic facts and quality diagnostics, but only
 * the validated workbook-global semantic response may create field candidates.
 */
@Component
public class RuleBasedRecognitionEngine {

    private final ObjectMapper objectMapper;
    private final JsonCanonicalizer canonicalizer;

    public RuleBasedRecognitionEngine(ObjectMapper objectMapper, JsonCanonicalizer canonicalizer) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    public RecognitionModelClient.RecognitionBatch recognize(
            TemplateFormat format, String sourceFileName, JsonNode structure
    ) {
        if (format == TemplateFormat.XLSX && structure.path("structureVersion").asInt() != 6) {
            throw new IllegalArgumentException("Excel structureVersion 必须为 6");
        }
        var fingerprint = canonicalizer.hash(structure);
        return new RecognitionModelClient.RecognitionBatch(
                List.of(), List.of(), "physical-facts", "no-business-rules-v6",
                "physical-facts-v6", fingerprint,
                canonicalizer.hashText(sourceFileName + "|" + fingerprint), null
        );
    }
}
