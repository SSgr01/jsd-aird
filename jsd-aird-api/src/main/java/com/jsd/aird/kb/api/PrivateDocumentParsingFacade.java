package com.jsd.aird.kb.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.UUID;

/** Reuses governed parsers without publishing or indexing a conversation-private file. */
public interface PrivateDocumentParsingFacade {
    JsonNode parse(UUID organizationId, UUID actorId, UUID fileId, String name, String contentType,
                   long size, InputStream content);
}
