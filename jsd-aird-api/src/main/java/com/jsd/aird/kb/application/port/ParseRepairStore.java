package com.jsd.aird.kb.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Repair candidates never change the active review revision or publication. */
public interface ParseRepairStore {
    record Repair(UUID id, UUID organizationId, UUID documentId, UUID versionId, UUID requestedBy,
                  String requestKey, String status, UUID jobId, UUID parseRunId, JsonNode result, String error,
                  UUID basePublicationId, UUID baseRevisionId, Integer baseLockVersion) { }
    Repair reserve(UUID org, UUID actor, UUID document, UUID version, String requestKey);
    Optional<Repair> find(UUID org, UUID id);
    void queued(UUID org, UUID id, UUID job);
    void running(UUID org, UUID id);
    void ready(UUID org, UUID id, UUID parseRun, JsonNode result);
    void failed(UUID org, UUID id, String error);
    void adopted(UUID org, UUID id);
    boolean lockUnchanged(Repair repair);
    Optional<JsonNode> previousResult(UUID org, UUID version);
}
