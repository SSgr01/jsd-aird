package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.rnd.api.AgentExperimentFacade;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentExperimentService implements AgentExperimentFacade {
    private final ExperimentRepository repository;
    private final ExperimentDraftFacade drafts;
    private final AuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonCanonicalizer canonical;
    private final TemplateDataImportFacade templates;

    public AgentExperimentService(ExperimentRepository repository, ExperimentDraftFacade drafts,
            AuthorizationService authorization, JdbcTemplate jdbc, ObjectMapper json,
            JsonCanonicalizer canonical, TemplateDataImportFacade templates) {
        this.repository = repository; this.drafts = drafts; this.authorization = authorization;
        this.jdbc = jdbc; this.json = json; this.canonical = canonical; this.templates = templates;
    }

    private PermissionCheck check(UUID id) {
        var actor = ActorContext.required();
        return new PermissionCheck(actor.organizationId(), actor.userId(), "experiment.view", "EXPERIMENT", id, "READ");
    }

    @Override public boolean available() { return authorization.resolveScope(check(null)).allowed(); }

    private ExperimentModels.Detail detail(UUID id) {
        authorization.require(check(id));
        return repository.detail(ActorContext.required().organizationId(), id)
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private ExperimentModels.Version version(UUID id, UUID versionId) {
        detail(id);
        return repository.version(ActorContext.required().organizationId(), id, versionId)
            .orElseThrow(() -> new ApiException(ApiErrorCode.VERSION_CONFLICT, "实验版本不属于该实验或已不可用"));
    }

    @Override public ObjectNode read(String action, ObjectNode args) {
        var result = json.createObjectNode();
        var candidates = result.putArray("candidates");
        if ("search_experiments".equals(action)) {
            authorization.require(check(null));
            int offset = args.path("offset").asInt();
            var query = args.path("query").asText("").strip();
            if (offset < 0 || offset > 100000 || query.length() > 1000) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            var org = ActorContext.required().organizationId();
            boolean briefing = args.hasNonNull("task_type");
            var sql = new StringBuilder("SELECT " + (briefing ? "id,updated_at,revision" : "id") + " FROM rnd.experiment WHERE organization_id=? AND deleted=false "
                + "AND (?='' OR title ILIKE ? OR experiment_no ILIKE ?)");
            var parameters = new java.util.ArrayList<Object>(java.util.List.of(org, query, "%" + query + "%", "%" + query + "%"));
            for (var field : java.util.List.of("updated_from", "updated_before")) if (args.hasNonNull(field)) {
                sql.append(" AND updated_at" + ("updated_from".equals(field) ? ">=?" : "<?"));
                parameters.add(java.sql.Timestamp.from(Instant.parse(args.path(field).asText())));
            }
            if (args.path("project_ids").isArray() && !args.path("project_ids").isEmpty()) {
                if (args.path("project_ids").size() > 20) throw new ApiException(ApiErrorCode.BAD_REQUEST);
                sql.append(" AND project_id IN (" + String.join(",", java.util.Collections.nCopies(args.path("project_ids").size(), "?")) + ")");
                args.path("project_ids").forEach(id -> parameters.add(UUID.fromString(id.asText())));
            }
            sql.append(briefing ? " ORDER BY updated_at,id" : " ORDER BY updated_at DESC,id");
            java.util.List<UUID> ids;
            if (briefing) {
                var index = jdbc.queryForList(sql.toString(), parameters.toArray());
                ids = index.stream().map(row -> (UUID)row.get("id")).toList();
                result.put("index_revision", canonical.hash(json.valueToTree(index.stream()
                    .filter(row -> authorization.check(check((UUID)row.get("id"))).allowed()).toList())));
            } else ids = jdbc.queryForList(sql.toString(), UUID.class, parameters.toArray());
            UUID project = uuid(args, "project_id");
            var allowed = ids.stream().filter(id -> authorization.check(check(id)).allowed())
                .filter(id -> project == null || project.equals(detail(id).summary().projectId()))
                .skip(offset).limit(21).toList();
            for (var id : allowed.stream().limit(20).toList()) {
                var item = detail(id);
                candidates.add(candidate(id, version(id, item.currentVersionId()), item.summary().title(),
                    json.createObjectNode().put("section", "summary")));
            }
            if (allowed.size() > 20) result.put("next_offset", offset + 20);
            var categories = result.putArray("categories");
            for (var category : repository.categories(org, false)) categories.add(json.valueToTree(category));
            var options = result.putArray("draft_templates");
            for (var option : templates.listPublished(org)) {
                if (!option.experimentImportReady() || option.importContractVersion() != 9
                        || !"EXPERIMENT_DATA".equals(option.templateUsage())) continue;
                options.addObject().put("template_version_id", option.versionId().toString()).put("name", option.name())
                    .put("template_code", option.templateCode()).put("version_no", option.versionNo());
            }
        } else if ("draft_options".equals(action)) {
            authorization.require(check(null));
            result.set("categories", json.valueToTree(repository.categories(ActorContext.required().organizationId(), false)));
        } else if ("get_experiment_record".equals(action)) {
            var id = UUID.fromString(args.path("experiment_id").asText());
            var item = detail(id);
            var vid = uuid(args, "version_id");
            candidates.add(candidate(id, version(id, vid == null ? item.currentVersionId() : vid), item.summary().title(), args));
            result.put("path", "/experiments/" + id);
            result.set("reading", candidates.get(0).path("reading"));
            var batchSources=version(id,vid==null?item.currentVersionId():vid).editModel().path("batchSources");
            if(batchSources.isArray())result.set("batch_sources",batchSources.deepCopy());
        } else throw new ApiException(ApiErrorCode.BAD_REQUEST);
        result.putObject("trace").put("kind", "AUTHORIZED_EXPERIMENT_RECORDS").put("read_at", Instant.now().toString());
        return result;
    }

    private ObjectNode candidate(UUID id, ExperimentModels.Version version, String title, JsonNode query) {
        var projection = new AgentExperimentProjection(json);
        var content = projection.page(id, version, query);
        var summary = detail(id).summary();
        var relations = json.createObjectNode();
        var relationText = new StringBuilder("当前业务归属（当前记录关系，不代表历史版本归属；关联正文需另行授权读取）：\n");
        if (summary.projectId() == null) relationText.append("当前无所属项目。\n");
        else {
            relations.putObject("project").put("id", summary.projectId().toString()).put("name", summary.projectName());
            relationText.append("所属项目：").append(summary.projectName() == null ? "名称未填写" : summary.projectName()).append('\n');
        }
        if (summary.stageId() != null) {
            relations.putObject("stage").put("id", summary.stageId().toString()).put("name", summary.stageName());
            relationText.append("所属阶段：").append(summary.stageName() == null ? "名称未填写" : summary.stageName()).append('\n');
        }
        if (summary.taskId() != null) {
            relations.putObject("task").put("id", summary.taskId().toString()).put("name", summary.taskName());
            relationText.append("所属任务：").append(summary.taskName() == null ? "名称未填写" : summary.taskName()).append('\n');
        }
        content.set("current_relations", relations);
        String text = projection.text(canonical.canonicalize(content));
        var page = json.createObjectNode().put("section", query.path("section").asText("overview"))
            .put("offset", query.path("offset").asInt(0)).put("limit", query.path("limit").asInt(8));
        for (var name : List.of("sample_key", "field_codes")) if (query.hasNonNull(name)) page.set(name, query.get(name));
        var source = json.createObjectNode().put("kind", "BUSINESS_RECORD").put("resource_type", "EXPERIMENT")
            .put("resource_id", id.toString()).put("document_id", id.toString()).put("version_id", version.id().toString())
            .put("source_node_key", "EXPERIMENT:" + id + ":" + version.id() + ":" + canonical.hash(page)).put("title", title)
            .put("content_sha256", canonical.hashText(text));
        if (summary.projectId() != null) source.put("project_id", summary.projectId().toString());
        source.putObject("anchor").put("experimentId", id.toString()).put("versionId", version.id().toString())
            .put("versionNo", version.versionNo()).put("version_hash", canonical.hash(version.editModel())).set("page", page);
        var result = json.createObjectNode().put("text", text).put("model_text", relationText + projection.modelText(content));
        var reading = result.putObject("reading").put("section", content.path("section").asText()).put("offset", content.path("offset").asInt());
        for (var key : List.of("section_counts", "next_offset", "total", "complete")) if (content.has(key)) reading.set(key, content.get(key));
        result.set("source", source); return result;
    }

    @Override public void validateSource(JsonNode source, String excerpt) {
        if (!"BUSINESS_RECORD".equals(source.path("kind").asText())
                || !"EXPERIMENT".equals(source.path("resource_type").asText())) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var id = UUID.fromString(source.path("resource_id").asText());
        var vid = UUID.fromString(source.path("version_id").asText());
        var record = version(id, vid);
        var authoritative = candidate(id, record, detail(id).summary().title(), source.path("anchor").path("page"));
        var expected = authoritative.path("source");
        // Original native evidence still checks its exact page body and excerpt.
        if (!source.path("anchor").has("version_hash")) ((ObjectNode)expected.path("anchor")).remove("version_hash");
        for (var key : List.of("document_id", "source_node_key", "content_sha256", "anchor"))
            if (!expected.path(key).equals(source.path(key))) throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "实验来源版本或正文已改变");
        if (excerpt != null && !authoritative.path("text").asText().contains(excerpt))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "摘录与实验原记录不一致");
    }

    @Override @Transactional public ObjectNode createDraft(ObjectNode args) {
        return createDraft(args, null);
    }

    @Override @Transactional public ObjectNode createDraft(ObjectNode args, JsonNode plannedWorkbook) {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "experiment.create", "EXPERIMENT", null, "CREATE"));
        var key = args.path("operation_key").asText();
        if (!key.matches("[a-f0-9]{64}")) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var request = args.deepCopy(); request.remove("operation_key");
        var hash = canonical.hash(request);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,29))", Object.class,
            actor.organizationId() + ":" + actor.userId() + ":" + key);
        var previous = receipt(key);
        if ("SUCCEEDED".equals(previous.path("status").asText())) {
            if (!hash.equals(previous.path("request_hash").asText())) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return (ObjectNode) previous.path("result");
        }
        if ("FAILED".equals(previous.path("status").asText())) {
            if (!hash.equals(previous.path("request_hash").asText())) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "该调用已终止且未创建草稿，不能重放");
        }
        validateDraft(args);
        var category = requiredUuid(args, "category_id");
        UUID project = uuid(args, "project_id"), stage = uuid(args, "stage_id"), task = uuid(args, "task_id");
        hierarchy(project, stage, task);
        UUID templateId = uuid(args, "template_version_id");
        JsonNode snapshot = null; String snapshotHash = null;
        if (templateId != null) {
            var definition = templates.getPublished(actor.organizationId(), templateId);
            if (definition.importContractVersion() != 9 || !"EXPERIMENT_DATA".equals(definition.importContract().path("templateUsage").asText()))
                throw new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED);
            var template = templates.getExperimentTemplateVersion(actor.organizationId(), templateId);
            snapshot = template.snapshot(); snapshotHash = template.snapshotHash();
            if (!com.jsd.aird.shared.lab.LabWorkbook.supported(snapshot))
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "该模板尚不支持自动填入方案，请在实验页面创建草稿");
        }
        var editModel = json.createObjectNode().put("plan", args.path("edit_model").path("plan").asText());
        if (args.path("edit_model").has("purpose")) editModel.set("purpose", args.path("edit_model").get("purpose"));
        if (snapshot != null && com.jsd.aird.shared.lab.LabWorkbook.supported(snapshot)) {
            var working = plannedWorkbook == null
                ? com.jsd.aird.tpl.api.TemplateWorkbookValues.blankCopy(snapshot, templates.getBindings(actor.organizationId(), templateId))
                : com.jsd.aird.shared.lab.LabWorkbook.prepare(json, plannedWorkbook, snapshot, key);
            var sheet = com.jsd.aird.shared.lab.LabWorkbook.sheet(working);
            var fields = com.jsd.aird.shared.lab.LabWorkbook.definition(working).path("fields");
            var values = new LinkedHashMap<String, String>();
            values.put("basic.title", args.path("title").asText()); values.put("basic.owner", actor.username());
            values.put("basic.date", args.path("planned_date").asText());
            values.put("basic.purpose", editModel.path("purpose").asText());
            // A sample field's first anchor is its label, not an input cell.
            // Keep the shared proposal in the template's experiment-level input.
            var planField = "EXPERIMENT".equals(fields.path("plan.shared").path("scope").asText())
                ? "plan.shared" : "basic.background";
            values.put(planField, editModel.path("plan").asText());
            for (var entry : values.entrySet()) {
                var at = com.jsd.aird.shared.lab.LabWorkbook.findField(sheet, entry.getKey());
                if (at != null && "EXPERIMENT".equals(fields.path(entry.getKey()).path("scope").asText())
                        && "VALUE".equals(com.jsd.aird.shared.lab.LabWorkbook.cell(sheet, at[0], at[1])
                            .path("custom").path("lab").path("role").asText())) {
                    com.jsd.aird.shared.lab.LabWorkbook.cell(sheet, at[0], at[1]).put("v", entry.getValue());
                } else if (entry.getKey().equals(planField)) {
                    throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "该模板没有可填写的共用实验方案字段，请在实验页面创建草稿");
                }
            }
            editModel.set("documentSnapshot", working); editModel.put("documentFormat", "excel");
        }
        var created = drafts.createResearchDraft(new ExperimentDraftFacade.ResearchDraftCommand(
            args.path("title").asText(), category, project, stage, task, actor.username(),
            LocalDate.parse(args.path("planned_date").asText()), templateId, snapshotHash, snapshot, editModel));
        var result = json.createObjectNode().put("id", created.experimentId().toString())
            .put("version_id", created.experimentVersionId().toString()).put("experiment_no", created.experimentNo())
            .put("title", created.title()).put("status", "DRAFT").put("path", "/experiments/" + created.experimentId());
        var receipt = json.createObjectNode().put("operation_key", key).put("request_hash", hash);
        receipt.set("result", result);
        jdbc.update("INSERT INTO rnd.experiment_audit(id,organization_id,experiment_id,experiment_version_id,action,after_jsonb,operator_id,operator_name) "
            + "VALUES(?,?,?,?,'AGENT_DRAFT_CREATED',?::jsonb,?,?)", UUID.randomUUID(), actor.organizationId(),
            created.experimentId(), created.experimentVersionId(), receipt.toString(), actor.userId(), actor.username());
        return result;
    }

    void hierarchy(UUID project, UUID stage, UUID task) {
        var actor = ActorContext.required();
        if (project == null && (stage != null || task != null)) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "阶段和任务必须属于指定项目");
        if (project == null) return;
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "project.view", "PROJECT", project, "READ"));
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM mdm.project WHERE organization_id=? AND id=? AND deleted=false)", Boolean.class, actor.organizationId(), project)))
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        if (stage != null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM mdm.project_stage s JOIN mdm.project p ON p.id=s.project_id "
                + "WHERE p.organization_id=? AND s.id=? AND p.id=? AND s.deleted=false AND p.deleted=false)", Boolean.class, actor.organizationId(), stage, project)))
            throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);
        if (task != null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM mdm.project_task t "
                + "JOIN mdm.project_stage s ON s.id=t.stage_id AND s.project_id=t.project_id JOIN mdm.project p ON p.id=t.project_id "
                + "WHERE p.organization_id=? AND t.id=? AND p.id=? AND t.deleted=false AND s.deleted=false AND p.deleted=false "
                + "AND (?::uuid IS NULL OR t.stage_id=?))", Boolean.class, actor.organizationId(), task, project, stage, stage)))
            throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);
    }

    @Override @Transactional public ObjectNode receipt(String key, ObjectNode arguments) {
        return receipt(key, arguments, false);
    }

    @Override @Transactional public ObjectNode reconcileReceipt(String key, ObjectNode arguments) {
        return receipt(key, arguments, true);
    }

    private ObjectNode receipt(String key, ObjectNode arguments, boolean closeUncommitted) {
        var actor = ActorContext.required();
        if (key == null || !key.matches("[a-f0-9]{64}")) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,29))", Object.class,
            actor.organizationId() + ":" + actor.userId() + ":" + key);
        var rows = jdbc.queryForList("SELECT after_jsonb::text FROM rnd.experiment_audit WHERE organization_id=? AND operator_id=? "
            + "AND action='AGENT_DRAFT_CREATED' AND after_jsonb->>'operation_key'=?", String.class, actor.organizationId(), actor.userId(), key);
        if (rows.isEmpty()) {
            var closed = jdbc.queryForList("SELECT detail_jsonb::text FROM ops.audit_log WHERE organization_id=? AND actor_id=? "
                + "AND action='AGENT_DRAFT_CLOSED' AND aggregate_type='AGENT_OPERATION' AND detail_jsonb->>'operation_key'=?",
                String.class, actor.organizationId(), actor.userId(), key);
            if (!closed.isEmpty()) {
                try { return (ObjectNode) json.readTree(closed.getFirst()); }
                catch (java.io.IOException error) { throw new IllegalStateException(error); }
            }
            // The shared transaction lock waits for an accepted creation to commit or roll back.
            // Closing an uncommitted operation also fences a delayed original request; it is never replayed.
            if (closeUncommitted && arguments != null) {
                var request = arguments.deepCopy(); request.remove("operation_key");
                var failed = json.createObjectNode().put("status", "FAILED").put("operation_key", key)
                    .put("request_hash", canonical.hash(request));
                failed.putObject("result").put("id", key).put("error", "EXPERIMENT_DRAFT_NOT_COMMITTED")
                    .put("message", "权威事务未提交草稿；原调用已终止，延迟请求不能再次创建");
                var operationId = UUID.nameUUIDFromBytes((actor.organizationId() + ":" + actor.userId() + ":" + key)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jdbc.update("INSERT INTO ops.audit_log(id,organization_id,actor_id,action,aggregate_type,aggregate_id,detail_jsonb) "
                    + "VALUES(?,?,?,'AGENT_DRAFT_CLOSED','AGENT_OPERATION',?,?::jsonb)", UUID.randomUUID(),
                    actor.organizationId(), actor.userId(), operationId, failed.toString());
                return failed;
            }
            // An absent row alone proves nothing. A request that cannot pass the
            // pre-write validator is a definite failure after the operation lock.
            if (arguments != null) {
                try { validateDraft(arguments); }
                catch (ApiException error) {
                    var failed = json.createObjectNode().put("id", key).put("error", "EXPERIMENT_DRAFT_VALIDATION_FAILED")
                        .put("message", error.getMessage());
                    return json.createObjectNode().put("status", "FAILED").put("operation_key", key).set("result", failed);
                }
            }
            return json.createObjectNode().put("status", "UNKNOWN");
        }
        try {
            var receipt = (ObjectNode) json.readTree(rows.getFirst());
            detail(UUID.fromString(receipt.path("result").path("id").asText()));
            return receipt.put("status", "SUCCEEDED");
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    private static void validateDraft(JsonNode args) {
        if (args.path("title").asText().isBlank() || !args.path("edit_model").isObject())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "请提供实验名称、计划日期、实验分类及方案内容");
        requiredUuid(args, "category_id");
        for (var name : List.of("project_id", "stage_id", "task_id")) uuid(args, name);
        requiredUuid(args, "template_version_id");
        try { LocalDate.parse(args.path("planned_date").asText()); }
        catch (java.time.DateTimeException error) { throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "planned_date 必须为有效日期 YYYY-MM-DD"); }
        if (args.path("edit_model").path("plan").asText().isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "edit_model.plan 必须包含用户确认的实验方案，不能使用 scheme 等未知字段");
    }

    private static UUID requiredUuid(JsonNode args, String name) {
        var value = uuid(args, name);
        if (value == null) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, name + " 必填");
        return value;
    }

    private static UUID uuid(JsonNode args, String name) {
        if (!args.hasNonNull(name)) return null;
        var value = args.path(name);
        if (!value.isTextual() || !value.asText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, name + " 必须为 UUID；无关联时省略该字段或使用 null，不能传空字符串");
        return UUID.fromString(value.asText());
    }
}
