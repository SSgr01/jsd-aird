package com.jsd.aird.kb.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.jsd.aird.kb.domain.DocumentParser;
import org.springframework.stereotype.Component;

/** Converts Qwen OCR output into the neutral source-node vocabulary. */
@Component
public class QwenDocumentParsingConverter {

    private static final Pattern HEADING = Pattern.compile(
            "^\\\\(section|subsection|subsubsection)\\*?\\{(.*)}\\s*$");
    private static final Pattern CAPTION = Pattern.compile("^\\\\caption\\*?\\{(.*)}\\s*$");
    private static final Pattern LAYOUT_COMMAND = Pattern.compile(
            "^\\\\(?:begin|end)\\s*\\{(?:document|table\\*?|center|minipage)}.*$|^\\\\(?:centering|small|footnotesize|scriptsize)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE = Pattern.compile(
            "\\\\(?:input|include|includegraphics|write|openout|read|catcode|csname|usepackage|documentclass)\\b[^\\n]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_FORMAT = Pattern.compile(
            "\\\\(?:textbf|textit|emph|underline|mathrm|mathbf|operatorname)\\{([^{}]*)}");
    private static final Pattern UNKNOWN_COMMAND = Pattern.compile("\\\\([a-zA-Z]+)\\*?");

    private final QwenTableParser tableParser = new QwenTableParser();

    public List<DocumentParser.TextBlock> convert(String latex, Integer pageNo) {
        return convertDetailed(latex, pageNo).blocks();
    }

    public ConversionResult convertDetailed(String latex, Integer pageNo) {
        var safe = sanitize(latex);
        var result = new ArrayList<DocumentParser.TextBlock>();
        var reliableTable = false;
        var tableNo = 0;
        for (var segment : tableParser.splitLatex(safe)) {
            if (segment.isTable()) {
                appendTable(result, segment.table(), pageNo, tableNo++);
                reliableTable |= segment.table().reliable();
            } else {
                appendText(result, segment.text(), pageNo);
            }
        }
        return new ConversionResult(List.copyOf(result), reliableTable,
                !reliableTable && tableParser.looksLikeTable(safe));
    }

    public ConversionResult convertTableHtml(String html, Integer pageNo) {
        var result = new ArrayList<DocumentParser.TextBlock>();
        var tableNo = 0;
        for (var table : tableParser.parseHtml(html)) appendTable(result, table, pageNo, tableNo++);
        return new ConversionResult(List.copyOf(result), !result.isEmpty(), true);
    }

    private void appendText(List<DocumentParser.TextBlock> result, String source, Integer pageNo) {
        var paragraph = new StringBuilder();
        String listType = null;
        var inFormula = false;
        var formula = new StringBuilder();
        for (var original : source.split("\\R", -1)) {
            var line = original.strip();
            if (LAYOUT_COMMAND.matcher(line).matches()) {
                flushParagraph(result, paragraph, pageNo);
                continue;
            }
            if (line.equals("\\[") || line.equals("$$")) {
                flushParagraph(result, paragraph, pageNo);
                inFormula = true;
                formula.setLength(0);
                continue;
            }
            if (inFormula) {
                if (line.equals("\\]") || line.equals("$$")) {
                    var value = cleanText(formula.toString());
                    if (!value.isBlank()) result.add(new DocumentParser.TextBlock(pageNo, "formula", value));
                    inFormula = false;
                } else {
                    if (!formula.isEmpty()) formula.append('\n');
                    formula.append(line);
                }
                continue;
            }
            if (line.startsWith("\\begin{itemize") || line.startsWith("\\begin{enumerate")) {
                flushParagraph(result, paragraph, pageNo);
                listType = line.startsWith("\\begin{enumerate") ? "ordered" : "bullet";
                continue;
            }
            if (line.startsWith("\\end{itemize") || line.startsWith("\\end{enumerate")) {
                flushParagraph(result, paragraph, pageNo);
                listType = null;
                continue;
            }
            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(result, paragraph, pageNo);
                var level = switch (heading.group(1)) {
                    case "section" -> 1;
                    case "subsection" -> 2;
                    default -> 3;
                };
                result.add(block(pageNo, "heading-" + level, cleanText(heading.group(2)), Map.of("level", level)));
                continue;
            }
            var caption = CAPTION.matcher(line);
            if (caption.matches()) {
                flushParagraph(result, paragraph, pageNo);
                result.add(block(pageNo, "heading-3", cleanText(caption.group(1)), Map.of("level", 3)));
                continue;
            }
            if (line.startsWith("\\item")) {
                flushParagraph(result, paragraph, pageNo);
                var value = cleanText(line.substring(5));
                if (!value.isBlank()) result.add(block(pageNo, "list-item", value,
                        Map.of("ordered", "ordered".equals(listType))));
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(result, paragraph, pageNo);
            } else if (listType != null) {
                result.add(block(pageNo, "list-item", cleanText(line),
                        Map.of("ordered", "ordered".equals(listType))));
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        if (inFormula && !formula.isEmpty()) {
            result.add(new DocumentParser.TextBlock(pageNo, "formula", cleanText(formula.toString())));
        }
        flushParagraph(result, paragraph, pageNo);
    }

    private void appendTable(List<DocumentParser.TextBlock> result, QwenTableParser.Table table,
                             Integer pageNo, int tableNo) {
        var group = "ocr-page-" + (pageNo == null ? "image" : pageNo) + "-table-" + tableNo;
        var rowNo = 0;
        for (var row : table.rows()) {
            var attributes = new LinkedHashMap<String, Object>();
            attributes.put("tableGroup", group);
            attributes.put("tableRowNo", rowNo++);
            attributes.put("tableColumnCount", table.columnCount());
            attributes.put("spreadsheetAxesRemoved", table.spreadsheetAxesRemoved());
            var cells = new ArrayList<Map<String, Object>>();
            for (var cell : row.cells()) {
                var value = new LinkedHashMap<String, Object>();
                value.put("text", cell.text());
                value.put("rowSpan", cell.rowSpan());
                value.put("columnSpan", cell.columnSpan());
                value.put("header", cell.header());
                cells.add(Map.copyOf(value));
            }
            attributes.put("cells", List.copyOf(cells));
            var text = row.cells().stream().map(QwenTableParser.Cell::text).reduce((left, right) -> left + " | " + right).orElse("");
            result.add(block(pageNo, "table-row", text, Map.copyOf(attributes)));
        }
    }

    private String sanitize(String value) {
        if (value == null) return "";
        return UNSAFE.matcher(value.replaceFirst("(?is)^\\s*```(?:latex|tex)?\\s*", "")
                .replaceFirst("(?is)\\s*```\\s*$", "")).replaceAll("");
    }

    private void flushParagraph(List<DocumentParser.TextBlock> result, StringBuilder value, Integer pageNo) {
        var text = cleanText(value.toString());
        if (!text.isBlank()) result.add(new DocumentParser.TextBlock(pageNo, "paragraph", text));
        value.setLength(0);
    }

    private String cleanText(String value) {
        var result = value == null ? "" : value;
        for (int index = 0; index < 4; index++) result = SIMPLE_FORMAT.matcher(result).replaceAll("$1");
        return UNKNOWN_COMMAND.matcher(result).replaceAll("$1")
                .replace("{", "").replace("}", "")
                .replace("~", " ").replaceAll("[ \\t]+", " ").strip();
    }

    private DocumentParser.TextBlock block(Integer pageNo, String section, String content,
                                            Map<String, Object> attributes) {
        return new DocumentParser.TextBlock(pageNo, section, content, null, null, null, List.of(),
                null, null, null, attributes);
    }

    public record ConversionResult(List<DocumentParser.TextBlock> blocks, boolean reliableTable,
                                   boolean tableCandidate) {
        public ConversionResult {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }
}
