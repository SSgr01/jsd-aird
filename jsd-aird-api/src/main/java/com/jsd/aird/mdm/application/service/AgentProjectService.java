package com.jsd.aird.mdm.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentProjectService implements AgentProjectFacade {
    private final ProjectService projects;
    private final ProjectStageService stages;
    private final ProjectWorkService work;
    private final AuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentProjectService(ProjectService projects, ProjectStageService stages, ProjectWorkService work,
            AuthorizationService authorization, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.projects = projects; this.stages = stages; this.work = work;
        this.authorization = authorization; this.jdbc = jdbc; this.mapper = mapper;
    }

    @Override public boolean available() {
        return authorization.resolveScope(check(null)).allowed();
    }

    private PermissionCheck check(UUID id) {
        var actor = ActorContext.required();
        return new PermissionCheck(actor.organizationId(), actor.userId(), "project.view", "PROJECT", id, "READ");
    }

    private UUID project(ObjectNode args) {
        var id = UUID.fromString(args.path("project_id").asText());
        var selected = new HashSet<String>();
        for (var ref : args.path("context_refs")) if ("PROJECT".equals(ref.path("kind").asText())) selected.add(ref.path("resource_id").asText());
        if (!selected.isEmpty() && !selected.contains(id.toString())) throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);
        authorization.require(check(id));
        projects.get(id); // Includes organization and soft-deletion checks.
        return id;
    }

    @Override public ObjectNode read(String action, ObjectNode args) {
        if (!available()) throw new ApiException(ApiErrorCode.PERMISSION_DENIED);
        int offset = args.path("offset").asInt();
        if (offset < 0 || offset > 100000) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var result = mapper.createObjectNode();
        var candidates = result.putArray("candidates");
        if ("search_projects".equals(action)) {
            var query = args.path("query").asText("").strip();
            if (query.length() > 1000) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            var actor = ActorContext.required();
            // Fetch identifiers only, then authorize before pagination or loading record contents.
            // No forbidden titles, total counts or pre-authorization ranking leave this boundary.
            var ids = jdbc.queryForList("SELECT id FROM mdm.project WHERE organization_id=? AND deleted=false "
                + "AND (?='' OR name ILIKE ? OR project_code ILIKE ?) ORDER BY updated_at DESC,id", UUID.class,
                actor.organizationId(), query, "%" + query + "%", "%" + query + "%");
            var selected = new HashSet<UUID>();
            for (var ref : args.path("context_refs")) if ("PROJECT".equals(ref.path("kind").asText())) selected.add(UUID.fromString(ref.path("resource_id").asText()));
            var allowed = ids.stream().filter(id -> selected.isEmpty() || selected.contains(id))
                .filter(id -> authorization.check(check(id)).allowed()).skip(offset).limit(21).toList();
            for (var id : allowed.stream().limit(20).toList()) {
                var value = projects.get(id);
                candidates.add(candidate(id, "PROJECT", id, value.version(), value.name(), mapper.valueToTree(value)));
            }
            if (allowed.size() > 20) result.put("next_offset", offset + 20);
        } else {
            var pid = project(args);
            switch (action) {
                case "get_project_snapshot" -> {
                    var value = projects.get(pid);
                    candidates.add(candidate(pid, "PROJECT", pid, value.version(), value.name(), mapper.valueToTree(value)));
                }
                case "list_project_stages" -> {
                    var values = stages.listByProject(pid);
                    for (var value : values.stream().skip(offset).limit(20).toList())
                        candidates.add(candidate(pid, "STAGE", value.id(), value.version(), value.name(), mapper.valueToTree(value)));
                    if (values.size() > offset + 20) result.put("next_offset", offset + 20);
                }
                case "list_project_tasks" -> {
                    if (offset % 20 != 0) throw new ApiException(ApiErrorCode.BAD_REQUEST);
                    var values = work.searchTasks(new ProjectTaskQuery(null, pid.toString(), null, null, null, null, offset / 20 + 1, 20));
                    for (var value : values)
                        candidates.add(candidate(pid, "TASK", value.id(), value.version(), value.name(), mapper.valueToTree(value)));
                    if (values.size() == 20) result.put("next_offset", offset + 20);
                }
                case "get_task_detail" -> {
                    var value = work.getTask(UUID.fromString(args.path("task_id").asText()));
                    if (!pid.equals(value.projectId())) throw new ApiException(ApiErrorCode.NOT_FOUND);
                    candidates.add(candidate(pid, "TASK", value.id(), value.version(), value.name(), mapper.valueToTree(value)));
                }
                default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "项目能力只接受已声明的只读操作");
            }
        }
        result.putObject("trace").put("kind", "AUTHORIZED_PROJECT_RECORDS").put("read_at", Instant.now().toString());
        return result;
    }

    private ObjectNode candidate(UUID pid, String type, UUID id, long version, String title, JsonNode value) {
        String text = value.toString();
        var source = mapper.createObjectNode().put("kind", "BUSINESS_RECORD").put("resource_type", type)
            .put("resource_id", id.toString()).put("project_id", pid.toString()).put("document_id", pid.toString())
            .put("version_id", Long.toString(version)).put("source_node_key", type + ":" + id)
            .put("title", title).put("content_sha256", sha(text));
        source.putObject("anchor").put("projectId", pid.toString()).put("recordId", id.toString()).put("recordType", type).put("recordVersion", version);
        return mapper.createObjectNode().put("text", text).set("source", source);
    }

    @Override public void authorizeSource(JsonNode source) {
        var pid = UUID.fromString(source.path("project_id").asText());
        authorization.require(check(pid));
        projects.get(pid);
        var id = UUID.fromString(source.path("resource_id").asText());
        switch (source.path("resource_type").asText()) {
            case "PROJECT" -> { if (!pid.equals(id)) throw new ApiException(ApiErrorCode.NOT_FOUND); }
            case "STAGE" -> { if (!pid.equals(stages.get(id).projectId())) throw new ApiException(ApiErrorCode.NOT_FOUND); }
            case "TASK" -> { if (!pid.equals(work.getTask(id).projectId())) throw new ApiException(ApiErrorCode.NOT_FOUND); }
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
        }
    }

    private String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
