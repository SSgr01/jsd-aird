package com.jsd.aird.tpl.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import com.jsd.aird.tpl.application.port.TabularStructureParser;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
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
                    var values = readRow(row, formatter);
                    rows.add(values);
                    if (!values.isEmpty()) {
                        firstColumn = Math.min(firstColumn, 1);
                        lastColumn = Math.max(lastColumn, values.size());
                    }
                }
                var header = guessHeader(rows);
                sheets.add(new TemplateDataImportFacade.ParsedSheet(
                        "sheet-" + (sheetIndex + 1), sheet.getSheetName(), sheetIndex,
                        firstRow + 1, lastRow + 1,
                        firstColumn == Integer.MAX_VALUE ? 1 : firstColumn,
                        Math.max(lastColumn, 1), header.candidates(), header.row(), header.row() + 1, rows));
            }
            return new TemplateDataImportFacade.ParsedTabularFile(
                    xlsx ? "XLSX" : "XLS", xlsx ? "poi-tabular-xlsx-v1" : "poi-tabular-xls-v1", sheets);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Excel 解析失败：" + exception.getMessage(), exception);
        }
    }

    private List<String> readRow(Row row, DataFormatter formatter) {
        if (row == null || row.getLastCellNum() <= 0) return List.of();
        var values = new ArrayList<String>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            var cell = row.getCell(index);
            values.add(cell == null ? "" : formatter.formatCellValue(cell));
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
            var sheet = new TemplateDataImportFacade.ParsedSheet(
                    "sheet-1", "CSV", 0, 1, rows.size(), 1,
                    rows.stream().mapToInt(List::size).max().orElse(1),
                    header.candidates(), header.row(), header.row() + 1, rows);
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
