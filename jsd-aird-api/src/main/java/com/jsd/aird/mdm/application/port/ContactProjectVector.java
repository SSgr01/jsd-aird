package com.jsd.aird.mdm.application.port;

import java.util.UUID;

public record ContactProjectVector(UUID partnerId, UUID contactId, String contactName,
                                   UUID projectId, String projectCode, String projectName,
                                   String projectOwner, String projectStatus,
                                   String currentStageName, Double progress) {
}
