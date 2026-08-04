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
    static final String PROMPT_VERSION = "template-global-semantic-v1";

    private static final Pattern ADDRESS = Pattern.compile(
            "^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$"
    );
    private static final Pattern INLINE_KEY_VALUE = Pattern.compile(
            "^\\s*([^：:\\r\\n]{1,40})\\s*[：:]\\s*(\\S[\\s\\S]*)$"
    );
    private static final Set<String> ANNOTATION_ROLES = Set.of(
            "DOCUMENT_TITLE", "INLINE_METADATA", "FIELD_LABEL", "FIELD_VALUE",
            "TABLE_HEADER", "TABLE_DATA", "TABLE_TOTAL", "INSTRUCTION", "CONFIRMATION",
            "SIGNATURE", "NOTE", "LOOKUP_DATA", "STATIC_REFERENCE", "UNKNOWN"
    );
    private static final Set<String> BLOCK_TYPES = Set.of(
            "DOCUMENT_HEADER", "FORM_FIELDS", "ROW_TABLE", "MATRIX", "INSTRUCTION_LIST",
            "CONFIRMATION_BLOCK", "SIGNATURE_BLOCK", "NOTE_BLOCK", "LOOKUP_TABLE", "UNKNOWN"
    );
    private static final Set<String> RELATION_TYPES = Set.of("LABEL_VALUE", "INLINE_TEXT");
    private static final Set<String> VALUE_TYPES = Set.of(
            "string", "number", "integer", "boolean", "date", "datetime", "time", "duration"
    );
    private static final Set<String> EDITABILITY = Set.of(
            "EDITABLE", "READ_ONLY", "CONDITIONAL", "UNKNOWN"
    );
    private static final Set<String> VALUE_SOURCES = Set.of(
            "USER_INPUT", "FORMULA", "REFERENCE", "STATIC", "MIXED", "UNKNOWN"
    );
    private static final Set<String> TABLE_KINDS = Set.of("ROW_TABLE", "MATRIX");
    private static final Set<String> TABLE_SEMANTIC_MODES = Set.of(
            "ROW_RECORDS", "CROSS_TAB", "RECORD_SET", "UNKNOWN"
    );
    private static final Set<String> ISSUE_CATEGORIES = Set.of(
            "FIELD_RELATION_UNCLEAR", "BUSINESS_BLOCK_UNCLEAR", "TABLE_STRUCTURE_UNCLEAR",
            "EDITABILITY_UNCLEAR", "LAYOUT_INCONSISTENT", "DUPLICATE_MEANING", "OTHER"
    );
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "BLOCKER");
    private static final Set<String> STATIC_BLOCK_TYPES = Set.of(
            "DOCUMENT_HEADER", "INSTRUCTION_LIST", "NOTE_BLOCK", "LOOKUP_TABLE"
    );

    private final ObjectMapper objectMapper;

    GlobalSemanticRecognitionProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode responseSchema() {
        try {
            return objectMapper.readTree(SCHEMA);
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

        var rejectedRelations = new RecoverySummary();
        var validRelations = validRelations(
                candidate.path("fieldRelations"), physicalFacts, sheets, blockIds, blocks, rejectedRelations
        );
        candidate.set("fieldRelations", validRelations);
        var relationIds = uniqueIds(validRelations, "fieldRelations");

        var rejectedTables = new RecoverySummary();
        var validTables = validTables(candidate.path("tables"), sheets, blockIds, blocks, rejectedTables);
        candidate.set("tables", validTables);
        var tableIds = uniqueIds(validTables, "tables");

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
            Set<String> blockIds, Map<String, JsonNode> blocks, RecoverySummary recovery
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

    private String physicalCellText(JsonNode physicalFacts, String sheetId, String range) {
        var anchor = range == null ? "" : range.split(":", 2)[0];
        for (var cell : physicalFacts.path("semanticCells")) {
            if (!sheetId.equals(cell.path("sheetId").asText())) continue;
            if (!anchor.equalsIgnoreCase(cell.path("address").asText())
                    && !range.equalsIgnoreCase(cell.path("mergedRange").asText(""))) continue;
            return cell.path("value").isTextual() ? cell.path("value").asText() : "";
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
            Map<String, JsonNode> blocks, RecoverySummary recovery
    ) {
        var result = objectMapper.createArrayNode();
        var ids = new HashSet<String>();
        for (var value : values) {
            try {
                requireObject(value, "tables[]");
                var table = (ObjectNode) value.deepCopy();
                var id = table.path("temporaryId").asText("");
                require(!id.isBlank() && ids.add(id), "tables 中 temporaryId 缺失或重复");
                validateTables(objectMapper.createArrayNode().add(table), sheets, blockIds, blocks);
                result.add(table);
            } catch (ProtocolViolationException exception) {
                recovery.reject(value, "range", sheets, blockIds);
                var pending = pendingTable(value, sheets, blockIds, blocks);
                if (pending != null) result.add(pending);
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
        pending.put("semanticMode", "UNKNOWN");
        pending.put("rowHeaderRange", "").put("columnHeaderRange", "").put("crossDataRange", "");
        pending.withArray("headerTree").removeAll();
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
                    "父业务块必须完整包含子业务块：" + entry.getKey());
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
                        "同一父块下的兄弟业务块不能重叠");
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
            exactKeys(value, path, Set.of(
                    "temporaryId", "sheetId", "range", "tableKind", "businessName",
                    "blockTemporaryId", "groupNameSuggestion", "semanticKeySuggestion",
                    "headerRange", "dataRange", "totalRange", "columns", "semanticMode",
                    "rowHeaderRange", "columnHeaderRange", "crossDataRange", "headerTree"
            ));
            requiredText(value, "temporaryId", path);
            requiredText(value, "businessName", path);
            validateRange(value, path, sheets);
            validateNamedRange(value, "headerRange", path, sheets);
            validateNamedRange(value, "dataRange", path, sheets);
            if (value.has("totalRange") && !value.path("totalRange").asText("").isBlank()) {
                validateNamedRange(value, "totalRange", path, sheets);
            }
            enumValue(value, "tableKind", TABLE_KINDS, path);
            enumValue(value, "semanticMode", TABLE_SEMANTIC_MODES, path);
            optionalRef(value, "blockTemporaryId", blockIds, path);
            require(nonBlankReference(value, "blockTemporaryId", blockIds),
                    path + ".blockTemporaryId 必须引用包含该表格的业务块");
            var tableRange = bounds(value.path("range").asText());
            var headerRange = bounds(value.path("headerRange").asText());
            var dataRange = bounds(value.path("dataRange").asText());
            var totalRange = value.path("totalRange").asText("").isBlank()
                    ? null : bounds(value.path("totalRange").asText());
            var block = blocks.get(value.path("blockTemporaryId").asText());
            require(block != null && value.path("sheetId").asText().equals(block.path("sheetId").asText())
                            && bounds(block.path("range").asText()).contains(tableRange),
                    path + " 的表格范围必须位于所属业务块内");
            require(tableRange.contains(headerRange) && tableRange.contains(dataRange)
                            && (totalRange == null || tableRange.contains(totalRange)),
                    path + " 的表头、数据和合计范围必须位于表格范围内");
            require(!headerRange.overlaps(dataRange), path + " 的表头范围与数据范围不能重叠");
            if ("ROW_TABLE".equals(value.path("tableKind").asText())) {
                require("ROW_RECORDS".equals(value.path("semanticMode").asText()),
                        path + " 的 ROW_TABLE 必须使用 ROW_RECORDS 语义模式");
                require(value.path("rowHeaderRange").asText("").isBlank()
                                && value.path("columnHeaderRange").asText("").isBlank()
                                && value.path("crossDataRange").asText("").isBlank(),
                        path + " 的 ROW_TABLE 不得虚构矩阵轴范围");
                require(headerRange.endRow() < dataRange.startRow(),
                        path + " 的 ROW_TABLE 表头必须位于数据区上方");
                if (totalRange != null) {
                    require(dataRange.endRow() < totalRange.startRow(),
                            path + " 的 ROW_TABLE 合计行必须位于数据区下方");
                }
            } else {
                require(Set.of("CROSS_TAB", "RECORD_SET").contains(value.path("semanticMode").asText()),
                        path + " 的 MATRIX 必须明确 CROSS_TAB 或 RECORD_SET 语义模式");
                validateNamedRange(value, "rowHeaderRange", path, sheets);
                validateNamedRange(value, "columnHeaderRange", path, sheets);
                validateNamedRange(value, "crossDataRange", path, sheets);
                var rowHeaderRange = bounds(value.path("rowHeaderRange").asText());
                var columnHeaderRange = bounds(value.path("columnHeaderRange").asText());
                var crossDataRange = bounds(value.path("crossDataRange").asText());
                require(tableRange.contains(rowHeaderRange) && tableRange.contains(columnHeaderRange)
                                && tableRange.contains(crossDataRange),
                        path + " 的矩阵行标题、列标题和交叉数据区必须位于矩阵范围内");
                require(!rowHeaderRange.overlaps(crossDataRange)
                                && !columnHeaderRange.overlaps(crossDataRange),
                        path + " 的矩阵轴标题不能与交叉数据区重叠");
                require(rowHeaderRange.endColumn() < crossDataRange.startColumn()
                                && columnHeaderRange.endRow() < crossDataRange.startRow(),
                        path + " 的矩阵行标题必须在数据区左侧、列标题必须在数据区上方");
                require(dataRange.equals(crossDataRange),
                        path + " 的 MATRIX dataRange 必须等于 crossDataRange");
            }
            validateHeaderTree(value.path("headerTree"), value, tableRange, path);
            require(value.path("columns").isArray() && !value.path("columns").isEmpty(),
                    path + ".columns 必须是非空数组");
            var columnIds = new HashSet<String>();
            for (int columnIndex = 0; columnIndex < value.path("columns").size(); columnIndex++) {
                var column = value.path("columns").get(columnIndex);
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
                require(headerRange.contains(columnLabel) && dataRange.contains(columnValue),
                        columnPath + " 的列名必须位于表头、值范围必须位于数据区");
                require(columnLabel.startColumn() == columnValue.startColumn()
                                && columnLabel.endColumn() == columnValue.endColumn(),
                        columnPath + " 的列名与数据区必须垂直对齐");
            }
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
            exactKeys(node, nodePath, Set.of("temporaryId", "parentTemporaryId", "name", "range", "axis"));
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

    private static final String SCHEMA = """
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
                "annotation":{"type":"object","additionalProperties":false,"required":["sheetId","range","role","temporaryRelationRef","temporaryBlockRef","temporaryTableRef"],"properties":{"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"role":{"enum":["DOCUMENT_TITLE","INLINE_METADATA","FIELD_LABEL","FIELD_VALUE","TABLE_HEADER","TABLE_DATA","TABLE_TOTAL","INSTRUCTION","CONFIRMATION","SIGNATURE","NOTE","LOOKUP_DATA","STATIC_REFERENCE","UNKNOWN"]},"temporaryRelationRef":{"type":"string"},"temporaryBlockRef":{"type":"string"},"temporaryTableRef":{"type":"string"}}},
                "block":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","range","type","parentTemporaryId","businessName","groupNameSuggestion","semanticKeySuggestion"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"type":{"enum":["DOCUMENT_HEADER","FORM_FIELDS","ROW_TABLE","MATRIX","INSTRUCTION_LIST","CONFIRMATION_BLOCK","SIGNATURE_BLOCK","NOTE_BLOCK","LOOKUP_TABLE","UNKNOWN"]},"parentTemporaryId":{"type":"string"},"businessName":{"type":"string","minLength":1},"groupNameSuggestion":{"type":"string"},"semanticKeySuggestion":{"type":"string"}}},
                "relation":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","labelRange","valueRange","relationType","businessName","blockTemporaryId","groupNameSuggestion","semanticKeySuggestion","valueType","required","editability","valueSource","unit","condition"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"labelRange":{"$ref":"#/$defs/range"},"valueRange":{"$ref":"#/$defs/range"},"relationType":{"enum":["LABEL_VALUE","INLINE_TEXT"]},"businessName":{"type":"string","minLength":1},"blockTemporaryId":{"type":"string"},"groupNameSuggestion":{"type":"string"},"semanticKeySuggestion":{"type":"string"},"valueType":{"$ref":"#/$defs/valueType"},"required":{"type":"boolean"},"editability":{"$ref":"#/$defs/editability"},"valueSource":{"$ref":"#/$defs/valueSource"},"unit":{"type":"string"},"condition":{"type":"string"}}},
                "column":{"type":"object","additionalProperties":false,"required":["temporaryId","name","labelRange","valueRange","valueType","editability","valueSource","unit","condition","semanticKeySuggestion"],"properties":{"temporaryId":{"type":"string","minLength":1},"name":{"type":"string","minLength":1},"labelRange":{"$ref":"#/$defs/range"},"valueRange":{"$ref":"#/$defs/range"},"valueType":{"$ref":"#/$defs/valueType"},"editability":{"$ref":"#/$defs/editability"},"valueSource":{"$ref":"#/$defs/valueSource"},"unit":{"type":"string"},"condition":{"type":"string"},"semanticKeySuggestion":{"type":"string"}}},
                "headerNode":{"type":"object","additionalProperties":false,"required":["temporaryId","parentTemporaryId","name","range","axis"],"properties":{"temporaryId":{"type":"string","minLength":1},"parentTemporaryId":{"type":"string"},"name":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"axis":{"enum":["ROW","COLUMN"]}}},
                "table":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","range","tableKind","businessName","blockTemporaryId","groupNameSuggestion","semanticKeySuggestion","headerRange","dataRange","totalRange","columns","semanticMode","rowHeaderRange","columnHeaderRange","crossDataRange","headerTree"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"tableKind":{"enum":["ROW_TABLE","MATRIX"]},"businessName":{"type":"string","minLength":1},"blockTemporaryId":{"type":"string"},"groupNameSuggestion":{"type":"string"},"semanticKeySuggestion":{"type":"string"},"headerRange":{"$ref":"#/$defs/range"},"dataRange":{"$ref":"#/$defs/range"},"totalRange":{"type":"string"},"columns":{"type":"array","minItems":1,"items":{"$ref":"#/$defs/column"}},"semanticMode":{"enum":["ROW_RECORDS","CROSS_TAB","RECORD_SET","UNKNOWN"]},"rowHeaderRange":{"type":"string"},"columnHeaderRange":{"type":"string"},"crossDataRange":{"type":"string"},"headerTree":{"type":"array","items":{"$ref":"#/$defs/headerNode"}}}},
                "issue":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","range","category","severity","title","description","businessImpact","rootBlockTemporaryId"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"category":{"enum":["FIELD_RELATION_UNCLEAR","BUSINESS_BLOCK_UNCLEAR","TABLE_STRUCTURE_UNCLEAR","EDITABILITY_UNCLEAR","LAYOUT_INCONSISTENT","DUPLICATE_MEANING","OTHER"]},"severity":{"enum":["INFO","WARNING","BLOCKER"]},"title":{"type":"string","minLength":1},"description":{"type":"string","minLength":1},"businessImpact":{"type":"string","minLength":1},"rootBlockTemporaryId":{"type":"string"}}}
              }
            }
            """;
}
