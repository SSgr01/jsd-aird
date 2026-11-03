package com.jsd.aird.ai.application;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
public final class PrivateDocumentNavigation {
    private PrivateDocumentNavigation() {}
    public static ObjectNode outline(ObjectMapper mapper,JsonNode nodes,int offset) {
        return com.jsd.aird.kb.api.ResearchDocumentOutline.outline(mapper,nodes,offset);
    }
}
