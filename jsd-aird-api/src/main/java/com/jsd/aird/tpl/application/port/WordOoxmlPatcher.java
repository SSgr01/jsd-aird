package com.jsd.aird.tpl.application.port;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;

public interface WordOoxmlPatcher {
    byte[] apply(byte[] source, ArrayNode operations);

    default byte[] applySnapshot(byte[] source, JsonNode snapshot) {
        return source;
    }
}
