package com.jsd.aird.kb.application;
import com.jsd.aird.shared.text.LatexEvidenceProjector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.kb.api.DocumentParser;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the neutral structured-document contract. Provider payloads and editor-specific
 * runtime state must never leak through this boundary.
 */
@Component
public class StructuredDocumentCodec {

    private static final Logger log = LoggerFactory.getLogger(StructuredDocumentCodec.class);

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NODE_COUNT = 20_000;
    public static final int MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
    private static final int PART_NODE_TARGET = 4_000;
    private static final int PART_BYTE_TARGET = 1024 * 1024;
    private static final int MAX_PARTS = 256;

    private static final Set<String> ALLOWED_NODES = Set.of(
            "doc", "paragraph", "heading", "text", "bulletList", "orderedList", "listItem",
            "blockquote", "codeBlock", "horizontalRule", "hardBreak", "table", "tableRow",
            "tableHeader", "tableCell", "image", "audioSegment", "formula", "inlineMath", "dataTableRef", "documentPart"
    );
    private static final Set<String> ALLOWED_MARKS = Set.of(
            "bold", "italic", "underline", "strike", "code", "link", "superscript", "subscript");

    private final ObjectMapper objectMapper;
    private final MineruInlineSemanticParser inlineParser;

    public StructuredDocumentCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inlineParser = new MineruInlineSemanticParser(objectMapper, new LatexEvidenceProjector());
    }

    /** Sanitizes provider output without guessing layout semantics. */
    public List<DocumentParser.TextBlock> normalizeBlocks(List<DocumentParser.TextBlock> blocks) {
        var result = new ArrayList<DocumentParser.TextBlock>();
        if (blocks == null) return List.of();
        for (var raw : blocks) {
            if (raw == null) continue;
            var text = normalize(raw.content());
            if (text.isBlank()) continue;
            result.add(copyWithText(raw, text));
        }
        if (log.isDebugEnabled() && result.size() != blocks.size()) {
            log.debug("structured_block_normalized inputBlocks={} outputBlocks={}", blocks.size(), result.size());
        }
        return List.copyOf(result);
    }

    public InitialDocuments initialize(List<DocumentParser.TextBlock> blocks) {
        var sourceNodes = new ArrayList<SourceNodeDraft>();
        var sourceContent = objectMapper.createArrayNode();
        var reviewContent = objectMapper.createArrayNode();
        var index = 0;
        ObjectNode sourceTable = null;
        ObjectNode reviewTable = null;
        String tableGroup = null;
        ObjectNode sourceList = null;
        ObjectNode reviewList = null;
        String listGroup = null;
        for (var block : normalizeBlocks(blocks)) {
            var sourceKey = UUID.randomUUID();
            var reviewNodeId = UUID.randomUUID();
            var text = normalize(block.content());
            var type = nodeType(block.section());
            var anchor = anchor(block);
            var confidence = objectMapper.createObjectNode();
            if (block.confidence() != null) confidence.put("textConfidence", block.confidence());
            else confidence.putNull("textConfidence");
            confidence.putNull("structureConfidence");
            confidence.putNull("tableConfidence");
            sourceNodes.add(new SourceNodeDraft(sourceKey, index++, type, text, anchor, confidence));
            if ("tableRow".equals(type)) {
                listGroup = null;
                sourceList = null;
                reviewList = null;
                var explicitGroup = block.attributes().get("tableGroup");
                var group = explicitGroup == null
                        ? String.valueOf(block.pageNo()) + "|" + String.valueOf(block.sheetName()) + "|" + block.section()
                        : explicitGroup.toString();
                if (!group.equals(tableGroup)) {
                    tableGroup = group;
                    sourceTable = objectMapper.createObjectNode().put("type", "table");
                    sourceTable.set("attrs", objectMapper.createObjectNode().put("sheetName", block.sheetName()));
                    sourceTable.set("content", objectMapper.createArrayNode());
                    sourceContent.add(sourceTable);
                    reviewTable = objectMapper.createObjectNode().put("type", "table");
                    reviewTable.set("attrs", objectMapper.createObjectNode().put("sheetName", block.sheetName()));
                    reviewTable.set("content", objectMapper.createArrayNode());
                    reviewContent.add(reviewTable);
                }
                ((ArrayNode) sourceTable.path("content")).add(tableRow(sourceKey, null, text, block.attributes(), false));
                ((ArrayNode) reviewTable.path("content")).add(tableRow(sourceKey, reviewNodeId, text, block.attributes(), true));
            } else if ("listItem".equals(type)) {
                tableGroup = null;
                sourceTable = null;
                reviewTable = null;
                var listType = Boolean.TRUE.equals(block.attributes().get("ordered")) ? "orderedList" : "bulletList";
                if (!listType.equals(listGroup)) {
                    listGroup = listType;
                    sourceList = objectMapper.createObjectNode().put("type", listType);
                    sourceList.set("content", objectMapper.createArrayNode());
                    sourceContent.add(sourceList);
                    reviewList = objectMapper.createObjectNode().put("type", listType);
                    reviewList.set("content", objectMapper.createArrayNode());
                    reviewContent.add(reviewList);
                }
                ((ArrayNode) sourceList.path("content")).add(listItem(sourceKey, null, text, false));
                ((ArrayNode) reviewList.path("content")).add(listItem(sourceKey, reviewNodeId, text, true));
            } else {
                tableGroup = null;
                sourceTable = null;
                reviewTable = null;
                listGroup = null;
                sourceList = null;
                reviewList = null;
                structuredNodes(type, sourceKey, reviewNodeId, text, block.attributes(), false)
                        .forEach(sourceContent::add);
                structuredNodes(type, sourceKey, reviewNodeId, text, block.attributes(), true)
                        .forEach(reviewContent::add);
            }
        }
        return new InitialDocuments(document(sourceContent), document(reviewContent), sourceNodes);
    }

    public JsonNode validate(JsonNode candidate) {
        if (candidate == null || !candidate.isObject() || !"doc".equals(candidate.path("type").asText())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认内容不是有效的结构化文档");
        }
        var copy = candidate.deepCopy();
        ((ObjectNode) copy).put("schemaVersion", SCHEMA_VERSION);
        var count = validateNode(copy, true, new HashSet<>(), 0);
        if (count > 1_000_000 || copy.toString().getBytes(StandardCharsets.UTF_8).length > 64 * 1024 * 1024)
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "结构化内容超过后端单文件处理容量，请联系管理员检查解析结果");
        return segment(copy);
    }

    /** Partition large documents server-side without changing file/version/source identities. */
    public JsonNode segment(JsonNode candidate) {
        if (candidate == null || !candidate.isObject() || !"doc".equals(candidate.path("type").asText())) return candidate;
        var content = candidate.path("content");
        if (!content.isArray() || content.isEmpty()) return candidate;
        boolean hasParts = false;
        for (var node : content) hasParts |= "documentPart".equals(node.path("type").asText());
        if (hasParts) {
            if (content.size() > MAX_PARTS) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文档分段数超过后端处理容量");
            boolean resize = false;
            for (var part : content) {
                if (!"documentPart".equals(part.path("type").asText()))
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "分段文档结构不一致");
                if (nodeCount(part) > MAX_NODE_COUNT || byteSize(part) > MAX_DOCUMENT_BYTES)
                    resize = true;
            }
            if (resize) {
                var flattened = (ObjectNode) candidate.deepCopy();
                var blocks = objectMapper.createArrayNode();
                for (var part : content) part.path("content").forEach(blocks::add);
                flattened.set("content", blocks);
                return segment(flattened);
            }
            return candidate;
        }
        if (nodeCount(candidate) <= MAX_NODE_COUNT && byteSize(candidate) <= MAX_DOCUMENT_BYTES) return candidate;
        var blocks = new ArrayList<JsonNode>();
        for (var node : content) splitContainer(node, blocks);
        var parts = objectMapper.createArrayNode();
        ArrayNode current = objectMapper.createArrayNode();
        int nodes = 1, bytes = 128;
        for (var block : blocks) {
            int blockNodes = nodeCount(block), blockBytes = byteSize(block);
            if (blockNodes + 1 > MAX_NODE_COUNT || blockBytes + 128 > MAX_DOCUMENT_BYTES)
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "单个解析内容块异常过大，请联系管理员检查解析结果");
            if (!current.isEmpty() && (nodes + blockNodes > PART_NODE_TARGET || bytes + blockBytes > PART_BYTE_TARGET)) {
                parts.add(documentPart(parts.size(), current));
                current = objectMapper.createArrayNode(); nodes = 1; bytes = 128;
            }
            current.add(block); nodes += blockNodes; bytes += blockBytes + 1;
        }
        if (!current.isEmpty()) parts.add(documentPart(parts.size(), current));
        if (parts.size() > MAX_PARTS) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文档分段数超过后端处理容量");
        var result = (ObjectNode) candidate.deepCopy();
        result.set("content", parts);
        return result;
    }

    private ObjectNode documentPart(int index, ArrayNode content) {
        var part = objectMapper.createObjectNode().put("type", "documentPart");
        part.putObject("attrs").put("partNo", index + 1);
        part.set("content", content);
        return part;
    }

    private void splitContainer(JsonNode node, List<JsonNode> output) {
        if (!Set.of("table", "bulletList", "orderedList").contains(node.path("type").asText())
                || !node.path("content").isArray() || node.path("attrs").has("reviewNodeId")
                || (nodeCount(node) <= PART_NODE_TARGET && byteSize(node) <= PART_BYTE_TARGET)) {
            output.add(node); return;
        }
        ArrayNode current = objectMapper.createArrayNode();
        int nodes = 1, bytes = 256, offset = 0;
        for (var child : node.path("content")) {
            int childNodes = nodeCount(child), childBytes = byteSize(child);
            if (!current.isEmpty() && (nodes + childNodes > PART_NODE_TARGET || bytes + childBytes > PART_BYTE_TARGET)) {
                addContainerPart(node, current, offset, output); offset += current.size();
                current = objectMapper.createArrayNode(); nodes = 1; bytes = 256;
            }
            current.add(child); nodes += childNodes; bytes += childBytes + 1;
        }
        if (!current.isEmpty()) addContainerPart(node, current, offset, output);
    }

    private void addContainerPart(JsonNode source, ArrayNode content, int offset, List<JsonNode> output) {
        var part = (ObjectNode) source.deepCopy();
        part.set("content", content);
        if ("orderedList".equals(source.path("type").asText()))
            part.withObject("/attrs").put("start", source.path("attrs").path("start").asInt(1) + offset);
        output.add(part);
    }

    private int byteSize(JsonNode node) { return node.toString().getBytes(StandardCharsets.UTF_8).length; }
    private int nodeCount(JsonNode node) {
        int count = 1;
        for (var child : node.path("content")) count += nodeCount(child);
        return count;
    }

    public Projection project(JsonNode confirmedDocument, List<UUID> excludedReviewNodeIds) {
        var excluded = new HashSet<>(excludedReviewNodeIds == null ? List.of() : excludedReviewNodeIds);
        var paragraphs = new ArrayList<ProjectedNode>();
        collectProjection(validate(confirmedDocument), excluded, new LinkedHashSet<>(), new HeadingState(), paragraphs);
        var text = paragraphs.stream().map(ProjectedNode::text)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return new Projection(text, List.copyOf(paragraphs));
    }

    public List<UUID> uuidList(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        var result = new ArrayList<UUID>();
        value.forEach(item -> {
            try { result.add(UUID.fromString(item.asText())); } catch (IllegalArgumentException ignored) { }
        });
        return List.copyOf(result);
    }

    private int validateNode(JsonNode node, boolean root, Set<UUID> reviewNodeIds, int depth) {
        var type = node.path("type").asText();
        if (depth > 128 || ("documentPart".equals(type) && depth != 1))
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认内容的分段位置或嵌套层级无效");
        if (!ALLOWED_NODES.contains(type) || (root && !"doc".equals(type))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认内容包含不支持的节点：" + type);
        }
        if (node.has("marks")) {
            node.path("marks").forEach(mark -> {
                if (!ALLOWED_MARKS.contains(mark.path("type").asText())) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认内容包含不支持的文本格式");
                }
            });
        }
        if (!root && !"text".equals(type) && node.has("attrs")) {
            var id = node.path("attrs").path("reviewNodeId").asText(null);
            if (id != null) {
                try {
                    if (!reviewNodeIds.add(UUID.fromString(id))) {
                        throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认内容包含重复节点标识");
                    }
                } catch (IllegalArgumentException exception) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认内容包含无效节点标识");
                }
            }
            var origin = node.path("attrs").path("origin").asText("source");
            if (!Set.of("source", "user").contains(origin)) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认内容包含无效来源类型");
            }
        }
        var count = 1;
        if (node.has("content")) {
            if (!node.path("content").isArray()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "节点内容必须是数组");
            for (var child : node.path("content")) count += validateNode(child, false, reviewNodeIds, depth + 1);
        }
        return count;
    }

    private void collectProjection(JsonNode node, Set<UUID> excluded, LinkedHashSet<UUID> inheritedSources,
                                   HeadingState headings, List<ProjectedNode> output) {
        var sources = new LinkedHashSet<>(inheritedSources);
        var attrs = node.path("attrs");
        if (attrs.path("sourceNodeKeys").isArray()) {
            for (var value : attrs.path("sourceNodeKeys")) {
                try { sources.add(UUID.fromString(value.asText())); } catch (IllegalArgumentException ignored) { }
            }
        }
        UUID reviewNodeId = null;
        try { reviewNodeId = UUID.fromString(attrs.path("reviewNodeId").asText()); }
        catch (Exception ignored) { }
        if (reviewNodeId != null && excluded.contains(reviewNodeId)) return;

        var type = node.path("type").asText();
        var attributes = node.path("attrs").isObject()
                ? objectMapper.convertValue(node.path("attrs"), Map.class) : Map.<String, Object>of();
        if ("heading".equals(type)) {
            var headingText = textContent(node).strip();
            var level = Math.min(6, Math.max(1, attrs.path("level").asInt(1)));
            if (!headingText.isBlank()) headings.update(level, headingText);
        }
        var headingPath = headings.path();
        if (Set.of("paragraph", "heading", "blockquote", "codeBlock", "listItem", "formula", "audioSegment", "image").contains(type)) {
            var text = textContent(node).strip();
            if (!text.isBlank()) output.add(new ProjectedNode(reviewNodeId, List.copyOf(sources), text, type,
                    List.copyOf(headingPath), attributes));
            return;
        }
        if ("tableRow".equals(type)) {
            var cells = new ArrayList<String>();
            node.path("content").forEach(cell -> cells.add(textContent(cell).strip()));
            var text = String.join(" | ", cells);
            if (!text.isBlank()) output.add(new ProjectedNode(reviewNodeId, List.copyOf(sources), text, type,
                    List.copyOf(headingPath), attributes));
            return;
        }
        if ("dataTableRef".equals(type)) return; // Streamed separately by the table projector.
        node.path("content").forEach(child -> collectProjection(child, excluded, sources, headings, output));
    }

    private String textContent(JsonNode node) {
        return inlineParser.evidenceText(node);
    }

    private ObjectNode document(ArrayNode content) {
        var value = objectMapper.createObjectNode().put("type", "doc").put("schemaVersion", SCHEMA_VERSION);
        value.set("content", content);
        return (ObjectNode) segment(value);
    }

    private List<ObjectNode> structuredNodes(String sourceType, UUID sourceKey, UUID firstReviewNodeId, String text,
                                             Map<String, Object> attributes, boolean review) {
        var tiptapType = tiptapType(sourceType);
        if (Set.of("image", "audioSegment", "dataTableRef").contains(tiptapType)) {
            return List.of(structuredNode(tiptapType, sourceType, sourceKey, firstReviewNodeId, text, attributes,
                    review, null, true));
        }
        var blocks = inlineParser.parseBlocks(text, "codeBlock".equals(tiptapType), "formula".equals(tiptapType));
        var result = new ArrayList<ObjectNode>();
        var ordinal = 0;
        for (var block : blocks) {
            var reviewNodeId = ordinal++ == 0 ? firstReviewNodeId : UUID.randomUUID();
            var nodeType = block.kind() == MineruInlineSemanticParser.BlockKind.FORMULA ? "formula" : tiptapType;
            result.add(structuredNode(nodeType, sourceType, sourceKey, reviewNodeId, text, attributes, review,
                    block, false));
        }
        return List.copyOf(result);
    }

    private ObjectNode structuredNode(String nodeType, String sourceType, UUID sourceKey, UUID reviewNodeId,
                                      String text, Map<String, Object> attributes, boolean review,
                                      MineruInlineSemanticParser.RichBlock block, boolean inlineOnly) {
        var node = objectMapper.createObjectNode().put("type", nodeType);
        var attrs = objectMapper.createObjectNode();
        if (review) {
            attrs.put("reviewNodeId", reviewNodeId.toString()).put("origin", "source");
            attrs.set("sourceNodeKeys", objectMapper.createArrayNode().add(sourceKey.toString()));
        } else attrs.put("sourceNodeKey", sourceKey.toString());
        if (attributes != null) {
            attributes.forEach((key, value) -> attrs.set(key, objectMapper.valueToTree(value)));
        }
        applyVisualType(sourceType, attrs);
        if ("heading".equals(nodeType) && !attrs.has("level")) attrs.put("level", 2);
        if ("formula".equals(nodeType)) attrs.put("latexRaw", block == null ? text : block.latexRaw());
        node.set("attrs", attrs);
        if (!"dataTableRef".equals(nodeType) && !"formula".equals(nodeType)) {
            node.set("content", inlineOnly ? inlineParser.inlineJson(text, "codeBlock".equals(nodeType))
                    : inlineParser.inlineJson(block.inline()));
        }
        return node;
    }

    private ObjectNode tableRow(UUID sourceKey, UUID reviewNodeId, String text,
                                Map<String, Object> attributes, boolean review) {
        var row = objectMapper.createObjectNode().put("type", "tableRow");
        var attrs = objectMapper.createObjectNode();
        if (review) {
            attrs.put("reviewNodeId", reviewNodeId.toString()).put("origin", "source");
            attrs.set("sourceNodeKeys", objectMapper.createArrayNode().add(sourceKey.toString()));
        } else {
            attrs.put("sourceNodeKey", sourceKey.toString());
        }
        if (attributes != null) {
            attributes.forEach((key, value) -> attrs.set(key, objectMapper.valueToTree(value)));
        }
        row.set("attrs", attrs);
        var cells = objectMapper.createArrayNode();
        var structured = attributes == null ? null : attributes.get("cells");
        if (structured instanceof List<?> values && !values.isEmpty()) {
            for (var value : values) {
                if (!(value instanceof Map<?, ?> cellValue)) continue;
                var content = cellValue.get("text") == null ? "" : cellValue.get("text").toString();
                var header = Boolean.TRUE.equals(cellValue.get("header"));
                cells.add(tableCell(content, header, positiveInt(cellValue.get("rowSpan")),
                        positiveInt(cellValue.get("columnSpan"))));
            }
        }
        if (cells.isEmpty()) {
            for (var value : text.split("\\s*\\|\\s*", -1)) cells.add(tableCell(value, false, 1, 1));
        }
        row.set("content", cells);
        return row;
    }

    private ObjectNode tableCell(String text, boolean header, int rowSpan, int columnSpan) {
        var cell = objectMapper.createObjectNode().put("type", header ? "tableHeader" : "tableCell");
        cell.putObject("attrs").put("colspan", columnSpan).put("rowspan", rowSpan).putNull("colwidth");
        var blocks = inlineParser.parseBlocks(text, false, false);
        var content = objectMapper.createArrayNode();
        for (var block : blocks) {
            if (block.kind() == MineruInlineSemanticParser.BlockKind.FORMULA) {
                var formula = content.addObject().put("type", "formula");
                formula.putObject("attrs").put("latexRaw", block.latexRaw());
            } else {
                var paragraph = content.addObject().put("type", "paragraph");
                paragraph.set("content", inlineParser.inlineJson(block.inline()));
            }
        }
        cell.set("content", content);
        return cell;
    }

    private int positiveInt(Object value) {
        if (value instanceof Number number) return Math.max(1, number.intValue());
        try { return Math.max(1, Integer.parseInt(String.valueOf(value))); }
        catch (RuntimeException ignored) { return 1; }
    }

    private ObjectNode listItem(UUID sourceKey, UUID reviewNodeId, String text, boolean review) {
        var item = objectMapper.createObjectNode().put("type", "listItem");
        var attrs = objectMapper.createObjectNode();
        if (review) {
            attrs.put("reviewNodeId", reviewNodeId.toString()).put("origin", "source");
            attrs.set("sourceNodeKeys", objectMapper.createArrayNode().add(sourceKey.toString()));
        } else {
            attrs.put("sourceNodeKey", sourceKey.toString());
        }
        item.set("attrs", attrs);
        var content = objectMapper.createArrayNode();
        for (var block : inlineParser.parseBlocks(text, false, false)) {
            if (block.kind() == MineruInlineSemanticParser.BlockKind.FORMULA) {
                var formula = content.addObject().put("type", "formula");
                formula.putObject("attrs").put("latexRaw", block.latexRaw());
            } else {
                var paragraph = content.addObject().put("type", "paragraph");
                paragraph.set("content", inlineParser.inlineJson(block.inline()));
            }
        }
        item.set("content", content);
        return item;
    }

    private String nodeType(String section) {
        if (section == null) return "paragraph";
        var value = section.toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("heading") || value.equals("title")) return "heading";
        if (value.contains("data-table-ref")) return "dataTableRef";
        if (value.contains("table") || value.contains("spreadsheet")) return "tableRow";
        if (value.contains("list")) return "listItem";
        if (value.contains("code")) return "codeBlock";
        if (value.contains("quote")) return "blockquote";
        if (value.contains("chart")) return "chart";
        if (value.contains("image")) return "image";
        if (value.contains("formula")) return "formula";
        if (value.contains("audio")) return "audioSegment";
        return "paragraph";
    }

    private String tiptapType(String sourceType) {
        return switch (sourceType) {
            case "chart" -> "image";
            case "heading", "image", "formula", "audioSegment", "dataTableRef", "codeBlock", "blockquote" -> sourceType;
            default -> "paragraph";
        };
    }

    private void applyVisualType(String sourceType, ObjectNode attributes) {
        if (attributes.has("visualType")) return;
        if ("chart".equals(sourceType)) attributes.put("visualType", "CHART");
        else if ("image".equals(sourceType)) attributes.put("visualType", "IMAGE");
    }

    private ObjectNode anchor(DocumentParser.TextBlock block) {
        var result = objectMapper.createObjectNode().put("version", 1);
        var attributes = block.attributes();
        putIfPresent(result, "provider", attributes.get("sourceProvider"));
        putIfPresent(result, "providerCoordinateSpace", attributes.get("providerCoordinateSpace"));
        putIfPresent(result, "coordinateSpace", attributes.get("coordinateSpace"));
        putIfPresent(result, "pageWidth", attributes.get("pageWidth"));
        putIfPresent(result, "pageHeight", attributes.get("pageHeight"));
        putIfPresent(result, "rotation", attributes.get("rotation"));
        putIfPresent(result, "locatorAccuracy", attributes.get("locatorAccuracy"));
        putIfPresent(result, "resultFileId", attributes.get("resultFileId"));
        putIfPresent(result, "resultEntryPath", attributes.get("resultEntryPath"));
        putIfPresent(result, "assetFileId", attributes.get("assetFileId"));
        putIfPresent(result, "caption", attributes.get("caption"));
        putIfPresent(result, "footnote", attributes.get("footnote"));
        putIfPresent(result, "ocrText", attributes.get("ocrText"));
        putIfPresent(result, "visualType", attributes.get("visualType"));
        putIfPresent(result, "searchable", attributes.get("searchable"));
        if (block.sheetName() != null) {
            result.put("kind", "sheet_range").put("sheetKey", block.sheetName())
                    .put("sheetName", block.sheetName()).put("range", block.cellRange());
        } else if (block.startTimeMs() != null) {
            result.put("kind", "time_range").put("startMs", block.startTimeMs());
            if (block.endTimeMs() != null) result.put("endMs", block.endTimeMs());
        } else if (block.paragraphId() != null) {
            result.put("kind", "docx_path").put("paragraphId", block.paragraphId());
        } else if (block.pageNo() != null) {
            result.put("kind", block.bbox().isEmpty() ? "page" : "page_region").put("page", block.pageNo());
            if (!block.bbox().isEmpty()) result.set("polygon", objectMapper.valueToTree(block.bbox()));
        } else {
            result.put("kind", "none");
        }
        return result;
    }

    private void putIfPresent(ObjectNode target, String key, Object value) {
        if (value != null) target.set(key, objectMapper.valueToTree(value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\u0000", "").replaceAll("[\\t\\r]+", " ").strip();
    }

    private DocumentParser.TextBlock copyWithText(DocumentParser.TextBlock block, String text) {
        return new DocumentParser.TextBlock(block.pageNo(), block.section(), text, block.sheetName(), block.cellRange(),
                block.paragraphId(), block.bbox(), block.startTimeMs(), block.endTimeMs(), block.confidence(),
                block.attributes());
    }

    private static final class HeadingState {
        private final ArrayList<String> values = new ArrayList<>();

        void update(int level, String text) {
            while (values.size() >= level) values.removeLast();
            values.add(text);
        }

        List<String> path() { return List.copyOf(values); }
    }

    public record InitialDocuments(JsonNode sourceDocument, JsonNode confirmedDocument,
                                   List<SourceNodeDraft> sourceNodes) { }
    public record SourceNodeDraft(UUID sourceNodeKey, int nodeNo, String nodeType, String rawText,
                                  JsonNode sourceAnchor, JsonNode confidence) { }
    public record Projection(String confirmedText, List<ProjectedNode> nodes) { }
    public record ProjectedNode(UUID reviewNodeId, List<UUID> sourceNodeKeys, String text, String nodeType,
                                List<String> headingPath, Map<String, Object> attributes) {
        public ProjectedNode {
            headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
