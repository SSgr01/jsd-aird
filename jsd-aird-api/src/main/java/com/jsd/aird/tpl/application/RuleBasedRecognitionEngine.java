package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * unambiguous row-table shape. Ambiguous table/block layouts remain on the
 * model and review path.
 */
@Component
public class RuleBasedRecognitionEngine {

    private static final int MAX_TEMPLATE_ROWS = 200;

    private static final Pattern EXPLICIT_LABEL = Pattern.compile("^\\s*([^：:\\r\\n]{1,30})[：:]\\s*$");
    private static final Pattern INLINE_LABEL = Pattern.compile("^\\s*([^：:\\r\\n]{1,30})[：:]\\s*(.+?)\\s*$");
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
        var docx = format == TemplateFormat.DOCX ? docxCandidates(structure) : null;
        var suggestions = format == TemplateFormat.XLSX
                ? simpleRowTableCandidates(structure)
                : docx.suggestions();
        if (format == TemplateFormat.XLSX && suggestions.isEmpty()) {
            suggestions = new ArrayList<>(suggestions);
            suggestions.addAll(explicitLabelValueCandidates(structure));
        }
        return new RecognitionModelClient.RecognitionBatch(
                suggestions, docx == null ? List.of() : docx.issues(),
                "physical-facts", "conservative-label-value-v6",
                "physical-fallback-v6", fingerprint,
                canonicalizer.hashText(sourceFileName + "|" + fingerprint), null
        );
    }

    /**
     * A strict one-header-row table is a physical contract, not a semantic
     * guessing problem.  Returning true lets the import service avoid a model
     * call for the common single-header row table while still leaving ambiguous
     * layouts to the model/review path.
     */
    public boolean isSimpleRowTableWorkbook(JsonNode structure) {
        var found = false;
        for (var sheet : structure.path("sheets")) {
            if (sheet.path("hidden").asBoolean(false)) continue;
            if (semanticCellsOfSheet(sheet).isEmpty()) continue;
            if (simpleRowTable(sheet) == null) return false;
            found = true;
        }
        return found;
    }

    private List<RecognitionModelClient.ModelSuggestion> simpleRowTableCandidates(JsonNode structure) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        for (var sheet : structure.path("sheets")) {
            if (sheet.path("hidden").asBoolean(false)) continue;
            var table = simpleRowTable(sheet);
            if (table != null) {
                var parent = simpleRowTableSuggestion(table);
                result.add(parent);
                result.addAll(simpleRowTableFieldSuggestions(parent));
            }
        }
        return List.copyOf(result);
    }

    /**
     * A simple row table has an unambiguous physical contract: the first
     * contiguous text row is the header and each following column is one
     * repeat field. Keep these children as reviewable suggestions so the user
     * can confirm the actual fields without triggering semantic recognition.
     */
    public List<RecognitionModelClient.ModelSuggestion> simpleRowTableFieldSuggestions(
            RecognitionModelClient.ModelSuggestion parent
    ) {
        if (parent == null || !"SIMPLE_ROW_TABLE".equals(parent.payload().path("reasonCode").asText())
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
                    .put("pendingReason", "SIMPLE_ROW_TABLE_FIELD_REVIEW")
                    .put("nameSource", "PHYSICAL_HEADER_FALLBACK")
                    .put("semanticFallback", true)
                    .put("recognitionOrigin", "RULE_DETERMINISTIC")
                    .put("reasonCode", "SIMPLE_ROW_TABLE_FIELD")
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

    private SimpleRowTable simpleRowTable(JsonNode sheet) {
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
        // columns. This rejects side notes and two-axis reports without guessing.
        for (var entry : rows.tailMap(headerRow + 1).entrySet()) {
            if (entry.getValue().stream().anyMatch(item ->
                    item.column() < startColumn || item.column() > endColumn)) return null;
        }
        var sheetId = sheet.path("id").asText(sheet.path("sheetId").asText(""));
        if (sheetId.isBlank()) return null;
        var sheetName = sheet.path("name").asText(sheetId);
        // Keep physical geometry faithful to the source.  Capacity for future
        // records is runtime metadata, not an artificial row-200 region.
        // Keep one concrete input row for a header-only template so the
        // physical data range remains valid.  Additional entry capacity is
        // carried separately as runtimeCapacity and never expands geometry.
        var endRow = Math.max(headerRow + 1, sheet.path("lastRow").asInt(headerRow));
        var headerRange = excelRange(startColumn, headerRow, endColumn, headerRow);
        var dataStartRow = headerRow + 1;
        var dataRange = excelRange(startColumn, dataStartRow, endColumn, endRow);
        var fullRange = excelRange(startColumn, headerRow, endColumn, endRow);
        return new SimpleRowTable(sheetId, sheetName, headerRow, startColumn, endColumn, endRow,
                headerRange, dataRange, fullRange, headerCells);
    }

    private RecognitionModelClient.ModelSuggestion simpleRowTableSuggestion(SimpleRowTable table) {
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
                .put("reasonCode", "SIMPLE_ROW_TABLE")
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
        payload.put("runtimeCapacity", table.runtimeCapacity());
        payload.set("columns", columns);
        payload.set("locator", locator);
        payload.set("terminationRule", objectMapper.createObjectNode()
                .put("type", "UNTIL_EMPTY_RECORD")
                .put("maxRecords", table.runtimeCapacity()));
        payload.set("tableModel", objectMapper.createObjectNode()
                .put("headerRange", table.headerRange())
                .put("dataRange", table.dataRange())
                .put("repeatAxis", "ROW")
                .put("recordHeight", 1)
                .put("recordWidth", table.columnCount())
                .put("recordStride", 1)
                .set("columns", columns.deepCopy()));
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("sheetId", table.sheetId())
                .put("headerRange", table.headerRange())
                .put("dataRange", table.dataRange())
                .put("rule", "ONE_HEADER_ROW_CONTIGUOUS_COLUMNS"));
        return new RecognitionModelClient.ModelSuggestion(
                "ROW_TABLE", payload, 0.99, evidence);
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

    private record SimpleRowTable(
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

        int runtimeCapacity() {
            return Math.max(MAX_TEMPLATE_ROWS - headerRow, maxRecords());
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

    private DocxCandidates docxCandidates(JsonNode structure) {
        var regions = new LinkedHashMap<String, DocxTableRegion>();
        var suggestions = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var issues = new ArrayList<RecognitionModelClient.QualityIssueSuggestion>();
        var documentIr = structure.path("documentIR").isObject()
                ? structure.path("documentIR") : structure;
        var tables = documentIr.path("tables").isArray() && !documentIr.path("tables").isEmpty()
                ? documentIr.path("tables") : documentIr.path("blocks");
        for (var table : tables) {
            if (!"TABLE".equals(table.path("type").asText(""))
                    || table.path("layoutContainer").asBoolean(false)) continue;
            var classification = classifyDocxTable(table);
            if (classification.type().isBlank()) {
                if (classification.ambiguous()) issues.add(docxDirectionIssue(table));
                continue;
            }
            var region = docxTableRegion(table, classification);
            regions.put(table.path("sourcePath").asText(""), region);
            if (region.suggestion() != null) suggestions.add(region.suggestion());
            suggestions.addAll(docxTableFieldCandidates(table, classification, region));
        }
        suggestions.addAll(docxContentControlCandidates(structure, regions));
        return new DocxCandidates(List.copyOf(suggestions), List.copyOf(issues));
    }

    /**
     * Separates form pairs from repeated record surfaces. A normal column table
     * deliberately has labels on both the top and left; that is not ambiguity.
     */
    private DocxTableClassification classifyDocxTable(JsonNode table) {
        var rows = table.path("rows");
        var rowCount = table.path("rowCount").asInt(rows.size());
        var columnCount = table.path("columnCount").asInt();
        if (!rows.isArray() || rowCount < 2 || columnCount < 2) {
            return new DocxTableClassification("", "", false, 0, 0);
        }

        var formPairs = 0;
        var formPairRows = 0;
        var logicalSlots = 0;
        for (var row : rows) {
            var rowPairs = 0;
            var cells = row.path("cells");
            logicalSlots += Math.max(columnCount, cells.size());
            for (var index = 0; index + 1 < cells.size(); index += 2) {
                var label = cells.path(index);
                var value = cells.path(index + 1);
                if (docxLabelCell(label) && docxValueCell(value)) rowPairs++;
            }
            if (rowPairs > 0) formPairRows++;
            formPairs += rowPairs;
        }
        var formCoverage = logicalSlots == 0 ? 0d : (formPairs * 2d) / logicalSlots;
        if (formPairs >= 2 && formPairRows >= Math.max(1, rowCount - 1) && formCoverage >= 0.70d) {
            return new DocxTableClassification("FORM_REGION", "", false, 0, 0);
        }

        var topCells = rows.path(0).path("cells");
        var topLabels = 0;
        var topOccupiedColumns = 0;
        for (var cell : topCells) {
            var value = cell.path("text").asText("").strip();
            if (value.isBlank()) continue;
            topOccupiedColumns += Math.max(1, cell.path("columnSpan").asInt(1));
            if (looksLikeFieldLabel(value)) topLabels++;
        }
        var repeatedBodyRows = 0;
        var rowIdentityRows = 0;
        for (var index = 1; index < rows.size(); index++) {
            var row = rows.path(index);
            var present = 0;
            for (var cell : row.path("cells")) {
                if (!cell.path("mergeContinuation").asBoolean(false)) {
                    present += Math.max(1, cell.path("columnSpan").asInt(1));
                }
            }
            if (present >= Math.max(2, columnCount - 1)) repeatedBodyRows++;
            var first = cellAt(row, 1);
            if (first != null && !first.path("text").asText("").strip().isBlank()
                    && !looksLikeFieldLabel(first.path("text").asText(""))) rowIdentityRows++;
        }
        var rowCandidate = topLabels >= 2
                && topOccupiedColumns >= Math.max(2, (int) Math.ceil(columnCount * 0.65d))
                && repeatedBodyRows >= 2;

        var labelBandVotes = new HashMap<Integer, Integer>();
        var identityCue = false;
        var sampleHeadingCue = false;
        for (var row : rows) {
            var bandEnd = leadingLabelBandEnd(row, columnCount);
            if (bandEnd > 0 && columnCount - bandEnd >= 2) {
                labelBandVotes.merge(bandEnd, 1, Integer::sum);
            }
            var rowText = leftBandText(row, Math.max(1, bandEnd));
            if (rowText.matches(".*(?:样品|试验|实验|配方|批号|编号).*")) identityCue = true;
            var firstCell = cellAt(row, 1);
            var firstEnd = firstCell == null ? 0
                    : firstCell.path("logicalColumnEnd").asInt(firstCell.path("columnIndex").asInt(1));
            var trailingIdentityCells = 0;
            var physicalIndex = 0;
            for (var cell : row.path("cells")) {
                physicalIndex++;
                var value = cell.path("text").asText("").strip();
                if (value.matches("(?i)^\\d+[#＃]$")
                        || value.matches("(?i)^(?:样品|试验|实验|配方)\\s*[甲乙丙丁A-Z0-9#＃-]+$")) {
                    sampleHeadingCue = true;
                    if (logicalColumnStart(cell, physicalIndex) > firstEnd) {
                        trailingIdentityCells++;
                    }
                }
            }
            if (firstEnd > 0 && columnCount - firstEnd >= 2 && trailingIdentityCells >= 2) {
                labelBandVotes.merge(firstEnd, 2, Integer::sum);
            }
        }
        var labelBandWidth = labelBandVotes.entrySet().stream()
                .max(java.util.Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(Map.Entry::getKey))
                .map(Map.Entry::getKey).orElse(0);
        var columnLabelRows = 0;
        if (labelBandWidth > 0) {
            for (var row : rows) {
                var hasLeftLabel = false;
                var physicalIndex = 0;
                for (var cell : row.path("cells")) {
                    physicalIndex++;
                    if (logicalColumnStart(cell, physicalIndex) > labelBandWidth) continue;
                    if (docxLabelCell(cell)) hasLeftLabel = true;
                }
                if (hasLeftLabel) columnLabelRows++;
            }
        }
        var columnCandidate = labelBandWidth > 0 && columnCount - labelBandWidth >= 2
                && columnLabelRows >= 2;

        if (columnCandidate && (identityCue || sampleHeadingCue)) {
            return new DocxTableClassification("COLUMN_TABLE", "COLUMN", false, labelBandWidth, 0);
        }
        if (rowCandidate && !columnCandidate) {
            return new DocxTableClassification("ROW_TABLE", "ROW", false, 0, 1);
        }
        if (columnCandidate && !rowCandidate) {
            return new DocxTableClassification("COLUMN_TABLE", "COLUMN", false, labelBandWidth, 0);
        }
        if (rowCandidate && columnCandidate) {
            if (rowIdentityRows >= 2) {
                return new DocxTableClassification("ROW_TABLE", "ROW", false, 0, 1);
            }
            return new DocxTableClassification("", "", true, labelBandWidth, 0);
        }
        return new DocxTableClassification("", "", false, labelBandWidth, 0);
    }

    private boolean docxLabelCell(JsonNode cell) {
        return cell != null && !cell.path("mergeContinuation").asBoolean(false)
                && looksLikeFieldLabel(cell.path("text").asText(""));
    }

    private boolean docxValueCell(JsonNode cell) {
        if (cell == null || cell.path("mergeContinuation").asBoolean(false)) return false;
        var value = cell.path("text").asText("").strip();
        return cell.path("editable").asBoolean(true)
                && (value.isBlank() || !looksLikeFieldLabel(value));
    }

    private int leadingLabelBandEnd(JsonNode row, int columnCount) {
        var end = 0;
        for (var column = 1; column <= columnCount; column++) {
            var cell = cellAt(row, column);
            if (cell == null) break;
            var structuralContinuation = cell.path("mergeContinuation").asBoolean(false);
            var value = cell.path("text").asText("").strip();
            if (!structuralContinuation && (value.isBlank() || !looksLikeFieldLabel(value))) break;
            end = Math.max(end, cell.path("logicalColumnEnd").asInt(column));
            column = end;
        }
        return end;
    }

    private String leftBandText(JsonNode row, int bandWidth) {
        var values = new ArrayList<String>();
        for (var cell : row.path("cells")) {
            if (cell.path("logicalColumnStart").asInt(cell.path("columnIndex").asInt()) > bandWidth) continue;
            var value = cell.path("text").asText("").strip();
            if (!value.isBlank()) values.add(value);
        }
        return String.join(" ", values);
    }

    private JsonNode cellAt(JsonNode row, int logicalColumn) {
        if (row == null) return null;
        var physicalIndex = 0;
        for (var cell : row.path("cells")) {
            physicalIndex++;
            var start = logicalColumnStart(cell, physicalIndex);
            var end = cell.path("logicalColumnEnd").asInt(start + cell.path("columnSpan").asInt(1) - 1);
            if (logicalColumn >= start && logicalColumn <= end) return cell;
        }
        return null;
    }

    private int logicalColumnStart(JsonNode cell, int physicalFallback) {
        return cell.path("logicalColumnStart").asInt(
                cell.path("columnIndex").asInt(Math.max(1, physicalFallback)));
    }

    private boolean looksLikeFieldLabel(String value) {
        var normalized = value == null ? "" : value.replaceAll("[：:]$", "").strip();
        if (normalized.length() < 2 || normalized.length() > 40) return false;
        if (normalized.matches("(?i)^[a-z0-9#._/-]{1,12}$")) return false;
        return !normalized.matches("^[+-]?(?:\\d+(?:\\.\\d+)?)%?$" );
    }

    private DocxTableRegion docxTableRegion(JsonNode table, DocxTableClassification classification) {
        var nodeId = table.path("id").asText();
        var sourcePath = table.path("sourcePath").asText();
        var rowCount = table.path("rowCount").asInt(table.path("rows").size());
        var columnCount = table.path("columnCount").asInt(1);
        var type = classification.type();
        var axis = classification.axis();
        var relationId = RecognitionIdentity.relationId("DOCX", nodeId, sourcePath, type);
        if ("FORM_REGION".equals(type)) {
            return new DocxTableRegion(sourcePath, relationId, type, "", null,
                    classification.labelBandWidth(), classification.headerDepth());
        }
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var bindingId = RecognitionIdentity.bindingId(fieldId, "DOCX_TABLE_REGION", sourcePath);
        var payload = objectMapper.createObjectNode()
                .put("kind", type).put("tableKind", type).put("blockType", type)
                .put("role", "REPEAT_REGION").put("mappingKind", "REPEAT_REGION")
                .put("relationId", relationId).put("fieldId", fieldId.toString())
                .put("bindingId", bindingId.toString())
                .put("fieldCode", "AUTO.WORD.TABLE_" + RecognitionIdentity.shortHash(relationId, 10).toUpperCase(Locale.ROOT))
                .put("fieldName", "ROW".equals(axis) ? "Word 按行明细" : "Word 按列明细")
                .put("groupName", "业务明细")
                .put("dataPath", "/recognized/word/" + safePathSegment(nodeId, relationId) + "/records")
                .put("valueType", "array").put("required", false)
                .put("editability", "EDITABLE").put("valueSource", "USER_INPUT")
                .put("locatorType", "DOCX_TABLE_REGION")
                .put("repeatAxis", axis)
                .put("recordHeight", "ROW".equals(axis) ? 1 : Math.max(1, rowCount))
                .put("recordWidth", "ROW".equals(axis) ? columnCount : 1)
                .put("recordStride", 1)
                .put("reviewRequired", true).put("candidateOnly", false)
                .put("publishable", true).put("autoAccept", false)
                .put("canonicalStatus", "PROVISIONAL").put("structureStatus", "CONFIRMED")
                .put("recognitionOrigin", "RULE_DETERMINISTIC")
                .put("reasonCode", "DOCX_REPEAT_TABLE")
                .put("regionId", relationId).put("blockId", relationId)
                .put("candidateRef", nodeId);
        payload.set("locator", objectMapper.createObjectNode()
                .put("locatorType", "DOCX_TABLE_REGION")
                .put("nodeId", nodeId).put("sourcePath", sourcePath));
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("source", "DOCX_TABLE_GEOMETRY").put("nodeId", nodeId)
                .put("sourcePath", sourcePath).put("repeatAxis", axis));
        var suggestion = new RecognitionModelClient.ModelSuggestion(
                type, payload, 0.90, evidence);
        return new DocxTableRegion(sourcePath, relationId, type, axis, suggestion,
                classification.labelBandWidth(), classification.headerDepth());
    }

    private List<RecognitionModelClient.ModelSuggestion> docxTableFieldCandidates(
            JsonNode table, DocxTableClassification classification, DocxTableRegion region
    ) {
        return switch (classification.type()) {
            case "FORM_REGION" -> docxFormFields(table, region);
            case "ROW_TABLE" -> docxRowFields(table, region);
            case "COLUMN_TABLE" -> docxColumnFields(table, region, classification.labelBandWidth());
            default -> List.of();
        };
    }

    private List<RecognitionModelClient.ModelSuggestion> docxFormFields(
            JsonNode table, DocxTableRegion region
    ) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        for (var row : table.path("rows")) {
            var cells = row.path("cells");
            for (var index = 0; index + 1 < cells.size(); index += 2) {
                var label = cells.path(index);
                var value = cells.path(index + 1);
                if (!docxLabelCell(label) || !docxValueCell(value)) continue;
                var parsed = parseDocxFieldLabel(label.path("text").asText(""));
                if (parsed.name().isBlank()) continue;
                result.add(docxTableFieldCandidate(table, region, label, List.of(value),
                        parsed, GroupNameNormalizer.BASIC_INFORMATION, "SCALAR", ""));
            }
        }
        return List.copyOf(result);
    }

    private List<RecognitionModelClient.ModelSuggestion> docxRowFields(
            JsonNode table, DocxTableRegion region
    ) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var rows = table.path("rows");
        if (!rows.isArray() || rows.size() < 2) return List.of();
        var columnCount = table.path("columnCount").asInt();
        var header = rows.path(0);
        for (var column = 1; column <= columnCount; column++) {
            var label = cellAt(header, column);
            if (!docxLabelCell(label)) continue;
            var values = new LinkedHashMap<String, JsonNode>();
            for (var rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                var value = cellAt(rows.path(rowIndex), column);
                if (value != null && !value.path("mergeContinuation").asBoolean(false)) {
                    values.putIfAbsent(value.path("sourcePath").asText(value.path("id").asText()), value);
                }
            }
            if (values.isEmpty()) continue;
            var parsed = parseDocxFieldLabel(label.path("text").asText(""));
            result.add(docxTableFieldCandidate(table, region, label, List.copyOf(values.values()),
                    parsed, "业务明细", "REPEAT_FIELD", "ROW"));
            column = Math.max(column, label.path("logicalColumnEnd").asInt(column));
        }
        return List.copyOf(result);
    }

    private List<RecognitionModelClient.ModelSuggestion> docxColumnFields(
            JsonNode table, DocxTableRegion region, int labelBandWidth
    ) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var columnCount = table.path("columnCount").asInt();
        var currentGroup = "";
        for (var row : table.path("rows")) {
            JsonNode label = null;
            var physicalIndex = 0;
            for (var cell : row.path("cells")) {
                physicalIndex++;
                var start = logicalColumnStart(cell, physicalIndex);
                var end = cell.path("logicalColumnEnd").asInt(start);
                if (start > labelBandWidth || end > labelBandWidth
                        || cell.path("mergeContinuation").asBoolean(false)) continue;
                var value = cell.path("text").asText("").strip();
                if (value.isBlank()) continue;
                if (end < labelBandWidth && looksLikeFieldLabel(value)) currentGroup = value;
                if (looksLikeFieldLabel(value)) label = cell;
            }
            if (label == null) continue;
            var values = new LinkedHashMap<String, JsonNode>();
            for (var column = labelBandWidth + 1; column <= columnCount; column++) {
                var value = cellAt(row, column);
                if (value != null && !value.path("mergeContinuation").asBoolean(false)) {
                    var valueKey = value.path("sourcePath").asText(value.path("id").asText(""));
                    values.putIfAbsent(valueKey.isBlank() ? "column-" + column : valueKey, value);
                }
            }
            if (values.size() < 2) continue;
            var parsed = parseDocxFieldLabel(label.path("text").asText(""));
            var groupName = currentGroup.isBlank() || currentGroup.equals(parsed.name()) ? "业务明细" : currentGroup;
            result.add(docxTableFieldCandidate(table, region, label, List.copyOf(values.values()),
                    parsed, groupName, "REPEAT_FIELD", "COLUMN"));
        }
        return List.copyOf(result);
    }

    private RecognitionModelClient.ModelSuggestion docxTableFieldCandidate(
            JsonNode table,
            DocxTableRegion region,
            JsonNode label,
            List<JsonNode> values,
            DocxFieldLabel parsed,
            String groupName,
            String mappingKind,
            String repeatAxis
    ) {
        var labelPath = label.path("sourcePath").asText(label.path("id").asText());
        var valueIdentity = values.stream()
                .map(value -> value.path("sourcePath").asText(value.path("id").asText()))
                .reduce((left, right) -> left + "|" + right).orElse("");
        var relationId = RecognitionIdentity.relationId(
                "DOCX", region.regionId(), labelPath, "DOCX_TABLE_CELL|" + valueIdentity);
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var firstValue = values.getFirst();
        var candidateRef = firstValue.path("id").asText(label.path("id").asText());
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR")
                .put("role", "FIELD")
                .put("blockType", region.type())
                .put("blockName", "FORM_REGION".equals(region.type()) ? "Word 基本信息"
                        : "ROW_TABLE".equals(region.type()) ? "Word 按行明细" : "Word 按列明细")
                .put("relationId", relationId)
                .put("fieldId", fieldId.toString())
                .put("fieldCode", "AUTO.WORD.FIELD_"
                        + RecognitionIdentity.shortHash(relationId, 12).toUpperCase(Locale.ROOT))
                .put("fieldName", parsed.name())
                .put("dataPath", "/recognized/word/" + safePathSegment(parsed.name(), fieldId.toString())
                        + "_" + RecognitionIdentity.shortHash(relationId, 6))
                .put("valueType", "string")
                .put("required", false)
                .put("unit", parsed.unit())
                .put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT")
                .put("mappingKind", mappingKind)
                .put("locatorType", "DOCX_TABLE_CELL")
                .put("source", "DOCX_TABLE_CELL")
                .put("candidateOnly", true)
                .put("reviewRequired", true)
                .put("publishable", false)
                .put("autoAccept", false)
                .put("pendingReason", "DOCX_FIELD_POSITION_REQUIRED")
                .put("groupName", groupName)
                .put("labelPath", groupName.equals("业务明细") || groupName.equals(GroupNameNormalizer.BASIC_INFORMATION)
                        ? parsed.name() : groupName + "/" + parsed.name())
                .put("regionId", region.regionId())
                .put("blockId", region.regionId())
                .put("regionRange", table.path("sourcePath").asText(""))
                .put("parentRelationId", region.regionId())
                .put("candidateRef", candidateRef);
        payload.put("suggestionLevel", "REPEAT_FIELD".equals(mappingKind) ? "CHILD" : "SCALAR");
        if (!repeatAxis.isBlank()) {
            payload.put("repeatAxis", repeatAxis)
                    .put("recordHeight", "ROW".equals(repeatAxis) ? 1 : table.path("rowCount").asInt(1))
                    .put("recordWidth", "ROW".equals(repeatAxis) ? table.path("columnCount").asInt(1) : 1)
                    .put("recordStride", 1);
        }
        var locator = payload.putObject("locator")
                .put("locatorType", "DOCX_TABLE_CELL")
                .put("nodeId", candidateRef)
                .put("labelAnchor", label.path("id").asText(""))
                .put("valueAnchor", candidateRef)
                .put("tablePath", table.path("sourcePath").asText(""))
                .put("labelCellPath", label.path("sourcePath").asText(""));
        var valuePaths = locator.putArray("valueCellPaths");
        var valueNodeIds = locator.putArray("valueNodeIds");
        values.forEach(value -> {
            valuePaths.add(value.path("sourcePath").asText(""));
            valueNodeIds.add(value.path("id").asText(""));
        });
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("source", "DOCX_TABLE_FIELD")
                .put("tablePath", table.path("sourcePath").asText(""))
                .put("labelCellPath", label.path("sourcePath").asText(""))
                .put("repeatAxis", repeatAxis));
        return new RecognitionModelClient.ModelSuggestion("SCALAR_FIELD", payload, 0.95, evidence);
    }

    private DocxFieldLabel parseDocxFieldLabel(String value) {
        var normalized = value == null ? "" : value.replaceAll("[：:]$", "").strip();
        var slash = Pattern.compile("^(.{1,40}?)\\s*[/／]\\s*([^/／]{1,20})$").matcher(normalized);
        if (slash.matches()) return new DocxFieldLabel(slash.group(1).strip(), slash.group(2).strip());
        var parenthesized = Pattern.compile("^(.{1,40}?)\\s*[（(]([^）)]{1,20})[）)]$").matcher(normalized);
        if (parenthesized.matches()) {
            return new DocxFieldLabel(parenthesized.group(1).strip(), parenthesized.group(2).strip());
        }
        return new DocxFieldLabel(normalized, "");
    }

    private RecognitionModelClient.QualityIssueSuggestion docxDirectionIssue(JsonNode table) {
        var nodeId = table.path("id").asText("");
        var evidence = objectMapper.createObjectNode()
                .put("nodeId", nodeId)
                .put("sourcePath", table.path("sourcePath").asText(""))
                .put("rowCount", table.path("rowCount").asInt())
                .put("columnCount", table.path("columnCount").asInt());
        return new RecognitionModelClient.QualityIssueSuggestion(
                "STRUCTURE_DIRECTION_UNCLEAR", "ERROR", "", "", nodeId,
                "Word 表格记录方向不明确",
                "该二维表同时具有行、列标签特征，无法可靠判断每条记录按行还是按列重复。",
                "必须先确认结构，系统不会猜测或降级。", 0.99, false,
                null, null, evidence, "DETECTED", nodeId, null);
    }

    /**
     * Content controls are deterministic Word fields. Plain label paragraphs
     * are also surfaced as review-only candidates so a document still has a
     * useful field model when no external semantic model is configured. They
     * intentionally remain unbound until the user inserts a real content
     * control at the chosen position.
     */
    private List<RecognitionModelClient.ModelSuggestion> docxContentControlCandidates(
            JsonNode structure, Map<String, DocxTableRegion> tableRegions
    ) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var occupied = new java.util.HashSet<String>();
        var controls = structure.path("documentIR").path("contentControls");
        if (!controls.isArray()) controls = structure.path("contentControls");
        for (var control : controls) {
            var nodeId = control.path("nodeId").asText("");
            var sourcePath = control.path("sourcePath").asText("");
            var tableRegion = tableRegions.entrySet().stream()
                    .filter(entry -> !entry.getKey().isBlank() && sourcePath.startsWith(entry.getKey() + "/"))
                    .max(java.util.Comparator.comparingInt(entry -> entry.getKey().length()))
                    .map(Map.Entry::getValue).orElse(null);
            var contentControlId = control.path("contentControlId").asText("");
            var documentMarkerId = control.path("markerId").asText("");
            var tag = control.path("tag").asText("").strip();
            var alias = control.path("alias").asText("").strip();
            var text = control.path("text").asText("").strip();
            var name = firstNonPlaceholder(alias, tag, text);
            var markerId = documentMarkerId;
            if (nodeId.isBlank()) continue;
            var fieldSeed = nodeId + "|" + markerId + "|" + name;
            var fieldId = RecognitionIdentity.fieldId(RecognitionIdentity.relationId(
                    "docx", nodeId, markerId, "DOCX_CONTENT_CONTROL"));
            var fieldCode = tag.isBlank()
                    ? "AUTO.WORD.FIELD_" + RecognitionIdentity.shortHash(fieldSeed, 12).toUpperCase(Locale.ROOT)
                    : tag;
            var dataPath = "/recognized/word/" + safePathSegment(name, fieldId.toString());
            var payload = objectMapper.createObjectNode()
                    .put("kind", "SCALAR")
                    .put("role", "FIELD")
                    .put("blockType", tableRegion == null ? "FORM_REGION" : tableRegion.type())
                    .put("blockName", tableRegion == null || "FORM_REGION".equals(tableRegion.type())
                            ? "Word 基本信息" : "Word 明细字段")
                    .put("fieldId", fieldId.toString())
                    .put("fieldCode", fieldCode)
                    .put("fieldName", name.isBlank() ? "待命名字段" : name)
                    .put("dataPath", dataPath)
                    .put("editability", "EDITABLE")
                    .put("valueSource", "USER_INPUT")
                    .put("valueType", "string")
                    .put("required", false)
                    .put("mappingKind", tableRegion == null || "FORM_REGION".equals(tableRegion.type())
                            ? "SCALAR" : "REPEAT_FIELD")
                    .put("locatorType", "DOCX_CONTENT_CONTROL")
                    .put("markerId", markerId)
                    .put("source", "DOCX_CONTENT_CONTROL")
                    .put("candidateOnly", false)
                    .put("reviewRequired", true)
                    .put("publishable", true)
                    .put("autoAccept", false)
                    .put("pendingReason", "DOCX_FIELD_REVIEW")
                    .put("standardMatchStatus", "UNMATCHED")
                    .put("requiresStandardConfirmation", false)
                    .put("groupName", tableRegion == null || "FORM_REGION".equals(tableRegion.type())
                            ? GroupNameNormalizer.BASIC_INFORMATION : "业务明细")
                    .put("regionId", tableRegion == null ? "docx-document" : tableRegion.regionId())
                    .put("blockId", tableRegion == null ? "docx-document" : tableRegion.regionId())
                    .put("regionRange", "DOCX")
                    .put("candidateRef", nodeId);
            if (tableRegion != null && !"FORM_REGION".equals(tableRegion.type())) {
                payload.put("parentRelationId", tableRegion.regionId())
                        .put("suggestionLevel", "CHILD")
                        .put("repeatAxis", tableRegion.axis())
                        .put("recordHeight", "ROW".equals(tableRegion.axis()) ? 1 : 1)
                        .put("recordWidth", 1)
                        .put("recordStride", 1);
            } else {
                payload.put("suggestionLevel", "SCALAR");
            }
            payload.set("locator", objectMapper.createObjectNode()
                    .put("nodeId", nodeId)
                    .put("markerId", markerId)
                    .put("sourcePath", sourcePath)
                    .put("contentControlId", contentControlId)
                    .put("tag", tag)
                    .put("alias", alias)
                    .put("text", text)
                    .put("locatorType", "DOCX_CONTENT_CONTROL"));
            var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                    .put("nodeId", nodeId).put("markerId", markerId)
                    .put("source", "DOCX_CONTENT_CONTROL"));
            result.add(new RecognitionModelClient.ModelSuggestion(
                    "SCALAR_FIELD", payload, 0.98, evidence));
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
            if (fieldName.matches("^类型(?:[一二三四五六七八九十]+|\\d+)$")) continue;
            result.add(docxTextLabelCandidate(nodeId, text, fieldName));
        }
        return List.copyOf(result);
    }

    private String docxLabelName(String text) {
        var inline = INLINE_LABEL.matcher(text);
        if (inline.matches()) return inline.group(1).strip();
        var explicit = EXPLICIT_LABEL.matcher(text);
        if (explicit.matches()) return explicit.group(1).strip();
        return "";
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

    private record DocxCandidates(
            List<RecognitionModelClient.ModelSuggestion> suggestions,
            List<RecognitionModelClient.QualityIssueSuggestion> issues
    ) {
    }

    private record DocxTableClassification(
            String type,
            String axis,
            boolean ambiguous,
            int labelBandWidth,
            int headerDepth
    ) {
    }

    private record DocxTableRegion(
            String sourcePath,
            String regionId,
            String type,
            String axis,
            RecognitionModelClient.ModelSuggestion suggestion,
            int labelBandWidth,
            int headerDepth
    ) {
    }

    private record DocxFieldLabel(String name, String unit) {
    }
}
