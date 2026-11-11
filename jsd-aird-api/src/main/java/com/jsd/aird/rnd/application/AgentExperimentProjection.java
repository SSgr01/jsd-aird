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
    private static final Set<String> VALUE_SECTIONS = Set.of("formulaItems", "processSteps", "testResults");
    private final ObjectMapper json;

    AgentExperimentProjection(ObjectMapper json) { this.json = json; }

    ObjectNode page(UUID id, ExperimentModels.Version version, JsonNode query) {
        String section = query.path("section").asText("overview");
        if ("record".equals(section)) {
            if (query.path("offset").asInt() != 0 || query.hasNonNull("sample_key") || query.hasNonNull("field_codes"))
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "完整实验读取不接受局部筛选，请使用分区分页");
            var full = json.createObjectNode().put("section", "record").put("complete", true);
            var overview = page(id, version, json.createObjectNode().put("section", "overview"));
            full.set("overview", overview); full.set("section_counts", overview.path("section_counts"));
            full.set("section_cell_counts", overview.path("section_cell_counts"));
            var sections = full.putObject("sections");
            for (String part : SECTIONS.stream().filter(value -> !"overview".equals(value)).sorted().toList()) {
                var pages = sections.putArray(part); int cursor = 0;
                do {
                    var value = page(id, version, json.createObjectNode().put("section", part).put("offset", cursor).put("limit", 40));
                    pages.add(value);
                    if (!value.has("next_offset")) break;
                    int next = value.path("next_offset").asInt();
                    if (next <= cursor || next > 4000) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "完整实验较大，请按概览分区分页读取");
                    cursor = next;
                } while (true);
            }
            if (modelText(full).length() > 40000)
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "完整实验较大，请按概览分区分页读取");
            return full;
        }
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
        int sampleIndex = 0;
        for (var sample : model.path("sampleGroups")) {
            var column = json.createObjectNode().put("sample_key", sample.path("sampleKey").asText())
                .put("label", sampleLabel(model, sample, ++sampleIndex));
            column.putObject("identity").put("experiment_id", id.toString()).put("version_id", version.id().toString())
                .put("sample_key", sample.path("sampleKey").asText());
            sampleColumns.add(column);
        }
        if ("overview".equals(section)) {
            var fields = result.putObject("fields");
            for (var name : List.of("title", "dataNature", "purpose", "plan", "conclusion"))
                if (model.has(name)) fields.set(name, model.get(name));
            if (model.path("batchSources").isArray()) result.set("batch_sources",model.path("batchSources"));
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
            var cellCounts = result.putObject("section_cell_counts");
            for (var name : SECTIONS.stream().sorted().toList()) if (!"overview".equals(name)) {
                counts.put(name, rows(model, name, "", Set.of()).size());
                if (VALUE_SECTIONS.contains(name)) cellCounts.put(name, model.path(name).size());
            }
            var metrics = result.putArray("available_metrics");
            var unmeasured = result.putArray("unmeasured_metrics");
            for (var item : rows(model, "testResults", "", Set.of())) {
                var metric = json.createObjectNode();
                for (var name : List.of("fieldCode", "testItem", "testStage", "unit", "testMethod", "testCondition"))
                    if (item.hasNonNull(name) && !(item.get(name).isTextual() && item.get(name).asText().isBlank()))
                        metric.set(name, item.get(name));
                boolean hasRecordedValue = false;
                for (var value : item.path("values"))
                    if (!"NOT_MEASURED".equals(value.path("valueStatus").asText())) hasRecordedValue = true;
                (hasRecordedValue ? metrics : unmeasured).add(metric);
            }
            // The status remains on every result cell, including partially missing metrics.
            result.put("unmeasured_metric_count", unmeasured.size());
            var issues = model.path("templateInstance").path("issues");
            if (issues.isArray()) result.set("data_issues", issues);
        } else {
            String sample = query.path("sample_key").asText("");
            var codes = new HashSet<String>();
            if (query.has("field_codes") && (!query.path("field_codes").isArray() || query.path("field_codes").size() > 8))
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "field_codes 必须为不超过8个指标的数组");
            for (var code : query.path("field_codes")) codes.add(code.asText());
            var rows = rows(model, section, sample, codes);
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

    /** Overview counts and page cursors must describe the same rows, not sample cells. */
    private List<JsonNode> rows(JsonNode model, String section, String sample, Set<String> codes) {
        var rows = new ArrayList<JsonNode>();
        var grouped = new LinkedHashMap<JsonNode, ObjectNode>();
        var definition = model.path("templateInstance").path("definition");
        for (var item : model.path("samples".equals(section) ? "sampleGroups" : section)) {
            if (!sample.isEmpty() && !sample.equals(item.path("sampleKey").asText())) continue;
            if (!codes.isEmpty() && !codes.contains(item.path("fieldCode").asText())) continue;
            if (!VALUE_SECTIONS.contains(section)) { rows.add(item); continue; }
            var header = json.createObjectNode();
            for (var name : List.of("fieldCode", "materialCode", "materialName", "feedSequence", "testItem", "operation", "testStage", "unit", "testMethod", "testCondition", "baseMetric", "duplicateOf", "supplemental", "compositionBasis"))
                if (item.has(name)) header.set(name, item.get(name));
            var field = definition.path("fields").path(item.path("fieldCode").asText());
            for (var name : List.of("label", "scope", "groupId", "semantic", "resultKind", "valueSource"))
                if (field.has(name)) header.set(name, field.get(name));
            for (var region : definition.path("regions")) if (region.path("id").equals(field.path("groupId")))
                header.set("group_label", region.path("label"));
            var row = grouped.computeIfAbsent(header, ignored -> {
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
        }
        rows.addAll(grouped.values());
        return rows;
    }

    private String sampleLabel(JsonNode model, JsonNode sample, int index) {
        String identity = sample.path("sourceIdentity").asText();
        if (!identity.isBlank()) return identity;
        // The standard workbook has a separate sample/plan number. An empty
        // experiment-number identity must not turn every table heading blank.
        for (var item : model.path("processSteps")) {
            if ("sample.number".equals(item.path("fieldCode").asText())
                    && item.path("sampleKey").equals(sample.path("sampleKey"))) {
                String number = item.path("value").asText("").strip();
                if (!number.isBlank()) return number;
            }
        }
        return "样品 " + index;
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

    /** Keep the full evidence above; show business content without editor/protocol JSON. */
    String modelText(JsonNode page) {
        if ("record".equals(page.path("section").asText())) {
            var full = new StringBuilder(modelText(page.path("overview")));
            page.path("sections").properties().forEach(entry -> {
                for (var part : entry.getValue()) full.append('\n').append(modelText(part));
            });
            return full.toString();
        }
        var output = new StringBuilder("实验版本：V").append(page.path("version_no").asText())
            .append("；状态：").append(com.jsd.aird.shared.business.BusinessViews.displayValue(page.path("status").asText())).append('\n');
        String section = page.path("section").asText();
        if ("summary".equals(section)) return output.append("这是目录摘要，正文尚未读取。结构化样品数：")
            .append(page.path("sample_count").asInt()).append("。样品数不包括背景中的方案、配方或历史引用；请读取同一版本概览后再判断资料缺口。").toString();
        if ("overview".equals(section)) {
            output.append(com.jsd.aird.shared.business.BusinessViews.modelMarkdown("实验共用内容", page.path("fields"))).append('\n');
            output.append("| 共用字段 | 内容 | 记录状态 |\n| --- | --- | --- |\n");
            for (var info : page.path("basic_info")) {
                String code = info.path("fieldCode").asText();
                String label = info.path("label").asText(code);
                if (label.equals(code)) label = switch (code) {
                    case "basic.background" -> "实验背景"; case "plan.shared" -> "共用方案";
                    case "basic.temperature" -> "环境温度"; case "basic.humidity" -> "相对湿度";
                    default -> label;
                };
                var value = info.hasNonNull("value") ? info.path("value") : info.path("rawValue");
                output.append("| ").append(cell(label)).append(" | ").append(cell(value.isNull() || value.isMissingNode() ? "未填写" : value.asText(value.toString())))
                    .append(info.path("unit").asText().isBlank() ? "" : " " + cell(info.path("unit").asText()))
                    .append(" | ").append(com.jsd.aird.shared.business.BusinessViews.displayValue(info.path("valueStatus").asText())).append(" |\n");
            }
            output.append("\n结构化分区行数（与分区分页总行数一致，不包含上面的背景、方案及历史引用）：\n");
            page.path("section_counts").properties().forEach(entry -> output.append("- ").append(sectionLabel(entry.getKey()))
                .append("：").append(entry.getValue().asInt()).append(" 行")
                .append(page.path("section_cell_counts").has(entry.getKey())
                    ? "；样品数据单元格 " + page.path("section_cell_counts").path(entry.getKey()).asInt() + " 个（含未记录项）" : "")
                .append('\n'));
            output.append("可读取指标：");
            for (var metric : page.path("available_metrics")) output.append(metric.path("testItem").asText(metric.path("fieldCode").asText()))
                .append(metric.path("unit").asText().isBlank() ? "" : "（" + metric.path("unit").asText() + "）")
                .append(metric.path("testMethod").asText().isBlank() ? "" : " / " + metric.path("testMethod").asText())
                .append(metric.path("testCondition").asText().isBlank() ? "" : " / " + metric.path("testCondition").asText()).append("；");
            output.append("\n完全未记录的指标数：").append(page.path("unmeasured_metric_count").asInt()).append('\n');
            output.append("\n样品读取索引（标识仅用于继续读取）：\n");
            for (var sample : page.path("sample_columns")) output.append("- ").append(sample.path("label").asText())
                .append("：").append(sample.path("sample_key").asText()).append('\n');
            return output.toString();
        }
        output.append(sectionLabel(section)).append('\n');
        var columns = new LinkedHashMap<String,String>();
        for (var sample : page.path("sample_columns")) columns.put(sample.path("sample_key").asText(), sample.path("label").asText());
        var values = json.createArrayNode();
        for (var row : page.path("records")) {
            if (!row.path("values").isArray()) {
                output.append(com.jsd.aird.shared.business.BusinessViews.modelMarkdown("记录", row)).append('\n'); continue;
            }
            var header = ((ObjectNode)row).deepCopy();
            header.remove(List.of("values", "fieldCode", "scope", "groupId", "semantic", "resultKind", "valueSource", "baseMetric", "duplicateOf"));
            var returned = new HashSet<String>();
            for (var value : row.path("values")) {
                String key = value.path("sample_key").asText();
                returned.add(key);
                var content = ((ObjectNode)value).deepCopy();
                content.remove(List.of("sample_key", "source_refs", "trainingEligible", "usageStatus"));
                if (content.path("rawValue").equals(content.path("value"))) content.remove("rawValue");
                if (content.path("actualQty").equals(content.path("value"))) content.remove("actualQty");
                // Keep each observation together: a value must never lose its
                // sample, metric, unit, method or condition during presentation.
                var item = json.createObjectNode().put("样品", columns.getOrDefault(key, key));
                item.setAll(header); item.setAll(content); values.add(item);
            }
            columns.forEach((key, label) -> {
                if (!returned.contains(key)) {
                    var item = values.addObject().put("样品", label);
                    item.setAll(header); item.putNull("value").put("记录状态", "本页未返回，不能据此判定未测");
                }
            });
        }
        if (!values.isEmpty()) output.append(com.jsd.aird.shared.business.BusinessViews.modelMarkdown("样品、指标与条件对应值", values)).append('\n');
        if (page.has("next_offset")) output.append("本分区尚未读完，下一页偏移量：").append(page.path("next_offset").asInt()).append("。\n");
        return output.toString();
    }
    private String sectionLabel(String section) {
        return switch (section) {
            case "samples" -> "样品"; case "formulaItems" -> "配方"; case "processSteps" -> "工艺";
            case "testResults" -> "结果"; case "tables" -> "表格"; case "events" -> "事件"; default -> section;
        };
    }

    private String cell(String value) { return value.replace("|", "\\|").replace("\r", "").replace("\n", "<br>"); }
}
