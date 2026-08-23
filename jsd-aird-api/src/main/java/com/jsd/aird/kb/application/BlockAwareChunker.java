package com.jsd.aird.kb.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Deterministic section-aware chunker that preserves every evidence anchor. */
@Component
public final class BlockAwareChunker {

    public static final int TARGET_TOKENS = 700;
    public static final int MAX_TOKENS = 1_200;
    public static final int OVERLAP_TOKENS = 100;
    public static final int HARD_LIMIT_TOKENS = 8_192;

    private static final Set<String> FIELD_TERMS = Set.of(
            "型号", "产品编号", "批号", "名称", "材料", "成分", "粘度", "黏度", "固含", "固体含量", "官能度",
            "温度", "固化温度", "固化能量", "波长", "拉伸强度", "断裂伸长率", "附着力", "密度", "用途", "颜色"
    );

    private final ObjectMapper mapper;

    public BlockAwareChunker(ObjectMapper mapper) { this.mapper = mapper; }

    public List<ChunkDraft> chunk(String documentTitle, List<StructuredDocumentCodec.ProjectedNode> projected,
                                  List<KnowledgeGovernanceRepository.SourceNodeView> sourceNodes,
                                  List<KnowledgeGovernanceRepository.LargeTableRow> largeTableRows) {
        var sourceByKey = new LinkedHashMap<UUID, KnowledgeGovernanceRepository.SourceNodeView>();
        safe(sourceNodes).forEach(value -> sourceByKey.put(value.sourceNodeKey(), value));
        var pieces = createPieces(projected, sourceByKey);
        safe(largeTableRows).forEach(row -> pieces.add(largeTablePiece(row)));
        if (pieces.isEmpty()) return List.of();

        var bySection = new LinkedHashMap<List<String>, List<Piece>>();
        for (var piece : pieces) bySection.computeIfAbsent(piece.headingPath(), ignored -> new ArrayList<>()).add(piece);
        var drafts = new ArrayList<ChunkDraft>();
        var parentOrdinal = 0;
        for (var section : bySection.entrySet()) {
            var searchable = section.getValue().stream().filter(Piece::searchable).toList();
            if (searchable.isEmpty()) continue;
            var parentKey = stableKey("parent", documentTitle, section.getKey(), parentOrdinal++);
            var fullContext = sectionContext(documentTitle, section.getKey(), section.getValue());
            drafts.add(draft(parentKey, null, "PARENT", documentTitle, section.getKey(), fullContext,
                    section.getValue(), List.of(), null));
            var tableGroups = new LinkedHashMap<String, List<Piece>>();
            var prose = new ArrayList<Piece>();
            for (var piece : searchable) {
                if (piece.tableGroup() == null) prose.add(piece);
                else tableGroups.computeIfAbsent(piece.tableGroup(), ignored -> new ArrayList<>()).add(piece);
            }
            var childOrdinal = 0;
            var contextTokens = estimateTokens(childPrefix(documentTitle, section.getKey(), prose));
            for (var batch : aggregate(prose, contextTokens)) {
                var content = childContent(documentTitle, section.getKey(), batch);
                drafts.add(draft(stableKey("child", documentTitle, section.getKey(), childOrdinal++), parentKey,
                        "CHILD", documentTitle, section.getKey(), content, batch, relations(batch), primary(batch)));
            }
            for (var table : tableGroups.values()) {
                for (var batch : tableBatches(documentTitle, section.getKey(), table)) {
                    var content = childContent(documentTitle, section.getKey(), batch);
                    drafts.add(draft(stableKey("child", documentTitle, section.getKey(), childOrdinal++), parentKey,
                            "CHILD", documentTitle, section.getKey(), content, batch, relations(batch), primary(batch)));
                }
            }
        }
        return List.copyOf(drafts);
    }

    private ArrayList<Piece> createPieces(List<StructuredDocumentCodec.ProjectedNode> nodes,
                                          Map<UUID, KnowledgeGovernanceRepository.SourceNodeView> sourceByKey) {
        var initial = new ArrayList<Piece>();
        for (var node : safe(nodes)) {
            if (!StringUtils.hasText(node.text()) || "heading".equals(node.nodeType())) continue;
            var searchable = !Boolean.FALSE.equals(node.attributes().get("searchable"));
            var anchors = node.sourceNodeKeys().stream().map(sourceByKey::get).filter(Objects::nonNull)
                    .map(KnowledgeGovernanceRepository.SourceNodeView::sourceAnchor).filter(Objects::nonNull).toList();
            var page = pages(anchors);
            var tableGroup = nullableString(node.attributes().get("tableGroup"));
            var tableRow = integer(node.attributes().get("tableRowIndex"));
            initial.add(new Piece(node.text().strip(), node.nodeType(), node.headingPath(), node.reviewNodeId() == null
                    ? List.of() : List.of(node.reviewNodeId()), node.sourceNodeKeys(), anchors, page.first(), page.last(),
                    tableGroup, tableRow, node.attributes(), searchable, List.of()));
        }
        return linkFieldValues(initial);
    }

    private ArrayList<Piece> linkFieldValues(List<Piece> source) {
        var result = new ArrayList<Piece>();
        for (var index = 0; index < source.size(); index++) {
            var field = source.get(index);
            if (index + 1 >= source.size() || field.tableGroup() != null || !isField(field.text())) {
                result.add(field);
                continue;
            }
            var value = source.get(index + 1);
            var confidence = spatialConfidence(field, value);
            if (confidence <= 0) {
                result.add(field);
                continue;
            }
            var relation = relation("FIELD_VALUE", field, value, confidence);
            result.add(merge(field, value, field.text().replaceAll("[：:]$", "") + "：" + value.text(), relation));
            index++;
        }
        return result;
    }

    private double spatialConfidence(Piece field, Piece value) {
        if (field.firstPage() == null || !Objects.equals(field.firstPage(), value.firstPage())) return 0;
        var left = polygon(field.anchors());
        var right = polygon(value.anchors());
        if (left.size() < 8 || right.size() < 8) return 0;
        var leftMidY = (left.get(1) + left.get(5)) / 2;
        var rightMidY = (right.get(1) + right.get(5)) / 2;
        var lineAligned = Math.abs(leftMidY - rightMidY) <= 0.035 && right.get(0) >= left.get(2) - 0.01;
        var vertical = right.get(1) >= left.get(5) - 0.01 && right.get(1) - left.get(5) <= 0.05;
        return lineAligned ? 0.92 : vertical ? 0.78 : 0;
    }

    private Piece merge(Piece field, Piece value, String text, JsonNode relation) {
        return new Piece(text, "fieldValue", field.headingPath(), union(field.reviewNodeIds(), value.reviewNodeIds()),
                union(field.sourceNodeKeys(), value.sourceNodeKeys()), unionJson(field.anchors(), value.anchors()),
                field.firstPage(), value.lastPage(), null, null, Map.of(), true, List.of(relation));
    }

    private Piece largeTablePiece(KnowledgeGovernanceRepository.LargeTableRow row) {
        var anchor = mapper.createObjectNode().put("version", 1).put("kind", "sheet_range")
                .put("sheetName", row.sheetName()).put("range", row.cellRange());
        return new Piece(row.projectedText(), "tableRow", List.of(row.sheetName()), List.of(), List.of(),
                List.of(anchor), null, null, "large-table:" + row.sourceTableId(), row.rowNo(),
                Map.of("tableRowIndex", row.rowNo()), true, List.of());
    }

    private List<List<Piece>> aggregate(List<Piece> source, int contextTokens) {
        var expanded = new ArrayList<Piece>();
        for (var piece : source) expanded.addAll(splitLong(piece));
        var targetTokens = Math.max(1, TARGET_TOKENS - contextTokens);
        var maximumTokens = Math.max(1, MAX_TOKENS - contextTokens);
        var batches = new ArrayList<List<Piece>>();
        var current = new ArrayList<Piece>();
        var currentTokens = 0;
        var hasNewContent = false;
        for (var piece : expanded) {
            var tokens = estimateTokens(piece.text());
            var discontinuous = !current.isEmpty() && !continuous(current.getLast(), piece);
            if (!current.isEmpty() && (discontinuous || currentTokens + tokens > maximumTokens)) {
                batches.add(List.copyOf(current));
                current = overlap(current);
                currentTokens = current.stream().mapToInt(value -> estimateTokens(value.text())).sum();
                hasNewContent = false;
                if (discontinuous) { current.clear(); currentTokens = 0; }
            }
            current.add(piece);
            currentTokens += tokens;
            hasNewContent = true;
            if (currentTokens >= targetTokens) {
                batches.add(List.copyOf(current));
                current = overlap(current);
                currentTokens = current.stream().mapToInt(value -> estimateTokens(value.text())).sum();
                hasNewContent = false;
            }
        }
        if (!current.isEmpty() && (batches.isEmpty() || hasNewContent)) batches.add(List.copyOf(current));
        return batches;
    }

    private List<List<Piece>> tableBatches(String documentTitle, List<String> headingPath, List<Piece> source) {
        var sorted = source.stream().sorted(Comparator.comparing(value -> value.tableRow() == null ? 0 : value.tableRow())).toList();
        if (sorted.isEmpty()) return List.of();
        var header = sorted.getFirst().tableRow() != null && sorted.getFirst().tableRow() == 0 ? sorted.getFirst() : null;
        var title = string(sorted.getFirst().attributes().get("tableCaption"));
        var headerCells = header == null ? List.<String>of() : cells(header.attributes());
        var normalized = new ArrayList<Piece>();
        for (var row : sorted) {
            if (row == header) continue;
            var values = cells(row.attributes());
            var text = tableRowText(headerCells, values, row.text());
            var relations = tableRelations(header, row, headerCells, values);
            normalized.add(new Piece(text, row.nodeType(), row.headingPath(), row.reviewNodeIds(), row.sourceNodeKeys(),
                    row.anchors(), row.firstPage(), row.lastPage(), row.tableGroup(), row.tableRow(), row.attributes(),
                    row.searchable(), relations));
        }
        if (normalized.isEmpty() && header != null) normalized.add(header);
        var result = new ArrayList<List<Piece>>();
        var current = new ArrayList<Piece>();
        var prefixTokens = estimateTokens(childPrefix(documentTitle, headingPath, source))
                + estimateTokens(joinNonBlank(title, header == null ? "" : header.text()));
        var tokenCount = prefixTokens;
        for (var row : normalized) {
            var rowTokens = estimateTokens(row.text());
            enforceHardLimit(row.text(), row.nodeType());
            if (!current.isEmpty() && tokenCount + rowTokens > MAX_TOKENS) {
                result.add(withTablePrefix(title, header, current));
                current = new ArrayList<>();
                tokenCount = prefixTokens;
            }
            current.add(row);
            tokenCount += rowTokens;
        }
        if (!current.isEmpty()) result.add(withTablePrefix(title, header, current));
        return result;
    }

    private List<Piece> withTablePrefix(String title, Piece header, List<Piece> rows) {
        var result = new ArrayList<Piece>();
        if (!title.isBlank()) {
            var evidence = header == null ? rows.getFirst() : header;
            result.add(new Piece("表格标题：" + title, "tableCaption", evidence.headingPath(), evidence.reviewNodeIds(),
                    evidence.sourceNodeKeys(), evidence.anchors(), evidence.firstPage(), evidence.lastPage(),
                    evidence.tableGroup(), -2, Map.of(), true, List.of()));
        }
        if (header != null) result.add(new Piece("表头：" + header.text(), header.nodeType(), header.headingPath(),
                header.reviewNodeIds(), header.sourceNodeKeys(), header.anchors(), header.firstPage(), header.lastPage(),
                header.tableGroup(), -1, header.attributes(), true, List.of()));
        result.addAll(rows);
        return List.copyOf(result);
    }

    private List<JsonNode> tableRelations(Piece header, Piece row, List<String> fields, List<String> values) {
        if (header == null || fields.isEmpty() || values.isEmpty()) return List.of();
        var result = new ArrayList<JsonNode>();
        for (var index = 0; index < Math.min(fields.size(), values.size()); index++) {
            if (fields.get(index).isBlank() || values.get(index).isBlank()) continue;
            var relation = relation("TABLE_FIELD_VALUE", header, row, 1.0);
            ((ObjectNode) relation).put("field", fields.get(index)).put("value", values.get(index));
            result.add(relation);
        }
        return List.copyOf(result);
    }

    private String tableRowText(List<String> fields, List<String> values, String fallback) {
        if (fields.isEmpty() || values.isEmpty()) return fallback;
        var pairs = new ArrayList<String>();
        for (var index = 0; index < Math.min(fields.size(), values.size()); index++) {
            if (!fields.get(index).isBlank() && !values.get(index).isBlank()) {
                pairs.add(fields.get(index).replaceAll("[：:]$", "") + "：" + values.get(index));
            }
        }
        return pairs.isEmpty() ? fallback : String.join("；", pairs);
    }

    private List<Piece> splitLong(Piece piece) {
        var tokens = estimateTokens(piece.text());
        if (tokens <= MAX_TOKENS) return List.of(piece);
        if ("codeBlock".equals(piece.nodeType()) || !hasSentenceBoundary(piece.text())) {
            enforceHardLimit(piece.text(), piece.nodeType());
            return List.of(piece);
        }
        var result = new ArrayList<Piece>();
        var sentences = piece.text().split("(?<=[。！？；.!?;\\n])");
        var current = new StringBuilder();
        for (var sentence : sentences) {
            if (!current.isEmpty() && estimateTokens(current + sentence) > MAX_TOKENS) {
                result.add(withText(piece, current.toString().strip()));
                current.setLength(0);
            }
            enforceHardLimit(sentence, piece.nodeType());
            current.append(sentence);
        }
        if (!current.isEmpty()) result.add(withText(piece, current.toString().strip()));
        return result;
    }

    private void enforceHardLimit(String text, String type) {
        var tokens = estimateTokens(text);
        if (tokens > HARD_LIMIT_TOKENS) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                    "发布失败：" + type + " 单个结构块约 " + tokens + " Token，超过 8192 硬上限，请拆分后重试");
        }
    }

    private ArrayList<Piece> overlap(List<Piece> current) {
        var result = new ArrayList<Piece>();
        var tokens = 0;
        for (var index = current.size() - 1; index >= 0 && tokens < OVERLAP_TOKENS; index--) {
            var piece = current.get(index);
            var pieceTokens = estimateTokens(piece.text());
            if (tokens + pieceTokens <= OVERLAP_TOKENS) {
                result.addFirst(piece);
                tokens += pieceTokens;
                continue;
            }
            var tail = tokenTail(piece.text(), OVERLAP_TOKENS - tokens);
            if (!tail.isBlank()) result.addFirst(withText(piece, tail));
            break;
        }
        return result;
    }

    private String tokenTail(String text, int budget) {
        if (budget <= 0 || text == null || text.isBlank()) return "";
        var codePoints = text.codePointCount(0, text.length());
        var low = 0;
        var high = codePoints;
        while (low < high) {
            var middle = (low + high) >>> 1;
            var offset = text.offsetByCodePoints(0, middle);
            if (estimateTokens(text.substring(offset)) <= budget) high = middle;
            else low = middle + 1;
        }
        return text.substring(text.offsetByCodePoints(0, low)).stripLeading();
    }

    private boolean continuous(Piece left, Piece right) {
        if (left.lastPage() == null || right.firstPage() == null) return true;
        return right.firstPage() - left.lastPage() <= 1;
    }

    private ChunkDraft draft(String key, String parentKey, String role, String title, List<String> headingPath,
                             String content, List<Piece> pieces, List<JsonNode> relations, JsonNode primary) {
        var reviews = new LinkedHashSet<UUID>();
        var sources = new LinkedHashSet<UUID>();
        var anchors = new ArrayList<JsonNode>();
        Integer firstPage = null;
        Integer lastPage = null;
        var types = new LinkedHashSet<String>();
        for (var piece : pieces) {
            reviews.addAll(piece.reviewNodeIds());
            sources.addAll(piece.sourceNodeKeys());
            for (var anchor : piece.anchors()) if (anchors.stream().noneMatch(anchor::equals)) anchors.add(anchor);
            if (piece.firstPage() != null) firstPage = firstPage == null ? piece.firstPage() : Math.min(firstPage, piece.firstPage());
            if (piece.lastPage() != null) lastPage = lastPage == null ? piece.lastPage() : Math.max(lastPage, piece.lastPage());
            types.add(piece.nodeType());
        }
        return new ChunkDraft(key, parentKey, role, title, headingPath, content, List.copyOf(reviews),
                List.copyOf(sources), primary, List.copyOf(anchors), List.copyOf(relations), firstPage, lastPage,
                String.join(",", types), estimateTokens(content));
    }

    private String sectionContext(String title, List<String> path, List<Piece> pieces) {
        return childContent(title, path, pieces);
    }

    private String childContent(String title, List<String> path, List<Piece> pieces) {
        return childPrefix(title, path, pieces) + "\n\n" + pieces.stream().map(Piece::text)
                .filter(StringUtils::hasText).reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private String childPrefix(String title, List<String> path, List<Piece> pieces) {
        var pages = pageRange(pieces);
        var types = pieces.stream().map(Piece::nodeType).distinct().reduce((left, right) -> left + "," + right).orElse("");
        var prefix = new ArrayList<String>();
        prefix.add("文档：" + title);
        if (!path.isEmpty()) prefix.add("章节：" + String.join(" > ", path));
        if (!pages.isBlank()) prefix.add("页码：" + pages);
        if (!types.isBlank()) prefix.add("来源类型：" + types);
        return String.join("\n", prefix);
    }

    private String pageRange(List<Piece> pieces) {
        var first = pieces.stream().map(Piece::firstPage).filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
        var last = pieces.stream().map(Piece::lastPage).filter(Objects::nonNull).max(Integer::compareTo).orElse(null);
        if (first == null) return "";
        return Objects.equals(first, last) ? String.valueOf(first) : first + "-" + last;
    }

    private JsonNode primary(List<Piece> pieces) {
        return pieces.stream().flatMap(value -> value.anchors().stream()).findFirst().orElse(null);
    }

    private List<JsonNode> relations(List<Piece> pieces) {
        return pieces.stream().flatMap(value -> value.relations().stream()).distinct().toList();
    }

    private JsonNode relation(String type, Piece field, Piece value, double confidence) {
        var result = mapper.createObjectNode().put("type", type).put("confidence", confidence);
        result.set("fieldEvidenceIds", mapper.valueToTree(field.sourceNodeKeys()));
        result.set("valueEvidenceIds", mapper.valueToTree(value.sourceNodeKeys()));
        result.set("fieldAnchors", mapper.valueToTree(field.anchors()));
        result.set("valueAnchors", mapper.valueToTree(value.anchors()));
        return result;
    }

    private PageRange pages(List<JsonNode> anchors) {
        var values = anchors.stream().filter(anchor -> anchor.has("page"))
                .map(anchor -> anchor.path("page").asInt()).toList();
        return values.isEmpty() ? new PageRange(null, null)
                : new PageRange(values.stream().min(Integer::compareTo).orElse(null),
                values.stream().max(Integer::compareTo).orElse(null));
    }

    private List<Double> polygon(List<JsonNode> anchors) {
        for (var anchor : anchors) {
            var value = anchor.path("polygon");
            if (!value.isArray()) continue;
            var result = new ArrayList<Double>();
            value.forEach(item -> {
                if (item.isNumber()) result.add(item.asDouble());
                else if (item.isArray()) item.forEach(number -> result.add(number.asDouble()));
            });
            if (!result.isEmpty()) return result;
        }
        return List.of();
    }

    private List<String> cells(Map<String, Object> attributes) {
        var value = attributes.get("cells");
        if (!(value instanceof List<?> values)) return List.of();
        var result = new ArrayList<String>();
        for (var item : values) {
            if (item instanceof Map<?, ?> cell) result.add(string(cell.get("text")));
            else result.add(string(item));
        }
        return List.copyOf(result);
    }

    private boolean isField(String value) {
        var normalized = value == null ? "" : value.strip().replaceAll("[：:]$", "");
        return FIELD_TERMS.contains(normalized);
    }

    private boolean hasSentenceBoundary(String value) { return value.matches("(?s).*[。！？；.!?;\\n].*"); }

    public static int estimateTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        var han = value.codePoints().filter(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN).count();
        var nonHanWords = java.util.Arrays.stream(value.replaceAll("[\\p{IsHan}]", " ").split("\\s+"))
                .filter(word -> !word.isBlank()).mapToInt(word -> Math.max(1, (word.length() + 3) / 4)).sum();
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, han + nonHanWords));
    }

    private String stableKey(String kind, String title, List<String> path, int ordinal) {
        var source = kind + "\n" + title + "\n" + String.join("\n", path) + "\n" + ordinal;
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return kind + ":" + java.util.HexFormat.of().formatHex(hash, 0, 12);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String joinNonBlank(String... values) {
        return java.util.Arrays.stream(values).filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value).strip(); }
    private String nullableString(Object value) {
        var result = string(value);
        return result.isBlank() ? null : result;
    }
    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
    private Piece withText(Piece source, String text) {
        return new Piece(text, source.nodeType(), source.headingPath(), source.reviewNodeIds(), source.sourceNodeKeys(),
                source.anchors(), source.firstPage(), source.lastPage(), source.tableGroup(), source.tableRow(),
                source.attributes(), source.searchable(), source.relations());
    }
    private <T> List<T> union(List<T> left, List<T> right) {
        var result = new LinkedHashSet<T>(left); result.addAll(right); return List.copyOf(result);
    }
    private List<JsonNode> unionJson(List<JsonNode> left, List<JsonNode> right) {
        var result = new ArrayList<JsonNode>(left);
        for (var value : right) if (result.stream().noneMatch(value::equals)) result.add(value);
        return List.copyOf(result);
    }
    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    public record ChunkDraft(String chunkKey, String parentKey, String role, String documentTitle,
                             List<String> headingPath, String content, List<UUID> reviewNodeIds,
                             List<UUID> sourceNodeKeys, JsonNode primaryAnchor, List<JsonNode> anchors,
                             List<JsonNode> relations, Integer firstPage, Integer lastPage,
                             String sourceTypes, int modelTokenLength) { }

    private record Piece(String text, String nodeType, List<String> headingPath, List<UUID> reviewNodeIds,
                         List<UUID> sourceNodeKeys, List<JsonNode> anchors, Integer firstPage, Integer lastPage,
                         String tableGroup, Integer tableRow, Map<String, Object> attributes, boolean searchable,
                         List<JsonNode> relations) { }
    private record PageRange(Integer first, Integer last) { }
}
