package com.jsd.aird.kb.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public interface MetadataExtractionModel {
    boolean enabled();
    boolean privateEndpoint();
    String policyHash();
    String model();
    JsonNode extract(JsonNode sources, UUID requestId);
}
