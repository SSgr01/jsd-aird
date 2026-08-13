package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.application.port.StandardFieldRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Conservative physical fallback for explicit label/value pairs plus the one
 * unambiguous long-table shape. Ambiguous table/block layouts remain on the
 * model and review path.
 */
@Component
public class RuleBasedRecognitionEngine {

    private static final int MAX_TEMPLATE_ROWS = 200;

    private static final Pattern EXPLICIT_LABEL = Pattern.compile("^\\s*([^：:\\r\\n]{1,30})[：:]\\s*$");
    private static final Pattern INLINE_LABEL = Pattern.compile("^\\s*([^：:\\r\\n]{1,30})[：:]\\s*(.+?)\\s*$");
    private static final Pattern NUMBERED_LABEL = Pattern.compile("^\\s*\\d{1,3}[.．、)）]\\s*(.{1,60}?)\\s*$");
    private static final Set<String> STATIC_PREFIXES = Set.of("注", "备注", "注意", "说明", "提示", "操作要求");

    private final ObjectMapper objectMapper;
    private final JsonCanonicalizer canonicalizer;
    private final StandardFieldRepository standardFieldRepository;

    @Autowired
    public RuleBasedRecognitionEngine(
            ObjectMapper objectMapper,
            JsonCanonicalizer canonicalizer,
            StandardFieldRepository standardFieldRepository
    ) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.standardFieldRepository = standardFieldRepository;
    }

    public RuleBasedRecognitionEngine(ObjectMapper objectMapper, JsonCanonicalizer canonicalizer) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.standardFieldRepository = null;
    }

    public RecognitionModelClient.RecognitionBatch recognize(
            TemplateFormat format, String sourceFileName, JsonNode structure
    ) {
        if (format == TemplateFormat.XLSX && structure.path("structureVersion").asInt() != 6) {
            throw new IllegalArgumentException("Excel structureVersion 必须为 6");
        }
        var fingerprint = canonicalizer.hash(structure);
        // Word is a document template, not an Excel-style business-field template.
        // Its deterministic output is the documentIR/structure tree produced by
        // DocxStructureParser.  Even explicit content controls are document facts;
        // they must not silently become TemplateBinding suggestions.
        var suggestions = format == TemplateFormat.XLSX
                ? simpleLongTableCandidates(structure)
                : List.<RecognitionModelClient.ModelSuggestion>of();
        if (format == TemplateFormat.XLSX && suggestions.isEmpty()) {
            suggestions = new ArrayList<>(suggestions);
            suggestions.addAll(explicitLabelValueCandidates(structure));
        }
        return new RecognitionModelClient.RecognitionBatch(
                suggestions, List.of(), "physical-facts", "conservative-label-value-v6",
                "physical-fallback-v6", fingerprint,
                canonicalizer.hashText(sourceFileName + "|" + fingerprint), null
        );
    }

    /**
     * A strict one-header-row table is a physical contract, not a semantic
     * guessing problem.  Returning true lets the import service avoid a model
     * call for the common long-table template while still leaving ambiguous
     * layouts to the model/review path.
     */
    public boolean isSimpleLongTableWorkbook(JsonNode structure) {
        var found = false;
        for (var sheet : structure.path("sheets")) {
            if (sheet.path("hidden").asBoolean(false)) continue;
            if (semanticCellsOfSheet(sheet).isEmpty()) continue;
            if (simpleLongTable(sheet) == null) return false;
            found = true;
        }
        return found;
    }

    private List<RecognitionModelClient.ModelSuggestion> simpleLongTableCandidates(JsonNode structure) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        for (var sheet : structure.path("sheets")) {
            if (sheet.path("hidden").asBoolean(false)) continue;
            var table = simpleLongTable(sheet);
            if (table != null) {
                var parent = simpleLongTableSuggestion(table);
                result.add(parent);
                result.addAll(simpleLongTableFieldSuggestions(parent));
            }
        }
        return List.copyOf(result);
    }

    /**
     * A simple long table has an unambiguous physical contract: the first
     * contiguous text row is the header and each following column is one
     * repeat field. Keep these children as reviewable suggestions so the user
     * can confirm the actual fields without triggering semantic recognition.
     */
    public List<RecognitionModelClient.ModelSuggestion> simpleLongTableFieldSuggestions(
            RecognitionModelClient.ModelSuggestion parent
    ) {
        if (parent == null || !"SIMPLE_LONG_TABLE".equals(parent.payload().path("reasonCode").asText())
                || !"ROW_TABLE".equals(parent.payload().path("kind").asText())
                || !parent.payload().path("columns").isArray()) {
            return List.of();
        }
        var payload = parent.payload();
        var parentRelationId = payload.path("relationId").asText("");
        var parentFieldId = payload.path("fieldId").asText("");
        var parentBindingId = payload.path("bindingId").asText("");
        var parentBlockId = payload.path("blockId").asText(payload.path("regionId").asText(""));
        var parentRegionId = payload.path("regionId").asText(parentBlockId);
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        for (var column : payload.path("columns")) {
            var name = column.path("name").asText("").strip();
            var code = column.path("code").asText("").strip();
            var valueRange = column.path("valueRange").asText("");
            if (name.isBlank() || code.isBlank() || valueRange.isBlank()) continue;

            var childRelationId = parentRelationId + "|child|" + code + "|"
                    + RecognitionIdentity.normalizeRange(valueRange);
            var childFieldId = RecognitionIdentity.fieldId(childRelationId);
            var childBindingId = RecognitionIdentity.bindingId(
                    childFieldId, "CELL_RANGE",
                    payload.path("locator").path("sheetId").asText("") + "|" + valueRange
            );
            var child = objectMapper.createObjectNode()
                    .put("kind", "SCALAR")
                    .put("suggestionLevel", "CHILD")
                    .put("mappingKind", "REPEAT_FIELD")
                    .put("relationId", childRelationId)
                    .put("modelRelationId", parentRelationId)
                    .put("fieldId", childFieldId.toString())
                    .put("bindingId", childBindingId.toString())
                    .put("parentRelationId", parentRelationId)
                    .put("parentFieldId", parentFieldId)
                    .put("parentBindingId", parentBindingId)
                    .put("regionId", parentRegionId)
                    .put("blockId", parentBlockId)
                    .put("parentBlockId", parentBlockId)
                    .put("fieldCode", column.path("fieldCode").asText("TABLE.COLUMN." + code))
                    .put("dataPath", column.path("dataPath").asText(""))
                    .put("fieldName", name)
                    .put("groupName", payload.path("groupName").asText("业务数据"))
                    .put("valueType", column.path("valueType").asText("string"))
                    .put("required", column.path("required").asBoolean(false))
                    .put("role", "FIELD")
                    .put("locatorType", "CELL_RANGE")
                    .put("editability", column.path("editability").asText("EDITABLE"))
                    .put("valueSource", column.path("valueSource").asText("USER_INPUT"))
                    .put("unit", column.path("unit").asText(""))
                    .put("repeatAxis", "ROW")
                    .put("recordHeight", payload.path("recordHeight").asInt(1))
                    .put("recordWidth", payload.path("recordWidth").asInt(1))
                    .put("recordStride", payload.path("recordStride").asInt(1))
                    .put("reviewRequired", true)
                    .put("candidateOnly", true)
                    .put("publishable", false)
                    .put("pendingReason", "SIMPLE_LONG_TABLE_FIELD_REVIEW")
                    .put("nameSource", "PHYSICAL_HEADER_FALLBACK")
                    .put("semanticFallback", true)
                    .put("recognitionOrigin", "RULE_DETERMINISTIC")
                    .put("reasonCode", "SIMPLE_LONG_TABLE_FIELD")
                    .put("reason", "字段名称来自单行表头，请人工确认后写入正式模板")
                    .put("interpretation", "每条记录从“" + name + "”列读取");

            if (column.has("standardFieldId")) child.set("standardFieldId", column.path("standardFieldId").deepCopy());
            for (var key : List.of("standardFieldVersion", "standardFieldName", "fieldOrigin",
                    "standardSelectionStatus", "standardMatchStatus", "dictionaryVersion",
                    "requiresStandardConfirmation", "uiType")) {
                if (column.has(key)) child.set(key, column.path(key).deepCopy());
            }
            var locator = child.putObject("locator")
                    .put("sheetId", payload.path("locator").path("sheetId").asText(""))
                    .put("sheetName", payload.path("locator").path("sheetName").asText(""))
                    .put("labelAddress", column.path("labelRange").asText(""))
                    .put("labelRange", column.path("labelRange").asText(""))
                    .put("address", valueRange)
                    .put("range", valueRange)
                    .put("logicalInputRange", valueRange)
                    .put("valueMode", "ARRAY_COLUMN")
                    .put("parentRange", payload.path("locator").path("dataRange").asText(
                            payload.path("dataRange").asText("")))
                    .put("parentBindingId", parentBindingId);
            if (column.has("dataStartRow")) child.put("dataStartRow", column.path("dataStartRow").asInt());
            if (column.has("columnOffset")) child.put("columnOffset", column.path("columnOffset").asInt());
            if (column.has("columnSpan")) child.put("columnSpan", column.path("columnSpan").asInt());

            var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                    .put("source", "PHYSICAL_HEADER")
                    .put("regionId", parentRegionId)
                    .put("labelRange", column.path("labelRange").asText(""))
                    .put("valueRange", valueRange));
            result.add(new RecognitionModelClient.ModelSuggestion(
                    "TABLE_CHILD_FIELD", child, parent.confidence(), evidence));
        }
        return List.copyOf(result);
    }

    private SimpleLongTable simpleLongTable(JsonNode sheet) {
        if (!sheet.path("mergedRanges").isEmpty()
                || sheet.path("formulaCount").asInt(0) > 0) return null;
        var rows = new TreeMap<Integer, List<CellPosition>>();
        for (var cell : semanticCellsOfSheet(sheet)) {
            if (!cell.path("value").isValueNode() || cell.path("value").asText("").strip().isBlank()) continue;
            if (cell.path("formula").isTextual() || "FORMULA".equals(cell.path("factType").asText(""))) return null;
            var position = position(cell);
            if (position == null) continue;
            rows.computeIfAbsent(position.row(), ignored -> new ArrayList<>())
                    .add(position);
        }
        if (rows.isEmpty()) return null;

        // Allow a single title cell above the header, but require the first
        // actual table row to be a contiguous textual header with no duplicate
        // names.  A merged/multi-row header is deliberately left to AI/review.
        Map.Entry<Integer, List<CellPosition>> headerEntry = null;
        for (var entry : rows.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            if (rows.headMap(entry.getKey()).values().stream().anyMatch(values -> values.size() >= 2)) continue;
            if (entry.getValue().stream().allMatch(item -> item.cell().path("value").isTextual())
                    && contiguous(entry.getValue()) && uniqueHeader(entry.getValue())
                    && headerLooksLikeFields(entry.getValue())) {
                headerEntry = entry;
                break;
            }
        }
        if (headerEntry == null) return null;
        var headerRow = headerEntry.getKey();
        var headerCells = headerEntry.getValue();
        var startColumn = headerCells.stream().mapToInt(CellPosition::column).min().orElse(0);
        var endColumn = headerCells.stream().mapToInt(CellPosition::column).max().orElse(0);
        if (startColumn < 1 || endColumn - startColumn + 1 != headerCells.size()) return null;

        // Every populated row below the header must stay inside the same
        // columns.  This rejects side notes and cross-tabs without guessing.
        for (var entry : rows.tailMap(headerRow + 1).entrySet()) {
            if (entry.getValue().stream().anyMatch(item ->
                    item.column() < startColumn || item.column() > endColumn)) return null;
        }
        var sheetId = sheet.path("id").asText(sheet.path("sheetId").asText(""));
        if (sheetId.isBlank()) return null;
        var sheetName = sheet.path("name").asText(sheetId);
        var endRow = Math.max(MAX_TEMPLATE_ROWS, sheet.path("lastRow").asInt(headerRow));
        var headerRange = excelRange(startColumn, headerRow, endColumn, headerRow);
        var dataStartRow = headerRow + 1;
        var dataRange = excelRange(startColumn, dataStartRow, endColumn, endRow);
        var fullRange = excelRange(startColumn, headerRow, endColumn, endRow);
        return new SimpleLongTable(sheetId, sheetName, headerRow, startColumn, endColumn, endRow,
                headerRange, dataRange, fullRange, headerCells);
    }

    private RecognitionModelClient.ModelSuggestion simpleLongTableSuggestion(SimpleLongTable table) {
        var relationId = RecognitionIdentity.relationId(
                table.sheetId(), table.headerRange(), table.dataRange(), "ROW_TABLE");
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var bindingId = RecognitionIdentity.bindingId(fieldId, "TABLE_REGION",
                table.sheetId() + "|" + table.fullRange());
        var tableName = table.sheetName() + "数据记录";
        var dataPath = "/recognized/" + safePathSegment(table.sheetName(), relationId) + "/records";
        var columns = objectMapper.createArrayNode();
        var usedCodes = new HashSet<String>();
        var ordinal = 1;
        for (var item : table.headerCells()) {
            var name = item.cell().path("value").asText("").strip();
            var code = uniqueCode(safePathSegment(name, "column_" + ordinal), usedCodes, ordinal);
            var standard = standard(name);
            var valueType = tableValueType(name);
            var valueRange = excelRange(item.column(), table.dataStartRow(), item.column(), table.endRow());
            var column = objectMapper.createObjectNode()
                    .put("code", code)
                    .put("name", name)
                    .put("fieldName", name)
                    .put("fieldCode", standard == null
                            ? "AUTO.TABLE.COLUMN_" + RecognitionIdentity.shortHash(relationId + "|" + code, 10).toUpperCase(Locale.ROOT)
                            : standard.fieldCode())
                    .put("dataPath", dataPath + "/*/" + code)
                    .put("valueType", valueType)
                    .put("required", false)
                    .put("editability", "EDITABLE")
                    .put("valueSource", "USER_INPUT")
                    .put("labelRange", excelAddress(item.column(), table.headerRow()))
                    .put("valueRange", valueRange)
                    .put("dataStartRow", table.dataStartRow())
                    .put("standardMatchStatus", standard == null ? "UNMATCHED" : "MATCHED")
                    .put("standardRequired", false)
                    .put("requiresStandardConfirmation", false)
                    .put("fieldOrigin", standard == null ? "TEMPLATE_LOCAL" : "STANDARD")
                    .put("standardSelectionStatus", standard == null ? "CUSTOM" : "MATCHED")
                    .put("dictionaryVersion", standard == null
                            ? StandardFieldDictionary.VERSION : standard.version());
            columns.add(column);
            ordinal++;
        }
        var locator = objectMapper.createObjectNode()
                .put("sheetId", table.sheetId())
                .put("sheetName", table.sheetName())
                .put("address", table.fullRange())
                .put("range", table.fullRange())
                .put("headerRange", table.headerRange())
                .put("dataRange", table.dataRange())
                .put("logicalInputRange", table.fullRange())
                .put("recordRange", table.fullRange())
                .put("locatorType", "TABLE_REGION")
                .put("valueMode", "ARRAY_ROW");
        var payload = objectMapper.createObjectNode()
                .put("kind", "ROW_TABLE")
                .put("tableKind", "ROW_TABLE")
                .put("range", table.fullRange())
                .put("headerRange", table.headerRange())
                .put("dataRange", table.dataRange())
                .put("role", "REPEAT_REGION")
                .put("blockType", "ROW_TABLE")
                .put("relationId", relationId)
                .put("fieldId", fieldId.toString())
                .put("bindingId", bindingId.toString())
                .put("fieldCode", "AUTO.TABLE." + RecognitionIdentity.shortHash(relationId, 10).toUpperCase(Locale.ROOT))
                .put("fieldName", tableName)
                .put("groupName", "业务数据")
                .put("dataPath", dataPath)
                .put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT")
                .put("valueType", "array")
                .put("required", false)
                .put("locatorType", "TABLE_REGION")
                .put("mappingKind", "REPEAT_REGION")
                .put("repeatAxis", "ROW")
                .put("recordHeight", 1)
                .put("recordWidth", table.columnCount())
                .put("recordStride", 1)
                .put("reviewRequired", false)
                .put("autoAccept", true)
                .put("publishable", true)
                .put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED")
                .put("recognitionOrigin", "RULE_DETERMINISTIC")
                .put("reasonCode", "SIMPLE_LONG_TABLE")
                .put("reason", "检测到单行表头的规则长表，按行生成可重复录入区域")
                .put("interpretation", "每一行填写一条记录，字段值从对应列读取")
                .put("standardMatchStatus", "NOT_APPLICABLE")
                .put("requiresStandardConfirmation", false)
                .put("fieldOrigin", "TEMPLATE_LOCAL")
                .put("standardSelectionStatus", "CUSTOM")
                .put("regionId", relationId)
                .put("blockId", relationId)
                .put("suggestionLevel", "ROOT")
                .put("dataStartRow", table.dataStartRow());
        payload.set("columns", columns);
        payload.set("locator", locator);
        payload.set("terminationRule", objectMapper.createObjectNode()
                .put("type", "UNTIL_EMPTY_RECORD")
                .put("maxRecords", table.maxRecords()));
        payload.set("longTableModel", objectMapper.createObjectNode()
                .put("mode", "ROW_TABLE")
                .put("recordAxis", "ROW")
                .put("headerRange", table.headerRange())
                .put("dataRange", table.dataRange())
                .put("output", "ONE_RECORD_PER_ROW")
                .put("maxRecords", table.maxRecords()));
        payload.set("recordProjection", objectMapper.createObjectNode()
                .put("kind", "ROW_TABLE")
                .put("sourceRange", table.dataRange())
                .put("recordAxis", "ROW")
                .put("headerRange", table.headerRange()));
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("sheetId", table.sheetId())
                .put("headerRange", table.headerRange())
                .put("dataRange", table.dataRange())
                .put("rule", "ONE_HEADER_ROW_CONTIGUOUS_COLUMNS"));
        return new RecognitionModelClient.ModelSuggestion(
                "TABLE_REGION", payload, 0.99, evidence);
    }

    private boolean contiguous(List<CellPosition> cells) {
        var columns = cells.stream().mapToInt(CellPosition::column).sorted().toArray();
        for (var index = 1; index < columns.length; index++) {
            if (columns[index] != columns[index - 1] + 1) return false;
        }
        return true;
    }

    private boolean uniqueHeader(List<CellPosition> cells) {
        var names = new HashSet<String>();
        return cells.stream().map(item -> item.cell().path("value").asText("").strip()
                        .toLowerCase(Locale.ROOT))
                .allMatch(name -> !name.isBlank() && !name.contains(":") && !name.contains("：")
                        && name.length() <= 80 && names.add(name));
    }

    private boolean headerLooksLikeFields(List<CellPosition> cells) {
        return cells.stream().map(item -> item.cell().path("value").asText("").strip())
                .anyMatch(name -> standard(name) != null
                        || name.matches(".*(名称|编号|编码|代码|批号|日期|时间|类型|状态|规格|单位|供应商|型号|原料|物料|产品|质量|结果|成本|数量|等级|类别|成分|粘度|酸值|羟值|外观|含量|比例|率|值|NCO).*" )
                        || name.matches("(?i).*(name|id|code|date|time|type|value|status|amount|qty|number|batch|material|product).*"));
    }

    private String uniqueCode(String base, Set<String> used, int ordinal) {
        var candidate = base.isBlank() ? "column_" + String.format(Locale.ROOT, "%02d", ordinal) : base;
        if (used.add(candidate)) return candidate;
        var suffix = 2;
        while (!used.add(candidate + "_" + suffix)) suffix++;
        return candidate + "_" + suffix;
    }

    private String tableValueType(String name) {
        var normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.contains("日期") || normalized.contains("时间")
                || normalized.contains("date") || normalized.contains("time")) return "date";
        return "string";
    }

    private List<JsonNode> semanticCellsOfSheet(JsonNode sheet) {
        var result = new ArrayList<JsonNode>();
        if (sheet.path("semanticCells").isArray()) sheet.path("semanticCells").forEach(result::add);
        return result;
    }

    private CellPosition position(JsonNode cell) {
        var row = cell.path("row").asInt(0);
        var column = cell.path("column").asInt(0);
        if (row > 0 && column > 0) return new CellPosition(cell, row, column);
        var address = cell.path("address").asText("").replace("$", "").toUpperCase(Locale.ROOT);
        var match = Pattern.compile("^([A-Z]+)([1-9][0-9]*)$").matcher(address);
        if (!match.matches()) return null;
        var parsedColumn = 0;
        for (var letter : match.group(1).toCharArray()) parsedColumn = parsedColumn * 26 + letter - 'A' + 1;
        return new CellPosition(cell, Integer.parseInt(match.group(2)), parsedColumn);
    }

    private String excelRange(int startColumn, int startRow, int endColumn, int endRow) {
        var start = excelAddress(startColumn, startRow);
        var end = excelAddress(endColumn, endRow);
        return start.equals(end) ? start : start + ":" + end;
    }

    private String excelAddress(int column, int row) {
        var result = new StringBuilder();
        for (var value = Math.max(1, column); value > 0; value = (value - 1) / 26) {
            result.insert(0, (char) ('A' + (value - 1) % 26));
        }
        return result + Integer.toString(Math.max(1, row));
    }

    private record CellPosition(JsonNode cell, int row, int column) {
    }

    private record SimpleLongTable(
            String sheetId, String sheetName, int headerRow, int startColumn, int endColumn,
            int endRow, String headerRange, String dataRange, String fullRange,
            List<CellPosition> headerCells
    ) {
        int columnCount() {
            return endColumn - startColumn + 1;
        }

        int dataStartRow() {
            return headerRow + 1;
        }

        int maxRecords() {
            return Math.max(1, endRow - headerRow);
        }
    }

    private List<RecognitionModelClient.ModelSuggestion> explicitLabelValueCandidates(JsonNode structure) {
        var byPosition = new HashMap<String, JsonNode>();
        var cells = semanticCells(structure);
        for (var cell : cells) {
            byPosition.put(position(cell.path("sheetId").asText(), cell.path("row").asInt(),
                    cell.path("column").asInt()), cell);
        }
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        for (var label : cells) {
            if (!label.path("value").isTextual() || label.path("formula").isTextual()) continue;
            var text = label.path("value").asText("");
            var sheetId = label.path("sheetId").asText();
            var inline = INLINE_LABEL.matcher(text);
            if (inline.matches()) {
                if (STATIC_PREFIXES.contains(inline.group(1).strip())) continue;
                // Text already present after the delimiter is source metadata
                // (for example "表单编号:JSD-QF-SC-001"), not a blank runtime
                // input surface. Preserve it in the workbook, but do not turn
                // it into an editable template field.
                continue;
            }
            var matcher = EXPLICIT_LABEL.matcher(text);
            if (!matcher.matches()) continue;
            var row = label.path("row").asInt();
            var column = label.path("column").asInt();
            var merged = label.path("mergedRange").asText("");
            if (!merged.isBlank() && lastColumn(merged) > column) {
                result.add(candidate(label, label, matcher.group(1).strip(),
                        merged, "MERGED_INLINE_LABEL", true));
                continue;
            }
            var rightColumn = Math.max(column, lastColumn(merged)) + 1;
            var bottomRow = Math.max(row, lastRow(merged)) + 1;
            // 兜底规则只保留一个最明确的邻接值：横向优先，只有横向没有值时
            // 才尝试纵向，避免同一标签生成互相竞争的两个候选。
            var horizontal = byPosition.get(position(sheetId, row, rightColumn));
            var vertical = byPosition.get(position(sheetId, bottomRow, column));
            var adjacent = validAdjacent(merged, horizontal, false)
                    ? new Adjacent("HORIZONTAL_LABEL_VALUE", horizontal)
                    : new Adjacent("VERTICAL_LABEL_VALUE", vertical);
            if (validAdjacent(merged, adjacent.value(),
                    "VERTICAL_LABEL_VALUE".equals(adjacent.relationType()))) {
                var value = adjacent.value();
                result.add(candidate(label, value, matcher.group(1).strip(),
                        value.path("mergedRange").asText(value.path("address").asText()),
                        adjacent.relationType(), false));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Content controls are deterministic Word fields. Plain label paragraphs
     * are also surfaced as review-only candidates so a document still has a
     * useful field model when no external semantic model is configured. They
     * intentionally remain unbound until the user inserts a real content
     * control at the chosen position.
     */
    private List<RecognitionModelClient.ModelSuggestion> docxContentControlCandidates(JsonNode structure) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var occupied = new java.util.HashSet<String>();
        var controls = structure.path("documentIR").path("contentControls");
        if (!controls.isArray()) controls = structure.path("contentControls");
        for (var control : controls) {
            var nodeId = control.path("nodeId").asText("");
            var contentControlId = control.path("contentControlId").asText("");
            var documentMarkerId = control.path("markerId").asText("");
            var tag = control.path("tag").asText("").strip();
            var alias = control.path("alias").asText("").strip();
            var text = control.path("text").asText("").strip();
            var name = firstNonPlaceholder(alias, tag, text);
            // w:id/contentControlId identifies the OOXML element only.  It is
            // not a data marker and cannot be used as a publishable binding;
            // only the explicit w:dataBinding storeItemID is stable across
            // controlled patches and document revisions.
            var markerId = documentMarkerId;
            if (nodeId.isBlank()) continue;
            var stableMarker = !documentMarkerId.isBlank();
            var fieldSeed = markerId + "|" + name;
            var fieldId = RecognitionIdentity.fieldId(RecognitionIdentity.relationId(
                    "docx", nodeId, markerId, "DOCX_CONTENT_CONTROL"));
            var fieldCode = tag.isBlank()
                    ? "AUTO.WORD.FIELD_" + RecognitionIdentity.shortHash(fieldSeed, 12).toUpperCase(Locale.ROOT)
                    : tag;
            var dataPath = "/recognized/word/" + safePathSegment(name, fieldId.toString());
            var payload = objectMapper.createObjectNode()
                    .put("kind", "SCALAR")
                    .put("role", "FIELD")
                    .put("blockType", "FORM_REGION")
                    .put("blockName", "Word 文档字段")
                    .put("fieldId", fieldId.toString())
                    .put("fieldCode", fieldCode)
                    .put("fieldName", name.isBlank() ? "待命名字段" : name)
                    .put("dataPath", dataPath)
                    .put("editability", "EDITABLE")
                    .put("valueSource", "USER_INPUT")
                    .put("valueType", "string")
                    .put("required", false)
                    .put("locatorType", "DOCX_CONTENT_CONTROL")
                    .put("markerId", markerId)
                    .put("source", "DOCX_CONTENT_CONTROL")
                    .put("candidateOnly", !stableMarker)
                    .put("reviewRequired", true)
                    .put("publishable", stableMarker)
                    .put("autoAccept", stableMarker)
                    .put("pendingReason", stableMarker ? "DOCX_FIELD_REVIEW" : "DOCX_MARKER_MISSING")
                    .put("standardMatchStatus", "UNMATCHED")
                    .put("requiresStandardConfirmation", false)
                    .put("groupName", GroupNameNormalizer.BASIC_INFORMATION)
                    .put("regionId", "docx-document")
                    .put("blockId", "docx-document")
                    .put("regionRange", "DOCX")
                    .put("candidateRef", nodeId);
            payload.set("locator", objectMapper.createObjectNode()
                    .put("nodeId", nodeId)
                    .put("markerId", markerId)
                    .put("contentControlId", contentControlId)
                    .put("tag", tag)
                    .put("alias", alias)
                    .put("text", text)
                    .put("locatorType", "DOCX_CONTENT_CONTROL"));
            var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                    .put("nodeId", nodeId).put("markerId", markerId)
                    .put("source", "DOCX_CONTENT_CONTROL"));
            result.add(new RecognitionModelClient.ModelSuggestion(
                    "SCALAR_FIELD", payload, stableMarker ? 0.98 : 0.65, evidence));
            occupied.add(nodeId);
        }

        var documentIr = structure.path("documentIR");
        var blocks = documentIr.path("blocks");
        if (!blocks.isArray()) blocks = structure.path("blocks");
        for (var block : blocks) {
            if (!"PARAGRAPH".equals(block.path("type").asText(""))) continue;
            var nodeId = block.path("id").asText("").strip();
            var text = block.path("text").asText("").strip();
            if (nodeId.isBlank() || text.isBlank() || occupied.contains(nodeId)) continue;
            var fieldName = docxLabelName(text);
            if (fieldName.isBlank() || STATIC_PREFIXES.contains(fieldName)) continue;
            result.add(docxTextLabelCandidate(nodeId, text, fieldName));
        }
        return List.copyOf(result);
    }

    private String docxLabelName(String text) {
        var inline = INLINE_LABEL.matcher(text);
        if (inline.matches()) return inline.group(1).strip();
        var explicit = EXPLICIT_LABEL.matcher(text);
        if (explicit.matches()) return explicit.group(1).strip();
        var numbered = NUMBERED_LABEL.matcher(text);
        return numbered.matches() ? numbered.group(1).strip() : "";
    }

    private RecognitionModelClient.ModelSuggestion docxTextLabelCandidate(
            String nodeId, String text, String fieldName
    ) {
        var relationId = RecognitionIdentity.relationId(
                "DOCX", nodeId, fieldName, "DOCX_TEXT_LABEL");
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var markerSeed = relationId + "|" + fieldName;
        var fieldCode = "AUTO.WORD.FIELD_"
                + RecognitionIdentity.shortHash(markerSeed, 12).toUpperCase(Locale.ROOT);
        var dataPath = "/recognized/word/" + safePathSegment(fieldName, fieldId.toString());
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR")
                .put("role", "FIELD")
                .put("relationId", relationId)
                .put("fieldId", fieldId.toString())
                .put("fieldCode", fieldCode)
                .put("fieldName", fieldName)
                .put("dataPath", dataPath)
                .put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT")
                .put("valueType", "string")
                .put("required", false)
                .put("locatorType", "DOCX_TEXT_LABEL")
                .put("source", "DOCX_TEXT_LABEL")
                .put("candidateOnly", true)
                .put("reviewRequired", true)
                .put("publishable", false)
                .put("autoAccept", false)
                .put("pendingReason", "DOCX_FIELD_POSITION_REQUIRED")
                .put("standardMatchStatus", "UNMATCHED")
                .put("requiresStandardConfirmation", false)
                .put("groupName", GroupNameNormalizer.BASIC_INFORMATION)
                .put("regionId", "docx-document")
                .put("blockId", "docx-document")
                .put("regionRange", "DOCX")
                .put("candidateRef", nodeId)
                .put("labelAnchor", nodeId)
                .put("valueAnchor", nodeId);
        payload.set("locator", objectMapper.createObjectNode()
                .put("nodeId", nodeId)
                .put("labelAnchor", nodeId)
                .put("valueAnchor", nodeId)
                .put("text", text)
                .put("locatorType", "DOCX_TEXT_LABEL"));
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("nodeId", nodeId)
                .put("text", text)
                .put("source", "DOCX_TEXT_LABEL_RULE"));
        return new RecognitionModelClient.ModelSuggestion(
                "SCALAR_FIELD", payload, 0.72, evidence);
    }

    private String firstNonPlaceholder(String... values) {
        for (var value : values) {
            if (value == null) continue;
            var normalized = value.strip();
            if (normalized.isBlank()) continue;
            if (normalized.matches("(?i)^(请输入|点击此处|单击此处|输入|placeholder).*$")) continue;
            return normalized;
        }
        return "";
    }

    private String safePathSegment(String value, String fallback) {
        var normalized = value == null ? "" : value.strip().replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return normalized.isBlank() ? "field_" + RecognitionIdentity.shortHash(fallback, 10) : normalized;
    }

    private List<JsonNode> semanticCells(JsonNode structure) {
        var result = new ArrayList<JsonNode>();
        for (var sheet : structure.path("sheets")) {
            if (sheet.path("semanticCells").isArray()) sheet.path("semanticCells").forEach(result::add);
        }
        return result;
    }

    private RecognitionModelClient.ModelSuggestion candidate(
            JsonNode label, JsonNode value, String fieldName, String valueRange,
            String relationType, boolean inline
    ) {
        var sheetId = label.path("sheetId").asText();
        var labelAddress = label.path("address").asText().toUpperCase(Locale.ROOT);
        var normalizedValueRange = valueRange.toUpperCase(Locale.ROOT);
        var relationId = RecognitionIdentity.relationId(sheetId, labelAddress, normalizedValueRange, relationType);
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var locatorType = inline ? "INLINE_TEXT" : "CELL_RANGE";
        var bindingId = RecognitionIdentity.bindingId(fieldId, locatorType, sheetId + "|" + normalizedValueRange);
        var formula = !inline && value.path("factType").asText().equals("FORMULA");
        var standard = standard(fieldName);
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR").put("relationId", relationId)
                .put("fieldId", fieldId.toString()).put("bindingId", bindingId.toString())
                .put("fieldCode", standard == null ? "AUTO.BASIC_INFORMATION.FIELD_"
                        + RecognitionIdentity.shortHash(relationId, 8).toUpperCase(Locale.ROOT)
                        : standard.fieldCode())
                .put("dataPath", standard == null ? "/recognized/basicInformation/field_"
                        + RecognitionIdentity.shortHash(relationId, 12)
                        : "/recognized/basicInformation/" + standard.pathSegment())
                // fieldName 保留原始模板标签，标准字段使用 fieldCode/dataPath 追踪；
                // 这样审核页仍能看到用户在 Excel 中实际写的名称。
                .put("fieldName", fieldName)
                .put("groupName", GroupNameNormalizer.BASIC_INFORMATION)
                .put("valueType", inline ? "string" : physicalValueType(value)).put("required", false)
                .put("role", "FIELD").put("locatorType", locatorType)
                .put("editability", formula ? "READ_ONLY" : "EDITABLE")
                .put("valueSource", formula ? "FORMULA" : "USER_INPUT")
                .put("dictionaryVersion", standard == null ? StandardFieldDictionary.VERSION : standard.version())
                .put("standardMatchStatus", standard == null ? "UNMATCHED" : "MATCHED")
                 .put("requiresStandardConfirmation", false)
                 .put("fieldOrigin", standard == null ? "TEMPLATE_LOCAL" : "STANDARD")
                 .put("standardSelectionStatus", standard == null ? "CUSTOM" : "MATCHED")
                 .put("requiresManualConfirmation", true)
                 .put("source", "RULE")
                 .put("reasonCode", "RULE_FALLBACK")
                .put("reason", "根据明确的标签和值位置生成待核对候选")
                .put("interpretation", "系统发现明确标签，请核对名称和填写位置。");
        var locator = objectMapper.createObjectNode()
                .put("sheetId", sheetId).put("sheetName", label.path("sheetName").asText(sheetId))
                .put("labelAddress", labelAddress).put("labelRange", labelAddress)
                .put("address", normalizedValueRange).put("anchorAddress", firstCell(normalizedValueRange))
                .put("logicalInputRange", normalizedValueRange).put("valueMode", inline ? "INLINE_TEXT" : "ANCHOR");
        if (inline) {
            locator.put("valuePart", "AFTER_DELIMITER").put("labelPrefix", fieldName);
        }
        payload.set("locator", locator);
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("labelAddress", labelAddress).put("valueAddress", normalizedValueRange)
                .put("rule", relationType));
        return new RecognitionModelClient.ModelSuggestion("SCALAR_FIELD", payload, 0.58, evidence);
    }

    private ResolvedStandard standard(String label) {
        if (standardFieldRepository != null) {
            try {
                var match = standardFieldRepository.search(label, null).stream().findFirst();
                if (match.isPresent()) {
                    var value = match.get();
                    return new ResolvedStandard(value.fieldCode(), value.version(),
                            pathSegment(value.fieldCode()));
                }
            } catch (RuntimeException ignored) {
                // Keep the conservative fallback available for offline imports.
            }
        }
        return StandardFieldDictionary.match(label)
                .map(value -> new ResolvedStandard(value.fieldCode(), StandardFieldDictionary.VERSION,
                        value.pathSegment()))
                .orElse(null);
    }

    private String pathSegment(String fieldCode) {
        var value = fieldCode == null ? "field" : fieldCode.substring(fieldCode.lastIndexOf('.') + 1);
        var result = new StringBuilder();
        for (var token : value.toLowerCase(Locale.ROOT).split("_")) {
            if (token.isBlank()) continue;
            result.append(result.isEmpty() ? token : Character.toUpperCase(token.charAt(0)) + token.substring(1));
        }
        return result.isEmpty() ? "field" : result.toString();
    }

    private record ResolvedStandard(String fieldCode, int version, String pathSegment) {
    }

    private String physicalValueType(JsonNode cell) {
        return switch (cell.path("physicalValueType").asText("").toLowerCase(Locale.ROOT)) {
            case "number", "numeric" -> "number";
            case "boolean" -> "boolean";
            default -> "string";
        };
    }

    private String position(String sheetId, int row, int column) {
        return sheetId + "|" + row + "|" + column;
    }

    private String firstCell(String range) {
        return range.split(":", 2)[0];
    }

    private int lastColumn(String range) {
        if (range == null || range.isBlank()) return 0;
        var cell = range.toUpperCase(Locale.ROOT).split(":", 2);
        var letters = cell[cell.length - 1].replaceAll("[0-9]+$", "");
        var result = 0;
        for (var letter : letters.toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private int lastRow(String range) {
        if (range == null || range.isBlank()) return 0;
        var cell = range.toUpperCase(Locale.ROOT).split(":", 2);
        var digits = cell[cell.length - 1].replaceAll("^[A-Z]+", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean validAdjacent(String labelMergedRange, JsonNode value, boolean vertical) {
        if (value == null || value.isMissingNode() || value.isNull()) return false;
        if (labelMergedRange != null && !labelMergedRange.isBlank()
                && labelMergedRange.equalsIgnoreCase(value.path("mergedRange").asText(""))) return false;
        var text = value.path("value").asText("").strip();
        if (text.isBlank()) return value.path("inputCandidate").asBoolean(false)
                || "INPUT_CANDIDATE".equals(value.path("factType").asText(""));
        // A populated cell immediately below a label is much more likely to
        // be the next label/section than a writable value. Horizontal cells
        // may legitimately contain an example/default value, so keep those.
        if (vertical && !value.path("inputCandidate").asBoolean(false)
                && !"INPUT_CANDIDATE".equals(value.path("factType").asText(""))
                && !"FORMULA".equals(value.path("factType").asText(""))) return false;
        if (EXPLICIT_LABEL.matcher(text).matches() || INLINE_LABEL.matcher(text).matches()) return false;
        return !STATIC_PREFIXES.contains(text.replaceAll("[：:]$", ""));
    }

    private record Adjacent(String relationType, JsonNode value) {
    }
}
