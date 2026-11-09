package com.jsd.aird.ops.api;

import java.util.UUID;

/** Business owners can restrict all generic download, preview and presigned-file paths. */
public interface FileReadPolicy {
    void requireRead(UUID organizationId, UUID actorId, UUID fileId);
}
