package com.jsd.aird.rnd.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Authorized, version-pinned experiment reads and idempotent draft creation. */
public interface AgentExperimentFacade {
    boolean available();
    ObjectNode read(String action, ObjectNode arguments);
    ObjectNode createDraft(ObjectNode arguments);
    ObjectNode createDraft(ObjectNode arguments, JsonNode plannedWorkbook);
    ObjectNode receipt(String operationKey, ObjectNode arguments);
    ObjectNode reconcileReceipt(String operationKey, ObjectNode arguments);
    default ObjectNode receipt(String operationKey) { return receipt(operationKey, null); }
    void validateSource(JsonNode source, String excerpt);
}
