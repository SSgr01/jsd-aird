package com.jsd.aird.kb.infrastructure;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.jsd.aird.kb.domain.DocumentParser;
import org.springframework.stereotype.Component;

/** Deterministic text/Markdown reader. These formats never enter the OCR route. */
@Component
public class PlainTextDocumentParser implements DocumentParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-+*]\\s+(.+)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.+)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".md")
                || name.endsWith(".markdown") || type.startsWith("text/");
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        try (var reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            var lines = reader.lines().toList();
            var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            if (name.endsWith(".csv")) return new ParsedDocument(parseCsv(lines), "text-csv-v2");
            if (name.endsWith(".md") || name.endsWith(".markdown")) {
                return new ParsedDocument(parseMarkdown(lines), "text-markdown-gfm-v2");
            }
            return new ParsedDocument(parseText(lines), "text-plain-v2");
        } catch (Exception exception) {
            throw new IllegalStateException("文本文件解析失败", exception);
        }
    }

    private List<TextBlock> parseText(List<String> lines) {
        var blocks = new ArrayList<TextBlock>();
        var paragraph = new StringBuilder();
        for (var line : lines) {
            if (line.isBlank()) flushParagraph(blocks, paragraph);
            else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flushParagraph(blocks, paragraph);
        return blocks;
    }

    private List<TextBlock> parseMarkdown(List<String> lines) {
        var blocks = new ArrayList<TextBlock>();
        var paragraph = new StringBuilder();
        var index = 0;
        var tableNo = 0;
        while (index < lines.size()) {
            var line = lines.get(index);
            if (line.stripLeading().startsWith("```")) {
                flushParagraph(blocks, paragraph);
                var fence = line.stripLeading();
                var language = fence.length() > 3 ? fence.substring(3).strip() : "";
                var code = new StringBuilder();
                index++;
                while (index < lines.size() && !lines.get(index).stripLeading().startsWith("```")) {
                    if (!code.isEmpty()) code.append('\n');
                    code.append(lines.get(index++));
                }
                blocks.add(block("code-block", code.toString(), language.isBlank() ? Map.of() : Map.of("language", language)));
                index++;
                continue;
            }
            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(blocks, paragraph);
                blocks.add(block("heading-" + heading.group(1).length(), heading.group(2),
                        Map.of("level", heading.group(1).length())));
                index++;
                continue;
            }
            if (index + 1 < lines.size() && line.contains("|") && TABLE_SEPARATOR.matcher(lines.get(index + 1)).matches()) {
                flushParagraph(blocks, paragraph);
                var section = "markdown-table-" + tableNo++;
                blocks.add(block(section, String.join(" | ", splitTableRow(line)), Map.of("header", true)));
                index += 2;
                while (index < lines.size() && !lines.get(index).isBlank() && lines.get(index).contains("|")) {
                    blocks.add(block(section, String.join(" | ", splitTableRow(lines.get(index))), Map.of()));
                    index++;
                }
                continue;
            }
            var bullet = BULLET.matcher(line);
            var ordered = ORDERED.matcher(line);
            if (bullet.matches() || ordered.matches()) {
                flushParagraph(blocks, paragraph);
                var value = bullet.matches() ? bullet.group(1) : ordered.group(1);
                blocks.add(block("list-item", value, Map.of("ordered", ordered.matches())));
                index++;
                continue;
            }
            if (line.isBlank()) flushParagraph(blocks, paragraph);
            else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
            index++;
        }
        flushParagraph(blocks, paragraph);
        return blocks;
    }

    private List<TextBlock> parseCsv(List<String> lines) {
        var blocks = new ArrayList<TextBlock>();
        for (var line : lines) {
            if (!line.isBlank()) blocks.add(block("csv-table", String.join(" | ", splitCsvRow(line)), Map.of()));
        }
        return blocks;
    }

    private List<String> splitTableRow(String line) {
        var value = line.strip();
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        var cells = new ArrayList<String>();
        var cell = new StringBuilder();
        var escaped = false;
        for (var character : value.toCharArray()) {
            if (escaped) { cell.append(character); escaped = false; }
            else if (character == '\\') escaped = true;
            else if (character == '|') { cells.add(cell.toString().strip()); cell.setLength(0); }
            else cell.append(character);
        }
        cells.add(cell.toString().strip());
        return cells;
    }

    private List<String> splitCsvRow(String line) {
        var cells = new ArrayList<String>();
        var cell = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < line.length(); index++) {
            var character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') { cell.append('"'); index++; }
                else quoted = !quoted;
            } else if (character == ',' && !quoted) { cells.add(cell.toString()); cell.setLength(0); }
            else cell.append(character);
        }
        cells.add(cell.toString());
        return cells;
    }

    private void flushParagraph(List<TextBlock> blocks, StringBuilder paragraph) {
        if (paragraph.isEmpty()) return;
        blocks.add(block("paragraph", paragraph.toString().strip(), Map.of()));
        paragraph.setLength(0);
    }

    private TextBlock block(String section, String content, Map<String, Object> attributes) {
        return new TextBlock(null, section, content, null, null, null, List.of(), null, null, null,
                new LinkedHashMap<>(attributes));
    }
}
