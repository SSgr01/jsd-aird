package com.jsd.aird.tpl.infrastructure;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Converts XLSX OOXML into a business-fidelity Univer workbook and recognition summary. */
@Component
public class XlsxStructureParser implements OfficeStructureParser {

    static final int STRUCTURE_VERSION = WorkbookRegionSegmenter.STRUCTURE_VERSION;
    private static final double POINT_TO_PIXEL = 96d / 72d;

    private final ObjectMapper objectMapper;

    public XlsxStructureParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TemplateFormat format() {
        return TemplateFormat.XLSX;
    }

    @Override
    public ParseResult parse(InputStream input) {
        try (var workbook = new XSSFWorkbook(input)) {
            var summary = objectMapper.createObjectNode();
            var sheets = objectMapper.createArrayNode();
            var snapshot = objectMapper.createObjectNode();
            var sheetOrder = objectMapper.createArrayNode();
            var snapshotSheets = objectMapper.createObjectNode();
            var issues = new ArrayList<ParseIssue>();
            var allCandidates = objectMapper.createArrayNode();
            var allMergedRanges = objectMapper.createArrayNode();
            var allDataValidations = objectMapper.createArrayNode();
            var formulaCount = 0;
            var mergedCount = 0;

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                var sheetId = "sheet-" + (sheetIndex + 1);
                var dimensions = dimensions(sheet);
                var sheetCandidates = objectMapper.createArrayNode();
                var mergedRanges = objectMapper.createArrayNode();
                var mergeData = objectMapper.createArrayNode();
                var mergeAddresses = new ArrayList<CellRangeAddress>();
                var dataValidations = validationSummaries(sheetId, sheet.getSheetName(), sheet);
                for (var validation : dataValidations) allDataValidations.add(validation.deepCopy());
                for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
                    var region = sheet.getMergedRegion(index);
                    mergeAddresses.add(region);
                    mergedRanges.add(regionSummary(sheetId, sheet.getSheetName(), region));
                    allMergedRanges.add(regionSummary(sheetId, sheet.getSheetName(), region));
                    mergeData.add(univerRange(region));
                }
                mergedCount += mergeAddresses.size();

                var cellData = objectMapper.createObjectNode();
                var rowData = objectMapper.createObjectNode();
                for (var row : sheet) {
                    var univerRow = objectMapper.createObjectNode();
                    if (row.getZeroHeight()) univerRow.put("hd", 1);
                    if (row.getHeight() != sheet.getDefaultRowHeight()) {
                        univerRow.put("h", Math.max(1, Math.round(row.getHeightInPoints() * POINT_TO_PIXEL)));
                    }
                    if (!univerRow.isEmpty()) rowData.set(String.valueOf(row.getRowNum()), univerRow);

                    var cells = objectMapper.createObjectNode();
                    for (var cell : row) {
                        var cellJson = cellData(workbook, cell);
                        if (!cellJson.isEmpty()) cells.set(String.valueOf(cell.getColumnIndex()), cellJson);
                        var candidate = candidateCell(cell, cellJson, sheetId, sheet.getSheetName(), mergeAddresses);
                        if (candidate != null) {
                            sheetCandidates.add(candidate);
                            allCandidates.add(candidate.deepCopy());
                        }
                        if (cell.getCellType() == CellType.FORMULA) formulaCount++;
                    }
                    if (!cells.isEmpty()) cellData.set(String.valueOf(row.getRowNum()), cells);
                }

                var columnData = objectMapper.createObjectNode();
                for (int column = 0; column <= dimensions.lastColumn(); column++) {
                    var data = objectMapper.createObjectNode();
                    if (sheet.isColumnHidden(column)) data.put("hd", 1);
                    var width = Math.round(sheet.getColumnWidthInPixels(column));
                    if (width > 0) data.put("w", width);
                    columnData.set(String.valueOf(column), data);
                }

                var sheetSummary = objectMapper.createObjectNode()
                        .put("id", sheetId)
                        .put("name", sheet.getSheetName())
                        .put("hidden", workbook.isSheetHidden(sheetIndex)
                                || workbook.isSheetVeryHidden(sheetIndex))
                        .put("firstRow", dimensions.firstRow() + 1)
                        .put("lastRow", dimensions.lastRow() + 1)
                        .put("mergedRegions", mergeAddresses.size())
                        .put("tables", sheet.getTables().size())
                        .put("dataValidations", sheet.getDataValidations().size())
                        .put("formulaCount", countFormulas(sheet))
                        .put("candidateCellCount", sheetCandidates.size())
                        .put("candidateCellsTruncated", false)
                        .put("usedRange", dimensions.a1());
                sheetSummary.set("candidateCells", sheetCandidates);
                sheetSummary.set("mergedRanges", mergedRanges);
                sheetSummary.set("rowData", rowData.deepCopy());
                sheetSummary.set("columnData", columnData.deepCopy());
                sheetSummary.set("dataValidationRules", dataValidations);
                sheets.add(sheetSummary);
                sheetOrder.add(sheetId);

                var snapshotSheet = objectMapper.createObjectNode();
                snapshotSheet.put("id", sheetId)
                        .put("name", sheet.getSheetName())
                        .put("hidden", workbook.isSheetHidden(sheetIndex)
                                || workbook.isSheetVeryHidden(sheetIndex) ? 1 : 0)
                        .put("rowCount", Math.max(200, dimensions.lastRow() + 20))
                        .put("columnCount", Math.max(50, dimensions.lastColumn() + 20))
                        .put("defaultColumnWidth", Math.round(sheet.getDefaultColumnWidth() * 7d))
                        .put("defaultRowHeight", Math.round(sheet.getDefaultRowHeightInPoints() * POINT_TO_PIXEL))
                        .put("showGridlines", sheet.isDisplayGridlines() ? 1 : 0)
                        .put("rightToLeft", sheet.isRightToLeft() ? 1 : 0);
                snapshotSheet.set("cellData", cellData);
                snapshotSheet.set("mergeData", mergeData);
                snapshotSheet.set("rowData", rowData);
                snapshotSheet.set("columnData", columnData);
                snapshotSheet.set("freeze", freezePane(sheet));
                snapshotSheet.set("rowHeader", objectMapper.createObjectNode().put("width", 46));
                snapshotSheet.set("columnHeader", objectMapper.createObjectNode().put("height", 20));
                snapshotSheets.set(sheetId, snapshotSheet);
            }

            summary.put("format", "XLSX");
            summary.put("structureVersion", STRUCTURE_VERSION);
            summary.put("parserVersion", "xlsx-business-regions-v5");
            summary.put("sheetCount", workbook.getNumberOfSheets());
            summary.put("formulaCount", formulaCount);
            summary.put("mergedRegionCount", mergedCount);
            summary.set("sheets", sheets);
            summary.set("candidateCells", allCandidates);
            summary.set("mergedRanges", allMergedRanges);
            summary.set("dataValidations", allDataValidations);
            new WorkbookRegionSegmenter(objectMapper).enrich(summary);
            if (!workbook.getAllNames().isEmpty()) {
                var names = objectMapper.createArrayNode();
                workbook.getAllNames().forEach(name -> names.add(objectMapper.createObjectNode()
                        .put("name", name.getNameName())
                        .put("formula", name.getRefersToFormula())));
                summary.set("namedRanges", names);
            }

            snapshot.put("id", UUID.randomUUID().toString());
            snapshot.put("name", "导入的 Excel 模板");
            snapshot.put("appVersion", "univer-0.25.1");
            snapshot.put("snapshotFormatVersion", 3);
            snapshot.set("sheetOrder", sheetOrder);
            snapshot.set("sheets", snapshotSheets);

            issues.add(new ParseIssue(
                    "INFO",
                    "XLSX_BUSINESS_FIDELITY",
                    "已保留合并、行列尺寸和核心单元格样式，并提取数据验证用于识别；图片、图表、数据验证往返及打印设置不在本次转换范围。",
                    objectMapper.createObjectNode()
            ));
            return new ParseResult(summary, snapshot, List.copyOf(issues));
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "XLSX 解析失败：" + exception.getMessage());
        }
    }

    private ObjectNode cellData(XSSFWorkbook workbook, Cell cell) {
        var result = objectMapper.createObjectNode();
        switch (cell.getCellType()) {
            case STRING -> {
                result.put("v", cell.getStringCellValue());
                result.put("t", 1);
            }
            case NUMERIC -> {
                result.put("v", cell.getNumericCellValue());
                result.put("t", 2);
            }
            case BOOLEAN -> {
                result.put("v", cell.getBooleanCellValue());
                result.put("t", 3);
            }
            case FORMULA -> result.put("f", "=" + cell.getCellFormula());
            case ERROR -> {
                result.put("v", "#ERROR");
                result.put("t", 1);
            }
            case BLANK, _NONE -> { }
        }
        var style = univerStyle(workbook, cell);
        if (!style.isEmpty()) result.set("s", style);
        return result;
    }

    private ObjectNode candidateCell(
            Cell cell,
            ObjectNode cellJson,
            String sheetId,
            String sheetName,
            List<CellRangeAddress> mergedRanges
    ) {
        var hasValue = cellJson.has("v") || cellJson.has("f");
        var style = cellJson.path("s");
        var styled = style.isObject() && !style.isEmpty();
        if (!hasValue && !styled) return null;
        var candidate = objectMapper.createObjectNode()
                .put("sheetId", sheetId)
                .put("sheetName", sheetName)
                .put("address", cell.getAddress().formatAsString())
                .put("row", cell.getRowIndex() + 1)
                .put("column", cell.getColumnIndex() + 1)
                .put("empty", !hasValue)
                .put("valueType", cell.getCellType().name())
                .put("hasComment", cell.getCellComment() != null)
                .put("hasHyperlink", cell.getHyperlink() != null)
                .put("locked", cell.getCellStyle().getLocked())
                .put("sheetProtected", cell.getSheet().getProtect())
                .put("bold", cell.getSheet().getWorkbook().getFontAt(
                        cell.getCellStyle().getFontIndex()
                ).getBold())
                .put("hasBorder", hasBorder(cell.getCellStyle()));
        if (cellJson.has("v")) candidate.set("value", cellJson.get("v"));
        if (cellJson.has("f")) {
            candidate.set("value", cellJson.get("f"));
            candidate.put("formula", true);
        }
        if (styled) candidate.set("style", style.deepCopy());
        mergedRanges.stream()
                .filter(region -> region.isInRange(cell))
                .findFirst()
                .ifPresent(region -> candidate.put("mergedRange", region.formatAsString()));
        return candidate;
    }

    private ObjectNode univerStyle(XSSFWorkbook workbook, Cell cell) {
        var source = (XSSFCellStyle) cell.getCellStyle();
        var font = (XSSFFont) workbook.getFontAt(source.getFontIndex());
        var style = objectMapper.createObjectNode();
        if (font.getFontName() != null) style.put("ff", font.getFontName());
        if (font.getFontHeightInPoints() > 0) style.put("fs", font.getFontHeightInPoints());
        if (font.getBold()) style.put("bl", 1);
        if (font.getItalic()) style.put("it", 1);
        putColor(style, "cl", font.getXSSFColor());
        if (source.getFillPattern() != FillPatternType.NO_FILL) {
            putColor(style, "bg", source.getFillForegroundXSSFColor());
        }
        var borders = objectMapper.createObjectNode();
        putBorder(borders, "t", source.getBorderTop(), source.getTopBorderXSSFColor());
        putBorder(borders, "r", source.getBorderRight(), source.getRightBorderXSSFColor());
        putBorder(borders, "b", source.getBorderBottom(), source.getBottomBorderXSSFColor());
        putBorder(borders, "l", source.getBorderLeft(), source.getLeftBorderXSSFColor());
        if (!borders.isEmpty()) style.set("bd", borders);
        var horizontal = horizontal(source.getAlignment());
        if (horizontal > 0) style.put("ht", horizontal);
        var vertical = vertical(source.getVerticalAlignment());
        if (vertical > 0) style.put("vt", vertical);
        if (source.getWrapText()) style.put("tb", 3);
        if (source.getRotation() != 0) {
            style.set("tr", objectMapper.createObjectNode().put("a", source.getRotation()));
        }
        var numberFormat = source.getDataFormatString();
        if (numberFormat != null && !"General".equalsIgnoreCase(numberFormat)) {
            style.set("n", objectMapper.createObjectNode().put("pattern", numberFormat));
        }
        return style;
    }

    private void putColor(ObjectNode target, String property, XSSFColor color) {
        var rgb = color == null ? null : color.getRGBWithTint();
        if (rgb == null || rgb.length < 3) return;
        target.set(property, objectMapper.createObjectNode().put(
                "rgb", String.format(Locale.ROOT, "#%02X%02X%02X", rgb[0] & 255, rgb[1] & 255, rgb[2] & 255)
        ));
    }

    private void putBorder(ObjectNode target, String side, BorderStyle style, XSSFColor color) {
        var mapped = border(style);
        if (mapped == 0) return;
        var border = objectMapper.createObjectNode().put("s", mapped);
        var rgb = color == null ? null : color.getRGBWithTint();
        if (rgb != null && rgb.length >= 3) {
            border.set("cl", objectMapper.createObjectNode().put(
                    "rgb", String.format(Locale.ROOT, "#%02X%02X%02X", rgb[0] & 255, rgb[1] & 255, rgb[2] & 255)
            ));
        } else {
            border.set("cl", objectMapper.createObjectNode().put("rgb", "#000000"));
        }
        target.set(side, border);
    }

    private int border(BorderStyle style) {
        return switch (style) {
            case NONE -> 0;
            case THIN -> 1;
            case HAIR -> 2;
            case DOTTED -> 3;
            case DASHED -> 4;
            case DASH_DOT -> 5;
            case DASH_DOT_DOT -> 6;
            case DOUBLE -> 7;
            case MEDIUM -> 8;
            case MEDIUM_DASHED -> 9;
            case MEDIUM_DASH_DOT -> 10;
            case MEDIUM_DASH_DOT_DOT -> 11;
            case SLANTED_DASH_DOT -> 12;
            case THICK -> 13;
        };
    }

    private int horizontal(HorizontalAlignment alignment) {
        return switch (alignment) {
            case LEFT -> 1;
            case CENTER, CENTER_SELECTION -> 2;
            case RIGHT -> 3;
            case JUSTIFY -> 4;
            case FILL -> 5;
            case DISTRIBUTED -> 6;
            default -> 0;
        };
    }

    private int vertical(VerticalAlignment alignment) {
        return switch (alignment) {
            case TOP -> 1;
            case CENTER, JUSTIFY, DISTRIBUTED -> 2;
            case BOTTOM -> 3;
        };
    }

    private boolean hasBorder(org.apache.poi.ss.usermodel.CellStyle style) {
        return style.getBorderTop() != BorderStyle.NONE || style.getBorderRight() != BorderStyle.NONE
                || style.getBorderBottom() != BorderStyle.NONE || style.getBorderLeft() != BorderStyle.NONE;
    }

    private ObjectNode regionSummary(String sheetId, String sheetName, CellRangeAddress region) {
        return objectMapper.createObjectNode()
                .put("sheetId", sheetId)
                .put("sheetName", sheetName)
                .put("address", region.formatAsString())
                .put("startRow", region.getFirstRow() + 1)
                .put("endRow", region.getLastRow() + 1)
                .put("startColumn", region.getFirstColumn() + 1)
                .put("endColumn", region.getLastColumn() + 1);
    }

    private ArrayNode validationSummaries(
            String sheetId, String sheetName, org.apache.poi.xssf.usermodel.XSSFSheet sheet
    ) {
        var result = objectMapper.createArrayNode();
        for (var validation : sheet.getDataValidations()) {
            for (var range : validation.getRegions().getCellRangeAddresses()) {
                var item = objectMapper.createObjectNode()
                        .put("sheetId", sheetId).put("sheetName", sheetName)
                        .put("address", range.formatAsString())
                        .put("allowBlank", validation.getEmptyCellAllowed())
                        .put("validationType", validation.getValidationConstraint().getValidationType());
                var formula1 = validation.getValidationConstraint().getFormula1();
                if (formula1 != null) item.put("formula1", formula1);
                result.add(item);
            }
        }
        return result;
    }

    private ObjectNode univerRange(CellRangeAddress region) {
        return objectMapper.createObjectNode()
                .put("startRow", region.getFirstRow())
                .put("endRow", region.getLastRow())
                .put("startColumn", region.getFirstColumn())
                .put("endColumn", region.getLastColumn());
    }

    private ObjectNode freezePane(org.apache.poi.xssf.usermodel.XSSFSheet sheet) {
        var pane = sheet.getPaneInformation();
        if (pane == null || !pane.isFreezePane()) {
            return objectMapper.createObjectNode()
                    .put("startRow", -1).put("startColumn", -1)
                    .put("xSplit", 0).put("ySplit", 0);
        }
        return objectMapper.createObjectNode()
                .put("startRow", pane.getHorizontalSplitPosition())
                .put("startColumn", pane.getVerticalSplitPosition())
                .put("xSplit", pane.getVerticalSplitPosition())
                .put("ySplit", pane.getHorizontalSplitPosition());
    }

    private Dimensions dimensions(org.apache.poi.xssf.usermodel.XSSFSheet sheet) {
        if (sheet.getPhysicalNumberOfRows() == 0) return new Dimensions(0, 0, 0, 0);
        var firstRow = Math.max(0, sheet.getFirstRowNum());
        var lastRow = Math.max(firstRow, sheet.getLastRowNum());
        var firstColumn = Integer.MAX_VALUE;
        var lastColumn = 0;
        for (var row : sheet) {
            if (row.getFirstCellNum() >= 0) firstColumn = Math.min(firstColumn, row.getFirstCellNum());
            if (row.getLastCellNum() > 0) lastColumn = Math.max(lastColumn, row.getLastCellNum() - 1);
        }
        if (firstColumn == Integer.MAX_VALUE) firstColumn = 0;
        return new Dimensions(firstRow, lastRow, firstColumn, lastColumn);
    }

    private int countFormulas(org.apache.poi.ss.usermodel.Sheet sheet) {
        var count = 0;
        for (var row : sheet) for (var cell : row) if (cell.getCellType() == CellType.FORMULA) count++;
        return count;
    }

    private int physicalCellCount(org.apache.poi.ss.usermodel.Sheet sheet) {
        var count = 0;
        for (var row : sheet) count += row.getPhysicalNumberOfCells();
        return count;
    }

    private record Dimensions(int firstRow, int lastRow, int firstColumn, int lastColumn) {
        String a1() {
            return columnName(firstColumn + 1) + (firstRow + 1) + ":"
                    + columnName(lastColumn + 1) + (lastRow + 1);
        }

        private static String columnName(int column) {
            var value = column;
            var result = new StringBuilder();
            while (value > 0) {
                value--;
                result.insert(0, (char) ('A' + value % 26));
                value /= 26;
            }
            return result.toString();
        }
    }
}
