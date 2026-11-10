package com.jsd.aird.mfg.upload.application.port;
import com.fasterxml.jackson.databind.JsonNode;
public interface DocxWorkbookConverter {
 record ConvertedWorksheet(JsonNode snapshot, JsonNode summary) {}
 ConvertedWorksheet convert(byte[] docx, String documentName);
}
