package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.rnd.domain.ExperimentModels;
import com.jsd.aird.shared.lab.LabValues;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.util.*;

/** Bounded, versioned business records; editor snapshots stay in the experiment editor. */
final class AgentExperimentProjection {
    static final Set<String> SECTIONS = Set.of("overview", "samples", "formulaItems", "processSteps", "testResults", "tables", "events");
    private final ObjectMapper json;

    AgentExperimentProjection(ObjectMapper json) { this.json = json; }

    ObjectNode page(UUID id, ExperimentModels.Version version, JsonNode query) {
        String section = query.path("section").asText("overview");
        int offset = query.path("offset").asInt(0), limit = query.path("limit").asInt(8);
        if (!(SECTIONS.contains(section) || "summary".equals(section)) || offset < 0 || offset > 100000 || limit < 1 || limit > 40)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "实验分页：section 无效，offset >= 0，limit 为 1–40");
        var model = version.editModel();
        var definition = model.path("templateInstance").path("definition");
        var result = json.createObjectNode().put("experiment_id", id.toString())
            .put("version_id", version.id().toString()).put("version_no", version.versionNo())
            .put("status", version.status()).put("section", section);
        if (version.templateVersionId() != null) result.put("template_version_id", version.templateVersionId().toString());
        result.put("template_kind", definition.path("kind").asText("MANUAL"));
        result.put("template_code", definition.path("templateCode").asText(""));
        // Search resolves identities. Detailed fields, metrics and sample
        // columns belong to the explicit versioned read, not every search hit.
        if ("summary".equals(section)) return result.put("sample_count", model.path("sampleGroups").size());
        var sampleColumns = result.putArray("sample_columns");
        for (var sample : model.path("sampleGroups")) {
            var column = json.createObjectNode().put("sample_key", sample.path("sampleKey").asText())
                .put("label", sample.path("sourceIdentity").asText());
            column.putObject("identity").put("experiment_id", id.toString()).put("version_id", version.id().toString())
                .put("sample_key", sample.path("sampleKey").asText());
            sampleColumns.add(column);
        }
        if ("overview".equals(section)) {
            var fields = result.putObject("fields");
            for (var name : List.of("title", "dataNature", "purpose", "plan", "conclusion"))
                if (model.has(name)) fields.set(name, model.get(name));
            var basics = result.putArray("basic_info");
            var snapshot = model.path("documentSnapshot");
            var sheet = LabWorkbook.sheet(snapshot);
            model.path("dynamicValues").path("fields").properties().forEach(entry -> {
                var field = definition.path("fields").path(entry.getKey());
                if ("SAMPLE".equals(field.path("scope").asText())) return;
                var at = sheet == null ? null : LabWorkbook.findField(sheet, entry.getKey());
                var cell = at == null ? json.createObjectNode() : sheet.path("cellData").path("" + at[0]).path("" + at[1]);
                var style = cell.path("s");
                String pattern = (style.isTextual() ? snapshot.path("styles").path(style.asText()) : style).path("n").path("pattern").asText("");
                var info = LabValues.parse(json, entry.getValue(), field, pattern).put("fieldCode", entry.getKey())
                    .put("label", field.path("label").asText(entry.getKey()));
                if (at != null) info.putObject("source_ref").put("sheetId", sheet.path("id").asText())
                    .put("cellRange", LabWorkbook.address(at[0], at[1]));
                basics.add(info);
            });
            var counts = result.putObject("section_counts");
            for (var name : SECTIONS.stream().sorted().toList()) if (!"overview".equals(name))
                counts.put(name, model.path("samples".equals(name) ? "sampleGroups" : name).size());
            var metrics = result.putArray("available_metrics");
            var seen = new HashSet<String>();
            var missing = new TreeSet<String>();
            var recorded = new HashSet<String>();
            for (var item : model.path("testResults")) {
                if ("NOT_MEASURED".equals(item.path("valueStatus").asText())) {
                    missing.add(item.path("fieldCode").asText()); continue;
                }
                recorded.add(item.path("fieldCode").asText());
                var metric = json.createObjectNode();
                for (var name : List.of("fieldCode", "testItem", "testStage", "unit"))
                    if (item.has(name)) metric.set(name, item.get(name));
                if (seen.add(metric.toString())) metrics.add(metric);
            }
            var unmeasured = result.putArray("unmeasured_metrics");
            missing.removeAll(recorded);
            for (var code : missing) unmeasured.add(code);
            // The status remains on every result cell, including partially missing metrics.
            result.put("unmeasured_metric_count", missing.size());
            var issues = model.path("templateInstance").path("issues");
            if (issues.isArray()) result.set("data_issues", issues);
        } else {
            String sample = query.path("sample_key").asText("");
            var codes = new HashSet<String>();
            if (query.has("field_codes") && (!query.path("field_codes").isArray() || query.path("field_codes").size() > 8))
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "field_codes 必须为不超过8个指标的数组");
            for (var code : query.path("field_codes")) codes.add(code.asText());
            var rows = new ArrayList<JsonNode>();
            var grouped = new LinkedHashMap<String, ObjectNode>();
            for (var item : model.path("samples".equals(section) ? "sampleGroups" : section)) {
                if (!sample.isEmpty() && !sample.equals(item.path("sampleKey").asText())) continue;
                if (!codes.isEmpty() && !codes.contains(item.path("fieldCode").asText())) continue;
                if (Set.of("formulaItems", "processSteps", "testResults").contains(section)) {
                    var header = json.createObjectNode();
                    for (var name : List.of("fieldCode", "materialCode", "materialName", "feedSequence", "testItem", "operation", "testStage", "unit", "testMethod", "testCondition", "baseMetric", "duplicateOf", "supplemental", "compositionBasis"))
                        if (item.has(name)) header.set(name, item.get(name));
                    var field = definition.path("fields").path(item.path("fieldCode").asText());
                    for (var name : List.of("label", "scope", "groupId", "semantic", "resultKind", "valueSource"))
                        if (field.has(name)) header.set(name, field.get(name));
                    for (var region : definition.path("regions")) if (region.path("id").equals(field.path("groupId")))
                        header.set("group_label", region.path("label"));
                    var row = grouped.computeIfAbsent(header.toString(), ignored -> {
                        var value = header.deepCopy(); value.putArray("values"); return value;
                    });
                    var cell = json.createObjectNode().put("sample_key", item.path("sampleKey").asText());
                    for (var name : List.of("value", "rawValue", "rawUnit", "valueStatus", "parseStatus", "operator", "direction", "usageStatus", "trainingEligible", "actualQty", "massPercent", "formulaComplete", "gradeOrder"))
                        if (item.has(name)) cell.set(name, item.get(name));
                    var locations = cell.putArray("source_refs");
                    for (var ref : item.path("sourceRefs")) {
                        var location = json.createObjectNode();
                        for (var name : List.of("sheetId", "cellRange")) if (ref.has(name)) location.set(name, ref.get(name));
                        locations.add(location);
                    }
                    ((com.fasterxml.jackson.databind.node.ArrayNode) row.path("values")).add(cell);
                } else rows.add(item);
            }
            rows.addAll(grouped.values());
            var records = result.putArray("records");
            int cursor = Math.min(offset, rows.size());
            while (cursor < rows.size() && records.size() < limit) {
                var original = rows.get(cursor);
                int size = original.toString().length();
                if (size > 60000) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "单条实验记录过大，请在原实验页查看");
                ObjectNode item = original.isObject() ? ((ObjectNode) original).deepCopy() : json.createObjectNode().set("value", original);
                if (!item.has("values")) item.putObject("sample_identity").put("experiment_id", id.toString()).put("version_id", version.id().toString())
                    .put("sample_key", original.path("sampleKey").asText(""));
                records.add(item);
                // Bound the text the model reads, rather than repeated JSON
                // property names and per-cell protocol metadata.
                if (text(result).length() > 9000 && records.size() > 1) {
                    records.remove(records.size() - 1); break;
                }
                cursor++;
            }
            result.put("offset", offset).put("total", rows.size());
            if (cursor < rows.size()) result.put("next_offset", cursor);
        }
        if (result.toString().length() > 80000)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "实验概览过大，请在原实验页查看");
        return result;
    }

    /** Same versioned evidence, with sample identities declared once and values in tables. */
    String text(JsonNode page) {
        if (!page.path("records").isArray() || page.path("records").isEmpty()
                || !page.path("records").get(0).path("values").isArray()) return page.toString();
        var output = new StringBuilder();
        var identity = ((ObjectNode) page).deepCopy();
        identity.remove(List.of("records", "sample_columns"));
        output.append(identity).append('\n');
        var columns = new LinkedHashMap<String, String>();
        for (var sample : page.path("sample_columns")) {
            var key = sample.path("sample_key").asText();
            var label = sample.path("label").asText();
            columns.put(key, label.isBlank() ? key : label);
        }
        for (var row : page.path("records")) for (var value : row.path("values")) {
            var key = value.path("sample_key").asText(); columns.putIfAbsent(key, key);
        }
        var samples = new ArrayList<>(columns.keySet());
        columns.forEach((key, label) -> output.append("样品 ").append(label).append(" = ").append(key).append('\n'));
        output.append("| 字段、单位、方法与条件 | ");
        for (var label : columns.values()) output.append(cell(label)).append(" | ");
        output.append("\n| --- | ").append("--- | ".repeat(samples.size())).append('\n');
        for (var row : page.path("records")) {
            var header = ((ObjectNode) row).deepCopy(); header.remove("values");
            var common = json.createObjectNode();
            var first = row.path("values").get(0);
            if (first != null) for (var name : List.of("valueStatus", "parseStatus", "rawUnit", "operator", "direction",
                    "usageStatus", "trainingEligible", "formulaComplete")) {
                if (!first.has(name)) continue;
                boolean same = true;
                for (var value : row.path("values")) if (!value.has(name) || !first.get(name).equals(value.get(name))) same = false;
                if (same) common.set(name, first.get(name));
            }
            if (!common.isEmpty()) header.set("cell_defaults", common);
            output.append("| ").append(cell(header.toString())).append(" | ");
            for (var key : samples) {
                var values = new ArrayList<String>();
                for (var value : row.path("values")) if (key.equals(value.path("sample_key").asText())) {
                    var compact = ((ObjectNode) value).deepCopy(); compact.remove("sample_key");
                    for (var entry : common.properties()) compact.remove(entry.getKey());
                    if (compact.path("rawValue").equals(compact.path("value"))) compact.remove("rawValue");
                    if (compact.path("rawUnit").equals(row.path("unit"))) compact.remove("rawUnit");
                    if (compact.path("actualQty").equals(compact.path("value"))) compact.remove("actualQty");
                    var locations = new ArrayList<String>();
                    for (var ref : compact.path("source_refs")) locations.add(ref.path("sheetId").asText() + ":" + ref.path("cellRange").asText());
                    compact.remove("source_refs");
                    if (!locations.isEmpty()) compact.put("at", String.join(",", locations));
                    values.add(compact.toString());
                }
                // A missing cell is distinct from NOT_MEASURED and numeric zero.
                output.append(cell(values.isEmpty() ? "MISSING" : String.join("; ", values))).append(" | ");
            }
            output.append('\n');
        }
        return output.toString();
    }

    private String cell(String value) { return value.replace("|", "\\|").replace("\r", "").replace("\n", "<br>"); }
}
