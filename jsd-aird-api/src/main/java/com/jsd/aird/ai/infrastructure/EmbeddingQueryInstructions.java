package com.jsd.aird.ai.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.api.EvidenceProtocol;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade.QueryTask;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** One versioned query policy; documents remain unwrapped and use the same model. */
@Component
public final class EmbeddingQueryInstructions {
    private static final JsonNode PRESETS = presets();
    private final String profile, version, knowledge, manual;

    public EmbeddingQueryInstructions(
            @Value("${app.ai.embedding.query.profile:none}") String profile,
            @Value("${app.ai.embedding.query.version:}") String version,
            @Value("${app.ai.embedding.query.knowledge-instruction:}") String knowledge,
            @Value("${app.ai.embedding.query.manual-instruction:}") String manual) {
        this.profile = profile == null ? "none" : profile.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("none", "generic", "materials").contains(this.profile))
            throw new IllegalArgumentException("Embedding query profile must be none, generic or materials");
        this.version = value(version, PRESETS.path("version").asText());
        this.knowledge = value(knowledge, PRESETS.path("materials".equals(this.profile) ? "materials" : "generic").asText());
        this.manual = value(manual, PRESETS.path("operation_manual").asText());
    }

    public static EmbeddingQueryInstructions plain() { return new EmbeddingQueryInstructions("none", "", "", ""); }

    public String format(String query, QueryTask task) {
        if (query == null || query.isBlank() || "none".equals(profile)) return query;
        var instruction = task == QueryTask.OPERATION_MANUAL ? manual : knowledge;
        return PRESETS.path("format").asText().replace("{instruction}", instruction).replace("{query}", query);
    }

    public Map<String, Object> describe() {
        return Map.of("profile", profile, "version", version,
                "knowledge_sha256", EvidenceProtocol.sha(knowledge), "manual_sha256", EvidenceProtocol.sha(manual),
                "format_sha256", EvidenceProtocol.sha(PRESETS.path("format").asText()));
    }

    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.strip(); }

    private static JsonNode presets() {
        try (var source = new ClassPathResource("ai/embedding-query-presets.json").getInputStream()) {
            return new ObjectMapper().readTree(source);
        } catch (IOException error) { throw new IllegalStateException("Embedding query presets are unavailable", error); }
    }
}
