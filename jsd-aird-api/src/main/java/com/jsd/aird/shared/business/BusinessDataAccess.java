package com.jsd.aird.shared.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.shared.security.Actor;
import java.util.Set;
import java.util.UUID;

/** Integration boundary for the record-permission workstream. No default grant. */
public interface BusinessDataAccess {
    Set<String> resourceTypes();
    boolean allowed(Actor actor, String type, UUID id, String operation, JsonNode context);
}
