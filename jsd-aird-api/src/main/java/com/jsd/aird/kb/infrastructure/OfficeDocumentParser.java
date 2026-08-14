package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jsd.aird.kb.domain.DocumentParser;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;

@Component
public class OfficeDocumentParser implements DocumentParser {

    private static final int INLINE_MAX_ROWS = 500;
    private static final int INLINE_MAX_COLUMNS = 50;
    private static final int INLINE_MAX_NON_EMPTY = 10_000;
    private static final int SHEET_MAX_ROWS = 100_000;
    private static final int SHEET_MAX_COLUMNS = 500;
    private static final int WORKBOOK_MAX_NON_EMPTY = 1_000_000;

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".xls")
                || name.endsWith(".pptx") || name.endsWith(".ppt");
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        var name = fileName.toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".docx")) return parseDocx(source);
            if (name.endsWith(".pptx") || name.endsWith(".ppt")) return parsePpt(source);
            return parseWorkbook(source);
        } catch (Exception exception) {
            throw new IllegalStateException("Office 文件解析失败: " + fileName, exception);
        }
    }

    private ParsedDocument parseDocx(InputStream source) throws Exception {
        var blocks = new ArrayList<TextBlock>();
        try (var document = new XWPFDocument(source)) {
            var paragraphNo = 0;
            var tableNo = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    var text = paragraph.getText().strip();
                    var paragraphId = "p-" + paragraphNo++;
                    if (!text.isBlank()) {
                        blocks.add(new TextBlock(null, paragraphSection(paragraph), text, null, null,
                                paragraphId, List.of(), null, null, null));
                    }
                    for (var run : paragraph.getRuns()) {
                        for (var picture : run.getEmbeddedPictures()) {
                            var description = picture.getDescription();
                            blocks.add(new TextBlock(null, "image", description == null || description.isBlank()
                                    ? "内嵌图片" : description, null, null, paragraphId,
                                    List.of(), null, null, null));
                        }
                    }
                } else if (element instanceof XWPFTable table) {
                    var rowNo = 0;
                    for (var row : table.getRows()) {
                        var values = row.getTableCells().stream().map(cell -> cell.getText().strip()).toList();
                        if (values.stream().anyMatch(value -> !value.isBlank())) {
                            blocks.add(new TextBlock(null, "table-row", String.join(" | ", values), null, null,
                                    "table-" + tableNo + "-row-" + rowNo, List.of(), null, null, null));
                        }
                        rowNo++;
                    }
                    tableNo++;
                }
            }
        }
        return new ParsedDocument(blocks, "office-docx-v2");
    }

    private String paragraphSection(XWPFParagraph paragraph) {
        var style = paragraph.getStyle();
        if (style != null && style.toLowerCase(Locale.ROOT).startsWith("heading")) {
            var level = style.replaceAll("\\D", "");
            return "heading-" + (level.isBlank() ? "2" : level);
        }
        if (paragraph.getNumID() != null) return "list-item";
        return "paragraph";
    }

    private ParsedDocument parsePpt(InputStream source) throws Exception {
        var blocks = new ArrayList<TextBlock>();
        try (var show = new XMLSlideShow(source)) {
            var page = 1;
            for (var slide : show.getSlides()) {
                var text = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape
                            && !textShape.getText().isBlank()) text.append(textShape.getText()).append('\n');
                }
                if (!text.isEmpty()) blocks.add(new TextBlock(page, "paragraph", text.toString().strip()));
                page++;
            }
        }
        return new ParsedDocument(blocks, "office-ppt-text-v2");
    }

    private ParsedDocument parseWorkbook(InputStream source) throws Exception {
        var blocks = new ArrayList<TextBlock>();
        var sourceTables = new ArrayList<SourceTable>();
        var formatter = new DataFormatter();
        var workbookNonEmpty = 0;
        try (var workbook = WorkbookFactory.create(source)) {
            for (var sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                var rowCount = sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1;
                var columnCount = 0;
                var nonEmpty = 0;
                for (var row : sheet) {
                    columnCount = Math.max(columnCount, Math.max(0, row.getLastCellNum()));
                    for (var cell : row) if (!formatter.formatCellValue(cell).isBlank()) nonEmpty++;
                }
                workbookNonEmpty += nonEmpty;
                if (rowCount > SHEET_MAX_ROWS || columnCount > SHEET_MAX_COLUMNS
                        || workbookNonEmpty > WORKBOOK_MAX_NON_EMPTY) {
                    throw new IllegalStateException("工作簿超出知识库校对上限，请拆分文件或转到数据中心");
                }
                if (rowCount > INLINE_MAX_ROWS || columnCount > INLINE_MAX_COLUMNS || nonEmpty > INLINE_MAX_NON_EMPTY) {
                    var sourceBlockNo = blocks.size();
                    var sheetKey = "sheet-" + sheetIndex;
                    var attributes = new LinkedHashMap<String, Object>();
                    attributes.put("sheetKey", sheetKey);
                    attributes.put("sheetName", sheet.getSheetName());
                    attributes.put("rowCount", rowCount);
                    attributes.put("columnCount", columnCount);
                    attributes.put("nonEmptyCount", nonEmpty);
                    blocks.add(new TextBlock(null, "data-table-ref",
                            sheet.getSheetName() + "（" + rowCount + " 行 × " + columnCount + " 列）",
                            sheet.getSheetName(), null, null, List.of(), null, null, null, attributes));
                    var cells = new ArrayList<TableCell>(nonEmpty);
                    for (var row : sheet) {
                        for (var cell : row) {
                            var value = formatter.formatCellValue(cell);
                            if (value.isBlank()) continue;
                            cells.add(new TableCell(row.getRowNum(), cell.getColumnIndex(), value,
                                    new CellReference(row.getRowNum(), cell.getColumnIndex()).formatAsString()));
                        }
                    }
                    sourceTables.add(new SourceTable(sourceBlockNo, sheetKey, sheet.getSheetName(), rowCount,
                            columnCount, nonEmpty, cells));
                    continue;
                }
                for (var row : sheet) {
                    var values = new ArrayList<String>();
                    for (int column = 0; column < columnCount; column++) {
                        var cell = row.getCell(column);
                        values.add(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                    if (values.stream().allMatch(String::isBlank)) continue;
                    var rowNumber = row.getRowNum() + 1;
                    var lastColumn = Math.max(0, columnCount - 1);
                    var range = "A" + rowNumber + ":" + CellReference.convertNumToColString(lastColumn) + rowNumber;
                    var attributes = new LinkedHashMap<String, Object>();
                    attributes.put("sheetKey", "sheet-" + sheetIndex);
                    attributes.put("mergedRanges", sheet.getMergedRegions().stream().map(Object::toString).toList());
                    blocks.add(new TextBlock(null, "spreadsheet-row", String.join(" | ", values),
                            sheet.getSheetName(), range, null, List.of(), null, null, null, attributes));
                }
            }
        }
        return new ParsedDocument(blocks, "office-workbook-v2", null,
                Map.of("nonEmptyCellCount", workbookNonEmpty), sourceTables);
    }
}
