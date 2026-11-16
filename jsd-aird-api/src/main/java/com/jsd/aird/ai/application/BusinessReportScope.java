package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.shared.business.BusinessRecordView;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A background grant narrows the existing record permission; it never grants one. */
@Service
public class BusinessReportScope {
    private final JdbcTemplate jdbc;
    public BusinessReportScope(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean contains(JsonNode projects,BusinessRecordView row){
        var allowed=new HashSet<String>();projects.forEach(pid->allowed.add(UUID.fromString(pid.asText()).toString()));
        if(allowed.isEmpty())return false;
        String direct=row.facts().path("projectId").asText(row.ref().path("project_id").asText(""));
        if(allowed.contains(direct))return true;
        for(var project:row.facts().path("projectIds"))if(allowed.contains(project.asText()))return true;
        String resource=switch(row.ref().path("type").asText()){case "DATA_IMPORT"->"DATA_IMPORT_JOB";case "SPECTRUM"->"SPECTRUM_CHART";default->null;};
        if(resource==null)return false;
        var targets=jdbc.queryForList("SELECT project_id FROM core.project_reference WHERE organization_id=? AND resource_type=? AND resource_id=? AND status='ACTIVE'",
            UUID.class,ActorContext.required().organizationId(),resource,UUID.fromString(row.ref().path("id").asText()));
        return targets.stream().anyMatch(pid->allowed.contains(pid.toString()));
    }
}
