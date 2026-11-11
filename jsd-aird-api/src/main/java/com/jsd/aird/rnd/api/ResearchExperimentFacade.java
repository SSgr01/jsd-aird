package com.jsd.aird.rnd.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** RND owns version reads and all measured-value edits. Event reads are organization scoped. */
public interface ResearchExperimentFacade {
    void requireReadableVersion(UUID organizationId, UUID experimentId, UUID versionId);
    boolean isCurrentCompletedVersion(UUID organizationId, UUID experimentId, UUID versionId);
    ObjectNode record(UUID organizationId, UUID experimentId, UUID versionId);
    ObjectNode saveMeasurements(ObjectNode arguments);
    ObjectNode measurementReceipt(String operationKey);
}
