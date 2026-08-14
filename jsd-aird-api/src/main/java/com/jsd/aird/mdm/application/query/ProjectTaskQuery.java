package com.jsd.aird.mdm.application.query;

public record ProjectTaskQuery(
    String keyword,
    String projectId,
    String stageId,
    String status,
    String owner,
    String priority,
    Integer page,
    Integer size
) {
}
