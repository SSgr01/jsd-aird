package com.jsd.aird.tpl.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Strict protocol-envelope validator with isolated recovery for invalid semantic items. */
final class GlobalSemanticRecognitionProtocol {

    static final int VERSION = 1;
    static final String PROMPT_VERSION = "template-global-semantic-v6-record-cadence";

    private static final Pattern ADDRESS = Pattern.compile(
            "^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$"
    );
    private static final Pattern INLINE_KEY_VALUE = Pattern.compile(
            "^\\s*([^：:\\r\\n]{1,40})\\s*[：:]\\s*(\\S[\\s\\S]*)$"
    );
    private static final Set<String> ANNOTATION_ROLES = Set.of(
            "DOCUMENT_TITLE", "INLINE_METADATA", "FIELD_LABEL", "FIELD_VALUE",
            "TABLE_HEADER", "TABLE_DATA", "TABLE_TOTAL", "INSTRUCTION", "CONFIRMATION",
            "NOTE", "LOOKUP_DATA", "STATIC_REFERENCE", "UNKNOWN"
    );
    private static final Set<String> BLOCK_TYPES = Set.of(
            "DOCUMENT_HEADER", "FORM_FIELDS", "ROW_TABLE", "MATRIX", "INSTRUCTION_LIST",
            "CONFIRMATION_BLOCK", "NOTE_BLOCK", "LOOKUP_TABLE",
            "COLUMN_TABLE", "FREE_TEXT", "STATIC_REFERENCE", "UNKNOWN"
    );
    private static final Set<String> RELATION_TYPES = Set.of("LABEL_VALUE", "INLINE_TEXT");
    private static final Set<String> VALUE_TYPES = SemanticProtocolTypes.VALUE_TYPES;
    private static final Set<String> EDITABILITY = SemanticProtocolTypes.EDITABILITY;
    private static final Set<String> VALUE_SOURCES = SemanticProtocolTypes.VALUE_SOURCES;
    private static final Set<String> TABLE_KINDS = Set.of("ROW_TABLE", "COLUMN_TABLE", "MATRIX");
    private static final Set<String> TABLE_SEMANTIC_MODES = Set.of(
            "ROW_RECORDS", "COLUMN_RECORDS", "CROSS_TAB", "RECORD_SET", "UNKNOWN"
    );
    private static final Set<String> REPEAT_AXES = Set.of("ROW", "COLUMN");
    private static final Set<String> TERMINATION_TYPES = Set.of(
            "UNTIL_TOTAL_ROW", "UNTIL_EMPTY_RECORD", "UNTIL_REGION_END",
            "UNTIL_LABEL", "FIXED_COUNT"
    );
    private static final Set<String> ISSUE_CATEGORIES = Set.of(
            "FIELD_RELATION_UNCLEAR", "BUSINESS_BLOCK_UNCLEAR", "TABLE_STRUCTURE_UNCLEAR",
            "EDITABILITY_UNCLEAR", "LAYOUT_INCONSISTENT", "DUPLICATE_MEANING", "OTHER"
    );
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "BLOCKER");
    private static final Set<String> STATIC_BLOCK_TYPES = Set.of(
            "DOCUMENT_HEADER", "INSTRUCTION_LIST", "NOTE_BLOCK", "LOOKUP_TABLE", "STATIC_REFERENCE"
    );

    private final ObjectMapper objectMapper;

    GlobalSemanticRecognitionProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode responseSchema() {
        try {
            var schema = objectMapper.readTree(SCHEMA);
            // Layout metadata is backend-derived. Older providers may still send
            // these fields, but they are intentionally not required from the
            // model and are normalized only for backward compatibility.
            var table = (ObjectNode) schema.path("$defs").path("table");
            var required = table.withArray("required");
            required.removeAll();
            table.path("properties").fieldNames().forEachRemaining(required::add);
            var terminationRule = (ObjectNode) schema.path("$defs").path("table")
                    .path("properties").path("terminationRule");
            var terminationRequired = terminationRule.withArray("required");
            if (!terminationRequired.toString().contains("\"type\"")) {
                terminationRequired.add("type");
            }
            // Do not add recordProjection, slots, bindings or measurement sizes
            // to the required list. They are computed by CanonicalMatrixCompiler.
            return schema;
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载全局语义识别协议", exception);
        }
    }

    ObjectNode validate(JsonNode response, JsonNode physicalFacts) {
        return validateWithDiagnostics(response, physicalFacts).response();
    }

    ValidationResult validateWithDiagnostics(JsonNode response, JsonNode physicalFacts) {
        requireObject(response, "响应根节点");
        var candidate = (ObjectNode) response.deepCopy();
        exactKeys(candidate, "响应根节点", Set.of(
                "recognitionProtocolVersion", "semanticAnnotations", "businessBlocks",
                "fieldRelations", "tables", "qualityIssues"
        ));
        require(candidate.path("recognitionProtocolVersion").isIntegralNumber()
                        && candidate.path("recognitionProtocolVersion").asInt() == VERSION,
                "recognitionProtocolVersion 必须为 1");
        for (var key : List.of("semanticAnnotations", "businessBlocks", "fieldRelations", "tables", "qualityIssues")) {
            require(candidate.path(key).isArray(), key + " 必须是数组");
        }

        var sheets = sheets(physicalFacts);
        var blockIds = uniqueIds(candidate.path("businessBlocks"), "businessBlocks");
        var recoveredBlocks = new RecoverySummary();
        normalizeOverlappingBlocks(candidate, recoveredBlocks);
        blockIds = uniqueIds(candidate.path("businessBlocks"), "businessBlocks");
        var blocks = validateBlocks(candidate.path("businessBlocks"), sheets, blockIds);
        repairUniquelyContainedRelationBlocks(candidate, blockIds);
        repairUniquelyContainedTableBlocks(candidate, blockIds);

        var normalized = new RecoverySummary();
        normalizeOwnedBlockRanges(candidate, physicalFacts, blocks, normalized);
        normalizeRelations(candidate.path("fieldRelations"), physicalFacts, normalized);
        normalizeTables(candidate.path("tables"), normalized);

        var rejectedRelations = new RecoverySummary();
        var rejectedRelationCandidates = objectMapper.createArrayNode();
        var validRelations = validRelations(
                candidate.path("fieldRelations"), physicalFacts, sheets, blockIds, blocks,
                rejectedRelations, rejectedRelationCandidates
        );
        candidate.set("fieldRelations", validRelations);
        var relationIds = uniqueIds(validRelations, "fieldRelations");

        var rejectedTables = new RecoverySummary();
        var rejectedTableCandidates = objectMapper.createArrayNode();
        var validTables = validTables(candidate.path("tables"), sheets, blockIds, blocks,
                rejectedTables, rejectedTableCandidates);
        candidate.set("tables", validTables);
        var tableIds = uniqueIds(validTables, "tables");

        // These are internal recovery channels. They are added only after the strict
        // protocol envelope has been validated and are consumed by the compiler so a
        // candidate is never silently lost from the review surface.
        candidate.set("_rejectedRelations", rejectedRelationCandidates);
        candidate.set("_rejectedTables", rejectedTableCandidates);

        var validAnnotations = validAnnotations(
                candidate.path("semanticAnnotations"), sheets, blockIds, relationIds, tableIds
        );
        candidate.set("semanticAnnotations", validAnnotations);

        var validIssues = validQualityIssues(candidate.path("qualityIssues"), sheets, blockIds);
        appendRecoveryIssue(validIssues, rejectedRelations, "FIELD_RELATION_UNCLEAR", "部分字段关系需要核对",
                "系统已忽略不符合字段关系约束的候选，其他有效字段和表格仍然保留。",
                "请核对被忽略区域是否包含需要填写的业务字段。", sheets, blockIds);
        appendRecoveryIssue(validIssues, rejectedTables, "TABLE_STRUCTURE_UNCLEAR", "部分表格结构需要核对",
                "系统已忽略结构范围不一致的表格候选，其他有效字段和表格仍然保留。",
                "请核对该区域的表头、数据区和合计范围。", sheets, blockIds);
        appendRecoveryIssue(validIssues, recoveredBlocks, "BUSINESS_BLOCK_UNCLEAR", "部分业务区域需要核对",
                "系统已按较大业务区域恢复可识别内容，无法确定归属的内容未写入正式字段。",
                "请在识别确认中补充未识别字段。", sheets, blockIds);
        appendNormalizationIssue(validIssues, normalized, sheets, blockIds);
        candidate.set("qualityIssues", validIssues);
        return new ValidationResult(candidate, new RecoveryDiagnostics(
                candidate.path("fieldRelations").size() + rejectedRelations.count,
                candidate.path("fieldRelations").size(), rejectedRelations.count,
                candidate.path("tables").size() + rejectedTables.count,
                candidate.path("tables").size(), rejectedTables.count,
                recoveredBlocks.count
        ));
    }

    private void normalizeOverlappingBlocks(ObjectNode response, RecoverySummary recovery) {
        var blocks = response.withArray("businessBlocks");
        var removed = new HashMap<String, String>();
        boolean changed;
        do {
            changed = false;
            for (int left = 0; left < blocks.size() && !changed; left++) {
                var first = blocks.get(left);
                var firstRange = bounds(first.path("range").asText(""));
                if (firstRange == null) continue;
                for (int right = left + 1; right < blocks.size(); right++) {
                    var second = blocks.get(right);
                    if (!first.path("sheetId").asText().equals(second.path("sheetId").asText())
                            || !first.path("parentTemporaryId").asText("")
                            .equals(second.path("parentTemporaryId").asText(""))) continue;
                    var secondRange = bounds(second.path("range").asText(""));
                    if (secondRange == null || !firstRange.overlaps(secondRange)
                            || firstRange.contains(secondRange) || secondRange.contains(firstRange)) continue;
                    var keep = firstRange.area() >= secondRange.area() ? first : second;
                    var drop = keep == first ? second : first;
                    var keepId = keep.path("temporaryId").asText("");
                    var dropId = drop.path("temporaryId").asText("");
                    if (keepId.isBlank() || dropId.isBlank()) continue;
                    removed.put(dropId, keepId);
                    recovery.reject(drop, "range", Map.of(), Set.of());
                    blocks.remove(drop == first ? left : right);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        if (removed.isEmpty()) return;

        for (var block : blocks) {
            if (!(block instanceof ObjectNode object)) continue;
            var parent = object.path("parentTemporaryId").asText("");
            if (removed.containsKey(parent)) object.put("parentTemporaryId", resolveReplacement(parent, removed));
        }
        migrateRemovedBlockReferences(response, removed, blocks);
    }

    private void migrateRemovedBlockReferences(
            ObjectNode response, Map<String, String> removed, ArrayNode blocks
    ) {
        for (var relation : response.withArray("fieldRelations")) {
            if (!(relation instanceof ObjectNode object)) continue;
            migrateRelationBlock(object, removed, blocks);
        }
        for (var table : response.withArray("tables")) {
            if (!(table instanceof ObjectNode object)) continue;
            var current = object.path("blockTemporaryId").asText("");
            if (!removed.containsKey(current)) continue;
            var replacement = findContainingBlock(object.path("sheetId").asText(""),
                    bounds(object.path("range").asText("")), resolveReplacement(current, removed), blocks);
            object.put("blockTemporaryId", replacement);
        }
        for (var annotation : response.withArray("semanticAnnotations")) {
            if (!(annotation instanceof ObjectNode object)) continue;
            var current = object.path("temporaryBlockRef").asText("");
            if (!removed.containsKey(current)) continue;
            var replacement = findContainingBlock(object.path("sheetId").asText(""),
                    bounds(object.path("range").asText("")), resolveReplacement(current, removed), blocks);
            object.put("temporaryBlockRef", replacement);
        }
    }

    private void migrateRelationBlock(ObjectNode relation, Map<String, String> removed, ArrayNode blocks) {
        var current = relation.path("blockTemporaryId").asText("");
        if (!removed.containsKey(current)) return;
        var label = bounds(relation.path("labelRange").asText(""));
        var value = bounds(relation.path("valueRange").asText(""));
        var replacement = findContainingBlock(relation.path("sheetId").asText(""), label,
                resolveReplacement(current, removed), blocks);
        if (replacement.isBlank() || value == null) {
            relation.put("blockTemporaryId", "");
            return;
        }
        JsonNode block = null;
        for (var item : blocks) {
            if (replacement.equals(item.path("temporaryId").asText(""))) {
                block = item;
                break;
            }
        }
        if (block == null || !bounds(block.path("range").asText("")).contains(value)) {
            relation.put("blockTemporaryId", "");
        } else {
            relation.put("blockTemporaryId", replacement);
        }
    }

    private String findContainingBlock(String sheetId, Range range, String preferredId, ArrayNode blocks) {
        if (range == null) return "";
        for (var block : blocks) {
            if (preferredId.equals(block.path("temporaryId").asText(""))
                    && sheetId.equals(block.path("sheetId").asText(""))
                    && bounds(block.path("range").asText("")).contains(range)) return preferredId;
        }
        return "";
    }

    private String resolveReplacement(String id, Map<String, String> removed) {
        var current = id;
        var visited = new HashSet<String>();
        while (removed.containsKey(current) && visited.add(current)) current = removed.get(current);
        return current;
    }

    /**
     * Repairs only a missing relation-to-block reference that is physically unambiguous.
     * Any absent, nested or otherwise ambiguous match is left untouched and rejected by
     * the normal strict reference validation below.
     */
    private void repairUniquelyContainedRelationBlocks(ObjectNode response, Set<String> blockIds) {
        var blocks = response.path("businessBlocks");
        for (var relation : response.withArray("fieldRelations")) {
            if (!(relation instanceof ObjectNode object)) continue;
            var reference = object.path("blockTemporaryId").asText("");
            if (!reference.isBlank() && blockIds.contains(reference)) continue;
            var sheetId = object.path("sheetId").asText("");
            var labelRange = bounds(object.path("labelRange").asText(""));
            var valueRange = bounds(object.path("valueRange").asText(""));
            if (labelRange == null || valueRange == null) continue;
            String match = null;
            long smallestArea = Long.MAX_VALUE;
            var matches = 0;
            for (var block : blocks) {
                if (!sheetId.equals(block.path("sheetId").asText(""))) continue;
                var blockRange = bounds(block.path("range").asText(""));
                if (blockRange == null) continue;
                if (!blockRange.contains(labelRange) || !blockRange.contains(valueRange)) continue;
                var area = blockRange.area();
                if (area < smallestArea) {
                    match = block.path("temporaryId").asText("");
                    smallestArea = area;
                    matches = 1;
                } else if (area == smallestArea) {
                    matches++;
                }
            }
            if (matches == 1) object.put("blockTemporaryId", match);
        }
    }

    private void repairUniquelyContainedTableBlocks(ObjectNode response, Set<String> blockIds) {
        var blocks = response.path("businessBlocks");
        for (var table : response.withArray("tables")) {
            if (!(table instanceof ObjectNode object)) continue;
            var reference = object.path("blockTemporaryId").asText("");
            if (!reference.isBlank() && blockIds.contains(reference)) continue;
            var sheetId = object.path("sheetId").asText("");
            var tableRange = bounds(object.path("range").asText(""));
            if (tableRange == null) continue;
            String match = null;
            long smallestArea = Long.MAX_VALUE;
            var matches = 0;
            for (var block : blocks) {
                if (!sheetId.equals(block.path("sheetId").asText(""))) continue;
                var blockRange = bounds(block.path("range").asText(""));
                if (blockRange == null || !blockRange.contains(tableRange)) continue;
                var area = blockRange.area();
                if (area < smallestArea) {
                    match = block.path("temporaryId").asText("");
                    smallestArea = area;
                    matches = 1;
                } else if (area == smallestArea) {
                    matches++;
                }
            }
            if (matches == 1) object.put("blockTemporaryId", match);
        }
    }

    private ArrayNode validRelations(
            JsonNode values, JsonNode physicalFacts, Map<String, SheetBounds> sheets,
            Set<String> blockIds, Map<String, JsonNode> blocks, RecoverySummary recovery,
            ArrayNode rejectedCandidates
    ) {
        var result = objectMapper.createArrayNode();
        var ids = new HashSet<String>();
        for (var value : values) {
            try {
                requireObject(value, "fieldRelations[]");
                var relation = (ObjectNode) value.deepCopy();
                normalizeInlineRelation(relation, physicalFacts);
                var id = relation.path("temporaryId").asText("");
                require(!id.isBlank() && ids.add(id), "fieldRelations 中 temporaryId 缺失或重复");
                validateRelations(objectMapper.createArrayNode().add(relation), sheets, blockIds, blocks);
                result.add(relation);
            } catch (ProtocolViolationException exception) {
                recovery.reject(value, "labelRange", sheets, blockIds);
                rejectedCandidates.add(value.deepCopy());
            }
        }
        return result;
    }

    private void normalizeInlineRelation(ObjectNode relation, JsonNode physicalFacts) {
        if (!"LABEL_VALUE".equals(relation.path("relationType").asText())) return;
        var labelRange = bounds(relation.path("labelRange").asText(""));
        var valueRange = bounds(relation.path("valueRange").asText(""));
        if (labelRange == null || !labelRange.equals(valueRange)) return;
        var content = physicalCellText(
                physicalFacts, relation.path("sheetId").asText(), relation.path("labelRange").asText()
        );
        var matcher = INLINE_KEY_VALUE.matcher(content);
        if (!matcher.matches() || !sameBusinessLabel(matcher.group(1), relation.path("businessName").asText())) {
            return;
        }
        relation.put("relationType", "INLINE_TEXT");
    }

    /**
     * Expands a model block only when its owned field/table ranges are unambiguous.
     * Models frequently return the label-side width for a block and forget the merged
     * value cells on the right. Rejecting the relation in that case loses valid work.
     */
    private void normalizeOwnedBlockRanges(
            ObjectNode response, JsonNode physicalFacts, Map<String, JsonNode> blocks,
            RecoverySummary recovery
    ) {
        for (var relation : response.withArray("fieldRelations")) {
            if (!(relation instanceof ObjectNode object)) continue;
            var block = blocks.get(object.path("blockTemporaryId").asText(""));
            if (block == null) continue;
            expandBlockIfSafe(object.path("sheetId").asText(""),
                    union(bounds(object.path("labelRange").asText()), bounds(object.path("valueRange").asText())),
                    block, response.path("businessBlocks"), physicalFacts, recovery, "valueRange");
        }
        for (var table : response.withArray("tables")) {
            if (!(table instanceof ObjectNode object)) continue;
            var block = blocks.get(object.path("blockTemporaryId").asText(""));
            if (block == null) continue;
            expandBlockIfSafe(object.path("sheetId").asText(""),
                    bounds(object.path("range").asText()), block, response.path("businessBlocks"),
                    physicalFacts, recovery, "range");
        }
    }

    private void expandBlockIfSafe(
            String sheetId, Range required, JsonNode blocks, JsonNode block,
            JsonNode physicalFacts, RecoverySummary recovery, String rangeKey
    ) {
        if (required == null) return;
        var current = bounds(block.path("range").asText(""));
        if (current == null || current.contains(required)) return;
        var sheet = sheetBounds(physicalFacts, sheetId);
        var expanded = union(current, required);
        if (sheet == null || expanded == null || !sheet.bounds().contains(expanded)) return;

        var parent = block.path("parentTemporaryId").asText("");
        for (var sibling : blocks) {
            if (sibling == block || !sheetId.equals(sibling.path("sheetId").asText())
                    || !parent.equals(sibling.path("parentTemporaryId").asText(""))) continue;
            var siblingRange = bounds(sibling.path("range").asText(""));
            if (siblingRange != null && expanded.overlaps(siblingRange)) return;
        }
        ((ObjectNode) block).put("range", address(expanded));
        recovery.normalized(sheetId, block.path("range").asText(""),
                block.path("temporaryId").asText(""));
    }

    private void normalizeRelations(JsonNode values, JsonNode physicalFacts, RecoverySummary recovery) {
        for (var value : values) {
            if (!(value instanceof ObjectNode relation)) continue;
            var label = bounds(relation.path("labelRange").asText(""));
            var input = bounds(relation.path("valueRange").asText(""));
            if (label == null || input == null) continue;

            var merged = commonMergedRange(physicalFacts, relation.path("sheetId").asText(""), label, input);
            if (merged != null && (label.overlaps(input)
                    || "INLINE_TEXT".equals(relation.path("relationType").asText()))) {
                relation.put("labelRange", merged).put("valueRange", merged)
                        .put("relationType", "INLINE_TEXT");
                recovery.normalized(relation.path("sheetId").asText(""), merged,
                        relation.path("temporaryId").asText(""));
            } else if ("INLINE_TEXT".equals(relation.path("relationType").asText())
                    && !label.equals(input) && !label.overlaps(input)) {
                relation.put("relationType", "LABEL_VALUE");
                recovery.normalized(relation.path("sheetId").asText(""),
                        relation.path("labelRange").asText(""), relation.path("temporaryId").asText(""));
            }
        }
    }

    private void normalizeTables(JsonNode values, RecoverySummary recovery) {
        for (var value : values) {
            if (!(value instanceof ObjectNode table)) continue;
            var kind = table.path("tableKind").asText("");
            if (!Set.of("ROW_TABLE", "COLUMN_TABLE").contains(kind)) continue;
            var repeatAxis = table.path("repeatAxis").asText("");
            if (repeatAxis.isBlank()) {
                repeatAxis = "COLUMN_TABLE".equals(kind) ? "COLUMN" : "ROW";
                table.put("repeatAxis", repeatAxis);
            }
            var header = bounds(table.path("headerRange").asText(""));
            var data = bounds(table.path("dataRange").asText(""));
            if (header != null && data != null && header.endRow() < data.startRow()) {
                var expectedMode = "COLUMN_TABLE".equals(kind) ? "COLUMN_RECORDS" : "ROW_RECORDS";
                if (!expectedMode.equals(table.path("semanticMode").asText(""))) {
                    table.put("semanticMode", expectedMode);
                    recovery.normalized(table.path("sheetId").asText(""),
                            table.path("range").asText(""), table.path("temporaryId").asText(""));
                }
                table.put("rowHeaderRange", "").put("columnHeaderRange", "").put("crossDataRange", "");
            }
            var termination = table.path("terminationRule");
            if (termination.isObject() && "FIXED_COUNT".equals(termination.path("type").asText())
                    && termination.path("maxRecords").asInt(0) <= 0
                    && termination.path("count").asInt(0) > 0) {
                ((ObjectNode) termination).put("maxRecords", termination.path("count").asInt());
                recovery.normalized(table.path("sheetId").asText(""),
                        table.path("range").asText(""), table.path("temporaryId").asText(""));
            }
        }
    }

    private String commonMergedRange(JsonNode physicalFacts, String sheetId, Range label, Range value) {
        for (var sheet : physicalFacts.path("sheets")) {
            if (!sheetId.equals(sheet.path("id").asText(sheet.path("sheetId").asText("")))) continue;
            for (var merged : sheet.path("mergedRanges")) {
                var range = bounds(merged.path("address").asText(merged.path("range").asText("")));
                if (range != null && range.contains(label) && range.contains(value)) {
                    return merged.path("address").asText(merged.path("range").asText(""));
                }
            }
        }
        return null;
    }

    private SheetBounds sheetBounds(JsonNode physicalFacts, String sheetId) {
        for (var sheet : physicalFacts.path("sheets")) {
            var id = sheet.path("id").asText(sheet.path("sheetId").asText(""));
            if (sheetId.equals(id)) {
                return new SheetBounds(id, sheet.path("usedRange").asText("A1"),
                        bounds(sheet.path("usedRange").asText("A1")));
            }
        }
        return null;
    }

    private Range union(Range left, Range right) {
        if (left == null) return right;
        if (right == null) return left;
        return new Range(Math.min(left.startColumn(), right.startColumn()),
                Math.min(left.startRow(), right.startRow()),
                Math.max(left.endColumn(), right.endColumn()),
                Math.max(left.endRow(), right.endRow()));
    }

    private String address(Range range) {
        var start = columnName(range.startColumn()) + range.startRow();
        var end = columnName(range.endColumn()) + range.endRow();
        return start.equals(end) ? start : start + ":" + end;
    }

    private String columnName(int value) {
        var current = value;
        var result = new StringBuilder();
        while (current > 0) {
            var remainder = (current - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            current = (current - 1) / 26;
        }
        return result.toString();
    }

    private String physicalCellText(JsonNode physicalFacts, String sheetId, String range) {
        var anchor = range == null ? "" : range.split(":", 2)[0];
        for (var sheet : physicalFacts.path("sheets")) {
            for (var cell : sheet.path("semanticCells")) {
                if (!sheetId.equals(cell.path("sheetId").asText())) continue;
                if (!anchor.equalsIgnoreCase(cell.path("address").asText())
                        && !range.equalsIgnoreCase(cell.path("mergedRange").asText(""))) continue;
                return cell.path("value").isTextual() ? cell.path("value").asText() : "";
            }
        }
        return "";
    }

    private boolean sameBusinessLabel(String inlineLabel, String businessName) {
        var left = normalizeBusinessText(inlineLabel);
        var right = normalizeBusinessText(businessName);
        return !left.isBlank() && !right.isBlank()
                && (left.equals(right) || left.contains(right) || right.contains(left));
    }

    private String normalizeBusinessText(String value) {
        return value == null ? "" : value.replaceAll("[\\s：:：()（）]", "").toLowerCase(Locale.ROOT);
    }

    private ArrayNode validTables(
            JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds,
            Map<String, JsonNode> blocks, RecoverySummary recovery, ArrayNode rejectedCandidates
    ) {
        var result = objectMapper.createArrayNode();
        var ids = new HashSet<String>();
        for (var value : values) {
            try {
                requireObject(value, "tables[]");
                var table = (ObjectNode) value.deepCopy();
                normalizeTableLayout(table);
                var id = table.path("temporaryId").asText("");
                require(!id.isBlank() && ids.add(id), "tables 中 temporaryId 缺失或重复");
                validateTables(objectMapper.createArrayNode().add(table), sheets, blockIds, blocks);
                result.add(table);
            } catch (ProtocolViolationException exception) {
                recovery.reject(value, "range", sheets, blockIds);
                rejectedCandidates.add(value.deepCopy());
            }
        }
        return result;
    }

    private ObjectNode pendingTable(
            JsonNode value, Map<String, SheetBounds> sheets, Set<String> blockIds,
            Map<String, JsonNode> blocks
    ) {
        if (!(value instanceof ObjectNode source)) return null;
        var sheetId = source.path("sheetId").asText("");
        var tableRange = bounds(source.path("range").asText(""));
        var headerRange = bounds(source.path("headerRange").asText(""));
        var dataRange = bounds(source.path("dataRange").asText(""));
        var sheet = sheets.get(sheetId);
        var block = blocks.get(source.path("blockTemporaryId").asText(""));
        if (sheet == null || tableRange == null || headerRange == null || dataRange == null
                || !sheet.bounds().contains(tableRange) || !tableRange.contains(headerRange)
                || !tableRange.contains(dataRange) || headerRange.overlaps(dataRange)
                || block == null || !sheetId.equals(block.path("sheetId").asText(""))
                || !bounds(block.path("range").asText("")).contains(tableRange)) return null;
        var pending = (ObjectNode) source.deepCopy();
        pending.withArray("columns").removeAll();
        // A rejected MATRIX candidate must retain the physical axes. Clearing them here
        // turned a recoverable matrix into an untraceable UNKNOWN table and made the
        // compiler infer every cell as a flat column. The candidate is still pending;
        // its structure is not promoted merely because the ranges are retained.
        if (!"MATRIX".equals(source.path("tableKind").asText())) {
            pending.put("semanticMode", "UNKNOWN");
            pending.put("rowHeaderRange", "").put("columnHeaderRange", "").put("crossDataRange", "");
            pending.withArray("headerTree").removeAll();
        }
        return pending;
    }

    private ArrayNode validAnnotations(
            JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds,
            Set<String> relationIds, Set<String> tableIds
    ) {
        var result = objectMapper.createArrayNode();
        for (var value : values) {
            try {
                requireObject(value, "semanticAnnotations[]");
                var annotation = (ObjectNode) value.deepCopy();
                var role = annotation.path("role").asText("");
                var relationRef = annotation.path("temporaryRelationRef").asText("");
                if (!relationRef.isBlank() && !relationIds.contains(relationRef)) {
                    if (Set.of("FIELD_LABEL", "FIELD_VALUE").contains(role)) continue;
                    annotation.put("temporaryRelationRef", "");
                }
                var tableRef = annotation.path("temporaryTableRef").asText("");
                if (!tableRef.isBlank() && !tableIds.contains(tableRef)) {
                    if (Set.of("TABLE_HEADER", "TABLE_DATA", "TABLE_TOTAL").contains(role)) continue;
                    annotation.put("temporaryTableRef", "");
                }
                var blockRef = annotation.path("temporaryBlockRef").asText("");
                if (!blockRef.isBlank() && !blockIds.contains(blockRef)) {
                    annotation.put("temporaryBlockRef", "");
                }
                validateAnnotations(objectMapper.createArrayNode().add(annotation),
                        sheets, blockIds, relationIds, tableIds);
                result.add(annotation);
            } catch (ProtocolViolationException ignored) {
                // A dangling field/table annotation has no independent business meaning.
            }
        }
        return result;
    }

    private ArrayNode validQualityIssues(
            JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds
    ) {
        var result = objectMapper.createArrayNode();
        var ids = new HashSet<String>();
        for (var value : values) {
            try {
                var id = value.path("temporaryId").asText("");
                require(!id.isBlank() && ids.add(id), "qualityIssues 中 temporaryId 缺失或重复");
                validateQualityIssues(objectMapper.createArrayNode().add(value), sheets, blockIds);
                result.add(value.deepCopy());
            } catch (ProtocolViolationException ignored) {
                // Invalid model-authored advice must not discard valid business fields.
            }
        }
        return result;
    }

    private void appendRecoveryIssue(
            ArrayNode issues, RecoverySummary recovery, String category, String title,
            String description, String impact, Map<String, SheetBounds> sheets, Set<String> blockIds
    ) {
        if (recovery.count == 0) return;
        var sheetId = recovery.sheetId;
        var range = recovery.range;
        if (!sheets.containsKey(sheetId) || bounds(range) == null) {
            var fallback = sheets.values().iterator().next();
            sheetId = fallback.id();
            range = fallback.usedRange();
        }
        var issueId = "protocol-recovery-" + category.toLowerCase(Locale.ROOT) + "-" + issues.size();
        issues.add(objectMapper.createObjectNode()
                .put("temporaryId", issueId).put("sheetId", sheetId).put("range", range)
                .put("category", category).put("severity", "WARNING").put("title", title)
                .put("description", description + " 共 " + recovery.count + " 项。")
                .put("businessImpact", impact)
                .put("rootBlockTemporaryId", blockIds.contains(recovery.blockTemporaryId)
                        ? recovery.blockTemporaryId : ""));
    }

    private void appendNormalizationIssue(
            ArrayNode issues, RecoverySummary recovery, Map<String, SheetBounds> sheets,
            Set<String> blockIds
    ) {
        if (recovery.count == 0) return;
        var sheetId = recovery.sheetId;
        var range = recovery.range;
        if (!sheets.containsKey(sheetId) || bounds(range) == null) {
            var fallback = sheets.values().iterator().next();
            sheetId = fallback.id();
            range = fallback.usedRange();
        }
        issues.add(objectMapper.createObjectNode()
                .put("temporaryId", "protocol-normalized-" + issues.size())
                .put("sheetId", sheetId).put("range", range)
                .put("category", "LAYOUT_INCONSISTENT").put("severity", "INFO")
                .put("title", "系统已自动规范部分识别结果")
                .put("description", "系统修正了 " + recovery.count
                        + " 项表格、合并单元格或业务区域范围，原始候选仍保留待人工确认。")
                .put("businessImpact", "不会自动进入正式模板，请确认字段名称、范围和填写方式。")
                .put("rootBlockTemporaryId", blockIds.contains(recovery.blockTemporaryId)
                        ? recovery.blockTemporaryId : ""));
    }

    private void validateAnnotations(
            JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds,
            Set<String> relationIds, Set<String> tableIds
    ) {
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "semanticAnnotations[" + index + "]";
            requireObject(value, path);
            exactKeys(value, path, Set.of(
                    "sheetId", "range", "role", "temporaryRelationRef", "temporaryBlockRef", "temporaryTableRef"
            ));
            validateRange(value, path, sheets);
            enumValue(value, "role", ANNOTATION_ROLES, path);
            optionalRef(value, "temporaryRelationRef", relationIds, path);
            optionalRef(value, "temporaryBlockRef", blockIds, path);
            optionalRef(value, "temporaryTableRef", tableIds, path);
            if (Set.of("FIELD_LABEL", "FIELD_VALUE").contains(value.path("role").asText())) {
                require(nonBlankReference(value, "temporaryRelationRef", relationIds),
                        path + " 的字段标签/值必须引用 fieldRelations");
            }
            if (Set.of("TABLE_HEADER", "TABLE_DATA", "TABLE_TOTAL").contains(value.path("role").asText())) {
                require(nonBlankReference(value, "temporaryTableRef", tableIds),
                        path + " 的表格范围必须引用 tables");
            }
        }
    }

    private Map<String, JsonNode> validateBlocks(JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds) {
        var blocks = new LinkedHashMap<String, JsonNode>();
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "businessBlocks[" + index + "]";
            requireObject(value, path);
            exactKeys(value, path, Set.of(
                    "temporaryId", "sheetId", "range", "type", "parentTemporaryId",
                    "businessName", "groupNameSuggestion", "semanticKeySuggestion"
            ));
            requiredText(value, "temporaryId", path);
            requiredText(value, "businessName", path);
            validateRange(value, path, sheets);
            enumValue(value, "type", BLOCK_TYPES, path);
            optionalRef(value, "parentTemporaryId", blockIds, path);
            blocks.put(value.path("temporaryId").asText(), value);
        }
        for (var entry : blocks.entrySet()) {
            var child = entry.getValue();
            var parentId = child.path("parentTemporaryId").asText("");
            if (parentId.isBlank()) continue;
            require(!parentId.equals(entry.getKey()), "业务块不能引用自身作为父块");
            var parent = blocks.get(parentId);
            require(parent != null && parent.path("sheetId").asText().equals(child.path("sheetId").asText()),
                    "父子业务块必须位于同一工作表");
            require(bounds(parent.path("range").asText()).contains(bounds(child.path("range").asText())),
                    "父业务块必须完整包含子业务块：parent=" + parentId
                            + "(" + parent.path("range").asText() + ") child=" + entry.getKey()
                            + "(" + child.path("range").asText() + ")");
            var visited = new HashSet<String>();
            var cursor = entry.getKey();
            while (cursor != null && visited.add(cursor)) {
                var current = blocks.get(cursor);
                cursor = current == null ? null : blankToNull(current.path("parentTemporaryId").asText(""));
            }
            require(cursor == null, "业务块父子关系不能形成循环");
        }
        var list = new ArrayList<>(blocks.values());
        for (int left = 0; left < list.size(); left++) {
            for (int right = left + 1; right < list.size(); right++) {
                var first = list.get(left);
                var second = list.get(right);
                if (!first.path("sheetId").asText().equals(second.path("sheetId").asText())) continue;
                if (!first.path("parentTemporaryId").asText("")
                        .equals(second.path("parentTemporaryId").asText(""))) continue;
                require(!bounds(first.path("range").asText()).overlaps(bounds(second.path("range").asText())),
                        "同一父块下的兄弟业务块不能重叠：first="
                                + first.path("temporaryId").asText() + "(" + first.path("range").asText()
                                + ") second=" + second.path("temporaryId").asText() + "("
                                + second.path("range").asText() + ")");
            }
        }
        return blocks;
    }

    private void validateRelations(
            JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds, Map<String, JsonNode> blocks
    ) {
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "fieldRelations[" + index + "]";
            requireObject(value, path);
            exactKeys(value, path, Set.of(
                    "temporaryId", "sheetId", "labelRange", "valueRange", "relationType",
                    "businessName", "blockTemporaryId", "groupNameSuggestion", "semanticKeySuggestion",
                    "valueType", "required", "editability", "valueSource", "unit", "condition"
            ));
            requiredText(value, "temporaryId", path);
            requiredText(value, "businessName", path);
            validateNamedRange(value, "labelRange", path, sheets);
            validateNamedRange(value, "valueRange", path, sheets);
            enumValue(value, "relationType", RELATION_TYPES, path);
            enumValue(value, "valueType", VALUE_TYPES, path);
            enumValue(value, "editability", EDITABILITY, path);
            enumValue(value, "valueSource", VALUE_SOURCES, path);
            require(value.path("required").isBoolean(), path + ".required 必须是布尔值");
            optionalRef(value, "blockTemporaryId", blockIds, path);
            require(nonBlankReference(value, "blockTemporaryId", blockIds),
                    path + ".blockTemporaryId 必须引用包含该字段的业务块");
            var block = blocks.get(value.path("blockTemporaryId").asText());
            var labelRange = bounds(value.path("labelRange").asText());
            var valueRange = bounds(value.path("valueRange").asText());
            require(block != null && value.path("sheetId").asText().equals(block.path("sheetId").asText())
                            && bounds(block.path("range").asText()).contains(labelRange)
                            && bounds(block.path("range").asText()).contains(valueRange),
                    path + " 的标签和值必须位于所属业务块范围内");
            var relationType = value.path("relationType").asText();
            if ("LABEL_VALUE".equals(relationType)) {
                require(!labelRange.overlaps(valueRange),
                        path + " 的 LABEL_VALUE 标签范围和值范围不能重叠");
            } else {
                require(labelRange.equals(valueRange),
                        path + " 的 INLINE_TEXT 标签和值必须使用同一单元格范围");
            }
            var allowedHeaderMetadata = "DOCUMENT_HEADER".equals(block.path("type").asText())
                    && "INLINE_TEXT".equals(relationType);
            require(!STATIC_BLOCK_TYPES.contains(block.path("type").asText()) || allowedHeaderMetadata,
                    path + " 不能从文档标题、操作说明或静态备注块生成字段");
            validateCondition(value, path);
        }
    }

    private void validateTables(
            JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds, Map<String, JsonNode> blocks
    ) {
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "tables[" + index + "]";
            requireObject(value, path);
            var table = (ObjectNode) value.deepCopy();
            normalizeTableLayout(table);
            exactKeys(value, path, Set.of(
                    "temporaryId", "sheetId", "range", "tableKind", "businessName",
                    "blockTemporaryId", "groupNameSuggestion", "semanticKeySuggestion",
                    "headerRange", "dataRange", "totalRange", "columns", "semanticMode",
                    "rowHeaderRange", "columnHeaderRange", "crossDataRange", "headerTree",
                    "cornerRange", "repeatAxis", "recordAxis", "recordHeight", "recordWidth",
                    "recordStride", "measureHeight", "recordHeightIncludesIdentity", "recordProjection",
                    "columnSlots", "columnMemberRole", "memberMode", "bindings", "terminationRule"
            ));
            requiredText(table, "temporaryId", path);
            requiredText(table, "businessName", path);
            validateRange(table, path, sheets);
            validateNamedRange(table, "headerRange", path, sheets);
            validateNamedRange(table, "dataRange", path, sheets);
            if (table.has("totalRange") && !table.path("totalRange").asText("").isBlank()) {
                validateNamedRange(table, "totalRange", path, sheets);
            }
            enumValue(table, "tableKind", TABLE_KINDS, path);
            enumValue(table, "semanticMode", TABLE_SEMANTIC_MODES, path);
            optionalRef(table, "blockTemporaryId", blockIds, path);
            require(nonBlankReference(table, "blockTemporaryId", blockIds),
                    path + ".blockTemporaryId 必须引用包含该表格的业务块");
            var tableRange = bounds(table.path("range").asText());
            var headerRange = bounds(table.path("headerRange").asText());
            var dataRange = bounds(table.path("dataRange").asText());
            var totalRange = table.path("totalRange").asText("").isBlank()
                    ? null : bounds(value.path("totalRange").asText());
            var block = blocks.get(table.path("blockTemporaryId").asText());
            require(block != null && table.path("sheetId").asText().equals(block.path("sheetId").asText())
                            && bounds(block.path("range").asText()).contains(tableRange),
                    path + " 的表格范围必须位于所属业务块内");
            require(tableRange.contains(headerRange) && tableRange.contains(dataRange)
                            && (totalRange == null || tableRange.contains(totalRange)),
                    path + " 的表头、数据和合计范围必须位于表格范围内");
            require(!headerRange.overlaps(dataRange), path + " 的表头范围与数据范围不能重叠");
            if (Set.of("ROW_TABLE", "COLUMN_TABLE").contains(table.path("tableKind").asText())) {
                var columnRecords = "COLUMN_TABLE".equals(table.path("tableKind").asText());
                var expectedMode = columnRecords ? "COLUMN_RECORDS" : "ROW_RECORDS";
                require(expectedMode.equals(table.path("semanticMode").asText()),
                        path + " 的重复表必须使用 " + expectedMode + " 语义模式");
                enumValue(table, "repeatAxis", REPEAT_AXES, path);
                require(table.path("recordHeight").asInt(0) > 0
                                && table.path("recordWidth").asInt(0) > 0
                                && table.path("recordStride").asInt(0) > 0,
                        path + " 的记录单元必须是正整数");
                require(table.path("rowHeaderRange").asText("").isBlank()
                                && table.path("columnHeaderRange").asText("").isBlank()
                                && table.path("crossDataRange").asText("").isBlank(),
                        path + " 的 ROW_TABLE 不得虚构矩阵轴范围");
                require(headerRange.endRow() < dataRange.startRow(),
                        path + " 的记录身份行必须位于数据区上方");
                if (totalRange != null) {
                    require(dataRange.endRow() < totalRange.startRow(),
                            path + " 的合计区域必须位于数据区下方");
                }
            } else {
                require(Set.of("CROSS_TAB", "RECORD_SET").contains(table.path("semanticMode").asText()),
                        path + " 的 MATRIX 必须明确 CROSS_TAB 或 RECORD_SET 语义模式");
                if (table.has("repeatAxis") && !table.path("repeatAxis").asText("").isBlank()) {
                    enumValue(table, "repeatAxis", REPEAT_AXES, path);
                }
                validateNamedRange(table, "rowHeaderRange", path, sheets);
                validateNamedRange(table, "columnHeaderRange", path, sheets);
                validateNamedRange(table, "crossDataRange", path, sheets);
                if (table.has("cornerRange") && !table.path("cornerRange").asText("").isBlank()) {
                    validateNamedRange(table, "cornerRange", path, sheets);
                }
                var rowHeaderRange = bounds(table.path("rowHeaderRange").asText());
                var columnHeaderRange = bounds(table.path("columnHeaderRange").asText());
                var crossDataRange = bounds(table.path("crossDataRange").asText());
                var cornerRange = table.path("cornerRange").asText("").isBlank()
                        ? null : bounds(table.path("cornerRange").asText());
                require(tableRange.contains(rowHeaderRange) && tableRange.contains(columnHeaderRange)
                                && tableRange.contains(crossDataRange),
                        path + " 的矩阵行标题、列标题和交叉数据区必须位于矩阵范围内");
                if (cornerRange != null) {
                    require(tableRange.contains(cornerRange)
                                    && cornerRange.endRow() < crossDataRange.startRow()
                                    && cornerRange.endColumn() < crossDataRange.startColumn(),
                            path + " 的矩阵角区必须位于行列轴交界处");
                }
                require(!rowHeaderRange.overlaps(crossDataRange)
                                && !columnHeaderRange.overlaps(crossDataRange),
                        path + " 的矩阵轴标题不能与交叉数据区重叠");
                require(rowHeaderRange.endColumn() < crossDataRange.startColumn()
                                && columnHeaderRange.endRow() < crossDataRange.startRow(),
                        path + " 的矩阵行标题必须在数据区左侧、列标题必须在数据区上方");
                require(dataRange.equals(crossDataRange),
                        path + " 的 MATRIX dataRange 必须等于 crossDataRange");
            }
            validateHeaderTree(table.path("headerTree"), table, tableRange, path);
            require(table.path("columns").isArray(), path + ".columns 必须是数组");
            // MATRIX 的字段身份来自行/列轴和交叉数据坐标，不要求模型虚构
            // “第1列/第2列”这样的列名。ROW/COLUMN_TABLE 仍要求显式列定义。
            if ("MATRIX".equals(table.path("tableKind").asText())) {
                require(table.path("columns").isEmpty(),
                        path + ".MATRIX.columns 必须为空，轴字段由 headerTree 定义");
            } else {
                require(!table.path("columns").isEmpty(), path + ".columns 必须是非空数组");
            }
            var columnIds = new HashSet<String>();
            for (int columnIndex = 0; columnIndex < table.path("columns").size(); columnIndex++) {
                var column = table.path("columns").get(columnIndex);
                var columnPath = path + ".columns[" + columnIndex + "]";
                requireObject(column, columnPath);
                exactKeys(column, columnPath, Set.of(
                        "temporaryId", "name", "labelRange", "valueRange", "valueType",
                        "editability", "valueSource", "unit", "condition", "semanticKeySuggestion"
                ));
                requiredText(column, "temporaryId", columnPath);
                requiredText(column, "name", columnPath);
                require(columnIds.add(column.path("temporaryId").asText()),
                        columnPath + ".temporaryId 重复");
                validateNamedRange(columnWithSheet(column, value.path("sheetId").asText()),
                        "labelRange", columnPath, sheets);
                validateNamedRange(columnWithSheet(column, value.path("sheetId").asText()),
                        "valueRange", columnPath, sheets);
                enumValue(column, "valueType", VALUE_TYPES, columnPath);
                enumValue(column, "editability", EDITABILITY, columnPath);
                enumValue(column, "valueSource", VALUE_SOURCES, columnPath);
                validateCondition(column, columnPath);
                var columnLabel = bounds(column.path("labelRange").asText());
                var columnValue = bounds(column.path("valueRange").asText());
                require(tableRange.contains(columnLabel) && dataRange.contains(columnValue),
                        columnPath + " 的字段标签和值范围必须位于表格内");
                if ("MATRIX".equals(table.path("tableKind").asText())
                        || "ROW".equals(table.path("repeatAxis").asText("ROW"))) {
                    require(columnLabel.startColumn() == columnValue.startColumn()
                                    && columnLabel.endColumn() == columnValue.endColumn(),
                            columnPath + " 的字段标签与数据区必须垂直对齐");
                } else {
                    require(columnLabel.startRow() == columnValue.startRow()
                                    && columnLabel.endRow() == columnValue.endRow(),
                            columnPath + " 的字段标签与数据区必须水平对齐");
                }
            }
            validateTerminationRule(table.path("terminationRule"), path);
        }
    }

    private void normalizeTableLayout(ObjectNode table) {
        var kind = table.path("tableKind").asText("");
        var data = bounds(table.path("dataRange").asText(""));
        if (!table.has("repeatAxis") || table.path("repeatAxis").asText("").isBlank()) {
            table.put("repeatAxis", "MATRIX".equals(kind) ? "" : "ROW");
        }
        if (!table.has("recordHeight") || table.path("recordHeight").asInt(0) <= 0) {
            table.put("recordHeight", "COLUMN".equals(table.path("repeatAxis").asText())
                    && data != null ? data.endRow() - data.startRow() + 1 : 1);
        }
        if (!table.has("recordWidth") || table.path("recordWidth").asInt(0) <= 0) {
            table.put("recordWidth", "ROW".equals(table.path("repeatAxis").asText())
                    && data != null ? data.endColumn() - data.startColumn() + 1 : 1);
        }
        if (!table.has("recordStride") || table.path("recordStride").asInt(0) <= 0) {
            table.put("recordStride", 1);
        }
        var terminationRule = table.path("terminationRule");
        // Some model providers satisfy the object-level schema by returning an
        // empty object, but that is not a usable termination rule. Treat it the
        // same as an omitted rule so a valid table is not discarded merely
        // because the provider omitted the default type.
        if (!table.has("terminationRule") || !terminationRule.isObject()
                || terminationRule.path("type").asText("").isBlank()) {
            var termination = objectMapper.createObjectNode();
            termination.put("type", table.path("totalRange").asText("").isBlank()
                    ? "UNTIL_REGION_END" : "UNTIL_TOTAL_ROW");
            table.set("terminationRule", termination);
        }
    }

    private void validateTerminationRule(JsonNode rule, String path) {
        require(rule.isObject(), path + ".terminationRule 必须是对象");
        enumValue(rule, "type", TERMINATION_TYPES, path + ".terminationRule");
        if ("UNTIL_LABEL".equals(rule.path("type").asText())) {
            require(!rule.path("label").asText("").isBlank(),
                    path + ".terminationRule.label 不能为空");
        }
        if ("FIXED_COUNT".equals(rule.path("type").asText())) {
            require(rule.path("maxRecords").asInt(0) > 0,
                    path + ".terminationRule.maxRecords 必须为正整数");
        }
    }

    private void validateHeaderTree(JsonNode tree, JsonNode table, Range tableRange, String path) {
        require(tree.isArray(), path + ".headerTree 必须是数组");
        if ("MATRIX".equals(table.path("tableKind").asText())) {
            require(!tree.isEmpty(), path + " 的 MATRIX 必须提供行列标题树");
        }
        var ids = new HashSet<String>();
        for (int index = 0; index < tree.size(); index++) {
            var node = tree.get(index);
            var nodePath = path + ".headerTree[" + index + "]";
            requireObject(node, nodePath);
            exactKeys(node, nodePath, Set.of(
                    "temporaryId", "parentTemporaryId", "name", "range", "axis", "role", "memberMode"
            ));
            requiredText(node, "temporaryId", nodePath);
            requiredText(node, "name", nodePath);
            require(ids.add(node.path("temporaryId").asText()), nodePath + ".temporaryId 重复");
            require(Set.of("ROW", "COLUMN").contains(node.path("axis").asText()),
                    nodePath + ".axis 包含未知枚举值");
            var range = bounds(node.path("range").asText());
            require(range != null && tableRange.contains(range), nodePath + ".range 必须位于表格范围内");
        }
        for (var node : tree) {
            var parent = node.path("parentTemporaryId").asText("");
            require(parent.isBlank() || ids.contains(parent), path + ".headerTree 引用了不存在的父标题");
            require(!parent.equals(node.path("temporaryId").asText()), path + ".headerTree 标题不能引用自身");
        }
    }

    private void validateQualityIssues(JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds) {
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "qualityIssues[" + index + "]";
            requireObject(value, path);
            exactKeys(value, path, Set.of(
                    "temporaryId", "sheetId", "range", "category", "severity", "title",
                    "description", "businessImpact", "rootBlockTemporaryId"
            ));
            requiredText(value, "temporaryId", path);
            requiredText(value, "title", path);
            requiredText(value, "description", path);
            requiredText(value, "businessImpact", path);
            validateRange(value, path, sheets);
            enumValue(value, "category", ISSUE_CATEGORIES, path);
            enumValue(value, "severity", SEVERITIES, path);
            optionalRef(value, "rootBlockTemporaryId", blockIds, path);
        }
    }

    private void validateCondition(JsonNode value, String path) {
        var editability = value.path("editability").asText();
        if ("CONDITIONAL".equals(editability)) {
            require(value.path("condition").isTextual() && !value.path("condition").asText().isBlank(),
                    path + " 条件可编辑时必须提供 condition");
        } else {
            require(!value.has("condition") || value.path("condition").isNull()
                            || value.path("condition").asText("").isBlank(),
                    path + " 仅 CONDITIONAL 可提供 condition");
        }
    }

    private ObjectNode columnWithSheet(JsonNode column, String sheetId) {
        var copy = (ObjectNode) column.deepCopy();
        copy.put("sheetId", sheetId);
        return copy;
    }

    private void validateRange(JsonNode value, String path, Map<String, SheetBounds> sheets) {
        requiredText(value, "sheetId", path);
        validateNamedRange(value, "range", path, sheets);
    }

    private void validateNamedRange(
            JsonNode value, String key, String path, Map<String, SheetBounds> sheets
    ) {
        requiredText(value, "sheetId", path);
        requiredText(value, key, path);
        var sheetId = value.path("sheetId").asText();
        var sheet = sheets.get(sheetId);
        require(sheet != null, path + ".sheetId 引用了不存在的工作表");
        var range = bounds(value.path(key).asText());
        require(range != null && sheet.bounds().contains(range), path + "." + key + " 超出工作表使用范围");
    }

    private Map<String, SheetBounds> sheets(JsonNode physicalFacts) {
        var result = new HashMap<String, SheetBounds>();
        for (var sheet : physicalFacts.path("sheets")) {
            var id = sheet.path("id").asText(sheet.path("sheetId").asText(""));
            var usedRange = sheet.path("usedRange").asText("A1");
            var used = bounds(usedRange);
            require(!id.isBlank() && used != null, "物理事实中的工作表范围无效");
            result.put(id, new SheetBounds(id, usedRange, used));
        }
        require(!result.isEmpty(), "物理事实中没有可识别工作表");
        return result;
    }

    private Set<String> uniqueIds(JsonNode values, String path) {
        var result = new HashSet<String>();
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            requireObject(value, path + "[" + index + "]");
            requiredText(value, "temporaryId", path + "[" + index + "]");
            require(result.add(value.path("temporaryId").asText()), path + " 中 temporaryId 重复");
        }
        return result;
    }

    private void optionalRef(JsonNode value, String key, Set<String> ids, String path) {
        if (!value.has(key) || value.path(key).isNull() || value.path(key).asText("").isBlank()) return;
        require(value.path(key).isTextual() && ids.contains(value.path(key).asText()),
                path + "." + key + " 引用了不存在的临时标识");
    }

    private boolean nonBlankReference(JsonNode value, String key, Set<String> ids) {
        return value.path(key).isTextual()
                && !value.path(key).asText().isBlank()
                && ids.contains(value.path(key).asText());
    }

    private void enumValue(JsonNode value, String key, Set<String> allowed, String path) {
        require(value.path(key).isTextual() && allowed.contains(value.path(key).asText()),
                path + "." + key + " 包含未知枚举值");
    }

    private void requiredText(JsonNode value, String key, String path) {
        require(value.path(key).isTextual() && !value.path(key).asText().isBlank(),
                path + "." + key + " 必须是非空文本");
    }

    private void requireObject(JsonNode value, String path) {
        require(value != null && value.isObject(), path + " 必须是对象");
    }

    private void exactKeys(JsonNode value, String path, Set<String> allowed) {
        var fields = value.fieldNames();
        while (fields.hasNext()) {
            var key = fields.next();
            require(allowed.contains(key), path + " 包含协议未定义字段：" + key);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new ProtocolViolationException(message);
    }

    private Range bounds(String value) {
        if (value == null) return null;
        var match = ADDRESS.matcher(value.replace("$", "").toUpperCase(Locale.ROOT));
        if (!match.matches()) return null;
        var startColumn = column(match.group(1));
        var startRow = Integer.parseInt(match.group(2));
        var endColumn = match.group(3) == null ? startColumn : column(match.group(3));
        var endRow = match.group(4) == null ? startRow : Integer.parseInt(match.group(4));
        return new Range(Math.min(startColumn, endColumn), Math.min(startRow, endRow),
                Math.max(startColumn, endColumn), Math.max(startRow, endRow));
    }

    private int column(String letters) {
        var result = 0;
        for (var letter : letters.toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static final class ProtocolViolationException extends IllegalArgumentException {
        ProtocolViolationException(String message) {
            super("全局语义识别协议校验失败：" + message);
        }
    }

    record ValidationResult(ObjectNode response, RecoveryDiagnostics diagnostics) {
    }

    record RecoveryDiagnostics(
            int relationsReturned,
            int relationsAccepted,
            int relationsRejected,
            int tablesReturned,
            int tablesAccepted,
            int tablesRejected,
            int blocksRecovered
    ) {
    }

    private final class RecoverySummary {
        private int count;
        private String sheetId = "";
        private String range = "";
        private String blockTemporaryId = "";

        private void normalized(String candidateSheetId, String candidateRange, String candidateBlock) {
            count++;
            if (!sheetId.isBlank()) return;
            sheetId = candidateSheetId == null ? "" : candidateSheetId;
            range = candidateRange == null ? "" : candidateRange;
            blockTemporaryId = candidateBlock == null ? "" : candidateBlock;
        }

        private void reject(JsonNode value, String rangeKey, Map<String, SheetBounds> sheets, Set<String> blockIds) {
            count++;
            if (!sheetId.isBlank()) return;
            var candidateSheet = value.path("sheetId").asText("");
            var candidateRange = value.path(rangeKey).asText("");
            var sheet = sheets.get(candidateSheet);
            var parsed = bounds(candidateRange);
            if (sheet == null || parsed == null || !sheet.bounds().contains(parsed)) return;
            sheetId = candidateSheet;
            range = candidateRange;
            var candidateBlock = value.path("blockTemporaryId").asText("");
            blockTemporaryId = blockIds.contains(candidateBlock) ? candidateBlock : "";
        }
    }

    private record SheetBounds(String id, String usedRange, Range bounds) {
    }

    private record Range(int startColumn, int startRow, int endColumn, int endRow) {
        boolean contains(Range other) {
            return other != null && startColumn <= other.startColumn && startRow <= other.startRow
                    && endColumn >= other.endColumn && endRow >= other.endRow;
        }

        boolean overlaps(Range other) {
            return other != null && startColumn <= other.endColumn && endColumn >= other.startColumn
                    && startRow <= other.endRow && endRow >= other.startRow;
        }

        long area() {
            return (long) (endColumn - startColumn + 1) * (endRow - startRow + 1);
        }
    }

    private static final String SCHEMA = schemaText("""
            {
              "$schema":"https://json-schema.org/draft/2020-12/schema",
              "type":"object","additionalProperties":false,
              "required":["recognitionProtocolVersion","semanticAnnotations","businessBlocks","fieldRelations","tables","qualityIssues"],
              "properties":{
                "recognitionProtocolVersion":{"const":1},
                "semanticAnnotations":{"type":"array","items":{"$ref":"#/$defs/annotation"}},
                "businessBlocks":{"type":"array","items":{"$ref":"#/$defs/block"}},
                "fieldRelations":{"type":"array","items":{"$ref":"#/$defs/relation"}},
                "tables":{"type":"array","items":{"$ref":"#/$defs/table"}},
                "qualityIssues":{"type":"array","items":{"$ref":"#/$defs/issue"}}
              },
              "$defs":{
                "range":{"type":"string","pattern":"^[A-Z]{1,4}[1-9][0-9]*(?::[A-Z]{1,4}[1-9][0-9]*)?$"},
                "editability":{"enum":["EDITABLE","READ_ONLY","CONDITIONAL","UNKNOWN"]},
                "valueSource":{"enum":["USER_INPUT","FORMULA","REFERENCE","STATIC","MIXED","UNKNOWN"]},
                "valueType":{"enum":["string","number","integer","boolean","date","datetime","time","duration"]},
                "annotation":{"type":"object","additionalProperties":false,"required":["sheetId","range","role","temporaryRelationRef","temporaryBlockRef","temporaryTableRef"],"properties":{"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"role":{"enum":["DOCUMENT_TITLE","INLINE_METADATA","FIELD_LABEL","FIELD_VALUE","TABLE_HEADER","TABLE_DATA","TABLE_TOTAL","INSTRUCTION","CONFIRMATION","NOTE","LOOKUP_DATA","STATIC_REFERENCE","UNKNOWN"]},"temporaryRelationRef":{"type":"string"},"temporaryBlockRef":{"type":"string"},"temporaryTableRef":{"type":"string"}}},
                "block":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","range","type","parentTemporaryId","businessName","groupNameSuggestion","semanticKeySuggestion"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"type":{"enum":["DOCUMENT_HEADER","FORM_FIELDS","ROW_TABLE","COLUMN_TABLE","MATRIX","FREE_TEXT","STATIC_REFERENCE","INSTRUCTION_LIST","CONFIRMATION_BLOCK","NOTE_BLOCK","LOOKUP_TABLE","UNKNOWN"]},"parentTemporaryId":{"type":"string"},"businessName":{"type":"string","minLength":1},"groupNameSuggestion":{"type":"string"},"semanticKeySuggestion":{"type":"string"}}},
                "relation":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","labelRange","valueRange","relationType","businessName","blockTemporaryId","groupNameSuggestion","semanticKeySuggestion","valueType","required","editability","valueSource","unit","condition"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"labelRange":{"$ref":"#/$defs/range"},"valueRange":{"$ref":"#/$defs/range"},"relationType":{"enum":["LABEL_VALUE","INLINE_TEXT"]},"businessName":{"type":"string","minLength":1},"blockTemporaryId":{"type":"string"},"groupNameSuggestion":{"type":"string"},"semanticKeySuggestion":{"type":"string"},"valueType":{"$ref":"#/$defs/valueType"},"required":{"type":"boolean"},"editability":{"$ref":"#/$defs/editability"},"valueSource":{"$ref":"#/$defs/valueSource"},"unit":{"type":"string"},"condition":{"type":"string"}}},
                "column":{"type":"object","additionalProperties":false,"required":["temporaryId","name","labelRange","valueRange","valueType","editability","valueSource","unit","condition","semanticKeySuggestion"],"properties":{"temporaryId":{"type":"string","minLength":1},"name":{"type":"string","minLength":1},"labelRange":{"$ref":"#/$defs/range"},"valueRange":{"$ref":"#/$defs/range"},"valueType":{"$ref":"#/$defs/valueType"},"editability":{"$ref":"#/$defs/editability"},"valueSource":{"$ref":"#/$defs/valueSource"},"unit":{"type":"string"},"condition":{"type":"string"},"semanticKeySuggestion":{"type":"string"}}},
                "headerNode":{"type":"object","additionalProperties":false,"required":["temporaryId","parentTemporaryId","name","range","axis"],"properties":{"temporaryId":{"type":"string","minLength":1},"parentTemporaryId":{"type":"string"},"name":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"axis":{"enum":["ROW","COLUMN"]}}},
                 "table":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","range","tableKind","businessName","blockTemporaryId","groupNameSuggestion","semanticKeySuggestion","headerRange","dataRange","totalRange","columns","semanticMode","rowHeaderRange","columnHeaderRange","crossDataRange","headerTree"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"tableKind":{"enum":["ROW_TABLE","COLUMN_TABLE","MATRIX"]},"businessName":{"type":"string","minLength":1},"blockTemporaryId":{"type":"string"},"groupNameSuggestion":{"type":"string"},"semanticKeySuggestion":{"type":"string"},"headerRange":{"$ref":"#/$defs/range"},"dataRange":{"$ref":"#/$defs/range"},"totalRange":{"type":"string"},"columns":{"type":"array","minItems":1,"items":{"$ref":"#/$defs/column"}},"semanticMode":{"enum":["ROW_RECORDS","CROSS_TAB","RECORD_SET","UNKNOWN"]},"rowHeaderRange":{"type":"string"},"columnHeaderRange":{"type":"string"},"crossDataRange":{"type":"string"},"headerTree":{"type":"array","items":{"$ref":"#/$defs/headerNode"}},"repeatAxis":{"enum":["ROW","COLUMN"]},"recordHeight":{"type":"integer","minimum":1},"recordWidth":{"type":"integer","minimum":1},"recordStride":{"type":"integer","minimum":1},"terminationRule":{"type":"object","additionalProperties":true,"properties":{"type":{"enum":["UNTIL_TOTAL_ROW","UNTIL_EMPTY_RECORD","UNTIL_REGION_END","UNTIL_LABEL","FIXED_COUNT"]},"label":{"type":"string"},"address":{"type":"string"},"maxRecords":{"type":"integer","minimum":1}}}}},
                "issue":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","range","category","severity","title","description","businessImpact","rootBlockTemporaryId"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"category":{"enum":["FIELD_RELATION_UNCLEAR","BUSINESS_BLOCK_UNCLEAR","TABLE_STRUCTURE_UNCLEAR","EDITABILITY_UNCLEAR","LAYOUT_INCONSISTENT","DUPLICATE_MEANING","OTHER"]},"severity":{"enum":["INFO","WARNING","BLOCKER"]},"title":{"type":"string","minLength":1},"description":{"type":"string","minLength":1},"businessImpact":{"type":"string","minLength":1},"rootBlockTemporaryId":{"type":"string"}}}
              }
            }
            """);

    private static String schemaText(String schema) {
        // MATRIX axes are valid field identity even when the physical header row is
        // blank. Do not force the model to invent column names just to satisfy JSON
        // Schema; ROW/COLUMN_TABLE are still checked as non-empty in validateTables().
        var result = schema
                .replace("\"columns\":{\"type\":\"array\",\"minItems\":1,",
                        "\"columns\":{\"type\":\"array\",")
                .replace("\"axis\":{\"enum\":[\"ROW\",\"COLUMN\"]}}",
                        "\"axis\":{\"enum\":[\"ROW\",\"COLUMN\"]},\"role\":{\"type\":\"string\"},\"memberMode\":{\"type\":\"string\"}}");
        result = result
                .replace("\"editability\":{\"enum\":[\"EDITABLE\",\"READ_ONLY\",\"CONDITIONAL\",\"UNKNOWN\"]}",
                        "\"editability\":" + jsonEnum(SemanticProtocolTypes.EDITABILITY))
                .replace("\"valueSource\":{\"enum\":[\"USER_INPUT\",\"FORMULA\",\"REFERENCE\",\"STATIC\",\"MIXED\",\"UNKNOWN\"]}",
                        "\"valueSource\":" + jsonEnum(SemanticProtocolTypes.VALUE_SOURCES))
                .replace("\"valueType\":{\"enum\":[\"string\",\"number\",\"integer\",\"boolean\",\"date\",\"datetime\",\"time\",\"duration\"]}",
                        "\"valueType\":" + jsonEnum(SemanticProtocolTypes.VALUE_TYPES));
        result = result.replace(
                "\"semanticMode\":{\"enum\":[\"ROW_RECORDS\",\"CROSS_TAB\",\"RECORD_SET\",\"UNKNOWN\"]}",
                "\"semanticMode\":{\"enum\":[\"ROW_RECORDS\",\"COLUMN_RECORDS\",\"CROSS_TAB\",\"RECORD_SET\",\"UNKNOWN\"]}"
        );
        return result.replace("\"terminationRule\":{\"type\":\"object\",",
                "\"cornerRange\":{\"type\":\"string\"},\"recordAxis\":{\"enum\":[\"ROW\",\"COLUMN\",\"UNKNOWN\"]},\"layoutMode\":{\"type\":\"string\"},\"measureHeight\":{\"type\":\"integer\",\"minimum\":1},\"recordHeightIncludesIdentity\":{\"type\":\"boolean\"},\"recordProjection\":{\"type\":\"object\",\"additionalProperties\":true},\"columnSlots\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"additionalProperties\":true}},\"columnMemberRole\":{\"type\":\"string\"},\"memberMode\":{\"type\":\"string\"},\"bindings\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"additionalProperties\":true}},\"terminationRule\":{\"type\":\"object\",");
    }

    private static String jsonEnum(Set<String> values) {
        return "{\"enum\":[" + values.stream().sorted()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }
}
