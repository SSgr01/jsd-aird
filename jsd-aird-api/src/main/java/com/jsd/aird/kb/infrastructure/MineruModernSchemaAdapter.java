package com.jsd.aird.kb.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Converts DocVortex Middle JSON / structured-content projections without rendering a whole document to Markdown. */
final class MineruModernSchemaAdapter {
    private static final Pattern NUMBER = Pattern.compile("^\\s*(\\d{1,4})[.)．、]\\s+.*", Pattern.DOTALL);
    private static final Pattern YEAR = Pattern.compile("\\b(?:18|19|20)\\d{2}\\b");
    private final ObjectMapper mapper;
    private final String prefix;
    private final ArrayNode items;
    private final List<String> warnings = new ArrayList<>();
    private String previousListGroup;

    MineruModernSchemaAdapter(ObjectMapper mapper, String entryName) {
        this.mapper = mapper;
        int slash = entryName.lastIndexOf('/');
        prefix = slash < 0 ? "" : entryName.substring(0, slash + 1);
        items = mapper.createArrayNode();
    }

    static boolean supports(JsonNode root) {
        return root != null && root.isObject() && root.path("pages").isArray();
    }

    Result convert(JsonNode root) {
        var layout = mapper.createObjectNode();
        var dimensions = layout.putArray("pdf_info");
        var declared = root.path("extensions").path("docvortex_layout").path("pages");
        for (var page : root.path("pages")) {
            int index = page.path("page_idx").asInt(dimensions.size());
            var size = dimensions.addObject().put("page_idx", index).putArray("page_size");
            JsonNode matching = null;
            for (var d : declared) if (d.path("page_idx").asInt(-1) == index) matching = d;
            size.add(matching == null ? page.path("width_pt").asDouble(1) : matching.path("width_pt").asDouble(1));
            size.add(matching == null ? page.path("height_pt").asDouble(1) : matching.path("height_pt").asDouble(1));
            for (var block : page.path("blocks")) append(block, index, null);
        }
        recoverReferences(items, warnings);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("resultSchema", root.path("schema").asText("structured_content"));
        metadata.put("providerCoordinateSpace", "MINERU_NORMALIZED");
        metadata.put("adapterVersion", "mineru-structured-v3");
        var mineru = root.path("extensions").path("mineru");
        if (mineru.hasNonNull("tier")) metadata.put("tier", mineru.path("tier").asText());
        if (mineru.hasNonNull("parse_mode")) metadata.put("parseMode", mineru.path("parse_mode").asText());
        metadata.put("completenessWarnings", List.copyOf(warnings));
        return new Result(items, layout, Map.copyOf(metadata));
    }

    private void append(JsonNode block, int page, String listGroup) {
        var type = block.path("type").asText("text");
        var content = block.path("content");
        if ((type.equals("list") || type.equals("index")) && content.isArray()) {
            String group = block.path("sub_type").asText().equals("ref_text") ? "references" : "list-" + page + "-" + items.size();
            if (block.path("continues_prev").asBoolean() && previousListGroup != null) group = previousListGroup;
            int start = items.size();
            for (var child : content) append(child, page, group);
            if (items.size() > start) {
                if (block.has("continues_prev")) ((ObjectNode) items.get(start)).set("continues_prev", block.get("continues_prev"));
                if (block.has("continues_next")) ((ObjectNode) items.get(items.size()-1)).set("continues_next", block.get("continues_next"));
            }
            previousListGroup = group;
            return;
        }
        var item = mapper.createObjectNode().put("type", type).put("page_idx", page);
        // Existing cloud adapter consumes 0..1000 coordinates. Scale once at this boundary.
        var bbox = item.putArray("bbox");
        for (var coordinate : block.path("bbox")) bbox.add(coordinate.asDouble() * 1000d);
        item.put("providerCoordinateSpace", "MINERU_NORMALIZED");
        item.put("sourceBlockIndex", block.path("index").asInt(items.size()));
        if (block.has("continues_prev")) item.set("continues_prev", block.get("continues_prev"));
        if (block.has("continues_next")) item.set("continues_next", block.get("continues_next"));
        if (listGroup != null) item.put("listGroup", listGroup).put("ordered", true);
        if (type.equals("doc_title") || type.equals("paragraph_title")) {
            item.put("type", "heading").put("level", block.path("level").asInt(type.equals("doc_title") ? 1 : 2));
        }
        if (type.equals("table")) {
            var body = child(block, "table_body");
            item.put("table_body", htmlImages(body == null ? inline(content) : inline(body.path("content"))));
            item.put("table_caption", captions(block, "table_caption", "captions"));
            item.put("table_footnote", captions(block, "table_footnote", "footnotes"));
        } else if (type.equals("image") || type.equals("chart")) {
            var body = child(block, type + "_body");
            String path = body == null ? block.path("image_source").asText() : body.path("image_path").asText();
            if (path.isBlank()) path = block.path("image_path").asText();
            item.put("img_path", asset(path));
            item.put(type + "_caption", captions(block, type + "_caption", "captions"));
            item.put(type + "_footnote", captions(block, type + "_footnote", "footnotes"));
            String extracted = body == null ? inline(content) : inline(body.path("content"));
            if (type.equals("chart")) {
                item.put("extractedChartData", extracted);
                item.put("derivedFromChart", !extracted.isBlank());
                // Preserve inferred numeric data for review without treating it as original prose.
                item.put("text", "");
            } else item.put("text", extracted);
        } else {
            item.put("text", inline(content));
            if (type.equals("ref_text")) item.put("listGroup", "references").put("ordered", true);
            if (type.equals("equation") && block.hasNonNull("image_path")) item.put("img_path", asset(block.path("image_path").asText()));
        }
        items.add(item);
        if (type.equals("table")) {
            // Captions are independent searchable source blocks, not repeated in every table row.
            for (String name : List.of("table_caption", "table_footnote")) {
                var text = item.path(name).asText();
                if (!text.isBlank()) items.add(item.deepCopy().put("type", name).put("text", text));
            }
        }
    }

    private JsonNode child(JsonNode block, String type) {
        if (block.path("content").isArray()) for (var c : block.path("content")) if (c.path("type").asText().equals(type)) return c;
        return null;
    }

    private String captions(JsonNode block, String childType, String projectionKey) {
        var texts = new ArrayList<String>();
        if (block.path("content").isArray()) for (var c : block.path("content")) {
            if (c.path("type").asText().equals(childType)) texts.add(inline(c.path("content")));
        }
        if (texts.isEmpty() && block.has(projectionKey)) texts.add(inline(block.path(projectionKey)));
        return String.join("\n", texts);
    }

    private String asset(String path) {
        if (path.isBlank()) return "";
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || java.util.Arrays.asList(normalized.split("/")).contains("..")) {
            throw MineruException.contract("MinerU 图片引用不是 ZIP 内相对路径", null);
        }
        return prefix + normalized.replaceFirst("^\\./", "");
    }

    private String htmlImages(String html) {
        var pattern = Pattern.compile("(?is)(<img\\b[^>]*?\\bsrc\\s*=\\s*)([\"'])(.*?)(\\2)");
        return pattern.matcher(html).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1) + m.group(2) + asset(m.group(3)) + m.group(4)));
    }

    private String inline(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isValueNode()) return node.asText();
        if (node.isArray()) {
            var out = new StringBuilder();
            for (var c : node) out.append(inline(c));
            return out.toString();
        }
        String text = inline(node.path("content"));
        String type = node.path("type").asText();
        if (type.equals("equation") || type.equals("inline_equation")) return "$" + text + "$";
        if (type.equals("hyperlink")) {
            String url = node.path("url").asText();
            if (url.matches("(?i)https?://.*")) return "[" + text + "](" + url + ")";
        }
        for (var style : node.path("styles")) text = switch (style.asText()) {
            case "bold" -> "**" + text + "**";
            case "italic" -> "*" + text + "*";
            case "superscript" -> "<sup>" + text + "</sup>";
            case "subscript" -> "<sub>" + text + "</sub>";
            default -> text;
        };
        return text;
    }

    static void recoverReferences(ArrayNode items, List<String> warnings) {
        boolean references = false;
        int expected = 1, recovered = 0;
        for (var node : items) {
            var item = (ObjectNode) node;
            String text = item.path("text").asText().strip();
            String type = item.path("type").asText();
            String heading = text.replaceAll("[*#]", "").strip().toLowerCase(Locale.ROOT);
            if (heading.matches("(?:\\d+[. ]+)?(?:references|bibliography|参考文献)[:：]?")) {
                references = true; expected = 1; continue;
            }
            if (!references) continue;
            if (type.equals("heading") || type.equals("paragraph_title") || type.equals("doc_title")) { references = false; continue; }
            if (heading.startsWith("publisher’s note") || heading.startsWith("publisher's note")) {
                if (expected > 2 && (type.equals("header") || type.equals("footer"))) {
                    item.put("type", "text").put("originalMineruType", type).put("recoveryReason", "REFERENCE_SECTION_END");
                }
                references = false; continue;
            }
            var number = NUMBER.matcher(text);
            if (!number.matches()) continue;
            int actual = Integer.parseInt(number.group(1));
            boolean auxiliary = type.equals("header") || type.equals("footer") || type.equals("page_header") || type.equals("page_footer");
            boolean citation = text.length() >= 45 && YEAR.matcher(text).find() && (text.contains(";") || text.contains(",") || text.toLowerCase(Locale.ROOT).contains("doi"));
            if (actual == expected && citation && (auxiliary || type.equals("ref_text") || type.equals("text") || type.equals("list_item"))) {
                if (auxiliary) {
                    item.put("type", "ref_text").put("originalMineruType", type).put("recoveryReason", "CONTIGUOUS_NUMBERED_REFERENCE");
                    item.put("listGroup", "references").put("ordered", true);
                    recovered++;
                }
                expected++;
            } else if (auxiliary && citation && actual > expected) warnings.add("REFERENCE_NUMBER_GAP: expected=" + expected + ", found=" + actual);
        }
        if (recovered > 0) warnings.add("REFERENCE_HEADERS_RECOVERED: " + recovered);
    }

    record Result(ArrayNode items, ObjectNode layout, Map<String, Object> metadata) { }
}
