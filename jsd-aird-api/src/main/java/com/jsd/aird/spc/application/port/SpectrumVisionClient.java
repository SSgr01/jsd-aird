package com.jsd.aird.spc.application.port;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface SpectrumVisionClient {

    static String dataUri(String contentType, byte[] bytes) {
        var type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    boolean isConfigured();

    default VisionResult analyze(VisionRequest request) {
        return analyze(request, StreamObserver.NOOP);
    }

    VisionResult analyze(VisionRequest request, StreamObserver observer);

    interface StreamObserver {
        StreamObserver NOOP = new StreamObserver() { };

        default void onOutputTextDelta(String delta) { }
    }

    record VisionRequest(String prompt, List<VisionImage> images, String scenarioTemplate) { }

    record VisionImage(UUID chartId, String category, int pageNo, String dataUri) { }

    record VisionResult(JsonNode result, JsonNode rawResponse, String model, String promptVersion) { }
}
