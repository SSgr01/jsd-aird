package com.jsd.aird.tpl.api;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
public interface TemplateWordSource {
 record SnapshotSource(JsonNode inlineSnapshot, UUID snapshotFileId) {}
 Optional<SnapshotSource> snapshotSource(UUID organizationId, UUID versionId);
 Optional<JsonNode> wordDocumentSource(UUID organizationId, UUID versionId);
}
