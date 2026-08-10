package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

import com.jsd.aird.kb.domain.DocumentParser;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

@Component
public class OfficeDocumentParser implements DocumentParser {

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
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                var text = paragraph.getText().strip();
                if (!text.isBlank()) blocks.add(new TextBlock(null, null, text));
            }
            document.getTables().forEach(table -> table.getRows().forEach(row -> {
                var text = row.getTableCells().stream().map(cell -> cell.getText().strip())
                        .filter(value -> !value.isBlank()).reduce((a, b) -> a + " | " + b).orElse("");
                if (!text.isBlank()) blocks.add(new TextBlock(null, "table", text));
            }));
        }
        return new ParsedDocument(blocks, "office-v1");
    }

    private ParsedDocument parsePpt(InputStream source) throws Exception {
        var blocks = new ArrayList<TextBlock>();
        try (var show = new XMLSlideShow(source)) {
            var page = 1;
            for (var slide : show.getSlides()) {
                var text = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape) {
                        if (!textShape.getText().isBlank()) text.append(textShape.getText()).append('\n');
                    }
                }
                if (!text.isEmpty()) blocks.add(new TextBlock(page, "slide", text.toString().strip()));
                page++;
            }
        }
        return new ParsedDocument(blocks, "office-v1");
    }

    private ParsedDocument parseWorkbook(InputStream source) throws Exception {
        var blocks = new ArrayList<TextBlock>();
        var formatter = new DataFormatter();
        try (var workbook = WorkbookFactory.create(source)) {
            for (var sheet : workbook) {
                var rowNo = 0;
                for (var row : sheet) {
                    var values = new ArrayList<String>();
                    row.forEach(cell -> values.add(formatter.formatCellValue(cell)));
                    var text = String.join(" | ", values).strip();
                    if (!text.isBlank()) blocks.add(new TextBlock(rowNo + 1, sheet.getSheetName(), text));
                    rowNo++;
                }
            }
        }
        return new ParsedDocument(blocks, "office-v1");
    }
}
