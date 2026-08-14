package com.jsd.aird.kb.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.jsd.aird.kb.domain.DocumentParser;
import org.springframework.stereotype.Component;

/** Converts Qwen document_parsing LaTeX into the neutral source-node vocabulary. */
@Component
public class QwenDocumentParsingConverter {

    private static final Pattern HEADING = Pattern.compile(
            "^\\\\(section|subsection|subsubsection)\\*?\\{(.*)}\\s*$");
    private static final Pattern UNSAFE = Pattern.compile(
            "\\\\(?:input|include|includegraphics|write|openout|read|catcode|csname|usepackage|documentclass)\\b[^\\n]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_FORMAT = Pattern.compile(
            "\\\\(?:textbf|textit|emph|underline|mathrm|mathbf|operatorname)\\{([^{}]*)}");
    private static final Pattern UNKNOWN_COMMAND = Pattern.compile("\\\\([a-zA-Z]+)\\*?");

    public List<DocumentParser.TextBlock> convert(String latex, Integer pageNo) {
        var safe = sanitize(latex);
        var result = new ArrayList<DocumentParser.TextBlock>();
        var paragraph = new StringBuilder();
        var table = new StringBuilder();
        var inTable = false;
        String listType = null;
        var inFormula = false;
        var formula = new StringBuilder();
        for (var original : safe.split("\\R", -1)) {
            var line = original.strip();
            if (line.startsWith("\\begin{tabular")) {
                flushParagraph(result, paragraph, pageNo);
                inTable = true;
                table.setLength(0);
                continue;
            }
            if (inTable) {
                if (line.startsWith("\\end{tabular")) {
                    flushTable(result, table, pageNo);
                    inTable = false;
                } else {
                    if (!table.isEmpty()) table.append('\n');
                    table.append(line);
                }
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
                result.add(block(pageNo, "heading-" + level, cleanText(heading.group(2)),
                        java.util.Map.of("level", level)));
                continue;
            }
            if (line.startsWith("\\item")) {
                flushParagraph(result, paragraph, pageNo);
                var value = cleanText(line.substring(5));
                if (!value.isBlank()) result.add(block(pageNo, "list-item", value,
                        java.util.Map.of("ordered", "ordered".equals(listType))));
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(result, paragraph, pageNo);
            } else if (listType != null) {
                result.add(block(pageNo, "list-item", cleanText(line),
                        java.util.Map.of("ordered", "ordered".equals(listType))));
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        if (inTable) flushTable(result, table, pageNo);
        if (inFormula && !formula.isEmpty()) result.add(new DocumentParser.TextBlock(pageNo, "formula", cleanText(formula.toString())));
        flushParagraph(result, paragraph, pageNo);
        return List.copyOf(result);
    }

    private String sanitize(String value) {
        if (value == null) return "";
        return UNSAFE.matcher(value.replaceFirst("^```(?:latex|tex)?\\s*", "")
                .replaceFirst("\\s*```$", "")).replaceAll("");
    }

    private void flushParagraph(List<DocumentParser.TextBlock> result, StringBuilder value, Integer pageNo) {
        var text = cleanText(value.toString());
        if (!text.isBlank()) result.add(new DocumentParser.TextBlock(pageNo, "paragraph", text));
        value.setLength(0);
    }

    private void flushTable(List<DocumentParser.TextBlock> result, StringBuilder value, Integer pageNo) {
        var source = value.toString().replace("\\hline", "").replaceAll("\\\\cline\\{[^}]*}", "");
        for (var row : source.split("\\\\\\\\")) {
            var cells = new ArrayList<String>();
            for (var cell : row.split("&")) cells.add(cleanText(cell));
            var text = String.join(" | ", cells).strip();
            if (!text.isBlank()) result.add(new DocumentParser.TextBlock(pageNo, "table-row", text));
        }
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
                                            java.util.Map<String, Object> attributes) {
        return new DocumentParser.TextBlock(pageNo, section, content, null, null, null, List.of(),
                null, null, null, attributes);
    }
}
