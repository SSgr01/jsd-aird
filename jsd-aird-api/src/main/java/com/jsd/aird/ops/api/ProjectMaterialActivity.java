package com.jsd.aird.ops.api;

import java.util.UUID;

/** Retain the project locator when a child record is removed. */
public interface ProjectMaterialActivity {
    void record(String operation,String type,UUID id,UUID projectId,String name);
}
