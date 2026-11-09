package com.jsd.aird.ai.api;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Versioned evidence transport; identity comes from the host, never request JSON. */
public interface RetrievalCapability {
    ObjectNode retrieve(ObjectNode request);
}
