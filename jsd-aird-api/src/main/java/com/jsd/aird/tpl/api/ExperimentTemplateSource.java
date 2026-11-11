package com.jsd.aird.tpl.api;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
/** Persisted experiment content required when copying a template. */
public interface ExperimentTemplateSource {
 Optional<Snapshot> templateSource(UUID organizationId, UUID experimentId);
 record SourceSummary(UUID id, String experimentNo, String title, boolean voided) {}
 record Snapshot(SourceSummary summary, JsonNode editModel, JsonNode templateSnapshot, UUID templateVersionId, UUID sourceFileId) {}
}
