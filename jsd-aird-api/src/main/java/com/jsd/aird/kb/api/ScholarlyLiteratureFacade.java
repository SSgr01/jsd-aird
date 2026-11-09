package com.jsd.aird.kb.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Public scholarly discovery; metadata is never a substitute for document evidence. */
public interface ScholarlyLiteratureFacade {
    JsonNode lookup(JsonNode arguments);
    JsonNode search(JsonNode arguments);
    JsonNode expand(JsonNode arguments);
    FullText download(String workId);

    record FullText(String name, byte[] content, JsonNode provenance) { }

    /** Bounded provider failure, safe to show without request headers or upstream bodies. */
    final class Unavailable extends RuntimeException {
        private final String code;
        private final long retryAfter;
        public Unavailable(String code, long retryAfter) {
            super(code);
            this.code = code;
            this.retryAfter = retryAfter;
        }
        public String code() { return code; }
        public long retryAfter() { return retryAfter; }
    }
}
