package com.jsd.aird.shared.office;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Rehydrates a persisted Univer workbook snapshot into a normal XLSX file.
 * This class deliberately has no storage or domain dependencies so both the
 * template and production export flows use exactly the same layout writer.
 */
@Component
public final class SnapshotWorkbookExporter {

    public static final String MANIFEST_SHEET = "_JSD_EXPORT_META";

    private final ObjectMapper objectMapper;

    public SnapshotWorkbookExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Result export(JsonNode snapshot, JsonNode mapping, JsonNode data, Manifest manifest) {
        var warnings = new ArrayList<Warning>();
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var styles = new HashMap<String, XSSFCellStyle>();
            var names = new HashMap<String, String>();
            var order = snapshot.path("sheetOrder");
            if (order.isArray()) {
                for (var sheetIdNode : order) {
                    var sheetId = sheetIdNode.asText();
                    var source = snapshot.path("sheets").path(sheetId);
                    if (!source.isObject()) continue;
                    var name = safeSheetName(source.path("name").asText("Sheet"));
                    var sheet = workbook.createSheet(name);
                    names.put(sheetId, name);
                    copyCells(workbook, snapshot.path("styles"), styles, source, sheet);
                    copyGeometry(source, sheet);
                }
            }
            if (workbook.getNumberOfSheets() == 0) workbook.createSheet("Sheet1");
            if (mapping != null && mapping.isArray()) {
                applyValues(snapshot, workbook, names, mapping, data == null ? objectMapper.createObjectNode() : data, warnings);
            }
            if (manifest != null) writeManifest(workbook, manifest);
            workbook.setForceFormulaRecalculation(true);
            workbook.write(output);
            return new Result(output.toByteArray(), List.copyOf(warnings));
        } catch (Exception exception) {
            throw new IllegalArgumentException("XLSX 导出失败：" + exception.getMessage(), exception);
        }
    }

    private void applyValues(
            JsonNode snapshot,
            XSSFWorkbook workbook,
            Map<String, String> sheetNames,
            JsonNode mapping,
            JsonNode data,
            List<Warning> warnings
    ) {
        for (var binding : mapping) {
            var status = binding.path("bindingStatus").asText("VALID");
            var locator = binding.path("locator");
            var address = first(locator, "logicalInputRange", "valueRange", "address", "range", "dataRange");
            var path = binding.path("dataPath").asText("");
            if (address.isBlank()) {
                warnings.add(new Warning("BINDING_MISSING", binding.path("bindingId").asText(""), path,
                        "字段没有有效位置，已留空"));
                continue;
            }
            if (!"VALID".equals(status) && !hasLocator(locator)) {
                warnings.add(new Warning("BINDING_INVALID", binding.path("bindingId").asText(""), path,
                        "字段位置无效，已留空"));
                continue;
            }
            var range = parseRange(address);
            var sheetName = resolveSheetName(locator, sheetNames);
            var target = sheetName.isBlank() ? null : workbook.getSheet(sheetName);
            var value = readPath(data, path);
            if (range == null || target == null) {
                warnings.add(new Warning("BINDING_MISSING", binding.path("bindingId").asText(""), path,
                        "字段位置无法定位，已留空"));
                continue;
            }
            var direction = binding.path("syncDirection").asText("TWO_WAY");
            if ("EDITOR_TO_DATA".equals(direction)) continue;
            writeRange(target, range, value, binding, warnings);
        }
    }

    private void writeRange(XSSFSheet sheet, Range range, JsonNode value, JsonNode binding, List<Warning> warnings) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            put(sheet, range.startRow(), range.startColumn(), value);
            return;
        }
        if (!value.isArray()) {
            put(sheet, range.startRow(), range.startColumn(), value);
            return;
        }
        var mode = binding.path("locator").path("valueMode").asText("");
        var targetRange = range;
        var capacity = "ARRAY_ROW".equals(mode)
                ? range.endColumn() - range.startColumn() + 1
                : range.endRow() - range.startRow() + 1;
        if (value.size() > capacity) {
            var extra = value.size() - capacity;
            targetRange = "ARRAY_ROW".equals(mode)
                    ? expandColumns(sheet, range, extra)
                    : expandRows(sheet, range, extra);
        }
        for (var index = 0; index < value.size(); index++) {
            var row = "ARRAY_ROW".equals(mode) ? targetRange.startRow() : targetRange.startRow() + index;
            var column = "ARRAY_ROW".equals(mode) ? targetRange.startColumn() + index : targetRange.startColumn();
            if (row > targetRange.endRow() || column > targetRange.endColumn()) {
                warnings.add(new Warning("REPEAT_CAPACITY_EXCEEDED", binding.path("bindingId").asText(""),
                        binding.path("dataPath").asText(""), "记录超过模板容量，超出部分未写入"));
                break;
            }
            put(sheet, row, column, value.get(index));
        }
    }

    private Range expandRows(XSSFSheet sheet, Range range, int extra) {
        var template = sheet.getRow(range.endRow() - 1);
        for (var index = 1; index <= extra; index++) {
            var target = sheet.getRow(range.endRow() - 1 + index);
            if (target == null) target = sheet.createRow(range.endRow() - 1 + index);
            copyRowStyle(template, target, range.startColumn() - 1, range.endColumn() - 1);
        }
        return new Range(range.startRow(), range.endRow() + extra, range.startColumn(), range.endColumn());
    }

    private Range expandColumns(XSSFSheet sheet, Range range, int extra) {
        for (var rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            var row = sheet.getRow(rowIndex);
            if (row == null) continue;
            var template = row.getCell(range.endColumn() - 1);
            for (var index = 1; index <= extra; index++) {
                var target = row.getCell(range.endColumn() - 1 + index);
                if (target == null) target = row.createCell(range.endColumn() - 1 + index);
                if (template != null && template.getCellStyle() != null) target.setCellStyle(template.getCellStyle());
            }
        }
        return new Range(range.startRow(), range.endRow(), range.startColumn(), range.endColumn() + extra);
    }

    private void copyRowStyle(org.apache.poi.ss.usermodel.Row source, org.apache.poi.ss.usermodel.Row target,
                              int startColumn, int endColumn) {
        if (source == null) return;
        target.setHeight(source.getHeight());
        for (var column = startColumn; column <= endColumn; column++) {
            var sourceCell = source.getCell(column);
            if (sourceCell == null) continue;
            var targetCell = target.getCell(column);
            if (targetCell == null) targetCell = target.createCell(column);
            targetCell.setCellStyle(sourceCell.getCellStyle());
        }
    }

    private void put(XSSFSheet sheet, int rowNumber, int columnNumber, JsonNode value) {
        var row = sheet.getRow(rowNumber - 1);
        if (row == null) row = sheet.createRow(rowNumber - 1);
        var cell = row.getCell(columnNumber - 1);
        if (cell == null) cell = row.createCell(columnNumber - 1);
        if (value == null || value.isNull() || value.isMissingNode()) {
            if (cell.getCellType() != CellType.FORMULA) cell.setBlank();
        } else if (value.isBoolean()) cell.setCellValue(value.asBoolean());
        else if (value.isNumber()) cell.setCellValue(value.asDouble());
        else cell.setCellValue(value.asText());
    }

    private JsonNode readPath(JsonNode current, String pointer) {
        if (pointer == null || pointer.isBlank()) return null;
        var parts = new ArrayList<String>();
        for (var part : pointer.split("/")) if (!part.isBlank()) parts.add(part.replace("~1", "/").replace("~0", "~"));
        return readPathParts(current, parts, 0);
    }

    private JsonNode readPathParts(JsonNode current, List<String> parts, int index) {
        if (index >= parts.size()) return current;
        if (current == null || current.isMissingNode() || current.isNull()) return null;
        if ("*".equals(parts.get(index))) {
            var result = objectMapper.createArrayNode();
            if (current.isArray()) current.forEach(item -> result.add(readPathParts(item, parts, index + 1)));
            return result;
        }
        return readPathParts(current.path(parts.get(index)), parts, index + 1);
    }

    private void copyCells(XSSFWorkbook workbook, JsonNode workbookStyles, Map<String, XSSFCellStyle> styles,
                           JsonNode source, XSSFSheet target) {
        var rows = source.path("cellData").fields();
        while (rows.hasNext()) {
            var rowEntry = rows.next();
            var row = target.createRow(Integer.parseInt(rowEntry.getKey()));
            var cells = rowEntry.getValue().fields();
            while (cells.hasNext()) {
                var cellEntry = cells.next();
                var cell = row.createCell(Integer.parseInt(cellEntry.getKey()));
                var sourceCell = cellEntry.getValue();
                if (sourceCell.has("f")) {
                    var formula = sourceCell.path("f").asText().replaceFirst("^=", "");
                    if (!formula.isBlank()) cell.setCellFormula(formula);
                } else if (sourceCell.path("v").isBoolean()) cell.setCellValue(sourceCell.path("v").asBoolean());
                else if (sourceCell.path("v").isNumber()) cell.setCellValue(sourceCell.path("v").asDouble());
                else if (sourceCell.has("v")) cell.setCellValue(sourceCell.path("v").asText());
                else cell.setCellType(CellType.BLANK);
                var styleNode = sourceCell.path("s").isTextual()
                        ? workbookStyles.path(sourceCell.path("s").asText()) : sourceCell.path("s");
                if (styleNode.isObject() && !styleNode.isEmpty()) {
                    cell.setCellStyle(styles.computeIfAbsent(styleNode.toString(), ignored -> createStyle(workbook, styleNode)));
                }
            }
        }
    }

    private void copyGeometry(JsonNode source, XSSFSheet target) {
        for (var merge : source.path("mergeData")) {
            target.addMergedRegion(new CellRangeAddress(merge.path("startRow").asInt(), merge.path("endRow").asInt(),
                    merge.path("startColumn").asInt(), merge.path("endColumn").asInt()));
        }
        var rows = source.path("rowData").fields();
        while (rows.hasNext()) {
            var entry = rows.next();
            var row = target.getRow(Integer.parseInt(entry.getKey()));
            if (row == null) row = target.createRow(Integer.parseInt(entry.getKey()));
            if (entry.getValue().has("h")) row.setHeightInPoints((float) (entry.getValue().path("h").asDouble() * 72d / 96d));
            row.setZeroHeight(entry.getValue().path("hd").asInt() == 1);
        }
        var columns = source.path("columnData").fields();
        while (columns.hasNext()) {
            var entry = columns.next();
            var index = Integer.parseInt(entry.getKey());
            if (entry.getValue().has("w")) target.setColumnWidth(index,
                    Math.min(255 * 256, Math.max(256, (int) Math.round(entry.getValue().path("w").asDouble() / 7d * 256d))));
            target.setColumnHidden(index, entry.getValue().path("hd").asInt() == 1);
        }
        // Univer persists frozen panes separately from row/column geometry.  Keep
        // them when rebuilding an XLSX so exported templates still scroll like
        // the saved workbook.  The parser stores splits as zero-based counts.
        var freeze = source.path("freeze");
        if (freeze.isObject()) {
            var rowSplit = Math.max(0, freeze.path("ySplit").asInt(freeze.path("startRow").asInt(0)));
            var columnSplit = Math.max(0, freeze.path("xSplit").asInt(freeze.path("startColumn").asInt(0)));
            if (rowSplit > 0 || columnSplit > 0) target.createFreezePane(columnSplit, rowSplit);
        }
    }

    private XSSFCellStyle createStyle(XSSFWorkbook workbook, JsonNode source) {
        var style = workbook.createCellStyle();
        var font = workbook.createFont();
        if (source.has("ff")) font.setFontName(source.path("ff").asText());
        if (source.has("fs")) font.setFontHeightInPoints((short) source.path("fs").asInt());
        font.setBold(source.path("bl").asInt() == 1);
        font.setItalic(source.path("it").asInt() == 1);
        var fontColor = color(source.path("cl"));
        if (fontColor != null) font.setColor(fontColor);
        style.setFont(font);
        var background = color(source.path("bg"));
        if (background != null) { style.setFillForegroundColor(background); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); }
        style.setAlignment(switch (source.path("ht").asInt()) {
            case 1 -> HorizontalAlignment.LEFT; case 2 -> HorizontalAlignment.CENTER; case 3 -> HorizontalAlignment.RIGHT;
            case 4 -> HorizontalAlignment.JUSTIFY; case 5 -> HorizontalAlignment.FILL; case 6 -> HorizontalAlignment.DISTRIBUTED;
            default -> HorizontalAlignment.GENERAL;
        });
        style.setVerticalAlignment(switch (source.path("vt").asInt()) { case 1 -> VerticalAlignment.TOP; case 2 -> VerticalAlignment.CENTER; default -> VerticalAlignment.BOTTOM; });
        style.setWrapText(source.path("tb").asInt() == 3);
        var pattern = source.path("n").path("pattern").asText("");
        if (!pattern.isBlank()) style.setDataFormat(workbook.createDataFormat().getFormat(pattern));
        var borders = source.path("bd");
        style.setBorderTop(border(borders.path("t").path("s").asInt())); style.setBorderRight(border(borders.path("r").path("s").asInt()));
        style.setBorderBottom(border(borders.path("b").path("s").asInt())); style.setBorderLeft(border(borders.path("l").path("s").asInt()));
        return style;
    }

    private BorderStyle border(int value) { return switch (value) {
        case 1 -> BorderStyle.THIN; case 2 -> BorderStyle.HAIR; case 3 -> BorderStyle.DOTTED; case 4 -> BorderStyle.DASHED;
        case 5 -> BorderStyle.DASH_DOT; case 6 -> BorderStyle.DASH_DOT_DOT; case 7 -> BorderStyle.DOUBLE; case 8 -> BorderStyle.MEDIUM;
        case 9 -> BorderStyle.MEDIUM_DASHED; case 10 -> BorderStyle.MEDIUM_DASH_DOT; case 11 -> BorderStyle.MEDIUM_DASH_DOT_DOT;
        case 12 -> BorderStyle.SLANTED_DASH_DOT; case 13 -> BorderStyle.THICK; default -> BorderStyle.NONE;
    }; }

    private XSSFColor color(JsonNode source) {
        var value = source.path("rgb").asText("").replace("#", "");
        if (!value.matches("[0-9A-Fa-f]{6}")) return null;
        return new XSSFColor(new byte[]{(byte) Integer.parseInt(value.substring(0, 2), 16), (byte) Integer.parseInt(value.substring(2, 4), 16), (byte) Integer.parseInt(value.substring(4, 6), 16)}, new DefaultIndexedColorMap());
    }

    private void writeManifest(XSSFWorkbook workbook, Manifest manifest) {
        var sheet = workbook.createSheet(MANIFEST_SHEET);
        putText(sheet, 0, "JSD_EXPORT", "1"); putText(sheet, 1, "templateVersionId", manifest.templateVersionId());
        putText(sheet, 2, "schemaHash", manifest.schemaHash()); putText(sheet, 3, "mappingHash", manifest.mappingHash());
        putText(sheet, 4, "source", manifest.source()); if (manifest.revisionId() != null) putText(sheet, 5, "revisionId", manifest.revisionId());
        workbook.setSheetVisibility(workbook.getSheetIndex(sheet), SheetVisibility.VERY_HIDDEN);
    }

    private void putText(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, String key, String value) {
        var row = sheet.createRow(rowIndex); row.createCell(0).setCellValue(key); row.createCell(1).setCellValue(value == null ? "" : value);
    }

    private String resolveSheetName(JsonNode locator, Map<String, String> names) {
        var byId = names.get(locator.path("sheetId").asText()); return byId == null ? locator.path("sheetName").asText("") : byId;
    }

    private boolean hasLocator(JsonNode locator) { return !first(locator, "logicalInputRange", "valueRange", "address", "range", "dataRange").isBlank(); }
    private String first(JsonNode source, String... keys) { for (var key : keys) if (!source.path(key).asText("").isBlank()) return source.path(key).asText(); return ""; }
    private String safeSheetName(String value) { var safe = value.replaceAll("[\\\\/?*\\[\\]:]", "_"); return safe.isBlank() ? "Sheet" : safe.substring(0, Math.min(31, safe.length())); }

    private Range parseRange(String value) {
        var match = java.util.regex.Pattern.compile("^([A-Z]+)([0-9]+)(?::([A-Z]+)([0-9]+))?$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value.replace("$", ""));
        if (!match.matches()) return null;
        return new Range(Integer.parseInt(match.group(2)), Integer.parseInt(match.group(4) == null ? match.group(2) : match.group(4)), column(match.group(1)), column(match.group(3) == null ? match.group(1) : match.group(3)));
    }
    private int column(String letters) { var result = 0; for (var c : letters.toUpperCase(Locale.ROOT).toCharArray()) result = result * 26 + c - 'A' + 1; return result; }

    public record Result(byte[] content, List<Warning> warnings) {}
    public record Warning(String code, String bindingId, String dataPath, String message) {}
    public record Manifest(String templateVersionId, String schemaHash, String mappingHash, String source, String revisionId) {}
    private record Range(int startRow, int endRow, int startColumn, int endColumn) {}
}
