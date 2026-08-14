package com.jsd.aird.kb.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Owns the neutral structured-document contract. Provider payloads and editor-specific
 * runtime state must never leak through this boundary.
 */
@Component
public class StructuredDocumentCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NODE_COUNT = 20_000;
    public static final int MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;

    private static final Set<String> ALLOWED_NODES = Set.of(
            "doc", "paragraph", "heading", "text", "bulletList", "orderedList", "listItem",
            "blockquote", "codeBlock", "horizontalRule", "hardBreak", "table", "tableRow",
            "tableHeader", "tableCell", "image", "audioSegment", "formula", "dataTableRef"
    );
    private static final Set<String> ALLOWED_MARKS = Set.of("bold", "italic", "underline", "strike", "code", "link");

    private final ObjectMapper objectMapper;

    public StructuredDocumentCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        for (var block : blocks == null ? List.<DocumentParser.TextBlock>of() : blocks) {
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
                var group = String.valueOf(block.pageNo()) + "|" + String.valueOf(block.sheetName()) + "|" + block.section();
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
                ((ArrayNode) sourceTable.path("content")).add(tableRow(sourceKey, null, text, false));
                ((ArrayNode) reviewTable.path("content")).add(tableRow(sourceKey, reviewNodeId, text, true));
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
                sourceContent.add(sourceNode(type, sourceKey, text, block.attributes()));
                reviewContent.add(reviewNode(type, reviewNodeId, sourceKey, text, block.attributes()));
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
        var count = validateNode(copy, true, new HashSet<>());
        if (count > MAX_NODE_COUNT) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认内容超过 20,000 个节点，请拆分文档");
        }
        if (copy.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认内容超过 5 MiB，请拆分文档");
        }
        return copy;
    }

    public Projection project(JsonNode confirmedDocument, List<UUID> excludedReviewNodeIds) {
        var excluded = new HashSet<>(excludedReviewNodeIds == null ? List.of() : excludedReviewNodeIds);
        var paragraphs = new ArrayList<ProjectedNode>();
        collectProjection(validate(confirmedDocument), excluded, new LinkedHashSet<>(), paragraphs);
        var text = paragraphs.stream().map(ProjectedNode::text)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
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

    private int validateNode(JsonNode node, boolean root, Set<UUID> reviewNodeIds) {
        var type = node.path("type").asText();
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
            for (var child : node.path("content")) count += validateNode(child, false, reviewNodeIds);
        }
        return count;
    }

    private void collectProjection(JsonNode node, Set<UUID> excluded, LinkedHashSet<UUID> inheritedSources,
                                   List<ProjectedNode> output) {
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
        if (Set.of("paragraph", "heading", "blockquote", "codeBlock", "listItem", "formula", "audioSegment").contains(type)) {
            var text = textContent(node).strip();
            if (!text.isBlank()) output.add(new ProjectedNode(reviewNodeId, List.copyOf(sources), text, type));
            return;
        }
        if ("tableRow".equals(type)) {
            var cells = new ArrayList<String>();
            node.path("content").forEach(cell -> cells.add(textContent(cell).strip()));
            var text = String.join(" | ", cells);
            if (!text.isBlank()) output.add(new ProjectedNode(reviewNodeId, List.copyOf(sources), text, type));
            return;
        }
        if ("dataTableRef".equals(type)) return; // Streamed separately by the table projector.
        node.path("content").forEach(child -> collectProjection(child, excluded, sources, output));
    }

    private String textContent(JsonNode node) {
        if ("text".equals(node.path("type").asText())) return node.path("text").asText("");
        var value = new StringBuilder();
        node.path("content").forEach(child -> value.append(textContent(child)));
        return value.toString();
    }

    private ObjectNode document(ArrayNode content) {
        return objectMapper.createObjectNode().put("type", "doc").put("schemaVersion", SCHEMA_VERSION)
                .set("content", content);
    }

    private ObjectNode sourceNode(String type, UUID sourceKey, String text, java.util.Map<String, Object> attributes) {
        var node = objectMapper.createObjectNode().put("type", tiptapType(type));
        var attrs = objectMapper.createObjectNode().put("sourceNodeKey", sourceKey.toString());
        attributes.forEach((key, value) -> attrs.set(key, objectMapper.valueToTree(value)));
        node.set("attrs", attrs);
        if (!"dataTableRef".equals(tiptapType(type))) node.set("content", textContent(text));
        return node;
    }

    private ObjectNode reviewNode(String type, UUID reviewNodeId, UUID sourceKey, String text,
                                  java.util.Map<String, Object> attributes) {
        var node = objectMapper.createObjectNode().put("type", tiptapType(type));
        var attrs = objectMapper.createObjectNode().put("reviewNodeId", reviewNodeId.toString()).put("origin", "source");
        attrs.set("sourceNodeKeys", objectMapper.createArrayNode().add(sourceKey.toString()));
        attributes.forEach((key, value) -> attrs.set(key, objectMapper.valueToTree(value)));
        if ("heading".equals(tiptapType(type)) && !attrs.has("level")) attrs.put("level", 2);
        node.set("attrs", attrs);
        if (!"dataTableRef".equals(tiptapType(type))) node.set("content", textContent(text));
        return node;
    }

    private ObjectNode tableRow(UUID sourceKey, UUID reviewNodeId, String text, boolean review) {
        var row = objectMapper.createObjectNode().put("type", "tableRow");
        var attrs = objectMapper.createObjectNode();
        if (review) {
            attrs.put("reviewNodeId", reviewNodeId.toString()).put("origin", "source");
            attrs.set("sourceNodeKeys", objectMapper.createArrayNode().add(sourceKey.toString()));
        } else {
            attrs.put("sourceNodeKey", sourceKey.toString());
        }
        row.set("attrs", attrs);
        var cells = objectMapper.createArrayNode();
        for (var value : text.split("\\s*\\|\\s*", -1)) {
            var cell = objectMapper.createObjectNode().put("type", "tableCell");
            var paragraph = objectMapper.createObjectNode().put("type", "paragraph");
            paragraph.set("content", textContent(value));
            cell.set("content", objectMapper.createArrayNode().add(paragraph));
            cells.add(cell);
        }
        row.set("content", cells);
        return row;
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
        var paragraph = objectMapper.createObjectNode().put("type", "paragraph");
        paragraph.set("content", textContent(text));
        item.set("content", objectMapper.createArrayNode().add(paragraph));
        return item;
    }

    private ArrayNode textContent(String text) {
        var content = objectMapper.createArrayNode();
        if (text != null && !text.isEmpty()) content.add(objectMapper.createObjectNode().put("type", "text").put("text", text));
        return content;
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
        if (value.contains("image")) return "image";
        if (value.contains("formula")) return "formula";
        if (value.contains("audio")) return "audioSegment";
        return "paragraph";
    }

    private String tiptapType(String sourceType) {
        return switch (sourceType) {
            case "heading", "image", "formula", "audioSegment", "dataTableRef", "codeBlock", "blockquote" -> sourceType;
            default -> "paragraph";
        };
    }

    private ObjectNode anchor(DocumentParser.TextBlock block) {
        var result = objectMapper.createObjectNode().put("version", 1);
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

    private String normalize(String value) {
        return value == null ? "" : value.replace("\u0000", "").replaceAll("[\\t\\r]+", " ").strip();
    }

    public record InitialDocuments(JsonNode sourceDocument, JsonNode confirmedDocument,
                                   List<SourceNodeDraft> sourceNodes) { }
    public record SourceNodeDraft(UUID sourceNodeKey, int nodeNo, String nodeType, String rawText,
                                  JsonNode sourceAnchor, JsonNode confidence) { }
    public record Projection(String confirmedText, List<ProjectedNode> nodes) { }
    public record ProjectedNode(UUID reviewNodeId, List<UUID> sourceNodeKeys, String text, String nodeType) { }
}
