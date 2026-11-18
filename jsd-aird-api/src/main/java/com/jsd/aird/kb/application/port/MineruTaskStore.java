package com.jsd.aird.kb.application.port;

import java.util.Optional;
import java.util.UUID;

/** Durable checkpoints, including partitions, scoped to an immutable document version. */
public interface MineruTaskStore {
    record Task(String batchId, String uploadUrl, boolean uploaded, String state) { }
    Optional<Task> find(UUID organization, UUID version, String key);
    void save(UUID organization, UUID version, String key, Task task);
    Optional<Task> latest(UUID organization, UUID version);
    default Optional<Task> previousTimeout(UUID organization, UUID version, String ocrMode) { return Optional.empty(); }
}
