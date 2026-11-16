package com.jsd.aird.kb.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MetadataExtractionCache {
    record Entry(String status, JsonNode response, UUID owner, Instant updatedAt) { }
    Optional<Entry> find(UUID organization, String key);
    boolean claim(UUID organization, String key, UUID owner, String model, String policy);
    void succeed(UUID organization, String key, UUID owner, JsonNode response);
    void fail(UUID organization, String key, UUID owner, String status);
    /** Explicit retry only; active calls cannot be reset. */
    boolean reset(UUID organization, String key);
}
