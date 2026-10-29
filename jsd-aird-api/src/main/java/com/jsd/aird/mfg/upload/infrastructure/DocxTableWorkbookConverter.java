package com.jsd.aird.mfg.upload.infrastructure;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Derives an editable workbook snapshot from the tables of a Word document, so a
 * Word production order can be edited in the sheet workspace exactly like an
 * upload that carried its own spreadsheet.
 *
 * <p>Only table content has a cell to live in.  Page setup, headers and
 * footers, prose paragraphs outside tables and embedded objects are reported
 * back in the summary instead of being silently dropped, so the workspace can
 * tell the user what stayed in the original file.</p>
 */
@Component
public class DocxTableWorkbookConverter {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.jsd.aird.shared.resource.IsolatedDocumentTasks isolated;

    /**
     * The sheet workspace needs somewhere to type, so a converted form keeps the
     * same minimum grid the client-side XLSX preview uses.
     */
    private static final int MIN_ROWS = 200;
    private static final int MIN_COLUMNS = 20;

    /** Word stores table geometry in twentieths of a point; the editor uses pixels. */
    private static final double TWIPS_PER_PIXEL = 15d;

    private final ObjectMapper objectMapper;

    public DocxTableWorkbookConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A snapshot ready to persist, plus the facts worth showing to the user. */
    public record ConvertedWorksheet(JsonNode snapshot, JsonNode summary) {
    }

    /**
     * Returns {@code null} when the document has no non-empty table, because then
     * there is nothing a worksheet could represent.
     */
    public ConvertedWorksheet convert(byte[] docx, String documentName) {
        if(isolated!=null)return isolated.execute("docx-workbook",new ByteArrayInputStream(docx),com.fasterxml.jackson.databind.node.TextNode.valueOf(documentName),ConvertedWorksheet.class);
        try (var document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            var sheets = new ArrayList<ObjectNode>();
            var cellCount = 0;
            // Only body-level tables become sheets: a nested table is inlined into
            // its parent cell instead, so the form does not explode into fragments.
            for (IBodyElement element : document.getBodyElements()) {
                if (!(element instanceof XWPFTable table)) continue;
                var sheet = toSheet(table, sheets.size() + 1);
                if (sheet == null) continue;
                cellCount += countCells(sheet.path("cellData"));
                sheets.add(sheet);
            }
            if (sheets.isEmpty()) return null;

            var snapshot = objectMapper.createObjectNode();
            snapshot.put("id", UUID.randomUUID().toString());
            snapshot.put("snapshotFormatVersion", 3);
            snapshot.put("name", documentName == null || documentName.isBlank()
                    ? "Word 生产单" : documentName);
            var sheetOrder = snapshot.putArray("sheetOrder");
            var sheetNodes = snapshot.putObject("sheets");
            for (var sheet : sheets) {
                var id = sheet.path("id").asText();
                sheetOrder.add(id);
                sheetNodes.set(id, sheet);
            }
            snapshot.set("styles", objectMapper.createObjectNode());

            var summary = objectMapper.createObjectNode()
                    .put("sheetCount", sheets.size())
                    .put("cellCount", cellCount)
                    .put("paragraphCharactersOutsideTables", paragraphCharactersOutsideTables(document))
                    .put("imageCount", document.getAllPictures().size());
            return new ConvertedWorksheet(snapshot, summary);
        } catch (Exception exception) {
            throw new IllegalStateException("Word 表格无法转换为工作表：" + exception.getMessage(), exception);
        }
    }

    /** Converts one table into a single sheet, or {@code null} when it is empty. */
    private ObjectNode toSheet(XWPFTable table, int index) {
        var cellData = objectMapper.createObjectNode();
        var mergeData = objectMapper.createArrayNode();
        var rows = table.getRows();
        // Grid column that starts a vertical merge -> the row it started on.  A
        // merge stays open while the following rows keep repeating the cell.
        var openVerticalMerges = new HashMap<Integer, Integer>();
        var usedRows = 0;
        var usedColumns = 0;
        for (var rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            var row = rows.get(rowIndex);
            Set<Integer> continued = new HashSet<>();
            List<Integer> opened = new ArrayList<>();
            var column = 0;
            for (var cell : row.getTableCells()) {
                var span = Math.max(1, gridSpan(cell));
                var verticalMerge = verticalMerge(cell);
                if ("continue".equals(verticalMerge)) {
                    if (openVerticalMerges.containsKey(column)) continued.add(column);
                } else {
                    var text = text(cell);
                    if (!text.isBlank()) writeCell(cellData, rowIndex, column, text);
                    if (span > 1) addMerge(mergeData, rowIndex, rowIndex, column, column + span - 1);
                    if ("restart".equals(verticalMerge)) opened.add(column);
                }
                column += span;
            }
            usedRows = Math.max(usedRows, rowIndex + 1);
            usedColumns = Math.max(usedColumns, column);
            closeVerticalMerges(mergeData, openVerticalMerges, continued, rowIndex);
            for (var startColumn : opened) openVerticalMerges.put(startColumn, rowIndex);
        }
        closeVerticalMerges(mergeData, openVerticalMerges, Set.of(), rows.size());

        if (cellData.isEmpty()) return null;
        var sheet = objectMapper.createObjectNode();
        sheet.put("id", "docx-table-" + index);
        sheet.put("name", "表格" + index);
        sheet.put("rowCount", Math.max(usedRows, MIN_ROWS));
        sheet.put("columnCount", Math.max(usedColumns, MIN_COLUMNS));
        sheet.set("cellData", cellData);
        if (!mergeData.isEmpty()) sheet.set("mergeData", mergeData);
        var columnData = columnData(table);
        if (!columnData.isEmpty()) sheet.set("columnData", columnData);
        return sheet;
    }

    /** Cells that carry text; empty form cells are not worth reporting as content. */
    private int countCells(JsonNode cellData) {
        var cells = 0;
        for (var row : cellData) cells += row.size();
        return cells;
    }

    private void writeCell(ObjectNode cellData, int rowIndex, int column, String text) {
        var rowKey = String.valueOf(rowIndex);
        var row = cellData.get(rowKey);
        var rowNode = row instanceof ObjectNode existing ? existing : cellData.putObject(rowKey);
        rowNode.putObject(String.valueOf(column)).put("v", text);
    }

    /** Closes every merge that the given row did not continue, ending it one row above. */
    private void closeVerticalMerges(com.fasterxml.jackson.databind.node.ArrayNode mergeData,
                                     Map<Integer, Integer> openVerticalMerges, Set<Integer> continued,
                                     int closedBeforeRow) {
        for (var startColumn : new ArrayList<>(openVerticalMerges.keySet())) {
            if (continued.contains(startColumn)) continue;
            var startRow = openVerticalMerges.remove(startColumn);
            if (closedBeforeRow - 1 > startRow) {
                addMerge(mergeData, startRow, closedBeforeRow - 1, startColumn, startColumn);
            }
        }
    }

    private void addMerge(com.fasterxml.jackson.databind.node.ArrayNode mergeData,
                          int startRow, int endRow, int startColumn, int endColumn) {
        mergeData.addObject()
                .put("startRow", startRow).put("endRow", endRow)
                .put("startColumn", startColumn).put("endColumn", endColumn);
    }

    /**
     * Cell text with nested tables inlined, so a form inside a form still arrives
     * in one cell instead of disappearing.
     */
    private String text(XWPFTableCell cell) {
        var value = new StringBuilder();
        for (var paragraph : cell.getParagraphs()) {
            value.append(paragraph.getText() == null ? "" : paragraph.getText()).append(' ');
        }
        for (var nested : cell.getTables()) {
            for (XWPFTableRow row : nested.getRows()) {
                for (var nestedCell : row.getTableCells()) value.append(text(nestedCell)).append(' ');
            }
        }
        return value.toString().replaceAll("\\s+", " ").strip();
    }

    /** How many grid columns the cell covers; 1 when it is not merged horizontally. */
    private int gridSpan(XWPFTableCell cell) {
        var properties = cell.getCTTc().getTcPr();
        if (properties == null || !properties.isSetGridSpan()) return 1;
        var value = properties.getGridSpan().getVal();
        return value == null ? 1 : value.intValue();
    }

    /** {@code restart}, {@code continue}, or an empty string when the cell is not merged. */
    private String verticalMerge(XWPFTableCell cell) {
        var properties = cell.getCTTc().getTcPr();
        if (properties == null || !properties.isSetVMerge()) return "";
        var value = properties.getVMerge().getVal();
        // A missing val means "continue" per the OOXML schema.
        return value == null ? "continue" : value.toString().toLowerCase(Locale.ROOT);
    }

    private ObjectNode columnData(XWPFTable table) {
        var widths = objectMapper.createObjectNode();
        var grid = table.getCTTbl().getTblGrid();
        if (grid == null) return widths;
        var columns = grid.getGridColList();
        for (var index = 0; index < columns.size(); index++) {
            var pixels = twipsToPixels(columns.get(index).getW());
            if (pixels <= 0) continue;
            widths.putObject(String.valueOf(index)).put("w", pixels);
        }
        return widths;
    }

    /** {@code ST_TwipsMeasure} is a union of a positive integer and the literal "auto". */
    private int twipsToPixels(Object width) {
        if (width == null) return 0;
        var text = width.toString().strip();
        if (text.isEmpty() || !text.chars().allMatch(character -> character >= '0' && character <= '9')) {
            return 0;
        }
        return (int) Math.round(Integer.parseInt(text) / TWIPS_PER_PIXEL);
    }

    /** Body text that has no cell to live in and therefore stays in the original file. */
    private int paragraphCharactersOutsideTables(XWPFDocument document) {
        var characters = 0;
        for (IBodyElement element : document.getBodyElements()) {
            if (!(element instanceof XWPFParagraph paragraph)) continue;
            var text = paragraph.getText();
            if (text != null) characters += text.replaceAll("\\s+", "").length();
        }
        return characters;
    }
}
