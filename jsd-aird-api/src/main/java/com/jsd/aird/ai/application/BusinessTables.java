package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.error.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.jsd.aird.shared.business.BusinessViews.*;

/** Bounded typed queries, not a SQL language or another data-analysis runtime. */
final class BusinessTables {
    private BusinessTables() { }
    static ObjectNode query(BusinessDataService service, ObjectMapper json, String type, ObjectNode args, List<BusinessRecordView> records) {
        if (args.has("metrics") && !args.path("metrics").isArray()) throw bad("metrics必须是统计列表");
        if (!Set.of("rows", "file").contains(args.path("output").asText("rows"))) throw bad("不支持该输出方式");
        var dataset = json.createArrayNode();
        var identities = json.createArrayNode();
        for (var record : records) {
            dataset.add(record.facts());
            identities.add(json.createObjectNode().set("ref", record.ref()));
        }
        var allFields = new LinkedHashSet<String>(); dataset.forEach(row -> flattenKeys(row, "", allFields));
        var fields = strings(args.path("fields"));
        if (fields.isEmpty()) fields.addAll(allFields);
        validateFields(fields, allFields);
        var selected = json.createArrayNode();
        for (int i = 0; i < dataset.size(); i++) {
            var values = json.createObjectNode(); for (var field : fields) values.set(field, at(dataset.path(i), field));
            values.set("ref", records.get(i).ref()); selected.add(values);
        }
        var groups = strings(args.path("group_by")); validateFields(groups, allFields);
        JsonNode table;
        if (args.path("metrics").isArray() && !args.path("metrics").isEmpty()) table = aggregate(json, dataset, groups, args.path("metrics"), allFields);
        else {
            if (!groups.isEmpty()) throw bad("group_by 需要同时给出统计指标");
            var page = json.createArrayNode();
            for (int i = BusinessDataService.offset(args); i < Math.min(selected.size(), BusinessDataService.offset(args) + BusinessDataService.limit(args)); i++) page.add(selected.get(i));
            table = page;
        }
        boolean file = "file".equals(args.path("output").asText("rows"));
        var schemaRows = file && args.path("metrics").isEmpty() ? selected : table;
        var columns = json.createArrayNode();
        var tableFields = new LinkedHashSet<String>(); schemaRows.forEach(row -> row.fieldNames().forEachRemaining(tableFields::add));
        if (tableFields.isEmpty()) tableFields.addAll(fields);
        for (var field : tableFields) {
            var kinds = new LinkedHashSet<String>();
            for (var row : schemaRows) if (!row.path(field).isNull()) kinds.add(row.path(field).getNodeType().name());
            var column = json.createObjectNode().put("field", field).put("label", label(field)); column.set("types", json.valueToTree(kinds)); columns.add(column);
        }
        var root = json.createObjectNode().put("type", type).put("total", records.size());
        var schema = json.createObjectNode(); schema.set("columns", columns);
        String snapshot = hash(clean(dataset).toString() + clean(identities));
        root.put("snapshot_hash", snapshot);
        var proof = json.createObjectNode().put("type", type).put("snapshot_hash", snapshot);
        if (file) {
            for (var record : records) service.require(type, id(record.ref()), "EXPORT", record.ref());
            var payload = json.createObjectNode(); payload.set("schema", schema); payload.set("rows", args.path("metrics").isEmpty() ? selected : table);
            String content = payload.toString(); byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 8 * 1024 * 1024) throw bad("分析数据集超过8MB，请缩小查询范围");
            var attachment = root.putObject("dataset").put("name", "business-" + hash(content).substring(0, 20) + ".json")
                .put("sha256", hash(content)).put("bytes", bytes.length).put("content_type", "application/json")
                .put("content_base64", Base64.getEncoder().encodeToString(bytes));
            proof.set("schema", schema); proof.set("rows", payload.path("rows"));
            attachment.put("row_count", payload.path("rows").size());
        } else {
            root.set("schema", schema); root.set("rows", table); proof.set("rows", table);
            if (args.path("metrics").isEmpty() && records.size() > BusinessDataService.offset(args) + BusinessDataService.limit(args))
                root.put("next_offset", BusinessDataService.offset(args) + BusinessDataService.limit(args));
        }
        String text = markdown("授权业务查询", proof);
        if (text.length() > 1_000_000) throw bad("来源快照过大，请分批获取分析数据集");
        String queryKey = hash(clean(args).toString());
        String queryId = UUID.nameUUIDFromBytes(queryKey.getBytes(StandardCharsets.UTF_8)).toString();
        var source = json.createObjectNode().put("kind", "BUSINESS_RECORD").put("resource_type", "BUSINESS_QUERY")
            .put("resource_id", queryId).put("document_id", queryId).put("version_id", snapshot)
            .put("source_node_key", "BUSINESS_QUERY:" + queryKey).put("title", "授权业务查询 · " + type).put("content_sha256", hash(text));
        var anchor = source.putObject("anchor").put("business_adapter", "v1"); anchor.set("query", clean(args));
        anchor.put("read_path", "/api/v1/agents/business/query?q=" + java.net.URLEncoder.encode(clean(args).toString(), StandardCharsets.UTF_8) + "&snapshot_hash=" + snapshot);
        if (!records.isEmpty()) anchor.put("path", records.getFirst().path());
        var candidate = json.createObjectNode().put("text", text).put("model_text", "已读取 " + records.size() + " 条授权业务记录；数值、字段类型及结果见本次查询表格或原生分析文件。");
        candidate.set("source", source); root.putArray("candidates").add(candidate);
        return service.timed(root);
    }
    static void validate(BusinessDataService service, JsonNode source, String excerpt) {
        if (!(source.path("anchor").path("query") instanceof ObjectNode query)) throw bad("缺少查询来源");
        var replay = service.invoke("business_query", query);
        BusinessDataService.validateCandidate(source, excerpt, replay.path("candidates").path(0));
    }
    private static ArrayNode aggregate(ObjectMapper json, ArrayNode rows, List<String> groups, JsonNode metrics, Set<String> fields) {
        var partitions = new LinkedHashMap<String,List<JsonNode>>();
        for (var row : rows) {
            var key = json.createArrayNode(); for (var group : groups) key.add(at(row, group));
            partitions.computeIfAbsent(key.toString(), ignored -> new ArrayList<>()).add(row);
        }
        if (rows.isEmpty() && groups.isEmpty()) partitions.put("[]", List.of());
        var output = json.createArrayNode();
        for (var partition : partitions.values()) {
            var result = json.createObjectNode();
            if (!partition.isEmpty()) for (var group : groups) result.set(group, at(partition.getFirst(), group));
            var names = new HashSet<String>(groups);
            for (var metric : metrics) {
                String field = metric.path("field").asText(); String op = metric.path("op").asText();
                if (!Set.of("count", "sum", "avg", "min", "max").contains(op)) throw bad("不支持该统计操作");
                if (!"count".equals(op) || !field.isBlank()) validateFields(List.of(field), fields);
                String name = metric.path("as").asText(op + (field.isBlank() ? "" : "_" + field));
                if (!names.add(name)) throw bad("统计列名重复");
                var values = new ArrayList<BigDecimal>(); int observed = 0;
                for (var row : partition) {
                    var value = field.isBlank() ? null : at(row, field);
                    if (value == null || value.isNull()) continue;
                    observed++;
                    if (!"count".equals(op)) { if (!value.isNumber()) throw bad("统计字段包含文本或未测状态，不能当作数值：" + field); values.add(value.decimalValue()); }
                }
                if ("count".equals(op)) { result.put(name, field.isBlank() ? partition.size() : observed); continue; }
                for (var condition : fields.stream().filter(namePart -> Set.of("unit", "unitCode", "scope", "method", "testConditions", "condition").contains(namePart.substring(namePart.lastIndexOf('.') + 1))).toList()) {
                    var distinct = new HashSet<String>(); partition.forEach(row -> distinct.add(at(row, condition).toString()));
                    if (distinct.size() > 1 && !groups.contains(condition)) throw bad("单位、库存域或测试条件不同，须先按 " + condition + " 分组");
                }
                if (values.isEmpty()) { result.putNull(name); continue; }
                BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal value = switch (op) {
                    case "sum" -> sum; case "avg" -> sum.divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL128);
                    case "min" -> Collections.min(values); case "max" -> Collections.max(values); default -> throw bad("无效统计");
                };
                result.put(name, value);
            }
            output.add(result);
        }
        return output;
    }
    private static List<String> strings(JsonNode value) {
        var result = new ArrayList<String>();
        if (value.isMissingNode() || value.isNull()) return result;
        if (!value.isArray() || value.size() > 60) throw bad("字段列表无效");
        for (var item : value) { if (!item.isTextual() || item.asText().length() > 200) throw bad("字段名无效"); result.add(item.asText()); }
        return result;
    }
    private static void validateFields(List<String> selected, Set<String> available) {
        for (var field : selected) if (!available.contains(field)) throw bad("未知字段：" + field + "；请先读取 schema");
    }
    private static void flattenKeys(JsonNode value, String prefix, Set<String> output) {
        value.properties().forEach(entry -> { String path = prefix + entry.getKey(); if (entry.getValue().isObject()) flattenKeys(entry.getValue(), path + ".", output); else output.add(path); });
    }
    private static JsonNode at(JsonNode row, String field) {
        for (var part : field.split("\\.")) row = row.path(part);
        return row.isMissingNode() ? com.fasterxml.jackson.databind.node.NullNode.instance : row;
    }
    private static ApiException bad(String message) { return new ApiException(ApiErrorCode.BAD_REQUEST, message); }
}
