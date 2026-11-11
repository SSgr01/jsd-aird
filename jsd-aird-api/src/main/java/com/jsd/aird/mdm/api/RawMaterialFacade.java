package com.jsd.aird.mdm.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Permission-checked chemical context for formula and research services. */
public interface RawMaterialFacade {
    JsonNode snapshot(JsonNode columns);
    void authorizeSnapshot(JsonNode snapshot);
}
