package com.jsd.aird.tpl.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import com.jsd.aird.tpl.application.port.TabularStructureParser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class GenericTabularStructureParser implements TabularStructureParser {

    private static final int MAX_ROWS = 10_000;
    private static final int HEADER_SCAN_ROWS = 30;

    @Override
    public TemplateDataImportFacade.ParsedTabularFile parse(InputStream input, String fileName) {
        var lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) return parseCsv(input);
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return parseWorkbook(input, lower.endsWith(".xlsx"));
        throw new IllegalArgumentException("数据中心仅支持 XLS、XLSX 或 CSV");
    }

    private TemplateDataImportFacade.ParsedTabularFile parseWorkbook(InputStream input, boolean xlsx) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            var formatter = new DataFormatter();
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            var sheets = new ArrayList<TemplateDataImportFacade.ParsedSheet>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                var rows = new ArrayList<List<String>>();
                int firstColumn = Integer.MAX_VALUE;
                int lastColumn = -1;
                int firstRow = Math.max(0, sheet.getFirstRowNum());
                int lastRow = Math.min(sheet.getLastRowNum(), MAX_ROWS - 1);
                for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    var values = readRow(row, formatter, evaluator);
                    rows.add(values);
                    if (!values.isEmpty()) {
                        firstColumn = Math.min(firstColumn, 1);
                        lastColumn = Math.max(lastColumn, values.size());
                    }
                }
                var header = guessHeader(rows);
                var layoutIr = layoutIr(sheet, formatter, evaluator, firstRow, lastRow);
                var fingerprint = fingerprint(layoutIr.path("fingerprintBasis"));
                ((ObjectNode) layoutIr).put("structureFingerprint", fingerprint);
                sheets.add(new TemplateDataImportFacade.ParsedSheet(
                        "sheet-" + (sheetIndex + 1), sheet.getSheetName(), sheetIndex,
                        firstRow + 1, lastRow + 1,
                        firstColumn == Integer.MAX_VALUE ? 1 : firstColumn,
                        Math.max(lastColumn, 1), header.candidates(), header.row(), header.row() + 1, rows,
                        layoutIr, fingerprint));
            }
            return new TemplateDataImportFacade.ParsedTabularFile(
                    xlsx ? "XLSX" : "XLS", xlsx ? "poi-tabular-xlsx-v1" : "poi-tabular-xls-v1", sheets);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Excel 解析失败：" + exception.getMessage(), exception);
        }
    }

    private JsonNode layoutIr(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator,
                              int firstRow, int lastRow) {
        var root = JsonNodeFactory.instance.objectNode()
                .put("layoutIrVersion", 1)
                .put("sheetName", sheet.getSheetName())
                .put("sheetHidden", sheet.getWorkbook().isSheetHidden(sheet.getWorkbook().getSheetIndex(sheet))
                        || sheet.getWorkbook().isSheetVeryHidden(sheet.getWorkbook().getSheetIndex(sheet)));
        var cells = root.putArray("cells");
        var basis = root.putObject("fingerprintBasis");
        var basisCells = basis.putArray("cells");
        var formulaRoles = basis.putArray("formulaRoles");
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            var row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int columnIndex = 0; columnIndex < Math.max(0, row.getLastCellNum()); columnIndex++) {
                var cell = row.getCell(columnIndex);
                if (cell == null || cell.getCellType() == CellType.BLANK) continue;
                var address = cell.getAddress().formatAsString();
                var display = formatter.formatCellValue(cell);
                var item = cells.addObject()
                        .put("address", address)
                        .put("row", rowIndex + 1)
                        .put("column", columnIndex + 1)
                        .put("displayValue", display)
                        .put("cellType", cell.getCellType().name())
                        .put("styleIndex", cell.getCellStyle().getIndex())
                        .put("locked", cell.getCellStyle().getLocked())
                        .put("hidden", cell.getCellStyle().getHidden());
                basisCells.addObject().put("address", address)
                        .put("value", normalizeFingerprintText(display))
                        .put("type", cell.getCellType().name());
                if (cell.getCellType() == CellType.FORMULA) {
                    item.put("formulaExpression", cell.getCellFormula()).put("valueSource", "FORMULA");
                    formulaRoles.add(address);
                    applyFormulaResult(cell, formatter, evaluator, item);
                } else {
                    item.put("valueSource", "INPUT")
                            .put("calculationSource", "NOT_APPLICABLE")
                            .put("calculationStatus", "NOT_APPLICABLE")
                            .put("formulaTrustStatus", "NOT_APPLICABLE");
                }
            }
        }
        var merges = root.putArray("merges");
        var basisMerges = basis.putArray("merges");
        for (var merged : sheet.getMergedRegions()) {
            var range = merged.formatAsString();
            merges.add(range);
            basisMerges.add(range);
        }
        var validations = root.putArray("validations");
        for (var validation : sheet.getDataValidations()) {
            var item = validations.addObject();
            item.put("regions", java.util.Arrays.stream(validation.getRegions().getCellRangeAddresses())
                    .map(org.apache.poi.ss.util.CellRangeAddress::formatAsString)
                    .collect(java.util.stream.Collectors.joining(",")));
            var constraint = validation.getValidationConstraint();
            if (constraint != null) {
                item.put("validationType", constraint.getValidationType());
                if (constraint.getFormula1() != null) item.put("formula1", constraint.getFormula1());
                if (constraint.getFormula2() != null) item.put("formula2", constraint.getFormula2());
            }
        }
        var dimensions = root.putObject("dimensions");
        var hiddenRows = dimensions.putArray("hiddenRows");
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            var row = sheet.getRow(rowIndex);
            if (row != null && row.getZeroHeight()) hiddenRows.add(rowIndex + 1);
        }
        var hiddenColumns = dimensions.putArray("hiddenColumns");
        int maxColumn = Math.max(0, cells.findValues("column").stream().mapToInt(JsonNode::asInt).max().orElse(0));
        for (int column = 0; column < maxColumn; column++) if (sheet.isColumnHidden(column)) hiddenColumns.add(column + 1);
        return root;
    }

    private void applyFormulaResult(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator, ObjectNode item) {
        try {
            var evaluated = evaluator.evaluate(cell);
            if (evaluated != null && evaluated.getCellType() != CellType.ERROR) {
                item.put("cachedValue", evaluated.formatAsString())
                        .put("calculationSource", "RECALCULATED")
                        .put("calculationStatus", "VALID")
                        .put("formulaTrustStatus", "TRUSTED_RECALCULATED");
                return;
            }
        } catch (RuntimeException ignored) {
            // Fall back to the workbook cache; its freshness cannot be proven.
        }
        var cached = formatter.formatCellValue(cell);
        if (cached != null && !cached.isBlank() && !cached.startsWith("=")) {
            item.put("cachedValue", cached)
                    .put("calculationSource", "CACHED")
                    .put("calculationStatus", "STALE_POSSIBLE")
                    .put("formulaTrustStatus", "UNVERIFIED_CACHE");
        } else {
            item.putNull("cachedValue")
                    .put("calculationSource", "MISSING")
                    .put("calculationStatus", "FAILED")
                    .put("formulaTrustStatus", "MISSING_RESULT");
        }
    }

    private String fingerprint(JsonNode node) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(node.toString().getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(digest.length * 2);
            for (var item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算工作表结构指纹", exception);
        }
    }

    private String normalizeFingerprintText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private List<String> readRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || row.getLastCellNum() <= 0) return List.of();
        var values = new ArrayList<String>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            var cell = row.getCell(index);
            if (cell == null) values.add("");
            else if (cell.getCellType() == CellType.FORMULA) {
                try { values.add(formatter.formatCellValue(cell, evaluator)); }
                catch (RuntimeException ignored) { values.add(formatter.formatCellValue(cell)); }
            } else values.add(formatter.formatCellValue(cell));
        }
        return List.copyOf(values);
    }

    private TemplateDataImportFacade.ParsedTabularFile parseCsv(InputStream input) {
        try {
            var bytes = input.readAllBytes();
            var charset = isUtf8(bytes) ? StandardCharsets.UTF_8 : Charset.forName("GB18030");
            var text = new String(bytes, charset).replaceFirst("^\\uFEFF", "");
            var lines = text.split("\\r?\\n", -1);
            var rows = new ArrayList<List<String>>();
            for (int i = 0; i < Math.min(lines.length, MAX_ROWS); i++) rows.add(parseCsvLine(lines[i]));
            var header = guessHeader(rows);
            var layout = JsonNodeFactory.instance.objectNode().put("layoutIrVersion", 1).put("format", "CSV");
            var fingerprint = fingerprint(JsonNodeFactory.instance.arrayNode().addAll(rows.stream()
                    .map(JsonNodeFactory.instance::pojoNode).toList()));
            ((ObjectNode) layout).put("structureFingerprint", fingerprint);
            var sheet = new TemplateDataImportFacade.ParsedSheet(
                    "sheet-1", "CSV", 0, 1, rows.size(), 1,
                    rows.stream().mapToInt(List::size).max().orElse(1),
                    header.candidates(), header.row(), header.row() + 1, rows, layout, fingerprint);
            return new TemplateDataImportFacade.ParsedTabularFile("CSV", "csv-tabular-v1", List.of(sheet));
        } catch (IOException exception) {
            throw new IllegalArgumentException("CSV 解析失败：" + exception.getMessage(), exception);
        }
    }

    private List<String> parseCsvLine(String line) {
        var result = new ArrayList<String>();
        var value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else quoted = !quoted;
            } else if (current == ',' && !quoted) {
                result.add(value.toString().trim());
                value.setLength(0);
            } else value.append(current);
        }
        result.add(value.toString().trim());
        return List.copyOf(result);
    }

    private HeaderGuess guessHeader(List<List<String>> rows) {
        int bestRow = 0;
        int bestCount = -1;
        var candidates = new ArrayList<Integer>();
        for (int index = 0; index < Math.min(rows.size(), HEADER_SCAN_ROWS); index++) {
            int count = (int) rows.get(index).stream().filter(value -> value != null && !value.isBlank()).count();
            if (count > 0 && count >= bestCount) {
                if (count > bestCount) {
                    candidates.clear();
                    bestRow = index + 1;
                }
                bestCount = count;
                candidates.add(index + 1);
            }
        }
        return new HeaderGuess(candidates.isEmpty() ? List.of(1) : List.copyOf(candidates), Math.max(bestRow, 1));
    }

    private boolean isUtf8(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf) return true;
        try {
            var value = new String(bytes, StandardCharsets.UTF_8);
            return value.indexOf('\uFFFD') < 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private record HeaderGuess(List<Integer> candidates, int row) {
    }
}
