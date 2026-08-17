package com.jsd.aird.kb.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the bounded table subset emitted by Qwen OCR. It deliberately does not
 * expand macros, execute commands, resolve links, or load external resources.
 */
final class QwenTableParser {

    private static final Pattern TABLE_BEGIN = Pattern.compile(
            "\\\\begin\\s*\\{(tabular\\*?|tabularx|longtable)}", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TOKEN = Pattern.compile(
            "(?is)<\\s*(/?)\\s*(table|thead|tbody|tfoot|tr|th|td|br)\\b([^>]*)>|([^<]+)");
    private static final Pattern HTML_UNSAFE = Pattern.compile(
            "(?is)<!--.*?-->|<(script|style|iframe|object|svg)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern HTML_UNKNOWN_TAG = Pattern.compile(
            "(?is)<\\s*/?\\s*(?!(?:table|thead|tbody|tfoot|tr|th|td|br)\\b)[a-zA-Z][^>]*>");
    private static final Pattern SPAN_ATTRIBUTE = Pattern.compile(
            "(?i)\\b(rowspan|colspan)\\s*=\\s*(?:\"(\\d+)\"|'(\\d+)'|(\\d+))");
    private static final Pattern EXCEL_COLUMN = Pattern.compile("[A-Za-z]{1,3}");
    private static final Pattern ROW_NUMBER = Pattern.compile("\\d{1,7}");
    private static final int MIN_AXIS_LENGTH = 3;
    private static final int MAX_SPAN = 1_000;

    List<LatexSegment> splitLatex(String source) {
        var value = source == null ? "" : source;
        var result = new ArrayList<LatexSegment>();
        var matcher = TABLE_BEGIN.matcher(value);
        var cursor = 0;
        while (matcher.find(cursor)) {
            var environment = matcher.group(1);
            var endPattern = Pattern.compile("\\\\end\\s*\\{" + Pattern.quote(environment) + "}",
                    Pattern.CASE_INSENSITIVE);
            var end = endPattern.matcher(value);
            if (!end.find(matcher.end())) break;
            if (matcher.start() > cursor) result.add(LatexSegment.text(value.substring(cursor, matcher.start())));
            var bodyStart = skipPreamble(value, matcher.end(), environment);
            var body = value.substring(Math.min(bodyStart, end.start()), end.start());
            result.add(LatexSegment.table(parseLatexTable(body)));
            cursor = end.end();
        }
        if (cursor < value.length()) result.add(LatexSegment.text(value.substring(cursor)));
        if (result.isEmpty()) result.add(LatexSegment.text(value));
        return List.copyOf(result);
    }

    List<Table> parseHtml(String source) {
        var safe = stripFence(HTML_UNKNOWN_TAG.matcher(
                HTML_UNSAFE.matcher(source == null ? "" : source).replaceAll("")).replaceAll(""));
        var tables = new ArrayList<Table>();
        var rows = new ArrayList<Row>();
        List<Cell> currentRow = null;
        StringBuilder currentText = null;
        var currentRowSpan = 1;
        var currentColumnSpan = 1;
        var currentHeader = false;
        var tableDepth = 0;
        var matcher = HTML_TOKEN.matcher(safe);
        while (matcher.find()) {
            if (matcher.group(4) != null) {
                if (currentText != null && tableDepth == 1) appendText(currentText, decodeEntities(matcher.group(4)));
                continue;
            }
            var closing = !matcher.group(1).isEmpty();
            var tag = matcher.group(2).toLowerCase(Locale.ROOT);
            if ("table".equals(tag)) {
                if (!closing) {
                    tableDepth++;
                    if (tableDepth == 1) rows = new ArrayList<>();
                } else if (tableDepth == 1) {
                    currentRow = closeCell(currentRow, currentText, currentRowSpan, currentColumnSpan, currentHeader);
                    currentText = null;
                    if (currentRow != null && !currentRow.isEmpty()) rows.add(new Row(List.copyOf(currentRow)));
                    currentRow = null;
                    if (!rows.isEmpty()) tables.add(normalize(new Table(List.copyOf(rows))));
                    tableDepth--;
                } else if (tableDepth > 1) {
                    tableDepth--;
                }
                continue;
            }
            if (tableDepth != 1) continue;
            if ("tr".equals(tag)) {
                if (!closing) {
                    currentRow = new ArrayList<>();
                } else {
                    currentRow = closeCell(currentRow, currentText, currentRowSpan, currentColumnSpan, currentHeader);
                    currentText = null;
                    if (currentRow != null && !currentRow.isEmpty()) rows.add(new Row(List.copyOf(currentRow)));
                    currentRow = null;
                }
            } else if ("td".equals(tag) || "th".equals(tag)) {
                if (!closing) {
                    currentRow = closeCell(currentRow, currentText, currentRowSpan, currentColumnSpan, currentHeader);
                    currentText = new StringBuilder();
                    currentRowSpan = span(matcher.group(3), "rowspan");
                    currentColumnSpan = span(matcher.group(3), "colspan");
                    currentHeader = "th".equals(tag);
                } else {
                    currentRow = closeCell(currentRow, currentText, currentRowSpan, currentColumnSpan, currentHeader);
                    currentText = null;
                }
            } else if ("br".equals(tag) && !closing && currentText != null) {
                currentText.append('\n');
            }
        }
        return tables.stream().filter(Table::reliable).toList();
    }

    boolean isSpreadsheetColumnSequence(String source) {
        return sequentialExcelColumns(tokens(source));
    }

    boolean isSpreadsheetRowSequence(String source) {
        return sequentialIntegers(tokens(source));
    }

    /** Remove axis runs from OCR paragraphs only after table structure proved both axes exist. */
    String removeSpreadsheetAxisRuns(String source) {
        var lines = (source == null ? "" : source).split("\\R", -1);
        var kept = new ArrayList<String>();
        var index = 0;
        while (index < lines.length) {
            var lineTokens = tokens(lines[index]);
            if (lineTokens.size() >= MIN_AXIS_LENGTH
                    && (sequentialExcelColumns(lineTokens) || sequentialIntegers(lineTokens))) {
                index++;
                continue;
            }
            if (lineTokens.size() == 1 && isAxisToken(lineTokens.getFirst())) {
                var end = index + 1;
                var run = new ArrayList<String>(lineTokens);
                while (end < lines.length) {
                    var next = tokens(lines[end]);
                    if (next.size() != 1 || !isAxisToken(next.getFirst())) break;
                    run.add(next.getFirst());
                    end++;
                }
                if (run.size() >= MIN_AXIS_LENGTH
                        && (sequentialExcelColumns(run) || sequentialIntegers(run))) {
                    index = end;
                    continue;
                }
            }
            kept.add(lines[index]);
            index++;
        }
        return String.join("\n", kept).replaceAll("(?s)\\A\\s+|\\s+\\z", "").strip();
    }

    private Table parseLatexTable(String body) {
        var rows = new ArrayList<Row>();
        for (var rowText : splitTopLevelRows(body)) {
            var cells = new ArrayList<Cell>();
            for (var cellText : splitTopLevel(rowText, '&')) cells.add(parseLatexCell(cellText));
            if (cells.stream().anyMatch(cell -> !cell.text().isBlank())) rows.add(new Row(List.copyOf(cells)));
        }
        return normalize(new Table(List.copyOf(rows)));
    }

    private Cell parseLatexCell(String source) {
        var text = removeRules(source).strip();
        var columnSpan = 1;
        var rowSpan = 1;
        var multicolumn = command(text, "multicolumn", 3);
        if (multicolumn != null) {
            columnSpan = positiveInt(multicolumn.arguments().get(0));
            text = multicolumn.arguments().get(2) + multicolumn.remainder();
        }
        var multirow = command(text.strip(), "multirow", 3);
        if (multirow != null) {
            rowSpan = positiveInt(multirow.arguments().get(0));
            text = multirow.arguments().get(2) + multirow.remainder();
        }
        return new Cell(cleanLatexText(text), rowSpan, columnSpan, false);
    }

    private Table normalize(Table table) {
        var normalized = stripSpreadsheetAxes(table);
        return inferHeaders(normalized);
    }

    private Table stripSpreadsheetAxes(Table table) {
        if (table.rows().size() < MIN_AXIS_LENGTH + 1) return table;
        var first = table.rows().getFirst().cells();
        if (first.size() < MIN_AXIS_LENGTH) return table;
        var columnOffset = first.getFirst().text().isBlank() ? 1 : 0;
        var columnLabels = first.subList(columnOffset, first.size());
        if (columnLabels.size() < MIN_AXIS_LENGTH || columnLabels.stream().anyMatch(cell -> cell.columnSpan() != 1)
                || !sequentialExcelColumns(columnLabels.stream().map(Cell::text).toList())) return table;

        var body = table.rows().subList(1, table.rows().size());
        var rowLabels = new ArrayList<String>();
        for (var row : body) {
            if (row.cells().isEmpty() || row.cells().getFirst().columnSpan() != 1) return table;
            rowLabels.add(row.cells().getFirst().text());
        }
        if (!sequentialIntegers(rowLabels)) return table;

        var stripped = new ArrayList<Row>();
        for (var row : body) {
            var cells = new ArrayList<>(row.cells());
            cells.removeFirst();
            if (!cells.isEmpty()) stripped.add(new Row(List.copyOf(cells)));
        }
        return new Table(List.copyOf(stripped), true);
    }

    private Table inferHeaders(Table table) {
        var width = table.columnCount();
        var rows = new ArrayList<Row>();
        for (var row : table.rows()) {
            var cells = new ArrayList<Cell>();
            for (var index = 0; index < row.cells().size(); index++) {
                var cell = row.cells().get(index);
                var fullWidthTitle = row.cells().size() == 1 && cell.columnSpan() >= Math.max(2, width);
                var rowLabel = index == 0 && row.cells().size() > 1 && !cell.text().isBlank()
                        && !cell.text().matches("[-+]?\\d+(?:[.,]\\d+)?");
                cells.add(new Cell(cell.text(), cell.rowSpan(), cell.columnSpan(),
                        cell.header() || fullWidthTitle || rowLabel));
            }
            rows.add(new Row(List.copyOf(cells)));
        }
        return new Table(List.copyOf(rows), table.spreadsheetAxesRemoved());
    }

    private int skipPreamble(String source, int offset, String environment) {
        var argumentCount = environment.toLowerCase(Locale.ROOT).startsWith("tabularx")
                || environment.toLowerCase(Locale.ROOT).startsWith("tabular*") ? 2 : 1;
        var cursor = offset;
        for (var index = 0; index < argumentCount; index++) {
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
            if (cursor >= source.length() || source.charAt(cursor) != '{') return cursor;
            cursor = balancedEnd(source, cursor);
        }
        return cursor;
    }

    private List<String> splitTopLevelRows(String source) {
        var rows = new ArrayList<String>();
        var current = new StringBuilder();
        var depth = 0;
        for (var index = 0; index < source.length(); index++) {
            var character = source.charAt(index);
            if (character == '{') depth++;
            else if (character == '}' && depth > 0) depth--;
            if (depth == 0 && character == '\\' && index + 1 < source.length()
                    && source.charAt(index + 1) == '\\') {
                rows.add(current.toString());
                current.setLength(0);
                index++;
            } else if (depth == 0 && source.startsWith("\\tabularnewline", index)) {
                rows.add(current.toString());
                current.setLength(0);
                index += "\\tabularnewline".length() - 1;
            } else {
                current.append(character);
            }
        }
        if (!current.isEmpty()) rows.add(current.toString());
        return rows;
    }

    private List<String> splitTopLevel(String source, char delimiter) {
        var values = new ArrayList<String>();
        var current = new StringBuilder();
        var depth = 0;
        for (var index = 0; index < source.length(); index++) {
            var character = source.charAt(index);
            if (character == '{') depth++;
            else if (character == '}' && depth > 0) depth--;
            if (character == delimiter && depth == 0 && (index == 0 || source.charAt(index - 1) != '\\')) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        values.add(current.toString());
        return values;
    }

    private Command command(String source, String name, int count) {
        var cursor = 0;
        var prefix = "\\" + name;
        if (!source.startsWith(prefix)) return null;
        cursor = prefix.length();
        if (cursor < source.length() && source.charAt(cursor) == '[') cursor = bracketEnd(source, cursor, '[', ']');
        var arguments = new ArrayList<String>();
        for (var index = 0; index < count; index++) {
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
            if (cursor >= source.length() || source.charAt(cursor) != '{') return null;
            var end = balancedEnd(source, cursor);
            arguments.add(source.substring(cursor + 1, Math.max(cursor + 1, end - 1)));
            cursor = end;
        }
        return new Command(List.copyOf(arguments), source.substring(cursor));
    }

    private int balancedEnd(String source, int start) {
        return bracketEnd(source, start, '{', '}');
    }

    private int bracketEnd(String source, int start, char open, char close) {
        var depth = 0;
        for (var index = start; index < source.length(); index++) {
            if (source.charAt(index) == open) depth++;
            else if (source.charAt(index) == close && --depth == 0) return index + 1;
        }
        return source.length();
    }

    private String removeRules(String source) {
        return source.replace("\\hline", "")
                .replaceAll("\\\\(?:cline|cmidrule)\\s*\\{[^}]*}", "")
                .replaceAll("\\\\(?:toprule|midrule|bottomrule|endhead|endfirsthead|endfoot|endlastfoot)\\b", "");
    }

    private String cleanLatexText(String source) {
        var value = source == null ? "" : source;
        for (var index = 0; index < 6; index++) {
            value = value.replaceAll("\\\\(?:textbf|textit|emph|underline|mathrm|mathbf|operatorname|makecell)\\s*\\{([^{}]*)}", "$1");
        }
        return value.replace("\\&", "&").replace("\\%", "%").replace("\\_", "_")
                .replaceAll("\\\\[a-zA-Z]+\\*?", "")
                .replace("{", "").replace("}", "").replace("~", " ")
                .replaceAll("[ \\t\\r\\n]+", " ").strip();
    }

    private List<Cell> closeCell(List<Cell> row, StringBuilder text, int rowSpan, int columnSpan, boolean header) {
        if (text != null) {
            if (row == null) row = new ArrayList<>();
            row.add(new Cell(text.toString().replaceAll("[ \\t\\r\\n]+", " ").strip(),
                    bounded(rowSpan), bounded(columnSpan), header));
        }
        return row;
    }

    private void appendText(StringBuilder target, String text) {
        var value = text.replaceAll("[ \\t\\r\\n]+", " ");
        if (!target.isEmpty() && !value.isBlank()) target.append(' ');
        target.append(value);
    }

    private int span(String attributes, String name) {
        var matcher = SPAN_ATTRIBUTE.matcher(attributes == null ? "" : attributes);
        while (matcher.find()) {
            if (!name.equalsIgnoreCase(matcher.group(1))) continue;
            for (var index = 2; index <= 4; index++) if (matcher.group(index) != null) return positiveInt(matcher.group(index));
        }
        return 1;
    }

    private int positiveInt(String value) {
        try { return bounded(Integer.parseInt(value.strip())); }
        catch (RuntimeException ignored) { return 1; }
    }

    private int bounded(int value) {
        return Math.max(1, Math.min(MAX_SPAN, value));
    }

    private boolean sequentialExcelColumns(List<String> values) {
        if (values.size() < MIN_AXIS_LENGTH) return false;
        var previous = excelColumnIndex(values.getFirst());
        if (previous < 0) return false;
        for (var index = 1; index < values.size(); index++) {
            var current = excelColumnIndex(values.get(index));
            if (current != previous + 1) return false;
            previous = current;
        }
        return true;
    }

    private boolean sequentialIntegers(List<String> values) {
        if (values.size() < MIN_AXIS_LENGTH) return false;
        var previous = integer(values.getFirst());
        if (previous < 0) return false;
        for (var index = 1; index < values.size(); index++) {
            var current = integer(values.get(index));
            if (current != previous + 1) return false;
            previous = current;
        }
        return true;
    }

    private List<String> tokens(String source) {
        if (source == null || source.isBlank()) return List.of();
        return List.of(source.strip().split("\\s+"));
    }

    private boolean isAxisToken(String value) {
        return excelColumnIndex(value) >= 0 || integer(value) >= 0;
    }

    private int excelColumnIndex(String value) {
        var text = value == null ? "" : value.strip();
        if (!EXCEL_COLUMN.matcher(text).matches()) return -1;
        var result = 0;
        for (var character : text.toUpperCase(Locale.ROOT).toCharArray()) result = result * 26 + character - 'A' + 1;
        return result - 1;
    }

    private int integer(String value) {
        var text = value == null ? "" : value.strip();
        if (!ROW_NUMBER.matcher(text).matches()) return -1;
        try { return Integer.parseInt(text); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private String decodeEntities(String source) {
        var value = source.replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        var numeric = Pattern.compile("&#(x?[0-9A-Fa-f]+);").matcher(value);
        var result = new StringBuffer();
        while (numeric.find()) {
            try {
                var token = numeric.group(1);
                var codePoint = Integer.parseInt(token.startsWith("x") || token.startsWith("X")
                        ? token.substring(1) : token, token.startsWith("x") || token.startsWith("X") ? 16 : 10);
                numeric.appendReplacement(result, Matcher.quoteReplacement(Character.toString(codePoint)));
            } catch (RuntimeException ignored) {
                numeric.appendReplacement(result, Matcher.quoteReplacement(numeric.group()));
            }
        }
        numeric.appendTail(result);
        return result.toString();
    }

    private String stripFence(String source) {
        return source.replaceFirst("(?is)^\\s*```(?:html)?\\s*", "")
                .replaceFirst("(?is)\\s*```\\s*$", "");
    }

    record LatexSegment(String text, Table table) {
        static LatexSegment text(String value) { return new LatexSegment(value, null); }
        static LatexSegment table(Table value) { return new LatexSegment(null, value); }
        boolean isTable() { return table != null; }
    }

    record Table(List<Row> rows, boolean spreadsheetAxesRemoved) {
        Table(List<Row> rows) { this(rows, false); }
        Table {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
        int columnCount() {
            return rows.stream().mapToInt(row -> row.cells().stream().mapToInt(Cell::columnSpan).sum()).max().orElse(0);
        }
        int nonEmptyCount() {
            return rows.stream().mapToInt(row -> (int) row.cells().stream().filter(cell -> !cell.text().isBlank()).count()).sum();
        }
        boolean reliable() {
            return rows.size() >= 2 && columnCount() >= 2 && nonEmptyCount() >= 3;
        }
    }

    record Row(List<Cell> cells) {
        Row { cells = cells == null ? List.of() : List.copyOf(cells); }
    }

    record Cell(String text, int rowSpan, int columnSpan, boolean header) {
        Cell { text = text == null ? "" : text.strip(); }
    }

    private record Command(List<String> arguments, String remainder) { }
}
