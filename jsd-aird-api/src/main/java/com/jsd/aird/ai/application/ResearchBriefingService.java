package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.rnd.api.AgentExperimentFacade;
import com.jsd.aird.shared.error.*;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Authorized activity indexes. Domain facades own queries and resource authorization. */
@Service
public class ResearchBriefingService {
    private final AgentProjectFacade projects;
    private final AgentExperimentFacade experiments;
    private final ObjectMapper json;

    public ResearchBriefingService(AgentProjectFacade projects, AgentExperimentFacade experiments, ObjectMapper json) {
        this.projects = projects; this.experiments = experiments; this.json = json;
    }

    public ObjectNode context(ObjectNode args) {
        String kind = args.path("task_type").asText(), section = args.path("section").asText("PROJECT");
        if (!Set.of("project-morning", "research-weekly", "experiment-review").contains(kind)
                || !Set.of("PROJECT", "STAGE", "TASK", "EXPERIMENT").contains(section))
            throw new ApiException(ApiErrorCode.BAD_REQUEST);
        Instant end = Instant.parse(args.path("window_end").asText());
        Instant start = args.hasNonNull("window_start") ? Instant.parse(args.path("window_start").asText()) : null;
        if (end.isAfter(Instant.now().plusSeconds(30)) || start != null && !start.isBefore(end))
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "活动时间范围无效");
        var request = args.deepCopy().put("record_type", section).put("updated_before", end.toString());
        if (!"project-morning".equals(kind) && start != null) request.put("updated_from", start.toString());
        var result = "EXPERIMENT".equals(section) ? experiments.read("search_experiments", request)
            : projects.read("briefing_records", request);
        result.remove(java.util.List.of("categories", "draft_templates"));
        for (var value : result.path("candidates")) {
            var source = value.path("source");
            var ref = json.createObjectNode().put("type", section).put("id", source.path("resource_id").asText())
                .put("revision", source.path("version_id").asText()).put("content_hash", source.path("content_sha256").asText());
            if (source.hasNonNull("project_id")) ref.set("project_id", source.get("project_id"));
            if ("EXPERIMENT".equals(section)) {
                ref.set("version_id", source.path("version_id"));
                if (source.path("anchor").hasNonNull("version_hash")) ref.set("version_hash", source.path("anchor").get("version_hash"));
            }
            ((ObjectNode)value).set("ref", ref);
        }
        result.put("section", section);
        result.put("window_end", end.toString());
        if (start != null) result.put("window_start", start.toString());
        result.put("activity_basis", "research-weekly".equals(kind) ? "本周实际更新时间；未保存前态时不推断具体变化" : "当前记录和实际计划日期");
        return result;
    }
}
