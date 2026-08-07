package com.jsd.aird.tpl.application.port;

import com.fasterxml.jackson.databind.node.ArrayNode;

public interface WordOoxmlPatcher {
    byte[] apply(byte[] source, ArrayNode operations);
}
