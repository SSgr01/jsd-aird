package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Component;

/** Deterministic recognition based on values, merged regions and styled blank input areas. */
@Component
public class RuleBasedRecognitionEngine {

    private static final Pattern ADDRESS = Pattern.compile("^([A-Z]{1,4})([1-9][0-9]*)$");
    private static final Pattern RANGE = Pattern.compile(
            "^([A-Z]{1,4})([1-9][0-9]*):([A-Z]{1,4})([1-9][0-9]*)$"
    );
    private static final Set<String> BUSINESS_WORDS = Set.of(
            "名称", "编号", "日期", "批次", "型号", "规格", "单位", "数量", "用量", "比例",
            "温度", "时间", "压力", "粘度", "强度", "结果", "产品", "项目", "原料", "树脂",
            "单体", "负责人", "审核", "批准", "客户", "备注", "说明", "实验", "方案", "目的"
    );

    private final ObjectMapper objectMapper;
    private final JsonCanonicalizer canonicalizer;

    public RuleBasedRecognitionEngine(ObjectMapper objectMapper, JsonCanonicalizer canonicalizer) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    public RecognitionModelClient.RecognitionBatch recognize(
            TemplateFormat format,
            String sourceFileName,
            JsonNode structure
    ) {
        if (format != TemplateFormat.XLSX) return emptyBatch(structure);
        if (structure.path("structureVersion").asInt() != 5) {
            throw new IllegalArgumentException("Excel structureVersion 必须为 5");
        }
        var cells = readCells(structure.path("candidateCells"));
        var byKey = new HashMap<String, GridCell>();
        var byRow = new LinkedHashMap<String, List<GridCell>>();
        for (var cell : cells) {
            byKey.put(cell.key(), cell);
            byRow.computeIfAbsent(cell.sheetId() + ":" + cell.row(), ignored -> new ArrayList<>()).add(cell);
        }
        byRow.values().forEach(row -> row.sort(Comparator.comparingInt(GridCell::column)));

        var suggestions = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var occupiedLabels = new HashSet<String>();

        recognizeStructuredRegions(cells, structure, suggestions, occupiedLabels);
        recognizeVerticalRegions(cells, structure, suggestions, occupiedLabels);
        recognizeHeaderTables(cells, byRow, structure, suggestions, occupiedLabels);

        for (var cell : cells) {
            if (!cell.textLabel() || occupiedLabels.contains(cell.key())) continue;
            var confidence = scalarConfidence(cell.text());
            if (confidence < 0.6) continue;
            var address = inferInputRange(cell, byKey);
            if (address.isBlank()) continue;
            var name = trimLabel(cell.text());
            var payload = basePayload(cell, name, inferValueType(name, inputNumberFormat(
                    cell.sheetId(), address, byKey, cell.numberFormat()
            )),
                    inferRequired(name) || hasRequiredValidation(structure, cell.sheetId(), address));
            payload.put("kind", "SCALAR");
            payload.put("groupName", inferGroup(name));
            payload.put("interpretation", "系统认为这里用于填写“" + name + "”。");
            payload.put("locatorType", "CELL_RANGE");
            payload.set("locator", locator(cell, address));
            applyRegionMetadata(payload, cell, structure);
            suggestions.add(new RecognitionModelClient.ModelSuggestion(
                    "SCALAR_FIELD", payload, confidence,
                    evidence(cell, "根据标签及相邻的空白样式区域识别填写位置")
            ));
        }

        var fingerprint = canonicalizer.hash(structure);
        return new RecognitionModelClient.RecognitionBatch(
                List.copyOf(suggestions), "rule-engine", "xlsx-regions-v5",
                "rule-recognition-v5", fingerprint,
                canonicalizer.hashText(sourceFileName + fingerprint + suggestions.size())
        );
    }

    private void recognizeStructuredRegions(
            List<GridCell> cells,
            JsonNode structure,
            List<RecognitionModelClient.ModelSuggestion> suggestions,
            Set<String> occupied
    ) {
        for (var region : structure.path("regions")) {
            if (region.path("analysisChild").asBoolean(false)) continue;
            var kind = region.path("kindCandidate").asText();
            if (!"MATRIX".equals(kind) && !"ROW_TABLE".equals(kind)) continue;
            var range = parsedRangeOrCell(region.path("address").asText());
            if (range == null) continue;
            var regionCells = cells.stream()
                    .filter(cell -> cell.sheetId().equals(region.path("sheetId").asText()))
                    .filter(cell -> range.contains(cell.column(), cell.row())).toList();
            var label = regionCells.stream().filter(GridCell::textLabel)
                    .min(Comparator.comparingInt(GridCell::row).thenComparingInt(GridCell::column))
                    .orElse(null);
            if (label == null || occupied.contains(label.key())) continue;
            var labels = regionCells.stream().filter(GridCell::textLabel)
                    .filter(cell -> cell.row() == range.startRow()).toList();
            var name = labels.isEmpty() ? trimLabel(label.text()) : inferRegionName(labels);
            var payload = basePayload(label, name, "array", inferRequired(name));
            payload.put("kind", kind).put("role", "REPEAT_REGION")
                    .put("locatorType", "MATRIX".equals(kind) ? "MATRIX_REGION" : "TABLE_REGION")
                    .put("groupName", inferGroup(name))
                    .put("interpretation", "MATRIX".equals(kind)
                            ? "系统认为这里按行列填写“" + name + "”结果。"
                            : "系统认为这里逐行填写“" + name + "”记录。");
            payload.set("locator", locator(label, region.path("address").asText()));
            payload.put("regionId", region.path("regionId").asText());
            payload.put("regionAddress", region.path("address").asText());
            payload.set("ruleScores", region.path("scores").deepCopy());
            if ("MATRIX".equals(kind)) applyMatrixModel(
                    payload, label, region.path("address").asText(), cells, structure
            ); else applyTableModel(payload, label, region.path("address").asText(), cells, structure);
            suggestions.add(new RecognitionModelClient.ModelSuggestion(
                    kind, payload, region.path("classificationConfidence").asDouble(0.75),
                    evidence(label, "按连续样式区域、表头层级和空白录入区识别")
            ));
            regionCells.stream().filter(GridCell::textLabel).map(GridCell::key).forEach(occupied::add);
        }
    }

    private void recognizeVerticalRegions(
            List<GridCell> cells,
            JsonNode structure,
            List<RecognitionModelClient.ModelSuggestion> suggestions,
            Set<String> occupied
    ) {
        for (var label : cells) {
            if (occupied.contains(label.key())) continue;
            var range = parsedRange(label.mergedRange());
            if (!label.textLabel() || range == null || range.height() < 3 || range.width() != 1) continue;
            var usedLastColumn = usedLastColumn(structure.path("sheets"), label.sheetId(), label.sheetName());
            if (usedLastColumn <= range.endColumn()) continue;
            var name = trimLabel(label.text());
            var kind = containsAny(name, "测试", "性能", "数据", "结果") ? "MATRIX" : "ROW_TABLE";
            var address = columnName(range.endColumn() + 1) + range.startRow() + ":"
                    + columnName(usedLastColumn) + range.endRow();
            var payload = basePayload(label, name, "array", inferRequired(name));
            payload.put("kind", kind);
            payload.put("role", "REPEAT_REGION");
            payload.put("locatorType", "MATRIX".equals(kind) ? "MATRIX_REGION" : "TABLE_REGION");
            payload.put("groupName", inferGroup(name));
            payload.put("interpretation", "MATRIX".equals(kind)
                    ? "系统认为这里按行列填写“" + name + "”结果。"
                    : "系统认为这里逐行填写“" + name + "”记录。");
            payload.set("locator", locator(label, address));
            applyRegionMetadata(payload, label, structure);
            if ("MATRIX".equals(kind)) applyMatrixModel(payload, label, address, cells, structure);
            else applyTableModel(payload, label, address, cells, structure);
            suggestions.add(new RecognitionModelClient.ModelSuggestion(
                    kind, payload, "MATRIX".equals(kind) ? 0.88 : 0.84,
                    evidence(label, "识别到纵向合并的业务章节及其右侧连续填写区域")
            ));
            var occupiedRange = parsedRangeOrCell(payload.path("regionAddress").asText(address));
            if (occupiedRange == null) occupied.add(label.key());
            else cells.stream().filter(cell -> cell.sheetId().equals(label.sheetId()) && cell.textLabel())
                    .filter(cell -> occupiedRange.contains(cell.column(), cell.row()))
                    .map(GridCell::key).forEach(occupied::add);
        }
    }

    private void recognizeHeaderTables(
            List<GridCell> cells,
            Map<String, List<GridCell>> byRow,
            JsonNode structure,
            List<RecognitionModelClient.ModelSuggestion> suggestions,
            Set<String> occupied
    ) {
        for (var row : byRow.values()) {
            var labels = row.stream().filter(GridCell::textLabel).toList();
            if (labels.size() < 3 || labels.stream().filter(cell -> cell.text().length() <= 16).count() < 3) {
                continue;
            }
            var first = labels.getFirst();
            if (occupied.contains(first.key())) continue;
            var containingRegion = regionFor(first, structure);
            if (containingRegion != null) {
                var declaredKind = containingRegion.path("kindCandidate").asText();
                if (!"ROW_TABLE".equals(declaredKind) && !"MATRIX".equals(declaredKind)) continue;
            }
            var last = labels.getLast();
            var endRow = inferRegionEnd(cells, first.sheetId(), first.row(), first.column(), last.column());
            if (containingRegion != null) {
                var regionRange = parsedRangeOrCell(containingRegion.path("address").asText());
                if (regionRange != null) endRow = Math.min(endRow, regionRange.endRow());
            }
            if (endRow <= first.row()) continue;
            var kind = looksLikeMatrix(labels) ? "MATRIX" : "ROW_TABLE";
            var name = inferRegionName(labels);
            var payload = basePayload(first, name, "array", inferRequired(name));
            payload.put("kind", kind);
            payload.put("role", "REPEAT_REGION");
            payload.put("locatorType", "MATRIX".equals(kind) ? "MATRIX_REGION" : "TABLE_REGION");
            payload.put("groupName", inferGroup(name));
            payload.put("interpretation", "MATRIX".equals(kind)
                    ? "系统认为每一行代表一个对象，每一列代表一个测试项目。"
                    : "系统认为每一行代表一条“" + name + "”记录。");
            var address = first.address() + ":" + columnName(last.column()) + endRow;
            var locator = locator(first, address);
            locator.put("headerRange", first.address() + ":" + last.address());
            payload.set("locator", locator);
            var columns = objectMapper.createArrayNode();
            for (var label : labels) {
                columns.add(objectMapper.createObjectNode()
                        .put("code", stableToken(label.sheetId() + "-" + label.address()))
                        .put("name", trimLabel(label.text()))
                        .put("valueType", inferValueType(label.text(), label.numberFormat()))
                        .put("required", inferRequired(label.text())));
                occupied.add(label.key());
            }
            payload.set("columns", columns);
            applyRegionMetadata(payload, first, structure);
            if ("MATRIX".equals(kind)) applyMatrixModel(payload, first, address, cells, structure);
            else applyTableModel(payload, first, address, cells, structure);
            suggestions.add(new RecognitionModelClient.ModelSuggestion(
                    kind, payload, "MATRIX".equals(kind) ? 0.82 : 0.80,
                    evidence(first, "识别到连续表头及其下方相同样式区域")
            ));
        }
    }

    private int inferRegionEnd(List<GridCell> cells, String sheetId, int headerRow, int firstColumn, int lastColumn) {
        return cells.stream()
                .filter(cell -> cell.sheetId().equals(sheetId))
                .filter(cell -> cell.row() > headerRow)
                .filter(cell -> cell.column() >= firstColumn && cell.column() <= lastColumn)
                .filter(cell -> cell.styled() || !cell.empty())
                .mapToInt(GridCell::row)
                .max().orElse(headerRow);
    }

    private boolean looksLikeMatrix(List<GridCell> labels) {
        var joined = labels.stream().map(GridCell::text).reduce("", (left, right) -> left + right);
        return containsAny(joined, "样品", "测试", "性能", "结果", "强度", "粘度");
    }

    private String inferInputRange(GridCell label, Map<String, GridCell> cells) {
        var labelRange = parsedRange(label.mergedRange());
        var startColumn = labelRange == null ? label.column() + 1 : labelRange.endColumn() + 1;
        var row = labelRange == null ? label.row() : labelRange.startRow();
        var adjacent = cells.get(label.sheetId() + ":" + columnName(startColumn) + row);
        if (adjacent == null) return "";
        if (!adjacent.empty()) return "";
        if (!adjacent.mergedRange().isBlank()) return adjacent.mergedRange();

        var endColumn = startColumn;
        for (int column = startColumn + 1; column <= startColumn + 20; column++) {
            var next = cells.get(label.sheetId() + ":" + columnName(column) + row);
            if (next == null || !next.empty() || !next.styled()) break;
            if (!next.mergedRange().isBlank()) return columnName(startColumn) + row + ":"
                    + next.mergedRange().split(":")[1];
            endColumn = column;
        }
        return endColumn == startColumn
                ? columnName(startColumn) + row
                : columnName(startColumn) + row + ":" + columnName(endColumn) + row;
    }

    private ObjectNode locator(GridCell label, String address) {
        var labelRange = label.mergedRange().isBlank() ? label.address() : label.mergedRange();
        var anchorAddress = address.contains(":") ? address.substring(0, address.indexOf(':')) : address;
        return objectMapper.createObjectNode()
                .put("sheetId", label.sheetId())
                .put("sheetName", label.sheetName())
                .put("labelAddress", label.address())
                .put("labelRange", labelRange)
                .put("address", address)
                .put("anchorAddress", anchorAddress)
                .put("logicalInputRange", address)
                .put("valueMode", "ANCHOR");
    }

    private ObjectNode basePayload(GridCell cell, String name, String valueType, boolean required) {
        var token = stableToken(cell.sheetId() + "-" + cell.address());
        return objectMapper.createObjectNode()
                .put("fieldCode", "AUTO." + token.toUpperCase(Locale.ROOT))
                .put("fieldName", name)
                .put("dataPath", "/recognized/" + token)
                .put("valueType", valueType)
                .put("required", required)
                .put("role", "FIELD")
                .put("reason", "根据 Excel 布局、合并区域和业务标签自动识别");
    }

    private List<GridCell> readCells(JsonNode source) {
        var result = new ArrayList<GridCell>();
        if (!source.isArray()) return result;
        for (var node : source) {
            var matcher = ADDRESS.matcher(node.path("address").asText("").toUpperCase(Locale.ROOT));
            if (!matcher.matches()) continue;
            var value = node.path("value");
            var empty = node.path("empty").asBoolean(!node.has("value"));
            var text = empty ? "" : value.asText("").strip();
            var numberFormat = node.path("style").path("n").path("pattern").asText("");
            result.add(new GridCell(
                    node.path("sheetId").asText(), node.path("sheetName").asText(),
                    node.path("address").asText().toUpperCase(Locale.ROOT), text,
                    columnIndex(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    value.isNumber(), empty, node.path("style").isObject() || node.path("hasBorder").asBoolean(),
                    node.path("mergedRange").asText("").toUpperCase(Locale.ROOT), numberFormat
            ));
        }
        return result;
    }

    private int usedLastColumn(JsonNode sheets, String sheetId, String sheetName) {
        if (!sheets.isArray()) return 0;
        for (var sheet : sheets) {
            if (!sheetId.equals(sheet.path("id").asText()) && !sheetName.equals(sheet.path("name").asText())) continue;
            var used = sheet.path("usedRange").asText("");
            var match = RANGE.matcher(used);
            if (match.matches()) return columnIndex(match.group(3));
        }
        return 0;
    }

    private Range parsedRange(String value) {
        var matcher = RANGE.matcher(value == null ? "" : value);
        if (!matcher.matches()) return null;
        return new Range(
                columnIndex(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                columnIndex(matcher.group(3)), Integer.parseInt(matcher.group(4))
        );
    }

    private RecognitionModelClient.RecognitionBatch emptyBatch(JsonNode structure) {
        var hash = canonicalizer.hash(structure);
        return new RecognitionModelClient.RecognitionBatch(
                List.of(), "rule-engine", "document-structure-v1", "rule-recognition-v4", hash, hash
        );
    }

    private com.fasterxml.jackson.databind.node.ArrayNode evidence(GridCell cell, String reason) {
        return objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("sheetName", cell.sheetName()).put("address", cell.address())
                .put("label", cell.text()).put("reason", reason));
    }

    private double scalarConfidence(String text) {
        var clean = trimLabel(text);
        if (text.endsWith(":") || text.endsWith("：")) return 0.92;
        if (BUSINESS_WORDS.stream().anyMatch(clean::contains)) return 0.88;
        return clean.length() <= 8 ? 0.64 : 0.45;
    }

    private String inferRegionName(List<GridCell> labels) {
        var joined = labels.stream().map(GridCell::text).reduce((left, right) -> left + "、" + right).orElse("明细");
        if (containsAny(joined, "原料", "物料", "用量")) return "原料明细";
        if (containsAny(joined, "测试", "结果", "性能")) return "性能测试";
        return "业务明细";
    }

    private String inferGroup(String value) {
        if (containsAny(value, "原料", "物料", "配方", "树脂", "单体", "用量")) return "原料信息";
        if (containsAny(value, "温度", "时间", "工艺", "压力", "反应")) return "工艺条件";
        if (containsAny(value, "测试", "性能", "结果", "强度", "粘度", "数据")) return "性能测试";
        if (containsAny(value, "审核", "批准", "签字")) return "审核信息";
        return GroupNameNormalizer.BASIC_INFORMATION;
    }

    private String inferValueType(String label, String numberFormat) {
        var format = numberFormat == null ? "" : numberFormat.toLowerCase(Locale.ROOT);
        if (format.contains("%")) return "number";
        if (format.contains("[h]") || format.contains("[m]") || format.contains("[s]")) return "duration";
        if (format.matches(".*[ymd].*")) return format.matches(".*[hs].*") ? "datetime" : "date";
        if (format.matches(".*[hs].*") && !format.contains("0.00")) return "time";
        if (format.matches(".*[0#].*") || containsAny(format, "¥", "$", "€", "£")) {
            return format.contains(".") ? "number" : "integer";
        }
        if (containsAny(label, "数量", "用量", "比例", "温度", "压力", "粘度", "强度", "膜厚", "拉伸率")) {
            return "number";
        }
        if (containsAny(label, "日期")) return "date";
        return "string";
    }

    private boolean inferRequired(String label) {
        return label.contains("*") || label.contains("必填") || label.contains("不能为空");
    }

    private String inputNumberFormat(
            String sheetId, String address, Map<String, GridCell> cells, String fallback
    ) {
        var range = parsedRangeOrCell(address);
        if (range == null) return fallback;
        var input = cells.get(sheetId + ":" + columnName(range.startColumn()) + range.startRow());
        return input == null || input.numberFormat().isBlank() ? fallback : input.numberFormat();
    }

    private boolean hasRequiredValidation(JsonNode structure, String sheetId, String address) {
        var fieldRange = parsedRangeOrCell(address);
        if (fieldRange == null) return false;
        for (var validation : structure.path("dataValidations")) {
            if (!sheetId.equals(validation.path("sheetId").asText())
                    || validation.path("allowBlank").asBoolean(true)) continue;
            var validationRange = parsedRangeOrCell(validation.path("address").asText());
            if (validationRange != null && validationRange.overlaps(fieldRange)) return true;
        }
        return false;
    }

    private void applyRegionMetadata(ObjectNode payload, GridCell anchor, JsonNode structure) {
        var selected = regionFor(anchor, structure);
        if (selected == null) return;
        payload.put("regionId", selected.path("regionId").asText());
        payload.put("regionAddress", selected.path("address").asText());
        payload.set("ruleScores", selected.path("scores").deepCopy());
    }

    private JsonNode regionFor(GridCell anchor, JsonNode structure) {
        JsonNode selected = null;
        var selectedArea = Integer.MAX_VALUE;
        for (var region : structure.path("regions")) {
            if (region.path("analysisChild").asBoolean(false)
                    || !anchor.sheetId().equals(region.path("sheetId").asText())) continue;
            var range = parsedRangeOrCell(region.path("address").asText());
            if (range == null || !range.contains(anchor.column(), anchor.row())) continue;
            var area = range.width() * range.height();
            if (area < selectedArea) {
                selected = region;
                selectedArea = area;
            }
        }
        return selected;
    }

    private void applyTableModel(
            ObjectNode payload, GridCell anchor, String address, List<GridCell> cells, JsonNode structure
    ) {
        var range = parsedRangeOrCell(address);
        if (range == null) return;
        var headerEnd = inferHeaderEnd(cells, anchor.sheetId(), range);
        var model = objectMapper.createObjectNode()
                .put("range", address)
                .put("headerRange", a1(new Range(
                        range.startColumn(), range.startRow(), range.endColumn(), headerEnd
                )))
                .put("dataRange", headerEnd < range.endRow()
                        ? a1(new Range(range.startColumn(), headerEnd + 1, range.endColumn(), range.endRow()))
                        : "")
                .put("expansionDirection", "ROWS");
        model.set("headerTree", headerTree(anchor.sheetId(), range, headerEnd, cells, structure));
        payload.set("tableModel", model);
    }

    private void applyMatrixModel(
            ObjectNode payload, GridCell anchor, String address, List<GridCell> cells, JsonNode structure
    ) {
        var range = parsedRangeOrCell(address);
        if (range == null) return;
        var headerEnd = inferHeaderEnd(cells, anchor.sheetId(), range);
        var rowHeaderColumns = inferRowHeaderColumns(cells, anchor.sheetId(), range, headerEnd);
        var rowHeaderEnd = Math.min(range.endColumn(), range.startColumn() + rowHeaderColumns - 1);
        var dataStartColumn = Math.min(range.endColumn(), rowHeaderEnd + 1);
        var dataStartRow = Math.min(range.endRow(), headerEnd + 1);
        var rowHeaderRange = a1(new Range(
                range.startColumn(), dataStartRow, rowHeaderEnd, range.endRow()
        ));
        var columnHeaderRange = a1(new Range(
                dataStartColumn, range.startRow(), range.endColumn(), headerEnd
        ));
        var dataRange = a1(new Range(
                dataStartColumn, dataStartRow, range.endColumn(), range.endRow()
        ));
        var orientation = inferRecordOrientation(
                cells, anchor.sheetId(), range, headerEnd, rowHeaderEnd
        );
        var semanticMode = "COLUMNS".equals(orientation) ? "RECORD_SET" : "CROSS_TAB";
        var model = objectMapper.createObjectNode()
                .put("semanticMode", semanticMode)
                .put("rowHeaderRange", rowHeaderRange)
                .put("columnHeaderRange", columnHeaderRange)
                .put("dataRange", dataRange);
        model.set("headerTree", headerTree(anchor.sheetId(), range, headerEnd, cells, structure));
        model.set("rowAxis", objectMapper.createObjectNode()
                .put("name", axisName(cells, anchor.sheetId(), range.startColumn(), dataStartRow, range.endRow(), "行项目"))
                .put("range", rowHeaderRange));
        model.set("columnAxis", objectMapper.createObjectNode()
                .put("name", axisName(cells, anchor.sheetId(), dataStartColumn, range.startRow(), headerEnd, "列项目"))
                .put("range", columnHeaderRange));
        model.set("units", inferredUnits(cells, anchor.sheetId(), range));
        model.set("recordAxis", objectMapper.createObjectNode()
                .put("orientation", orientation)
                .put("range", dataRange)
                .put("recordSpan", 1)
                .set("keyNodes", objectMapper.createArrayNode()));
        model.set("measureTree", measureTree(
                cells, anchor.sheetId(), range.startColumn(), rowHeaderEnd,
                dataStartColumn, range.endColumn(), dataStartRow, range.endRow()
        ));
        model.set("expansion", objectMapper.createObjectNode()
                .put("rows", "ROWS".equals(orientation))
                .put("columns", "COLUMNS".equals(orientation)));
        var logicalInputRanges = inputRanges(cells, anchor.sheetId(), parsedRangeOrCell(dataRange));
        model.set("inputRanges", logicalInputRanges.deepCopy());
        model.set("logicalInputRanges", logicalInputRanges);
        payload.set("matrixModel", model);
        var locator = (ObjectNode) payload.path("locator");
        locator.put("rowHeaderRange", rowHeaderRange);
        locator.put("columnHeaderRange", columnHeaderRange);
        locator.put("dataRange", dataRange);
        locator.put("anchorAddress", dataRange.split(":", 2)[0]);
        locator.put("logicalInputRange", dataRange);
        locator.put("valueMode", "RECORD_SET".equals(semanticMode) ? "RECORD_SET" : "ARRAY");
    }

    private String inferRecordOrientation(
            List<GridCell> cells, String sheetId, Range range, int headerEnd, int rowHeaderEnd
    ) {
        var rowLabels = cells.stream().filter(cell -> cell.sheetId().equals(sheetId) && cell.textLabel())
                .filter(cell -> cell.row() > headerEnd && cell.row() <= range.endRow())
                .filter(cell -> cell.column() >= range.startColumn() && cell.column() <= rowHeaderEnd)
                .count();
        var columnLabels = cells.stream().filter(cell -> cell.sheetId().equals(sheetId) && cell.textLabel())
                .filter(cell -> cell.row() >= range.startRow() && cell.row() <= headerEnd)
                .filter(cell -> cell.column() > rowHeaderEnd && cell.column() <= range.endColumn())
                .count();
        return rowLabels >= 3 && range.endColumn() - rowHeaderEnd >= 2 && rowLabels >= columnLabels
                ? "COLUMNS" : "ROWS";
    }

    private com.fasterxml.jackson.databind.node.ArrayNode measureTree(
            List<GridCell> cells, String sheetId, int startColumn, int endColumn,
            int dataStartColumn, int dataEndColumn, int startRow, int endRow
    ) {
        var result = objectMapper.createArrayNode();
        cells.stream().filter(cell -> cell.sheetId().equals(sheetId) && cell.textLabel())
                .filter(cell -> cell.row() >= startRow && cell.row() <= endRow)
                .filter(cell -> cell.column() >= startColumn && cell.column() <= endColumn)
                .sorted(Comparator.comparingInt(GridCell::row).thenComparingInt(GridCell::column))
                .forEach(cell -> result.add(objectMapper.createObjectNode()
                        .put("semanticId", stableToken(cell.sheetId() + "-measure-" + cell.address()))
                        .put("name", trimLabel(cell.text()))
                        .put("labelRange", cell.mergedRange().isBlank() ? cell.address() : cell.mergedRange())
                        .put("level", Math.max(0, cell.column() - startColumn))
                        .put("valueBand", columnName(dataStartColumn) + cell.row() + ":"
                                + columnName(dataEndColumn) + cell.row())
                        .set("children", objectMapper.createArrayNode())));
        return result;
    }

    private int inferHeaderEnd(List<GridCell> cells, String sheetId, Range range) {
        var end = range.startRow();
        var foundHeader = false;
        for (int row = range.startRow(); row <= Math.min(range.endRow(), range.startRow() + 3); row++) {
            var currentRow = row;
            var textCount = cells.stream().filter(cell -> cell.sheetId().equals(sheetId))
                    .filter(cell -> cell.row() == currentRow)
                    .filter(cell -> cell.column() >= range.startColumn() && cell.column() <= range.endColumn())
                    .filter(GridCell::textLabel).count();
            if (textCount > 0) {
                end = row;
                foundHeader = true;
            } else if (foundHeader) break;
        }
        return end;
    }

    private int inferRowHeaderColumns(List<GridCell> cells, String sheetId, Range range, int headerEnd) {
        var columns = 0;
        for (int column = range.startColumn(); column <= Math.min(range.endColumn(), range.startColumn() + 2); column++) {
            var currentColumn = column;
            var textCount = cells.stream().filter(cell -> cell.sheetId().equals(sheetId))
                    .filter(cell -> cell.column() == currentColumn && cell.row() > headerEnd
                            && cell.row() <= range.endRow()).filter(GridCell::textLabel).count();
            if (textCount >= 2) columns++;
            else break;
        }
        return Math.max(1, columns);
    }

    private com.fasterxml.jackson.databind.node.ArrayNode headerTree(
            String sheetId, Range range, int headerEnd, List<GridCell> cells, JsonNode structure
    ) {
        var result = objectMapper.createArrayNode();
        for (var merge : structure.path("mergedRanges")) {
            if (!sheetId.equals(merge.path("sheetId").asText())) continue;
            var merged = parsedRangeOrCell(merge.path("address").asText());
            if (merged == null || merged.startRow() > headerEnd || !merged.overlaps(range)) continue;
            var label = cells.stream().filter(cell -> cell.sheetId().equals(sheetId))
                    .filter(cell -> cell.row() == merged.startRow() && cell.column() == merged.startColumn())
                    .map(GridCell::text).findFirst().orElse("");
            result.add(objectMapper.createObjectNode()
                    .put("id", stableToken(sheetId + "-" + merge.path("address").asText()))
                    .put("name", label).put("range", merge.path("address").asText())
                    .put("level", Math.max(0, merged.startRow() - range.startRow())));
        }
        if (result.isEmpty()) {
            cells.stream().filter(cell -> cell.sheetId().equals(sheetId) && cell.textLabel())
                    .filter(cell -> cell.row() >= range.startRow() && cell.row() <= headerEnd)
                    .filter(cell -> cell.column() >= range.startColumn() && cell.column() <= range.endColumn())
                    .forEach(cell -> result.add(objectMapper.createObjectNode()
                            .put("id", stableToken(cell.key())).put("name", trimLabel(cell.text()))
                            .put("range", cell.address()).put("level", cell.row() - range.startRow())));
        }
        return result;
    }

    private String axisName(
            List<GridCell> cells, String sheetId, int column, int startRow, int endRow, String fallback
    ) {
        return cells.stream().filter(cell -> cell.sheetId().equals(sheetId) && cell.column() == column)
                .filter(cell -> cell.row() >= startRow && cell.row() <= endRow && cell.textLabel())
                .map(cell -> trimLabel(cell.text())).findFirst().orElse(fallback);
    }

    private ObjectNode inferredUnits(List<GridCell> cells, String sheetId, Range range) {
        var result = objectMapper.createObjectNode();
        var pattern = Pattern.compile("(℃|°C|%|MPa|kPa|Pa|mPa[·.]s|kg|g|mg|h|min|s)", Pattern.CASE_INSENSITIVE);
        cells.stream().filter(cell -> cell.sheetId().equals(sheetId) && cell.textLabel())
                .filter(cell -> range.contains(cell.column(), cell.row())).forEach(cell -> {
                    var matcher = pattern.matcher(cell.text());
                    if (matcher.find()) result.put(cell.address(), matcher.group(1));
                });
        return result;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode inputRanges(
            List<GridCell> cells, String sheetId, Range range
    ) {
        var result = objectMapper.createArrayNode();
        if (range == null) return result;
        var byRow = new java.util.TreeMap<Integer, List<GridCell>>();
        cells.stream().filter(cell -> cell.sheetId().equals(sheetId) && cell.empty() && cell.styled())
                .filter(cell -> range.contains(cell.column(), cell.row()))
                .forEach(cell -> byRow.computeIfAbsent(cell.row(), ignored -> new ArrayList<>()).add(cell));
        byRow.forEach((row, rowCells) -> {
            rowCells.sort(Comparator.comparingInt(GridCell::column));
            var start = rowCells.getFirst().column();
            var end = start;
            for (int index = 1; index < rowCells.size(); index++) {
                var column = rowCells.get(index).column();
                if (column > end + 1) {
                    result.add(a1(new Range(start, row, end, row)));
                    start = column;
                }
                end = column;
            }
            result.add(a1(new Range(start, row, end, row)));
        });
        return result;
    }

    private Range parsedRangeOrCell(String value) {
        var range = parsedRange(value);
        if (range != null) return range;
        var matcher = ADDRESS.matcher(value == null ? "" : value.toUpperCase(Locale.ROOT));
        return matcher.matches() ? new Range(
                columnIndex(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                columnIndex(matcher.group(1)), Integer.parseInt(matcher.group(2))
        ) : null;
    }

    private String a1(Range range) {
        var start = columnName(range.startColumn()) + range.startRow();
        var end = columnName(range.endColumn()) + range.endRow();
        return start.equals(end) ? start : start + ":" + end;
    }

    private boolean containsAny(String value, String... words) {
        for (var word : words) if (value.contains(word)) return true;
        return false;
    }

    private String trimLabel(String value) {
        return value.replaceAll("[：:]$", "").strip();
    }

    private String stableToken(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private int columnIndex(String letters) {
        var result = 0;
        for (var letter : letters.toUpperCase(Locale.ROOT).toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private String columnName(int column) {
        var value = column;
        var result = new StringBuilder();
        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return result.toString();
    }

    private record GridCell(
            String sheetId, String sheetName, String address, String text,
            int column, int row, boolean numeric, boolean empty, boolean styled, String mergedRange,
            String numberFormat
    ) {
        boolean textLabel() {
            return !empty && !numeric && !text.isBlank() && text.length() <= 40 && !text.startsWith("=");
        }
        String key() { return sheetId + ":" + address; }
    }

    private record Range(int startColumn, int startRow, int endColumn, int endRow) {
        int width() { return endColumn - startColumn + 1; }
        int height() { return endRow - startRow + 1; }
        boolean contains(int column, int row) {
            return column >= startColumn && column <= endColumn && row >= startRow && row <= endRow;
        }
        boolean overlaps(Range other) {
            return startColumn <= other.endColumn && endColumn >= other.startColumn
                    && startRow <= other.endRow && endRow >= other.startRow;
        }
    }
}
