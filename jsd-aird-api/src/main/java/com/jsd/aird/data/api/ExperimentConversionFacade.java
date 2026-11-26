package com.jsd.aird.data.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.api.FileStorageFacade;
import java.util.List;
import java.util.UUID;

/** Authorized preparation boundary; intentionally exposes no human confirmation operation. */
public interface ExperimentConversionFacade {
    List<ObjectNode> list(UUID batchId);
    ObjectNode get(UUID id,boolean lock);
    FileStorageFacade.StoredFile original(UUID id);
    ObjectNode stage(UUID id,UUID artifactId,UUID planId,UUID reportId,UUID fileId,JsonNode plan,JsonNode report,String resultSha);
    ObjectNode fail(UUID id,String reason);
    ObjectNode updateLaunchStatus(UUID id,String status,String expectedUpdatedAt,String reason,String requestId);
}
