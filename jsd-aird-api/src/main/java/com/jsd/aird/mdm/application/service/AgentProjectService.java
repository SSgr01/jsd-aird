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
    private final CustomerRequirementService requirements;
    private final MaterialService materials;

    public AgentProjectService(ProjectService projects, ProjectStageService stages, ProjectWorkService work,
            AuthorizationService authorization, JdbcTemplate jdbc, ObjectMapper mapper,
            CustomerRequirementService requirements, MaterialService materials) {
        this.projects = projects; this.stages = stages; this.work = work;
        this.authorization = authorization; this.jdbc = jdbc; this.mapper = mapper;
        this.requirements = requirements; this.materials = materials;
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
            var allowed = ids.stream()
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
                    // Search summaries contain joined display names; evidence must use
                    // the same authoritative task shape as its later validation read.
                    for (var value : values) {
                        var task = work.getTask(value.id());
                        if (!pid.equals(task.projectId())) throw new ApiException(ApiErrorCode.NOT_FOUND);
                        candidates.add(candidate(pid, "TASK", task.id(), task.version(), task.name(), mapper.valueToTree(task)));
                    }
                    if (values.size() == 20) result.put("next_offset", offset + 20);
                }
                case "get_task_detail" -> {
                    var value = work.getTask(UUID.fromString(args.path("task_id").asText()));
                    if (!pid.equals(value.projectId())) throw new ApiException(ApiErrorCode.NOT_FOUND);
                    candidates.add(candidate(pid, "TASK", value.id(), value.version(), value.name(), mapper.valueToTree(value)));
                }
                case "get_project_records" -> {
                    var type = args.path("record_type").asText();
                    var ids = relatedIds(pid, type);
                    if (args.hasNonNull("record_id")) {
                        var id = UUID.fromString(args.path("record_id").asText());
                        if (!ids.contains(id)) throw new ApiException(ApiErrorCode.NOT_FOUND);
                        candidates.add(relatedCandidate(pid, type, id));
                    } else {
                        for (var id : ids.stream().skip(offset).limit(20).toList())
                            candidates.add(relatedCandidate(pid, type, id));
                        if (ids.size() > offset + 20) result.put("next_offset", offset + 20);
                    }
                    result.put("relation", "EXPLICIT_PROJECT_LINK");
                }
                default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "项目能力只接受已声明的只读操作");
            }
        }
        result.putObject("trace").put("kind", "AUTHORIZED_PROJECT_RECORDS").put("read_at", Instant.now().toString());
        return result;
    }

    private List<UUID> relatedIds(UUID pid, String type) {
        return switch (type) {
            case "REQUIREMENT" -> jdbc.queryForList("SELECT id FROM mdm.customer_requirement WHERE deleted=false "
                + "AND (project_id=? OR assigned_project_ids @> jsonb_build_array(CAST(? AS text))) ORDER BY updated_at DESC,id",
                UUID.class, pid, pid.toString());
            case "MATERIAL" -> jdbc.queryForList("SELECT m.id FROM mdm.project_material pm JOIN mdm.material m ON m.id=pm.material_id "
                + "WHERE pm.project_id=? ORDER BY pm.created_at DESC,pm.id", UUID.class, pid);
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "record_type 只能是 REQUIREMENT 或 MATERIAL");
        };
    }

    private ObjectNode relatedCandidate(UUID pid, String type, UUID id) {
        if ("REQUIREMENT".equals(type)) {
            var value = requirements.requirement(id);
            return candidate(pid, type, id, value.version(), value.title(), mapper.valueToTree(value));
        }
        var value = materials.get(id); // Real stored material; never the UI's demonstration list.
        return candidate(pid, type, id, value.version(), value.name(), mapper.valueToTree(value));
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
        var type = source.path("resource_type").asText();
        long version = switch (type) {
            case "PROJECT" -> { if (!pid.equals(id)) throw new ApiException(ApiErrorCode.NOT_FOUND); yield projects.get(id).version(); }
            case "STAGE" -> { var value = stages.get(id); if (!pid.equals(value.projectId())) throw new ApiException(ApiErrorCode.NOT_FOUND); yield value.version(); }
            case "TASK" -> { var value = work.getTask(id); if (!pid.equals(value.projectId())) throw new ApiException(ApiErrorCode.NOT_FOUND); yield value.version(); }
            case "REQUIREMENT", "MATERIAL" -> {
                if (!relatedIds(pid, type).contains(id)) throw new ApiException(ApiErrorCode.NOT_FOUND);
                yield "REQUIREMENT".equals(type) ? requirements.requirement(id).version() : materials.get(id).version();
            }
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
        };
        // Related experiment counts and available UI actions can change without
        // changing this record's version. Citation validation still checks the
        // complete body hash and excerpt against a fresh authoritative read.
        if (!Long.toString(version).equals(source.path("version_id").asText())
                || !pid.toString().equals(source.path("document_id").asText())
                || !(type + ":" + id).equals(source.path("source_node_key").asText()))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
    }

    @Override public void validateSource(JsonNode source, String excerpt) {
        authorizeSource(source);
        var args = mapper.createObjectNode().put("project_id", source.path("project_id").asText());
        String action;
        switch (source.path("resource_type").asText()) {
            case "PROJECT" -> action = "get_project_snapshot";
            case "TASK" -> { action = "get_task_detail"; args.put("task_id", source.path("resource_id").asText()); }
            case "REQUIREMENT", "MATERIAL" -> {
                action = "get_project_records";
                args.put("record_type", source.path("resource_type").asText()).put("record_id", source.path("resource_id").asText());
            }
            case "STAGE" -> {
                var stage = stages.get(UUID.fromString(source.path("resource_id").asText()));
                var authoritative = candidate(UUID.fromString(source.path("project_id").asText()), "STAGE", stage.id(), stage.version(), stage.name(), mapper.valueToTree(stage));
                validateSnapshot(source, excerpt, authoritative); return;
            }
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
        }
        validateSnapshot(source, excerpt, read(action, args).path("candidates").path(0));
    }

    private void validateSnapshot(JsonNode source, String excerpt, JsonNode candidate) {
        for (var field : List.of("version_id", "source_node_key", "content_sha256", "anchor"))
            // JSON and JSONB preserve the value, not Jackson's IntNode/LongNode
            // representation. Keep every identity field strict while comparing
            // integral version values across the transport boundary.
            if (!source.path(field).equals((left, right) -> left.isIntegralNumber() && right.isIntegralNumber()
                    ? left.bigIntegerValue().compareTo(right.bigIntegerValue()) : left.equals(right) ? 0 : 1,
                    candidate.path("source").path(field))) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        if (excerpt != null && !candidate.path("text").asText().contains(excerpt)) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
    }

    private String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
