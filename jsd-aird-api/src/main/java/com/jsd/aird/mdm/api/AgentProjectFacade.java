package com.jsd.aird.mdm.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Host-owned, read-only AI projection. No persistence models cross the tool transport. */
public interface AgentProjectFacade {
    boolean available();
    ObjectNode read(String action, ObjectNode arguments);
    void authorizeSource(JsonNode source);
}
