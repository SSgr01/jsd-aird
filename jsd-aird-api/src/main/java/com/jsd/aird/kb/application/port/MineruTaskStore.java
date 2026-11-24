package com.jsd.aird.kb.application.port;

import java.util.Optional;
import java.util.UUID;

/** Durable checkpoints, including partitions, scoped to an immutable document version. */
public interface MineruTaskStore {
    record Task(String batchId, String uploadUrl, boolean uploaded, String state,
                String provider, String endpointFingerprint, String remoteFileId,
                java.util.Map<String, String> uploadHeaders) {
        public Task(String batchId, String uploadUrl, boolean uploaded, String state) {
            this(batchId, uploadUrl, uploaded, state, "cloud", null, null, java.util.Map.of());
        }
        public Task {
            uploadHeaders = uploadHeaders == null ? java.util.Map.of() : java.util.Map.copyOf(uploadHeaders);
        }
    }
    Optional<Task> find(UUID organization, UUID version, String key);
    void save(UUID organization, UUID version, String key, Task task);
    Optional<Task> latest(UUID organization, UUID version);
    default Optional<Task> previousTimeout(UUID organization, UUID version, String ocrMode) { return Optional.empty(); }
}
