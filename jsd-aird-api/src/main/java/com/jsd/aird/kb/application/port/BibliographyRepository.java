package com.jsd.aird.kb.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BibliographyRepository {
    record Record(UUID documentId, UUID versionId, int revision, String status, String documentType,
                  JsonNode accepted, JsonNode provenance, JsonNode candidates, JsonNode manualFields,
                  UUID parseRunId, String sourceFingerprint, String message) { }
    record Target(UUID documentId, UUID versionId) { }
    record Task(UUID id, UUID organizationId, UUID ownerId, Instant createdAt) { }
    record Item(UUID id, UUID taskId, UUID documentId, UUID versionId, String status,
                int generation, int failureCount, Instant retryAt, String errorCode) { }
    record Source(UUID parseRunId, String fingerprint, List<Page> pages) { }
    record Page(String text, JsonNode anchor, String nodeKey, String nodeType) { }
    Optional<Record> find(UUID org, UUID version, Integer revision);
    boolean save(UUID org, UUID actor, Record value, int expectedRevision);
    List<Target> targets(UUID org, UUID category, List<UUID> documents, UUID after, Instant before, int limit);
    Source source(UUID org, UUID document, UUID version);
    void lockVersion(UUID org, UUID document, UUID version);
    JsonNode vectorStatus(UUID org, UUID document, UUID version);
    Task createTask(UUID org, UUID owner, String requestKey, String scopeFingerprint);
    List<Task> tasks(UUID org, UUID owner, int limit);
    Optional<Task> task(UUID org, UUID owner, UUID id);
    Item addItem(UUID task, Target target);
    Optional<Item> item(UUID task, UUID item);
    List<Item> items(UUID task, UUID after, int limit);
    boolean updateItem(UUID item, int generation, String status, String code, Instant retryAt, boolean failure);
    Item retryItem(UUID item, int generation);
}
