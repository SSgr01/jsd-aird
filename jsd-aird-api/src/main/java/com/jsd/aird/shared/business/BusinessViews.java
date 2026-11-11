package com.jsd.aird.shared.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class BusinessViews {
    private BusinessViews() { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TECHNICAL = Set.of("allowedActions", "workbookSnapshot", "inlineSnapshot",
        "templateSnapshot", "memberSnapshot", "schema", "mapping", "wordDocument", "rawResponseJson",
        "documentSnapshot", "templateInstance");

    public static UUID id(JsonNode ref) { return uuid(ref, "id"); }
    public static UUID uuid(JsonNode value, String key) {
        try { return UUID.fromString(value.path(key).asText()); }
        catch (IllegalArgumentException e) { throw new ApiException(ApiErrorCode.BAD_REQUEST, "无效业务对象引用：" + key); }
    }
    public static UUID optionalId(JsonNode value, String key) {
        return !value.hasNonNull(key) ? null : uuid(value, key);
    }
    public static ObjectNode ref(String type, UUID id) {
        return JSON.createObjectNode().put("type", type).put("id", id.toString());
    }
    public static BusinessRecordView.Relation link(String name, String type, UUID id) {
        return new BusinessRecordView.Relation(name, ref(type, id));
    }
    public static void projectLinks(List<BusinessRecordView.Relation> links, UUID project, UUID stage, UUID task) {
        if (project == null) return;
        links.add(link("所属项目", "PROJECT", project));
        if (stage != null) links.add(new BusinessRecordView.Relation("所属阶段", ref("STAGE", stage).put("project_id", project.toString())));
        if (task != null) links.add(new BusinessRecordView.Relation("所属任务", ref("TASK", task).put("project_id", project.toString())));
    }
    public static ObjectNode facts(ObjectMapper mapper, Object value, String... fields) {
        JsonNode raw = mapper.valueToTree(value);
        var output = mapper.createObjectNode();
        for (var field : fields) if (raw.has(field)) output.set(field, clean(raw.get(field)));
        return output;
    }
    public static JsonNode clean(JsonNode value) {
        if (value.isArray()) {
            var result = JSON.createArrayNode(); value.forEach(item -> result.add(clean(item))); return result;
        }
        if (!value.isObject()) return value.deepCopy();
        var result = JSON.createObjectNode();
        var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add);
        for (var key : keys) if (!TECHNICAL.contains(key)) result.set(key, clean(value.get(key)));
        return result;
    }
    public static BusinessRecordView view(String type, UUID id, Object revision, String title, ObjectNode facts,
            String path, List<BusinessRecordView.Relation> links) {
        var reference = ref(type, id).put("revision", revision == null ? hash(clean(facts).toString()) : revision.toString());
        return new BusinessRecordView(reference, title == null ? type : title, (ObjectNode) clean(facts), links, path);
    }
    /** Read saved business values, without serializing workbook styles or editor internals. */
    public static JsonNode document(JsonNode snapshot) {
        var result = JSON.createObjectNode();
        if (snapshot == null || snapshot.isMissingNode() || snapshot.isNull()) return result;
        if (snapshot.has("sheets")) {
            var cells = result.putArray("cells");
            for (var sheet : snapshot.path("sheets")) {
                if (sheet.path("name").asText().startsWith("_JSD")) continue;
                for (var row : sheet.path("cellData").properties()) for (var column : row.getValue().properties()) {
                    var cell = column.getValue();
                    var value = cell.has("v") ? cell.path("v") : cell.path("p").path("body").path("dataStream");
                    if (value.isMissingNode() && !cell.hasNonNull("f")) continue;
                    var item = cells.addObject().put("sheet", sheet.path("name").asText())
                        .put("cell", com.jsd.aird.shared.lab.LabWorkbook.address(Integer.parseInt(row.getKey()), Integer.parseInt(column.getKey())));
                    item.set("value", value.isMissingNode() ? JSON.nullNode() : value.deepCopy());
                    if (cell.hasNonNull("f")) item.set("formula", cell.path("f"));
                }
            }
        } else if (snapshot.path("body").has("dataStream")) {
            result.put("text", snapshot.path("body").path("dataStream").asText()
                .replace('\u001a', '\n').replace('\u001b', '\n').replace("\u001c", "")
                .replace('\u001d', '\t').replace('\u000e', '\n').replace('\u000f', '\n').replace('\r', '\n'));
        }
        return result;
    }
    public static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }
    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static ApiException missing() { return new ApiException(ApiErrorCode.NOT_FOUND, "业务记录不存在或不可读取"); }
    public static void pinned(JsonNode requested, BusinessRecordView actual) {
        if (!requested.path("type").equals(actual.ref().path("type")) || !requested.path("id").equals(actual.ref().path("id")))
            throw new IllegalStateException("Business reader returned another record");
        if (requested.hasNonNull("revision") && !requested.path("revision").asText().equals(actual.ref().path("revision").asText()))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "记录已变化，请重新读取；旧引用不会自动指向新内容");
    }
    public static String markdown(String title, JsonNode facts) {
        var out = new StringBuilder("## ").append(cell(title)).append('\n');
        append(out, facts, 0); return out.toString().strip();
    }
    /** Display projection only. The original facts and source hash remain authoritative. */
    public static String modelMarkdown(String title, JsonNode facts) {
        String result = markdown(title, display(facts));
        if (facts.has("materialCount")) result += "\n关联资料数统计项目资料，不是配方物料数；是否已有配方须读取关联实验的背景、方案与配方正文。";
        return result;
    }
    private static final Set<String> MODEL_INTERNAL = Set.of("id", "organizationId", "version", "createdBy", "updatedBy",
        "projectId", "stageId", "taskId", "partnerId", "customerId", "templateVersionId");
    private static final Map<String,String> DISPLAY_LABELS = Map.ofEntries(
        Map.entry("title", "标题"), Map.entry("projectCode", "项目编号"), Map.entry("taskCode", "任务编号"),
        Map.entry("description", "说明"), Map.entry("background", "背景"), Map.entry("purpose", "目的"),
        Map.entry("plan", "方案"), Map.entry("conclusion", "结论"), Map.entry("dataNature", "数据性质"),
        Map.entry("materialCount", "关联资料数"), Map.entry("experimentCount", "关联实验数"),
        Map.entry("taskCount", "任务数"), Map.entry("unfinishedTaskCount", "未完成任务数"),
        Map.entry("stageCount", "阶段数"), Map.entry("memberCount", "团队人数"), Map.entry("progress", "进度"),
        Map.entry("priority", "优先级"), Map.entry("ownerName", "负责人"), Map.entry("assigneeName", "任务负责人"),
        Map.entry("plannedStartDate", "计划开始日期"), Map.entry("plannedEndDate", "计划结束日期"),
        Map.entry("actualStartDate", "实际开始日期"), Map.entry("actualEndDate", "实际结束日期"),
        Map.entry("dueDate", "截止日期"), Map.entry("createdAt", "创建时间"), Map.entry("updatedAt", "更新时间"),
        Map.entry("fieldCode", "字段"), Map.entry("label", "名称"), Map.entry("sampleKey", "样品标识"),
        Map.entry("sourceIdentity", "样品名称"), Map.entry("materialCode", "物料编号"), Map.entry("materialName", "物料名称"),
        Map.entry("testItem", "指标"), Map.entry("operation", "工序"), Map.entry("testStage", "测试阶段"),
        Map.entry("testMethod", "方法"), Map.entry("testCondition", "条件"), Map.entry("group_label", "分组"),
        Map.entry("value", "数值"), Map.entry("rawValue", "原始值"), Map.entry("rawUnit", "原始单位"),
        Map.entry("actualQty", "用量"), Map.entry("massPercent", "质量分数（%）"), Map.entry("feedSequence", "加料顺序"),
        Map.entry("valueStatus", "记录状态"), Map.entry("parseStatus", "解析状态"),
        Map.entry("operator", "数值关系"), Map.entry("direction", "评价方向"), Map.entry("compositionBasis", "配方基准"),
        Map.entry("gradeOrder", "等级顺序"), Map.entry("formulaComplete", "配方是否完整"));
    public static String displayValue(String value) {
        return switch (value) {
            case "NOT_STARTED", "PENDING" -> "未开始"; case "IN_PROGRESS" -> "进行中";
            case "COMPLETED", "DONE" -> "已完成"; case "DRAFT" -> "草稿"; case "PUBLISHED" -> "已发布";
            case "CANCELLED" -> "已取消"; case "BLOCKED" -> "受阻"; case "PENDING_REVIEW" -> "待审核";
            case "HIGH" -> "高"; case "MEDIUM" -> "中"; case "LOW" -> "低";
            case "RECORDED", "VALID" -> "已记录"; case "NOT_MEASURED" -> "未记录";
            case "MISSING" -> "缺失"; case "SYNTHETIC" -> "合成数据";
            default -> value;
        };
    }
    public static JsonNode display(JsonNode value) {
        if (value.isArray()) {
            var result = JSON.createArrayNode(); value.forEach(item -> result.add(display(item))); return result;
        }
        if (!value.isObject()) return value.deepCopy();
        var result = JSON.createObjectNode();
        value.properties().forEach(entry -> {
            var key = entry.getKey();
            if (MODEL_INTERNAL.contains(key) || TECHNICAL.contains(key)) return;
            var item = display(entry.getValue());
            if (item.isTextual() && Set.of("status", "priority", "dataNature", "valueStatus", "parseStatus").contains(key))
                item = JSON.getNodeFactory().textNode(displayValue(item.asText()));
            result.set(DISPLAY_LABELS.getOrDefault(key, label(key)), item);
        });
        return result;
    }
    private static void append(StringBuilder out, JsonNode value, int depth) {
        if (value.isObject()) {
            value.properties().forEach(entry -> {
                if (entry.getValue().isContainerNode()) {
                    out.append('\n').append("#".repeat(Math.min(6, depth + 3))).append(' ').append(label(entry.getKey())).append('\n');
                    append(out, entry.getValue(), depth + 1);
                } else out.append("- ").append(label(entry.getKey())).append(": ").append(cell(entry.getValue())).append('\n');
            });
        } else if (value.isArray()) {
            if (value.isEmpty()) { out.append("无记录\n"); return; }
            boolean table = true; var columns = new LinkedHashSet<String>();
            for (var row : value) {
                if (!row.isObject()) { table = false; break; }
                row.fieldNames().forEachRemaining(columns::add);
                for (var field : row) if (field.isContainerNode()) table = false;
            }
            if (table && !columns.isEmpty()) {
                out.append('|'); columns.forEach(column -> out.append(label(column)).append('|'));
                out.append('\n').append('|'); columns.forEach(column -> out.append("---|")); out.append('\n');
                for (var row : value) { out.append('|'); columns.forEach(column -> out.append(cell(row.path(column))).append('|')); out.append('\n'); }
            } else for (int i = 0; i < value.size(); i++) { out.append('\n').append("记录 ").append(i + 1).append('\n'); append(out, value.get(i), depth + 1); }
        } else out.append(cell(value)).append('\n');
    }
    private static String cell(Object value) {
        String text = value instanceof JsonNode node ? node.isNull() || node.isMissingNode() ? "缺失" : node.isValueNode() ? node.asText() : node.toString() : String.valueOf(value);
        return text.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }
    public static String label(String key) {
        return switch (key) {
            case "name", "displayName" -> "名称"; case "status" -> "状态"; case "quantityKg" -> "数量（kg）";
            case "beforeKg" -> "变动前（kg）"; case "afterKg" -> "变动后（kg）";
            case "productName" -> "产品"; case "customerName" -> "客户"; case "batchNo" -> "批号";
            case "businessNo", "orderNo", "requirementCode", "recordCode" -> "业务编号";
            case "plannedDate", "businessDate" -> "业务日期"; case "data", "editModel" -> "业务内容";
            case "testConditions" -> "测试条件"; case "unit", "unitCode" -> "单位"; case "method" -> "方法";
            case "scope" -> "库存域"; case "customerFeedback" -> "客户反馈"; case "rawRequirement" -> "原始需求";
            case "ownerName" -> "负责人"; case "remark" -> "备注"; case "read_at" -> "读取时间";
            default -> key;
        };
    }
}
