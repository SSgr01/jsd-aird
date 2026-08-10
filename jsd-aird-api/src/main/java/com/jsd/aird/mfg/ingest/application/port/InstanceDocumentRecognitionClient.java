package com.jsd.aird.mfg.ingest.application.port;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Dedicated instance-value visual protocol. It is intentionally unrelated to template recognition. */
public interface InstanceDocumentRecognitionClient {

    boolean isConfigured();

    Result recognize(JsonNode schema, JsonNode mapping, List<ImageSource> images);

    record ImageSource(String contentType, byte[] content) {
    }

    record Result(JsonNode data, List<ValueItem> items, String model) {
    }

    record ValueItem(
            String itemKey,
            String itemKind,
            String bindingId,
            String fieldCode,
            String dataPath,
            String recordKey,
            Integer recordIndex,
            JsonNode rawValue,
            JsonNode normalizedValue,
            JsonNode sourceLocator,
            double confidence,
            boolean handwritten
    ) {
    }
}
