package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.rnd.api.AgentExperimentFacade;
import com.jsd.aird.shared.business.BusinessViews;
import com.jsd.aird.shared.error.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Reuses the existing authorized project, experiment and retrieval paths. */
@Service
public class BusinessExistingData {
    public static final Set<String> TYPES = Set.of("PROJECT", "STAGE", "TASK", "REQUIREMENT", "MATERIAL", "EXPERIMENT", "STRUCTURED_DATA");
    private final AgentProjectFacade projects;
    private final AgentExperimentFacade experiments;
    private final SharedRetrievalService retrieval;
    private final ObjectMapper json;
    public BusinessExistingData(AgentProjectFacade projects, AgentExperimentFacade experiments, SharedRetrievalService retrieval, ObjectMapper json) {
        this.projects = projects; this.experiments = experiments; this.retrieval = retrieval; this.json = json;
    }
    public boolean available(String type) {
        return "EXPERIMENT".equals(type) ? experiments.available() : "STRUCTURED_DATA".equals(type) || projects.available();
    }
    public Set<String> filters(String type) {
        return switch (type) {
            case "PROJECT" -> Set.of("query", "customer_id");
            case "EXPERIMENT" -> Set.of("query", "project_id");
            case "STRUCTURED_DATA" -> Set.of("query", "file_name", "record_terms", "field_terms", "mode");
            default -> Set.of("project_id");
        };
    }
    public Set<String> requiredFilters(String type) { return Set.of("STAGE", "TASK", "REQUIREMENT", "MATERIAL").contains(type) ? Set.of("project_id") : Set.of(); }
    public ObjectNode invoke(String action, ObjectNode args) {
        String type = args.path("type").asText(args.path("ref").path("type").asText());
        if (!Set.of("business_search", "business_read").contains(action)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "该类型请使用查找和完整读取");
        var filters = BusinessDataService.filters(args);
        if (args.path("include_file").asBoolean()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "此类型请使用已有原生附件或资料原文件入口");
        filters.fieldNames().forEachRemaining(field -> {
            if (!filters(type).contains(field)) throw BusinessDataService.invalidFilter(
                filters(type), requiredFilters(type), field, "unsupported_filter", "不支持该筛选字段：" + field);
        });
        if ("business_read".equals(action)) return execute(action, args);
        for (var field : requiredFilters(type)) if (!filters.hasNonNull(field)) throw BusinessDataService.invalidFilter(
            filters(type), requiredFilters(type), field, "required", "缺少必要筛选字段：" + field);
        int offset = BusinessDataService.offset(args), limit = BusinessDataService.limit(args);
        if ("STRUCTURED_DATA".equals(type)) {
            var full = execute(action, args); var selected = json.createArrayNode(); var all = full.path("candidates");
            for (int i = offset; i < Math.min(all.size(), offset + limit); i++) selected.add(all.path(i));
            full.set("candidates", selected); if (all.size() > offset + limit) full.put("next_offset", offset + limit);
            return full;
        }
        // Existing domain APIs page by 20. Translate once, including offsets within a page.
        var selected = json.createArrayNode(); ObjectNode response = null; int pageOffset = offset / 20 * 20;
        while (true) {
            var request = args.deepCopy().put("offset", pageOffset); request.remove("limit");
            var page = execute(action, request); if (response == null) response = page.deepCopy();
            var all = page.path("candidates");
            for (int i = Math.max(0, offset - pageOffset); i < all.size() && selected.size() < limit; i++) selected.add(all.path(i));
            if (selected.size() == limit) {
                if (offset + limit < pageOffset + all.size() || page.has("next_offset")) response.put("next_offset", offset + limit);
                else response.remove("next_offset");
                break;
            }
            if (!page.has("next_offset")) { response.remove("next_offset"); break; }
            int next = page.path("next_offset").asInt();
            if (next <= pageOffset) throw new IllegalStateException("Non-progressing business page");
            pageOffset = next;
        }
        response.set("candidates", selected); return response;
    }
    private ObjectNode execute(String action, ObjectNode args) {
        var ref = args.path("ref").isObject() ? (ObjectNode)args.path("ref") : json.createObjectNode();
        String type = args.path("type").asText(ref.path("type").asText());
        var options = args.deepCopy(); options.remove(List.of("ref", "type", "filters"));
        if (args.path("filters").isObject()) options.setAll((ObjectNode)args.path("filters"));
        boolean search = "business_search".equals(action);
        if ("STRUCTURED_DATA".equals(type)) {
            if (!search) {
                options.put("file_name", ref.path("file_name").asText());
                options.putArray("record_terms").add(ref.path("record_key").asText());
                if (ref.hasNonNull("field")) options.putArray("field_terms").add(ref.path("field").asText());
            } else if (options.hasNonNull("query") && !options.path("query").asText().isBlank()) {
                if (!options.path("record_terms").isArray()) options.putArray("record_terms");
                ((com.fasterxml.jackson.databind.node.ArrayNode)options.path("record_terms")).add(options.path("query").asText());
            }
            var result = retrieval.structured(options); var selected = json.createArrayNode();
            for (var candidate : result.path("candidates")) {
                var source = candidate.path("source");
                if (!search && (!ref.path("source_node_key").equals(source.path("source_node_key"))
                        || !ref.path("id").equals(source.path("resource_id")))) continue;
                if (!search && (!ref.path("revision").equals(source.path("version_id"))
                        || !ref.path("content_hash").equals(source.path("content_sha256")))) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                var reference = json.createObjectNode().put("type", "STRUCTURED_DATA").put("id", source.path("resource_id").asText())
                    .put("revision", source.path("version_id").asText()).put("source_node_key", source.path("source_node_key").asText())
                    .put("file_name", source.path("title").asText()).put("record_key", candidate.path("record_key").asText())
                    .put("content_hash", source.path("content_sha256").asText());
                if (!candidate.path("field").asText().isBlank()) reference.set("field", candidate.path("field"));
                ((ObjectNode)candidate).set("ref", reference); selected.add(candidate);
            }
            if (!search && selected.isEmpty()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "原结构化值已变化或不可读取，请重新查询");
            result.set("candidates", selected); return result;
        }
        if ("EXPERIMENT".equals(type)) {
            if (!search) {
                options.put("experiment_id", BusinessViews.id(ref).toString());
                String version = ref.path("version_id").asText(ref.path("revision").asText(""));
                if (!version.isBlank()) options.put("version_id", version);
            }
            var result = experiments.read(search ? "search_experiments" : "get_experiment_record", options);
            if (!search && ref.hasNonNull("version_hash")) for (var item : result.path("candidates"))
                if (!ref.path("version_hash").equals(item.path("source").path("anchor").path("version_hash")))
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "实验草稿正文已改变，请重新读取活动索引");
            return decorate(result, type);
        }
        String operation;
        if (search && "PROJECT".equals(type)) operation = "search_projects";
        else {
            String project = ref.path("project_id").asText(options.path("project_id").asText(""));
            if ("PROJECT".equals(type)) project = BusinessViews.id(ref).toString();
            options.put("project_id", project);
            operation = switch (type) {
                case "PROJECT" -> "get_project_snapshot";
                case "STAGE" -> "list_project_stages";
                case "TASK" -> search ? "list_project_tasks" : "get_task_detail";
                case "REQUIREMENT", "MATERIAL" -> "get_project_records";
                default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
            };
            if ("get_task_detail".equals(operation)) options.put("task_id", BusinessViews.id(ref).toString());
            if (Set.of("REQUIREMENT", "MATERIAL").contains(type)) {
                options.put("record_type", type); if (!search) options.put("record_id", BusinessViews.id(ref).toString());
            }
        }
        var result = projects.read(operation, options);
        if (!search && "STAGE".equals(type)) {
            var found = json.createArrayNode(); int offset = options.path("offset").asInt();
            while (true) {
                for (var item : result.path("candidates")) if (ref.path("id").asText().equals(item.path("source").path("resource_id").asText())) found.add(item);
                if (!found.isEmpty() || !result.has("next_offset")) break;
                offset = result.path("next_offset").asInt(); if (offset > 5000) throw new ApiException(ApiErrorCode.BAD_REQUEST, "请缩小阶段范围");
                result = projects.read(operation, options.put("offset", offset));
            }
            result.set("candidates", found); result.remove("next_offset");
            if (found.isEmpty()) throw BusinessViews.missing();
        }
        if (!search && ref.hasNonNull("revision")) for (var item : result.path("candidates"))
            if (!ref.path("revision").asText().equals(item.path("source").path("version_id").asText())) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        if (!search && ref.hasNonNull("content_hash")) for (var item : result.path("candidates"))
            if (!ref.path("content_hash").asText().equals(item.path("source").path("content_sha256").asText()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        return decorate(result, type);
    }
    private ObjectNode decorate(ObjectNode result, String type) {
        for (var candidate : result.path("candidates")) {
            var source = candidate.path("source");
            var reference = json.createObjectNode().put("type", type).put("id", source.path("resource_id").asText())
                .put("revision", source.path("version_id").asText());
            if (source.hasNonNull("project_id")) reference.set("project_id", source.get("project_id"));
            if ("EXPERIMENT".equals(type)) {
                reference.set("version_id", source.path("version_id"));
                if (source.path("anchor").hasNonNull("version_hash")) reference.set("version_hash", source.path("anchor").get("version_hash"));
            }
            ((ObjectNode)candidate).set("ref", reference);
        }
        return result;
    }
}
