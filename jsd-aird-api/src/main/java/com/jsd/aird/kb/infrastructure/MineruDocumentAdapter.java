package com.jsd.aird.kb.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.domain.DocumentParser;

/** Converts MinerU's provider payload into the JSD neutral document contract. */
final class MineruDocumentAdapter {

    private static final String PROVIDER = "MINERU";
    private static final String PROVIDER_COORDINATE_SPACE = "MINERU_PAGE_PIXELS";
    private static final String JSD_COORDINATE_SPACE = "JSD_NORMALIZED";

    private final ObjectMapper mapper;
    private final QwenTableParser tableParser = new QwenTableParser();

    MineruDocumentAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Parsed parsePrecise(byte[] zipBytes, String fileName) {
        var files = unzip(zipBytes);
        var content = firstJson(files, "_content_list.json");
        var layout = firstJson(files, "layout.json");
        var pages = pages(layout);
        var blocks = new ArrayList<DocumentParser.TextBlock>();
        var tableIndex = 0;
        if (content != null && content.isArray()) {
            for (var item : content) {
                var type = item.path("type").asText("text").toLowerCase(Locale.ROOT);
                var pageNo = item.has("page_idx") ? item.path("page_idx").asInt() + 1 : null;
                var page = page(pageNo, pages);
                var bbox = normalizedPolygon(item.path("bbox"), page);
                if ("table".equals(type)) {
                    tableIndex = appendTable(blocks, item, pageNo, page, bbox, tableIndex);
                    continue;
                }
                if ("image".equals(type) || "image_body".equals(type)) {
                    var attributes = attributes(pageNo, page, bbox);
                    var imagePath = item.path("img_path").asText(null);
                    if (imagePath != null) attributes.put("imagePath", imagePath);
                    blocks.add(new DocumentParser.TextBlock(pageNo, "image", "图片", null, null, null,
                            bbox, null, null, null, attributes));
                    continue;
                }
                var text = normalizeText(item.path("text").asText(""));
                if (text.isBlank()) continue;
                var level = item.path("text_level").asInt(0);
                var section = level > 0 ? "heading-" + Math.min(6, level) : "paragraph";
                if (text.contains("$") && (text.startsWith("$") || text.contains("\\mathrm"))) section = "formula";
                var attributes = attributes(pageNo, page, bbox);
                if (level > 0) attributes.put("level", Math.min(6, level));
                attributes.put("mineruType", type);
                blocks.add(new DocumentParser.TextBlock(pageNo, section, text, null, null, null,
                        bbox, null, null, null, attributes));
            }
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("provider", PROVIDER);
        metadata.put("coordinateSpace", JSD_COORDINATE_SPACE);
        metadata.put("providerCoordinateSpace", PROVIDER_COORDINATE_SPACE);
        metadata.put("pageCount", pages.size());
        metadata.put("pages", pages.stream().map(Page::metadata).toList());
        metadata.put("resultFiles", files.keySet().stream().sorted().toList());
        return new Parsed(List.copyOf(blocks), metadata);
    }

    Parsed parseAgent(String markdown, String fileName) {
        var blocks = new ArrayList<DocumentParser.TextBlock>();
        var lines = markdown == null ? List.<String>of()
                : java.util.Arrays.asList(markdown.replace("\r\n", "\n").split("\n", -1));
        var paragraph = new StringBuilder();
        var table = new StringBuilder();
        var tableIndex = 0;
        for (var raw : lines) {
            var line = raw.strip();
            if (line.startsWith("<table")) {
                flushParagraph(blocks, paragraph);
                table.setLength(0);
                table.append(line);
                if (line.contains("</table>")) {
                    tableIndex = appendMarkdownTable(blocks, table.toString(), tableIndex);
                    table.setLength(0);
                }
                continue;
            }
            if (!table.isEmpty()) {
                table.append(line);
                if (line.contains("</table>")) {
                    tableIndex = appendMarkdownTable(blocks, table.toString(), tableIndex);
                    table.setLength(0);
                }
                continue;
            }
            if (line.startsWith("#")) {
                flushParagraph(blocks, paragraph);
                var hashes = line.indexOf(' ');
                var level = hashes <= 0 ? 1 : Math.min(6, hashes);
                var text = normalizeText(hashes <= 0 ? line.replaceFirst("^#+", "") : line.substring(hashes + 1));
                if (!text.isBlank()) {
                    blocks.add(new DocumentParser.TextBlock(null, "heading-" + level, text,
                            null, null, null, List.of(), null, null, null,
                            Map.of("sourceProvider", PROVIDER, "coordinateSpace", "NONE", "level", level,
                                    "agentFallback", true)));
                }
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(blocks, paragraph);
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(normalizeText(line));
            }
        }
        flushParagraph(blocks, paragraph);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("provider", PROVIDER);
        metadata.put("coordinateSpace", "NONE");
        metadata.put("agentFallback", true);
        metadata.put("fileName", fileName);
        return new Parsed(List.copyOf(blocks), metadata);
    }

    private void flushParagraph(List<DocumentParser.TextBlock> blocks, StringBuilder paragraph) {
        var text = normalizeText(paragraph.toString());
        if (!text.isBlank()) {
            var section = text.contains("$") ? "formula" : "paragraph";
            blocks.add(new DocumentParser.TextBlock(null, section, text, null, null, null, List.of(), null,
                    null, null, Map.of("sourceProvider", PROVIDER, "coordinateSpace", "NONE")));
        }
        paragraph.setLength(0);
    }

    private int appendMarkdownTable(List<DocumentParser.TextBlock> blocks, String html, int tableIndex) {
        var parsed = tableParser.parseHtml(html);
        if (parsed.isEmpty()) return tableIndex;
        for (var table : parsed) {
            var group = "mineru-agent-table-" + tableIndex;
            for (var rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
                var row = table.rows().get(rowIndex);
                var cells = cells(row, rowIndex == 0);
                var attrs = new LinkedHashMap<String, Object>();
                attrs.put("sourceProvider", PROVIDER);
                attrs.put("coordinateSpace", "NONE");
                attrs.put("tableGroup", group);
                attrs.put("tableRowIndex", rowIndex);
                attrs.put("cells", cells);
                blocks.add(new DocumentParser.TextBlock(null, "mineru-table-row", cells.stream()
                        .map(value -> String.valueOf(value.get("text"))).reduce((a, b) -> a + " | " + b).orElse(""),
                        null, null, null, List.of(), null, null, null, attrs));
            }
            tableIndex++;
        }
        return tableIndex;
    }

    private int appendTable(List<DocumentParser.TextBlock> blocks, JsonNode item, Integer pageNo, Page page,
                            List<Double> bbox, int tableIndex) {
        var parsed = tableParser.parseHtml(item.path("table_body").asText(""));
        if (parsed.isEmpty()) return tableIndex;
        var group = "mineru-table-" + tableIndex;
        var tableCount = parsed.stream().mapToInt(table -> table.rows().size()).sum();
        var rowIndex = 0;
        for (var table : parsed) {
            for (var row : table.rows()) {
                var cells = cells(row, rowIndex == 0);
                var rowBox = rowPolygon(bbox, rowIndex, tableCount);
                var attrs = attributes(pageNo, page, rowBox);
                attrs.put("tableGroup", group);
                attrs.put("tableRowIndex", rowIndex++);
                attrs.put("tableRowCount", tableCount);
                attrs.put("cells", cells);
                attrs.put("mineruType", "table");
                blocks.add(new DocumentParser.TextBlock(pageNo, "mineru-table-row", cells.stream()
                        .map(value -> String.valueOf(value.get("text"))).reduce((a, b) -> a + " | " + b).orElse(""),
                        null, null, null, rowBox, null, null, null, attrs));
            }
        }
        var footnoteIndex = 0;
        for (var footnote : item.path("table_footnote")) {
            var text = normalizeText(footnote.asText(""));
            if (text.isBlank()) continue;
            var section = text.contains("$") ? "formula" : "paragraph";
            var attrs = attributes(pageNo, page, bbox);
            attrs.put("tableGroup", group);
            attrs.put("formulaBoundToTable", true);
            attrs.put("tableFootnoteIndex", footnoteIndex++);
            blocks.add(new DocumentParser.TextBlock(pageNo, section, text, null, null, null,
                    bbox, null, null, null, attrs));
        }
        return tableIndex + 1;
    }

    private List<Map<String, Object>> cells(QwenTableParser.Row row, boolean forceHeader) {
        var result = new ArrayList<Map<String, Object>>();
        for (var cell : row.cells()) {
            var value = new LinkedHashMap<String, Object>();
            value.put("text", normalizeText(cell.text()));
            value.put("header", forceHeader || cell.header());
            value.put("rowSpan", cell.rowSpan());
            value.put("columnSpan", cell.columnSpan());
            result.add(value);
        }
        return result;
    }

    private Map<String, Object> attributes(Integer pageNo, Page page, List<Double> bbox) {
        var result = new LinkedHashMap<String, Object>();
        result.put("sourceProvider", PROVIDER);
        result.put("providerCoordinateSpace", PROVIDER_COORDINATE_SPACE);
        result.put("coordinateSpace", JSD_COORDINATE_SPACE);
        if (pageNo != null) result.put("pageNo", pageNo);
        if (page != null) {
            result.put("pageWidth", page.width());
            result.put("pageHeight", page.height());
            result.put("rotation", page.rotation());
        }
        if (!bbox.isEmpty()) result.put("polygon", bbox);
        return result;
    }

    private List<Double> rowPolygon(List<Double> tableBox, int row, int count) {
        if (tableBox.size() < 8 || count <= 0) return tableBox;
        var top = tableBox.get(1);
        var bottom = tableBox.get(5);
        var height = (bottom - top) / count;
        var rowTop = top + height * row;
        var rowBottom = row == count - 1 ? bottom : rowTop + height;
        return List.of(tableBox.get(0), rowTop, tableBox.get(2), rowTop,
                tableBox.get(2), rowBottom, tableBox.get(0), rowBottom);
    }

    private List<Double> normalizedPolygon(JsonNode value, Page page) {
        if (value == null || !value.isArray() || value.size() < 4 || page == null) return List.of();
        var x1 = value.get(0).asDouble();
        var y1 = value.get(1).asDouble();
        var x2 = value.get(2).asDouble();
        var y2 = value.get(3).asDouble();
        var points = List.of(new Point(x1, y1), new Point(x2, y1), new Point(x2, y2), new Point(x1, y2));
        var transformed = points.stream().map(point -> rotate(point, page)).toList();
        var width = page.displayWidth();
        var height = page.displayHeight();
        var left = transformed.stream().mapToDouble(Point::x).min().orElse(0);
        var top = transformed.stream().mapToDouble(Point::y).min().orElse(0);
        var right = transformed.stream().mapToDouble(Point::x).max().orElse(0);
        var bottom = transformed.stream().mapToDouble(Point::y).max().orElse(0);
        return List.of(clamp(left / width), clamp(top / height), clamp(right / width), clamp(top / height),
                clamp(right / width), clamp(bottom / height), clamp(left / width), clamp(bottom / height));
    }

    private Point rotate(Point point, Page page) {
        return switch (Math.floorMod(page.rotation(), 360)) {
            case 90 -> new Point(page.height() - point.y(), point.x());
            case 180 -> new Point(page.width() - point.x(), page.height() - point.y());
            case 270 -> new Point(point.y(), page.width() - point.x());
            default -> point;
        };
    }

    private double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    private List<Page> pages(JsonNode layout) {
        var result = new ArrayList<Page>();
        var source = layout == null ? null : layout.path("pdf_info");
        if (source != null && source.isArray()) {
            for (var item : source) {
                var size = item.path("page_size");
                var width = size.isArray() && size.size() > 0 ? size.get(0).asDouble(1) : 1;
                var height = size.isArray() && size.size() > 1 ? size.get(1).asDouble(1) : 1;
                result.add(new Page(item.path("page_idx").asInt(result.size()), width, height,
                        item.path("rotation").asInt(item.path("page_rotation").asInt(0))));
            }
        }
        return List.copyOf(result);
    }

    private Page page(Integer pageNo, List<Page> pages) {
        if (pageNo == null) return null;
        return pages.stream().filter(value -> value.index() + 1 == pageNo).findFirst().orElse(null);
    }

    private JsonNode firstJson(Map<String, byte[]> files, String suffix) {
        return files.entrySet().stream().filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).endsWith(suffix))
                .findFirst().map(entry -> readJson(entry.getValue())).orElse(null);
    }

    private JsonNode readJson(byte[] bytes) {
        try { return mapper.readTree(bytes); }
        catch (IOException exception) { throw new IllegalStateException("MinerU 结果 JSON 无法读取", exception); }
    }

    private Map<String, byte[]> unzip(byte[] bytes) {
        var result = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                var output = new ByteArrayOutputStream();
                zip.transferTo(output);
                result.put(entry.getName().replace('\\', '/'), output.toByteArray());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("MinerU 结果 ZIP 无法读取", exception);
        }
        if (result.isEmpty()) throw new IllegalStateException("MinerU 结果 ZIP 为空");
        return result;
    }

    private String normalizeText(String source) {
        if (source == null) return "";
        return source.replace('\u0000', ' ').replaceAll("[\\t\\r]+", " ")
                .replaceAll("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])", "")
                .replaceAll("[ ]{2,}", " ").strip();
    }

    record Parsed(List<DocumentParser.TextBlock> blocks, Map<String, Object> metadata) { }
    record Page(int index, double width, double height, int rotation) {
        double displayWidth() { return rotation % 180 == 0 ? width : height; }
        double displayHeight() { return rotation % 180 == 0 ? height : width; }
        Map<String, Object> metadata() {
            return Map.of("pageNo", index + 1, "width", width, "height", height, "rotation", rotation,
                    "providerCoordinateSpace", PROVIDER_COORDINATE_SPACE);
        }
    }
    record Point(double x, double y) { }
}
