package com.jsd.aird.tpl.api;
import com.fasterxml.jackson.databind.JsonNode;
public interface RuleRecognition {
 RecognitionModelClient.RecognitionBatch recognize(TemplateFormat format, String sourceFileName, JsonNode structure);
}
