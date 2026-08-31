package com.jsd.aird.kb.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.domain.DocumentParser;

/** Converts a validated MinerU result archive into the neutral structured block contract. */
final class MineruDocumentAdapter {

    static final int MAX_ENTRIES = 4_096;
    static final long MAX_ENTRY_BYTES = 128L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    static final long MAX_JSON_BYTES = 64L * 1024 * 1024;
    static final double MAX_COMPRESSION_RATIO = 100d;

    private static final String PROVIDER = "MINERU";
    private static final String PROVIDER_COORDINATE_SPACE = "MINERU_0_1000";
    private static final String JSD_COORDINATE_SPACE = "JSD_NORMALIZED";

    private final ObjectMapper mapper;
    private final QwenTableParser tableParser = new QwenTableParser();

    MineruDocumentAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Parsed parsePrecise(byte[] zipBytes, String fileName) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("mineru-adapter-", ".zip");
            Files.write(temporary, zipBytes);
            return parsePrecise(temporary, fileName, null);
        } catch (IOException exception) {
            throw MineruException.adapter("MinerU 结果 ZIP 无法读取", exception);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    Parsed parsePrecise(Path zipPath, String fileName, UUID resultFileId) {
        return parsePrecise(zipPath, fileName, resultFileId, Map.of());
    }

    Parsed parsePrecise(Path zipPath, String fileName, UUID resultFileId,
                        Map<String, UUID> assetFileIds) {
        try (var archive = inspect(zipPath)) {
            var contentEntry = selectContentEntry(archive.entries());
            var content = readJson(archive.zip(), contentEntry);
            var layoutEntry = archive.entries().stream()
                    .filter(entry -> lower(entry.getName()).endsWith("layout.json"))
                    .findFirst().orElse(null);
            var layout = layoutEntry == null ? null : readJson(archive.zip(), layoutEntry);
            var pages = pages(layout);
            var contentListV2 = lower(contentEntry.getName()).endsWith("content_list_v2.json");
            var items = flattenContent(content, contentListV2);
            var blocks = new ArrayList<DocumentParser.TextBlock>();
            var tableIndex = 0;
            for (var item : items) {
                var type = normalizedType(item);
                var kind = contentKind(type);
                var pageNo = pageNo(item);
                var page = page(pageNo, pages);
                var bbox = normalizedPolygon(item.path("bbox"));
                if (kind == ContentKind.TABLE) {
                    tableIndex = appendTable(blocks, item, pageNo, page, bbox, tableIndex);
                    continue;
                }
                if (kind == ContentKind.IMAGE || kind == ContentKind.CHART) {
                    appendVisual(blocks, item, type, kind, pageNo, page, bbox, resultFileId, assetFileIds);
                    continue;
                }
                appendTextBlock(blocks, item, type, kind, pageNo, page, bbox);
            }
            if (blocks.stream().noneMatch(block -> !normalizeText(block.content()).isBlank())) {
                throw MineruException.contract("MinerU 结果没有非空有效内容", null);
            }
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("provider", PROVIDER);
            metadata.put("coordinateSpace", JSD_COORDINATE_SPACE);
            metadata.put("providerCoordinateSpace", PROVIDER_COORDINATE_SPACE);
            metadata.put("contentListVersion", contentListV2 ? 2 : 1);
            metadata.put("pageCount", pages.size());
            metadata.put("pages", pages.stream().map(Page::metadata).toList());
            metadata.put("resultFiles", archive.entries().stream().map(ZipEntry::getName).sorted().toList());
            if (resultFileId != null) metadata.put("resultFileId", resultFileId.toString());
            return new Parsed(List.copyOf(blocks), metadata);
        } catch (MineruException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw MineruException.adapter("MinerU 结果 ZIP/JSON 解析失败", exception);
        }
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
                var space = line.indexOf(' ');
                var level = Math.min(6, Math.max(1, space <= 0 ? leadingHashes(line) : space));
                var text = normalizeText(space <= 0 ? line.replaceFirst("^#+", "") : line.substring(space + 1));
                if (!text.isBlank()) {
                    blocks.add(new DocumentParser.TextBlock(null, "heading-" + level, text,
                            null, null, null, List.of(), null, null, null,
                            Map.of("sourceProvider", PROVIDER, "coordinateSpace", "NONE", "level", level,
                                    "agentFallback", true, "searchable", false)));
                }
                continue;
            }
            if (line.isBlank()) flushParagraph(blocks, paragraph);
            else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(normalizeText(line));
            }
        }
        flushParagraph(blocks, paragraph);
        if (blocks.isEmpty()) throw MineruException.contract("MinerU Agent 返回空内容", null);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("provider", PROVIDER);
        metadata.put("coordinateSpace", "NONE");
        metadata.put("agentFallback", true);
        metadata.put("reviewWarning", "该版本使用 MinerU Agent 降级解析，版面和坐标质量较低，请重点审核");
        metadata.put("fileName", fileName);
        return new Parsed(List.copyOf(blocks), metadata);
    }

    private void appendTextBlock(List<DocumentParser.TextBlock> blocks, JsonNode item, String type,
                                 ContentKind kind, Integer pageNo, Page page, List<Double> bbox) {
        var text = switch (kind) {
            case HEADING -> firstText(item, "text", "title_content", "content");
            case PARAGRAPH -> firstText(item, "text", "paragraph_content", "content");
            case FORMULA -> firstText(item, "text", "math_content", "latex", "equation", "content");
            case CODE -> firstText(item, "code_body", "code_content", "algorithm_content", "text", "content");
            case LIST -> listText(item, firstText(item, "text", "content"));
            default -> firstText(item, "text", type + "_content", "content", "latex", "equation", "code_body");
        };
        text = normalizeText(text);
        if (text.isBlank()) return;
        var level = integerField(item, "text_level", integerField(item, "level", 0));
        var effectiveKind = kind == ContentKind.PARAGRAPH && level > 0 ? ContentKind.HEADING : kind;
        var section = switch (effectiveKind) {
            case HEADING -> "heading-" + Math.min(6, Math.max(1, level == 0 ? 1 : level));
            case FORMULA -> "formula";
            case LIST -> "listItem";
            case CODE -> "code";
            case HEADER -> "header";
            case FOOTER -> "footer";
            case PAGE_NUMBER -> "pageNumber";
            case CAPTION -> "caption";
            case FOOTNOTE -> "footnote";
            default -> "paragraph";
        };
        var attributes = attributes(pageNo, page, bbox);
        attributes.put("mineruType", type);
        var subType = firstText(item, "sub_type");
        if (!subType.isBlank()) attributes.put("mineruSubType", subType);
        if (section.startsWith("heading-")) {
            attributes.put("level", Integer.parseInt(section.substring("heading-".length())));
            attributes.put("searchable", false);
        }
        if (kind == ContentKind.HEADER || kind == ContentKind.FOOTER || kind == ContentKind.PAGE_NUMBER
                || kind == ContentKind.UNKNOWN) {
            attributes.put("searchable", false);
        }
        if ("code".equals(section)) attributes.put("preserveWhitespace", true);
        blocks.add(new DocumentParser.TextBlock(pageNo, section, text, null, null, null,
                bbox, null, null, confidence(item), attributes));
    }

    private void appendVisual(List<DocumentParser.TextBlock> blocks, JsonNode item, String type, ContentKind kind,
                              Integer pageNo, Page page, List<Double> bbox, UUID resultFileId,
                              Map<String, UUID> assetFileIds) {
        var chart = kind == ContentKind.CHART;
        var caption = firstText(item, chart ? "chart_caption" : "image_caption", "caption");
        var footnote = firstText(item, chart ? "chart_footnote" : "image_footnote", "footnote");
        var ocrText = firstText(item, "ocr_text", "text", "content");
        var text = joinNonBlank(caption, ocrText, footnote);
        if (text.isBlank()) text = chart ? "[图表]" : "[图片]";
        var attributes = attributes(pageNo, page, bbox);
        attributes.put("mineruType", type);
        attributes.put("visualType", chart ? "CHART" : "IMAGE");
        var subType = firstText(item, "sub_type");
        if (!subType.isBlank()) attributes.put("mineruSubType", subType);
        attributes.put("caption", caption);
        attributes.put("footnote", footnote);
        attributes.put("ocrText", normalizeText(ocrText));
        attributes.put("searchable", !joinNonBlank(caption, ocrText, footnote).isBlank());
        var assetPath = assetPath(item);
        if (!assetPath.isBlank()) {
            var normalizedPath = normalizeEntryPath(assetPath);
            attributes.put("resultEntryPath", normalizedPath);
            var assetFileId = assetFileIds == null ? null : assetFileIds.get(normalizedPath);
            if (assetFileId != null) attributes.put("assetFileId", assetFileId.toString());
        }
        if (resultFileId != null) attributes.put("resultFileId", resultFileId.toString());
        blocks.add(new DocumentParser.TextBlock(pageNo, chart ? "chart" : "image", text,
                null, null, null, bbox, null, null, confidence(item), attributes));
    }

    private String normalizeEntryPath(String value) {
        var normalized = value == null ? "" : value.replace('\\', '/').strip();
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    private void flushParagraph(List<DocumentParser.TextBlock> blocks, StringBuilder paragraph) {
        var text = normalizeText(paragraph.toString());
        if (!text.isBlank()) {
            blocks.add(new DocumentParser.TextBlock(null, "paragraph", text, null, null, null, List.of(), null,
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
                var cells = cells(table.rows().get(rowIndex), rowIndex == 0);
                var attrs = new LinkedHashMap<String, Object>();
                attrs.put("sourceProvider", PROVIDER);
                attrs.put("coordinateSpace", "NONE");
                attrs.put("tableGroup", group);
                attrs.put("tableRowIndex", rowIndex);
                attrs.put("logicalRowNo", rowIndex);
                attrs.put("locatorAccuracy", "NONE");
                attrs.put("cells", cells);
                blocks.add(tableRow(null, cells, List.of(), attrs));
            }
            tableIndex++;
        }
        return tableIndex;
    }

    private int appendTable(List<DocumentParser.TextBlock> blocks, JsonNode item, Integer pageNo, Page page,
                            List<Double> bbox, int tableIndex) {
        var parsed = tableParser.parseHtml(firstText(item, "table_body", "html"));
        if (parsed.isEmpty()) {
            var plain = normalizeText(firstText(item, "text", "content"));
            if (!plain.isBlank()) {
                var attrs = attributes(pageNo, page, bbox);
                attrs.put("tableGroup", "mineru-table-" + tableIndex);
                attrs.put("logicalRowNo", 0);
                attrs.put("locatorAccuracy", "APPROXIMATE");
                blocks.add(new DocumentParser.TextBlock(pageNo, "mineru-table-row", plain,
                        null, null, null, bbox, null, null, confidence(item), attrs));
                return tableIndex + 1;
            }
            return tableIndex;
        }
        var group = "mineru-table-" + tableIndex;
        var title = firstText(item, "table_caption", "caption");
        var footnote = firstText(item, "table_footnote", "footnote");
        var rowIndex = 0;
        for (var table : parsed) {
            for (var row : table.rows()) {
                var cells = cells(row, rowIndex == 0);
                var attrs = attributes(pageNo, page, bbox);
                attrs.put("tableGroup", group);
                attrs.put("tableRowIndex", rowIndex);
                attrs.put("logicalRowNo", rowIndex++);
                attrs.put("tableRowCount", parsed.stream().mapToInt(value -> value.rows().size()).sum());
                attrs.put("locatorAccuracy", "APPROXIMATE");
                attrs.put("tableCaption", title);
                attrs.put("tableFootnote", footnote);
                attrs.put("cells", cells);
                attrs.put("mineruType", "table");
                blocks.add(tableRow(pageNo, cells, bbox, attrs));
            }
        }
        return tableIndex + 1;
    }

    private DocumentParser.TextBlock tableRow(Integer pageNo, List<Map<String, Object>> cells,
                                               List<Double> bbox, Map<String, Object> attrs) {
        var text = cells.stream().map(value -> String.valueOf(value.get("text")))
                .reduce((left, right) -> left + " | " + right).orElse("");
        return new DocumentParser.TextBlock(pageNo, "mineru-table-row", text,
                null, null, null, bbox, null, null, null, attrs);
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

    /** MinerU content-list coordinates are already in a stable 0..1000 page space. */
    private List<Double> normalizedPolygon(JsonNode value) {
        if (value == null || !value.isArray() || value.size() < 4) return List.of();
        if (value.size() >= 8) {
            var result = new ArrayList<Double>(8);
            for (var index = 0; index < 8; index++) result.add(clamp(value.get(index).asDouble() / 1000d));
            return List.copyOf(result);
        }
        var x1 = clamp(value.get(0).asDouble() / 1000d);
        var y1 = clamp(value.get(1).asDouble() / 1000d);
        var x2 = clamp(value.get(2).asDouble() / 1000d);
        var y2 = clamp(value.get(3).asDouble() / 1000d);
        return List.of(x1, y1, x2, y1, x2, y2, x1, y2);
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

    private List<JsonNode> flattenContent(JsonNode root, boolean contentListV2) {
        var result = new ArrayList<JsonNode>();
        if (contentListV2 && root != null && root.isArray()) {
            for (var pageIndex = 0; pageIndex < root.size(); pageIndex++) {
                var page = root.get(pageIndex);
                var inheritedPage = page.isArray() || (page.isObject() && !page.has("type"))
                        ? Integer.valueOf(pageIndex) : null;
                flatten(page, inheritedPage, result);
            }
            return result;
        }
        flatten(root, null, result);
        return result;
    }

    private void flatten(JsonNode node, Integer inheritedPage, List<JsonNode> result) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            for (var child : node) flatten(child, inheritedPage, result);
            return;
        }
        if (!node.isObject()) return;
        Integer page = node.has("page_idx") ? Integer.valueOf(node.path("page_idx").asInt()) : inheritedPage;
        var nested = node.has("content") && node.path("content").isArray() ? node.path("content")
                : node.has("blocks") && node.path("blocks").isArray() ? node.path("blocks") : null;
        if (nested != null && !node.has("type")) {
            for (var child : nested) {
                if (page != null && child.isObject() && !child.has("page_idx")) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) child).put("page_idx", page);
                }
                flatten(child, page, result);
            }
        } else {
            if (page != null && !node.has("page_idx")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("page_idx", page);
            }
            result.add(node);
        }
    }

    private Integer pageNo(JsonNode item) {
        if (item.has("page_idx")) return item.path("page_idx").asInt() + 1;
        if (item.has("page_no")) return Math.max(1, item.path("page_no").asInt());
        return null;
    }

    private String normalizedType(JsonNode item) {
        return item.path("type").asText(item.path("content_type").asText("text"))
                .strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private ContentKind contentKind(String type) {
        return switch (type) {
            case "title", "heading" -> ContentKind.HEADING;
            case "text", "paragraph" -> ContentKind.PARAGRAPH;
            case "image", "image_body" -> ContentKind.IMAGE;
            case "chart", "chart_body" -> ContentKind.CHART;
            case "table", "table_body" -> ContentKind.TABLE;
            case "equation", "formula", "interline_equation", "inline_equation", "equation_interline" -> ContentKind.FORMULA;
            case "code", "code_block", "algorithm" -> ContentKind.CODE;
            case "list", "list_item", "index", "ref_text" -> ContentKind.LIST;
            case "header", "page_header" -> ContentKind.HEADER;
            case "footer", "page_footer" -> ContentKind.FOOTER;
            case "page_number" -> ContentKind.PAGE_NUMBER;
            case "aside_text", "page_aside_text" -> ContentKind.ASIDE;
            case "footnote", "page_footnote", "table_footnote", "image_footnote", "chart_footnote" -> ContentKind.FOOTNOTE;
            case "table_caption", "image_caption", "chart_caption", "code_caption" -> ContentKind.CAPTION;
            default -> ContentKind.UNKNOWN;
        };
    }

    private Double confidence(JsonNode item) {
        if (item.has("score") && item.path("score").isNumber()) return item.path("score").asDouble();
        if (item.has("confidence") && item.path("confidence").isNumber()) return item.path("confidence").asDouble();
        return null;
    }

    private String listText(JsonNode item, String fallback) {
        var list = field(item, "list_items");
        if (list == null || !list.isArray()) list = field(item, "items");
        if (list == null || !list.isArray()) return fallback;
        var values = new ArrayList<String>();
        for (var value : list) {
            var text = value.isTextual() ? value.asText() : firstText(value, "item_content", "text", "content");
            text = normalizeText(text);
            if (!text.isBlank()) values.add("- " + text);
        }
        return values.isEmpty() ? fallback : String.join("\n", values);
    }

    private String firstText(JsonNode item, String... names) {
        for (var name : names) {
            var value = field(item, name);
            var text = joinedText(value);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String joinedText(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isTextual() || value.isNumber()) return normalizeText(value.asText());
        if (value.isArray()) {
            var values = new ArrayList<String>();
            var inline = true;
            for (var item : value) {
                var text = joinedText(item);
                text = normalizeText(text);
                if (!text.isBlank()) values.add(text);
                inline &= item.isObject() && item.has("type");
            }
            return String.join(inline ? "" : "\n", values);
        }
        if (value.isObject()) {
            for (var name : List.of("text", "content", "item_content", "children")) {
                if (value.has(name)) {
                    var text = joinedText(value.get(name));
                    if (!text.isBlank()) return text;
                }
            }
        }
        return "";
    }

    private JsonNode field(JsonNode item, String name) {
        if (item == null || item.isNull()) return null;
        var direct = item.get(name);
        if (direct != null && !direct.isNull() && !("content".equals(name) && direct.isObject())) return direct;
        var payload = item.get("content");
        if (payload != null && payload.isObject()) {
            var nested = payload.get(name);
            if (nested != null && !nested.isNull()) return nested;
        }
        return direct;
    }

    private int integerField(JsonNode item, String name, int fallback) {
        var value = field(item, name);
        return value != null && value.isNumber() ? value.asInt() : fallback;
    }

    private String assetPath(JsonNode item) {
        var direct = firstText(item, "img_path", "image_path", "chart_path");
        if (!direct.isBlank()) return direct;
        var source = field(item, "image_source");
        return source != null && source.isObject() ? joinedText(source.get("path")) : "";
    }

    private String joinNonBlank(String... values) {
        return java.util.Arrays.stream(values).map(this::normalizeText).filter(value -> !value.isBlank())
                .distinct().reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private int leadingHashes(String value) {
        var result = 0;
        while (result < value.length() && value.charAt(result) == '#') result++;
        return result;
    }

    private JsonNode readJson(ZipFile zip, ZipEntry entry) throws IOException {
        try (var input = zip.getInputStream(entry)) {
            return mapper.readTree(readLimited(input, Math.min(MAX_ENTRY_BYTES, MAX_JSON_BYTES), "JSON"));
        }
    }

    private Archive inspect(Path zipPath) throws IOException {
        var zip = new ZipFile(zipPath.toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8);
        try {
            var entries = new ArrayList<ZipEntry>();
            var seen = new HashSet<String>();
            long total = 0;
            Enumeration<? extends ZipEntry> source = zip.entries();
            while (source.hasMoreElements()) {
                var entry = source.nextElement();
                if (entry.isDirectory()) continue;
                if (entries.size() >= MAX_ENTRIES) throw MineruException.contract("MinerU 结果 ZIP entry 数超过 4096", null);
                var normalized = safeEntryName(entry.getName());
                if (!seen.add(normalized.toLowerCase(Locale.ROOT))) {
                    throw MineruException.contract("MinerU 结果 ZIP 含重复 entry：" + normalized, null);
                }
                var size = entry.getSize();
                if (size < 0) throw MineruException.contract("MinerU 结果 ZIP entry 大小未知：" + normalized, null);
                if (size > MAX_ENTRY_BYTES) throw MineruException.contract("MinerU 结果 ZIP 单 entry 超过 128 MiB", null);
                total = Math.addExact(total, size);
                if (total > MAX_TOTAL_BYTES) throw MineruException.contract("MinerU 结果 ZIP 总解压大小超过 1 GiB", null);
                var compressed = entry.getCompressedSize();
                if (size > 0 && compressed <= 0) throw MineruException.contract("MinerU 结果 ZIP 压缩信息无效", null);
                if (compressed > 0 && (double) size / compressed > MAX_COMPRESSION_RATIO) {
                    throw MineruException.contract("MinerU 结果 ZIP 压缩比超过 100", null);
                }
                entries.add(entry);
            }
            if (entries.isEmpty()) throw MineruException.contract("MinerU 结果 ZIP 为空", null);
            return new Archive(zip, List.copyOf(entries));
        } catch (RuntimeException exception) {
            zip.close();
            throw exception;
        }
    }

    private ZipEntry selectContentEntry(List<ZipEntry> entries) {
        var stable = entries.stream().filter(entry -> lower(entry.getName()).endsWith("content_list.json"))
                .findFirst();
        if (stable.isPresent()) return stable.get();
        return entries.stream().filter(entry -> lower(entry.getName()).endsWith("content_list_v2.json"))
                .findFirst().orElseThrow(() -> MineruException.contract("MinerU 结果缺少 content_list.json", null));
    }

    private String safeEntryName(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\u0000') >= 0) {
            throw MineruException.contract("MinerU 结果 ZIP entry 名无效", null);
        }
        var slash = raw.replace('\\', '/');
        if (slash.startsWith("/") || slash.matches("^[A-Za-z]:.*")) {
            throw MineruException.contract("MinerU 结果 ZIP 含绝对路径", null);
        }
        var normalized = Paths.get(slash).normalize().toString().replace('\\', '/');
        if (normalized.equals("..") || normalized.startsWith("../")) {
            throw MineruException.contract("MinerU 结果 ZIP 含路径穿越", null);
        }
        return normalized;
    }

    private byte[] readLimited(InputStream input, long maximum, String kind) throws IOException {
        var output = new ByteArrayOutputStream();
        var buffer = new byte[16 * 1024];
        long total = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            total += read;
            if (total > maximum) throw MineruException.contract("MinerU 结果 " + kind + " 超过大小限制", null);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String lower(String value) { return value.toLowerCase(Locale.ROOT); }

    private String normalizeText(String source) {
        if (source == null) return "";
        return source.replace('\u0000', ' ').replaceAll("[\\t\\r]+", " ")
                .replaceAll("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])", "")
                .replaceAll("[ ]{2,}", " ").strip();
    }

    record Parsed(List<DocumentParser.TextBlock> blocks, Map<String, Object> metadata) { }
    private enum ContentKind {
        HEADING, PARAGRAPH, IMAGE, CHART, TABLE, FORMULA, CODE, LIST,
        HEADER, FOOTER, PAGE_NUMBER, ASIDE, FOOTNOTE, CAPTION, UNKNOWN
    }
    record Page(int index, double width, double height, int rotation) {
        Map<String, Object> metadata() {
            return Map.of("pageNo", index + 1, "width", width, "height", height, "rotation", rotation,
                    "providerCoordinateSpace", PROVIDER_COORDINATE_SPACE);
        }
    }
    private record Archive(ZipFile zip, List<ZipEntry> entries) implements AutoCloseable {
        @Override public void close() throws IOException { zip.close(); }
    }
}
