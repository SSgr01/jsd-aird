package com.jsd.aird.tpl.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.CanonicalMatrixCompiler;
import com.jsd.aird.tpl.application.GroupNameNormalizer;
import com.jsd.aird.tpl.application.RecognitionIdentity;
import com.jsd.aird.tpl.application.StandardFieldDictionary;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.application.port.StandardFieldRepository;

/** Converts a validated sparse semantic response into persistence-compatible candidates. */
final class GlobalSemanticSuggestionCompiler {

    private static final Set<String> STATIC_BLOCK_TYPES = Set.of(
            "DOCUMENT_HEADER", "INSTRUCTION_LIST", "NOTE_BLOCK", "LOOKUP_TABLE", "STATIC_REFERENCE"
    );

    private final ObjectMapper objectMapper;
    private final StandardFieldRepository standardFieldRepository;
    private final CanonicalMatrixCompiler matrixCompiler;

    GlobalSemanticSuggestionCompiler(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    GlobalSemanticSuggestionCompiler(ObjectMapper objectMapper, StandardFieldRepository standardFieldRepository) {
        this.objectMapper = objectMapper;
        this.standardFieldRepository = standardFieldRepository;
        this.matrixCompiler = new CanonicalMatrixCompiler(objectMapper);
    }

    Compiled compile(ObjectNode response, JsonNode physicalFacts) {
        var suggestions = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var sheetNames = sheetNames(physicalFacts);
        var blocks = stableBlocks(response.path("businessBlocks"));

        var semanticModel = objectMapper.createObjectNode()
                .put("kind", "SEMANTIC_MODEL")
                .put("recognitionMode", "FAITHFUL")
                .put("recognitionProtocolVersion", GlobalSemanticRecognitionProtocol.VERSION);
        var semanticAnnotations = remapAnnotations(response.path("semanticAnnotations"), blocks);
        semanticModel.set("semanticAnnotations", semanticAnnotations);
        var stableBlockArray = objectMapper.createArrayNode();
        blocks.values().forEach(block -> stableBlockArray.add(block.deepCopy()));
        semanticModel.set("businessBlocks", stableBlockArray);
        semanticModel.set("staticRegions", staticRegions(semanticAnnotations, stableBlockArray));
        semanticModel.set("diagnostics", rejectedDiagnostics(response));
        suggestions.add(new RecognitionModelClient.ModelSuggestion(
                "SEMANTIC_MODEL", semanticModel, 1, objectMapper.createArrayNode()
        ));

        var units = new ArrayList<Unit>();
        for (var relation : response.path("fieldRelations")) {
            if (!isFormalRelation(relation, response.path("tables"), blocks)) continue;
            units.add(new Unit("RELATION", relation, relation.path("sheetId").asText(),
                    relation.path("valueRange").asText(), groupName(relation, blocks)));
        }
        for (var table : response.path("tables")) {
            units.add(new Unit("TABLE", table, table.path("sheetId").asText(),
                    table.path("range").asText(), groupName(table, blocks)));
        }
        units.sort(Comparator.comparing(Unit::sheetId).thenComparingInt(unit -> row(unit.range()))
                .thenComparingInt(unit -> column(unit.range())));
        var groupOrdinals = new HashMap<String, Integer>();
        for (var unit : units) {
            var groupCode = GroupNameNormalizer.code(unit.groupName());
            var ordinal = groupOrdinals.merge(groupCode, 1, Integer::sum);
            if ("RELATION".equals(unit.type())) {
                var relation = relation(unit.value(), unit.groupName(), groupCode, ordinal, blocks, sheetNames);
                if (relation != null) suggestions.add(relation);
            } else {
                var tableSuggestion = table(unit.value(), unit.groupName(), groupCode, ordinal,
                        blocks, sheetNames, physicalFacts);
                suggestions.add(tableSuggestion);
                suggestions.addAll(tableChildren(tableSuggestion));
            }
        }

        normalizeUniqueDataPaths(suggestions);

        var qualityIssues = new ArrayList<RecognitionModelClient.QualityIssueSuggestion>();
        for (var issue : response.path("qualityIssues")) {
            var rootTemporaryId = issue.path("rootBlockTemporaryId").asText("");
            var rootBlockId = blocks.containsKey(rootTemporaryId)
                    ? blocks.get(rootTemporaryId).path("blockId").asText() : "sheet-root";
            var evidence = issue.path("temporaryId").asText("").startsWith("protocol-recovery-")
                    ? objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("internalRecovery", true))
                    : objectMapper.createArrayNode();
            qualityIssues.add(new RecognitionModelClient.QualityIssueSuggestion(
                    issue.path("category").asText(), issue.path("severity").asText(),
                    issue.path("sheetId").asText(), sheetNames.getOrDefault(
                            issue.path("sheetId").asText(), issue.path("sheetId").asText()),
                    issue.path("range").asText(), issue.path("title").asText(),
                    issue.path("description").asText(), issue.path("businessImpact").asText(),
                    1, false, objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                    evidence, "DETECTED", rootBlockId, null
            ));
        }
        return new Compiled(List.copyOf(suggestions), List.copyOf(qualityIssues));
    }

    /**
     * Compiles the validated v2 batch response without sending it through the
     * legacy protocol validator.  The compiler creates its own internal
     * persistence shape only after protocol validation has completed; the v2
     * response remains the audited source of truth.
     */
    Compiled compileRegionBatch(ObjectNode normalized, JsonNode context) {
        return compile(regionCompilerEnvelope(normalized, context), context);
    }

    private ObjectNode regionCompilerEnvelope(ObjectNode normalized, JsonNode context) {
        var result = objectMapper.createObjectNode().put("recognitionProtocolVersion", 1);
        result.putArray("semanticAnnotations");
        var blocks = result.putArray("businessBlocks");
        var relations = result.putArray("fieldRelations");
        var tables = result.putArray("tables");
        result.set("qualityIssues", normalized.path("qualityIssues").deepCopy());
        var contexts = new LinkedHashMap<String, JsonNode>();
        for (var region : context.path("semanticRegions")) {
            var id = region.path("regionId").asText(region.path("blockId").asText(""));
            if (!id.isBlank()) contexts.put(id, region);
        }
        for (var semantic : normalized.path("regions")) {
            var id = semantic.path("regionId").asText();
            var geometry = contexts.get(id);
            if (geometry == null) continue;
            var sheetId = geometry.path("sheetId").asText();
            var range = geometry.path("range").asText();
            var type = geometry.path("type").asText("UNKNOWN");
            var blockId = id;
            blocks.add(objectMapper.createObjectNode().put("temporaryId", blockId)
                    .put("sheetId", sheetId).put("range", range).put("type", type)
                    .put("parentTemporaryId", "")
                    .put("businessName", semantic.path("businessName").asText("待确认区域"))
                    .put("groupNameSuggestion", "").put("semanticKeySuggestion", ""));
            if (Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE").contains(type)) {
                var structure = geometry.path("structure");
                var matrix = "MATRIX".equals(type);
                var table = objectMapper.createObjectNode()
                        .put("temporaryId", "table-" + id).put("sheetId", sheetId).put("range", range)
                        .put("tableKind", type)
                        .put("businessName", semantic.path("businessName").asText("待确认表格"))
                        .put("blockTemporaryId", blockId).put("groupNameSuggestion", "")
                        .put("semanticKeySuggestion", "")
                        .put("headerRange", structure.path(matrix ? "columnHeaderRange" : "headerRange").asText(range))
                        .put("dataRange", structure.path(matrix ? "crossDataRange" : "dataRange").asText(range))
                        .put("totalRange", "")
                        .put("semanticMode", matrix ? "CROSS_TAB" : "ROW_RECORDS")
                        .put("rowHeaderRange", structure.path("rowHeaderRange").asText(""))
                        .put("columnHeaderRange", structure.path("columnHeaderRange").asText(""))
                        .put("crossDataRange", structure.path("crossDataRange").asText(""))
                        .put("recordAxis", structure.path("recordAxis").asText("UNKNOWN"));
                for (var key : List.of("canonicalStatus", "structureStatus", "candidateOnly",
                        "physicalStructureOnly", "reviewRequired", "structureConflict",
                        "resolutionGroupId", "pendingReason")) {
                    if (geometry.has(key)) table.set(key, geometry.path(key).deepCopy());
                }
                table.set("cornerRange", structure.path("cornerRange").deepCopy());
                table.set("headerTree", objectMapper.createArrayNode());
                table.set("rowDimensions", semantic.path("rowDimensions").deepCopy());
                table.set("rowAttributes", semantic.path("rowAttributes").deepCopy());
                var columns = table.putArray("columns");
                if (!matrix) {
                    for (var relation : semantic.path("fieldRelations")) {
                        var relationCopy = (ObjectNode) relation.deepCopy();
                        relationCopy.put("sheetId", sheetId).put("blockTemporaryId", blockId);
                        relations.add(relationCopy);
                        columns.add(objectMapper.createObjectNode()
                                .put("temporaryId", relation.path("temporaryId").asText("column-" + columns.size()))
                                .put("name", relation.path("businessName").asText("待确认列"))
                                .put("labelRange", relation.path("labelRange").asText())
                                .put("valueRange", relation.path("valueRange").asText())
                                .put("valueType", relation.path("valueType").asText("UNKNOWN"))
                                .put("editability", relation.path("editability").asText("UNKNOWN"))
                                .put("valueSource", relation.path("valueSource").asText("UNKNOWN"))
                                .put("unit", relation.path("unit").asText(""))
                                .put("condition", relation.path("condition").asText("")));
                    }
                }
                tables.add(table);
            } else {
                for (var relation : semantic.path("fieldRelations")) {
                    var relationCopy = (ObjectNode) relation.deepCopy();
                    relationCopy.put("sheetId", sheetId).put("blockTemporaryId", blockId);
                    relations.add(relationCopy);
                }
            }
        }
        return result;
    }

    private ArrayNode rejectedDiagnostics(JsonNode response) {
        var diagnostics = objectMapper.createArrayNode();
        var rejectedRelations = response.path("_rejectedRelations");
        if (rejectedRelations.isArray() && !rejectedRelations.isEmpty()) {
            diagnostics.add(objectMapper.createObjectNode()
                    .put("stage", "PROTOCOL_VALIDATION")
                    .put("reasonCode", "REJECTED_FIELD_RELATIONS")
                    .put("message", "系统已忽略不符合字段关系约束的候选")
                    .set("detail", objectMapper.createObjectNode().put("count", rejectedRelations.size())));
        }
        var rejectedTables = response.path("_rejectedTables");
        if (rejectedTables.isArray() && !rejectedTables.isEmpty()) {
            diagnostics.add(objectMapper.createObjectNode()
                    .put("stage", "PROTOCOL_VALIDATION")
                    .put("reasonCode", "REJECTED_TABLE_CANDIDATES")
                    .put("message", "系统已忽略不符合结构范围约束的表格候选")
                    .set("detail", objectMapper.createObjectNode().put("count", rejectedTables.size())));
        }
        return diagnostics;
    }

    /**
     * A standard semantic path is a useful default, but it is not a physical
     * field identity. Keep the first canonical path for compatibility and add
     * a deterministic suffix to later physical occurrences.
     */
    private void normalizeUniqueDataPaths(List<RecognitionModelClient.ModelSuggestion> suggestions) {
        var usedPaths = new HashSet<String>();
        var childPaths = new HashMap<String, String>();
        for (var suggestion : suggestions) {
            if ("SEMANTIC_MODEL".equals(suggestion.suggestionType())
                    || !(suggestion.payload() instanceof ObjectNode payload)) {
                continue;
            }
            var relationId = payload.path("relationId").asText("");
            var level = payload.path("suggestionLevel").asText("");
            if ("ROOT".equals(level) && payload.path("columns").isArray()) {
                var parentPath = reserveDataPath(payload.path("dataPath").asText(""), relationId, usedPaths);
                if (!parentPath.isBlank()) payload.put("dataPath", parentPath);
                for (var column : payload.path("columns")) {
                    if (!(column instanceof ObjectNode columnObject)) continue;
                    var childRelation = childRelationId(relationId, columnObject);
                    var columnPath = reserveDataPath(
                            column.path("dataPath").asText(""), childRelation, usedPaths
                    );
                    if (!columnPath.isBlank()) {
                        columnObject.put("dataPath", columnPath);
                        childPaths.put(childRelation, columnPath);
                    }
                }
                continue;
            }
            if ("CHILD".equals(level)) {
                var childPath = childPaths.get(relationId);
                if (childPath == null) {
                    childPath = reserveDataPath(payload.path("dataPath").asText(""), relationId, usedPaths);
                }
                if (!childPath.isBlank()) payload.put("dataPath", childPath);
                continue;
            }
            var uniquePath = reserveDataPath(payload.path("dataPath").asText(""), relationId, usedPaths);
            if (!uniquePath.isBlank()) payload.put("dataPath", uniquePath);
        }
    }

    private String reserveDataPath(String basePath, String identity, Set<String> usedPaths) {
        if (basePath == null || basePath.isBlank()) return "";
        var candidate = basePath;
        if (usedPaths.contains(candidate)) {
            var suffix = RecognitionIdentity.shortHash(identity + "|" + basePath, 10);
            candidate = basePath + "__" + suffix;
            var ordinal = 2;
            while (usedPaths.contains(candidate)) candidate = basePath + "__" + suffix + "_" + ordinal++;
        }
        usedPaths.add(candidate);
        return candidate;
    }

    private String childRelationId(String parentRelationId, JsonNode column) {
        return childRelationId(parentRelationId, column.path("code").asText("column"),
                column.path("valueRange").asText(""));
    }

    private String childRelationId(String parentRelationId, String code, String valueRange) {
        return parentRelationId + "|child|" + code + "|"
                + RecognitionIdentity.normalizeRange(valueRange);
    }

    private RecognitionModelClient.ModelSuggestion relation(
            JsonNode source, String groupName, String groupCode, int ordinal,
            Map<String, ObjectNode> blocks, Map<String, String> sheetNames
    ) {
        if ("INLINE_TEXT".equals(source.path("relationType").asText())
                && sameRange(source.path("labelRange").asText(), source.path("valueRange").asText())) {
            return null;
        }
        var sheetId = source.path("sheetId").asText();
        var relationId = RecognitionIdentity.relationId(
                sheetId, source.path("labelRange").asText(), source.path("valueRange").asText(),
                source.path("relationType").asText()
        );
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var inlineText = "INLINE_TEXT".equals(source.path("relationType").asText());
        var locatorType = inlineText ? "INLINE_TEXT" : "CELL_RANGE";
        var bindingId = RecognitionIdentity.bindingId(
                fieldId, locatorType, sheetId + "|" + source.path("valueRange").asText()
        );
        var standard = standard(source.path("businessName").asText());
        var relationFieldCode = standard == null
                ? fieldCode("FIELD", relationId) : standard.fieldCode();
        var relationDataPath = standard == null
                ? dataPath("field", relationId)
                : "/recognized/basicInformation/" + standard.pathSegment();
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR")
                .put("suggestionLevel", "SCALAR")
                .put("relationId", relationId)
                .put("fieldId", fieldId.toString())
                .put("bindingId", bindingId.toString())
                .put("temporaryRelationId", source.path("temporaryId").asText())
                .put("fieldCode", relationFieldCode)
                .put("dataPath", relationDataPath)
                .put("fieldName", source.path("businessName").asText())
                .put("groupName", groupName)
                .put("valueType", source.path("valueType").asText())
                .put("required", source.path("required").asBoolean(false))
                .put("role", "FIELD").put("locatorType", locatorType)
                .put("editability", source.path("editability").asText())
                .put("valueSource", source.path("valueSource").asText())
                 .put("dictionaryVersion", standard == null ? StandardFieldDictionary.VERSION : standard.version())
                 .put("standardMatchStatus", standard == null ? "UNMATCHED" : "MATCHED")
                 .put("requiresStandardConfirmation", standard == null)
                .put("unit", source.path("unit").asText(""))
                .put("condition", source.path("condition").asText(""))
                .put("reason", "根据完整工作簿的标签和值关系识别")
                 .put("interpretation", "系统认为这里用于填写或读取“"
                         + source.path("businessName").asText() + "”。");
        if (standard != null && standard.id() != null) {
            payload.put("standardFieldId", standard.id().toString())
                    .put("standardFieldVersion", standard.version())
                    .put("standardFieldName", standard.displayName())
                    .put("fieldOrigin", "STANDARD")
                    .put("standardSelectionStatus", "MATCHED")
                    .put("uiType", standard.uiType());
        }
        var locator = locator(sheetId, sheetNames.getOrDefault(sheetId, sheetId),
                source.path("labelRange").asText(), source.path("valueRange").asText(),
                inlineText ? "INLINE_TEXT" : "ANCHOR");
        if (inlineText) {
            locator.put("valuePart", "AFTER_DELIMITER");
            locator.put("labelPrefix", source.path("businessName").asText());
        }
        payload.set("locator", locator);
        attachBlock(payload, source.path("blockTemporaryId").asText(""), blocks);
        return new RecognitionModelClient.ModelSuggestion(
                "SCALAR_FIELD", payload, confidence(source), objectMapper.createArrayNode()
        );
    }

    private RecognitionModelClient.ModelSuggestion table(
            JsonNode source, String groupName, String groupCode, int ordinal,
            Map<String, ObjectNode> blocks, Map<String, String> sheetNames, JsonNode physicalFacts
    ) {
        var sheetId = source.path("sheetId").asText();
        var kind = source.path("tableKind").asText();
        var relationId = RecognitionIdentity.relationId(
                sheetId, source.path("headerRange").asText(), source.path("dataRange").asText(), kind
        );
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var locatorType = "MATRIX".equals(kind) ? "MATRIX_REGION" : "TABLE_REGION";
        var bindingId = RecognitionIdentity.bindingId(
                fieldId, locatorType, sheetId + "|" + source.path("range").asText()
        );
        // 模型有时只能确定“这里是一张表”，却没有返回 columns。此时不能把
        // 整张表降级成一个孤立的父节点；物理解析器已经掌握了表头和数据区，
        // 用它补出可审核的候选子字段，待用户确认后再进入正式 Mapping。
        // MATRIX columns are runtime member slots, not semantic field
        // relations.  Their geometry and instances are generated by
        // CanonicalMatrixCompiler below; asking the generic table fallback to
        // inspect them would recreate one business field per sample column.
        var sourceColumns = "MATRIX".equals(kind)
                ? List.<JsonNode>of() : normalizeColumns(source, physicalFacts);
        var formulaTable = !"MATRIX".equals(kind) && isFormulaTable(source, source.path("columns"));
        var parentFieldCode = formulaTable ? "FORMULA.ITEMS" : fieldCode("TABLE", relationId);
        var parentDataPath = formulaTable ? "/formulaItems" : dataPath("table", relationId);
        var payload = objectMapper.createObjectNode()
                .put("kind", kind).put("relationId", relationId)
                .put("suggestionLevel", "ROOT")
                .put("fieldId", fieldId.toString()).put("bindingId", bindingId.toString())
                .put("temporaryRelationId", source.path("temporaryId").asText())
                .put("fieldCode", parentFieldCode)
                .put("dataPath", parentDataPath)
                .put("fieldName", source.path("businessName").asText())
                .put("groupName", groupName).put("valueType", "array")
                .put("required", false).put("role", "REPEAT_REGION")
                .put("locatorType", locatorType)
                .put("repeatAxis", source.path("repeatAxis").asText(
                        Set.of("MATRIX").contains(kind) ? "" : "COLUMN_TABLE".equals(kind) ? "COLUMN" : "ROW"))
                .put("recordHeight", source.path("recordHeight").asInt(1))
                .put("recordWidth", source.path("recordWidth").asInt(1))
                .put("recordStride", source.path("recordStride").asInt(1))
                .put("hasIndependentChildren", true)
                .put("reason", "根据完整工作簿的表头、数据区和业务上下文识别")
                .put("interpretation", "ROW_TABLE".equals(kind)
                        ? "系统认为这里按行填写或读取“" + source.path("businessName").asText() + "”记录。"
                        : "COLUMN_TABLE".equals(kind)
                        ? "系统认为这里按列填写或读取“" + source.path("businessName").asText() + "”记录。"
                        : "系统认为这里按两个业务维度记录“" + source.path("businessName").asText() + "”。");
        var locator = locator(sheetId, sheetNames.getOrDefault(sheetId, sheetId),
                source.path("headerRange").asText(), source.path("range").asText(), "ARRAY");
        locator.put("headerRange", source.path("headerRange").asText());
        locator.put("dataRange", source.path("dataRange").asText());
        locator.put("logicalInputRange", source.path("dataRange").asText());
        if (source.has("totalRange")) locator.put("totalRange", source.path("totalRange").asText());
        if (source.path("terminationRule").isObject()) {
            payload.set("terminationRule", source.path("terminationRule").deepCopy());
            locator.set("terminationRule", source.path("terminationRule").deepCopy());
        }
        if ("MATRIX".equals(kind)) {
            locator.put("rowHeaderRange", source.path("rowHeaderRange").asText());
            locator.put("columnHeaderRange", source.path("columnHeaderRange").asText());
            locator.put("crossDataRange", source.path("crossDataRange").asText());
        }
        payload.set("locator", locator);
        for (var key : List.of("canonicalStatus", "structureStatus", "candidateOnly",
                "physicalStructureOnly", "reviewRequired", "structureConflict",
                "resolutionGroupId", "pendingReason", "publishable", "humanResolved",
                "canonicalStructureMayReopen", "modelAssessmentVerdict", "resolutionSource")) {
            if (source.has(key)) payload.set(key, source.path(key).deepCopy());
        }
        var columns = objectMapper.createArrayNode();
        var runtimeSlots = objectMapper.createArrayNode();
        var hasRuntimeSlots = false;
        var columnOrdinal = 0;
        var usedColumnPaths = new java.util.HashSet<String>();
        var tableEditability = "READ_ONLY";
        var tableValueSource = "STATIC";
        for (var sourceColumn : sourceColumns) {
            columnOrdinal++;
            var editability = sourceColumn.path("editability").asText();
            var valueSource = sourceColumn.path("valueSource").asText();
            if ("EDITABLE".equals(editability) || "CONDITIONAL".equals(editability)) tableEditability = "EDITABLE";
            if ("USER_INPUT".equals(valueSource) || "MIXED".equals(valueSource)) tableValueSource = "USER_INPUT";
            var columnIdentity = uniqueColumnIdentity(
                    sourceColumn, columnOrdinal, formulaTable, usedColumnPaths
            );
            var column = objectMapper.createObjectNode()
                    .put("code", columnIdentity.code())
                    .put("relationId", childRelationId(relationId, columnIdentity.code(),
                            sourceColumn.path("valueRange").asText("")))
                    .put("fieldCode", columnIdentity.fieldCode())
                    .put("dataPath", formulaTable
                            ? "/formulaItems/*/" + columnIdentity.pathSegment()
                            : parentDataPath + "/*/" + columnIdentity.pathSegment())
                    .put("name", columnIdentity.name())
                    .put("labelRange", sourceColumn.path("labelRange").asText())
                    .put("valueRange", sourceColumn.path("valueRange").asText())
                    .put("columnOffset", sourceColumn.path("columnOffset").asInt(
                            Math.max(0, columnStart(sourceColumn.path("valueRange").asText()) - 1)))
                    .put("columnSpan", sourceColumn.path("columnSpan").asInt(1))
                    .put("valueType", sourceColumn.path("valueType").asText())
                    .put("editability", editability).put("valueSource", valueSource)
                    .put("unit", sourceColumn.path("unit").asText(""))
                    .put("condition", sourceColumn.path("condition").asText(""))
                    .put("required", false)
                    .put("dataStartRow", firstRow(sourceColumn.path("valueRange").asText()));
            if (column.path("name").asText("").isBlank()) {
                hasRuntimeSlots = true;
                column.put("runtimeInputOnly", true);
                runtimeSlots.add(runtimeSlot(column, source, physicalFacts));
            }
            if (sourceColumn.path("physicalColumnRanges").isArray()) {
                column.set("physicalColumnRanges", sourceColumn.path("physicalColumnRanges").deepCopy());
            }
            if (sourceColumn.has("mergeRange")) {
                column.put("mergeRange", sourceColumn.path("mergeRange").asText());
            }
            if (sourceColumn.has("valueMode")) {
                column.put("valueMode", sourceColumn.path("valueMode").asText());
            }
            var standard = standard(sourceColumn.path("name").asText());
            if (standard != null) column.put("fieldCode", standard.fieldCode());
            column.put("dictionaryVersion", standard == null ? StandardFieldDictionary.VERSION : standard.version())
                    .put("standardMatchStatus", standard == null ? "UNMATCHED" : "MATCHED")
                    .put("requiresStandardConfirmation", standard == null);
            if (standard != null && standard.id() != null) {
                column.put("standardFieldId", standard.id().toString())
                        .put("standardFieldVersion", standard.version())
                        .put("standardFieldName", standard.displayName())
                        .put("fieldOrigin", "STANDARD")
                        .put("standardSelectionStatus", "MATCHED")
                        .put("uiType", standard.uiType());
            }
            if (sourceColumn.path("name").asText("").contains("比例") && isMassUnit(sourceColumn)) {
                column.put("semanticConflict", true)
                        .put("conflictCode", "RATIO_OR_THEORETICAL_QUANTITY")
                        .put("conflictMessage", "该列名称是“配方比例”，但单位表现为质量单位，可能是比例或理论投料量。");
                column.set("semanticAlternatives", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("fieldCode", "FORMULA.ITEM.RATIO")
                                .put("name", "配方比例"))
                        .add(objectMapper.createObjectNode()
                                .put("fieldCode", "FORMULA.ITEM.THEORETICAL_KG")
                                .put("name", "理论投料量")));
            }
            columns.add(column);
        }
        payload.put("editability", tableEditability).put("valueSource", tableValueSource);
        payload.set("columns", columns);
        if (hasRuntimeSlots) {
            payload.put("runtimeInputOnly", true)
                    .put("blankAxisPolicy", "SKIP_EMPTY_RUNTIME_MEMBER")
                    .put("trainingPolicy", "REQUIRE_RUNTIME_MEMBER");
            payload.set("columnSlots", runtimeSlots);
        }
        // A MATRIX deliberately has no generic table columns: its D:I-like
        // runtime members are compiled as slots below.  Do not turn the
        // absence of fieldRelations into TABLE_STRUCTURE_UNCLEAR, otherwise
        // a confirmed cross-tab is downgraded to candidateOnly merely because
        // its runtime header cells are still blank.
        if (!"MATRIX".equals(kind)
                && (columns.isEmpty() || "UNKNOWN".equals(source.path("semanticMode").asText()))) {
            payload.put("candidateOnly", true)
                    .put("pendingReason", "TABLE_STRUCTURE_UNCLEAR")
                    .put("reason", "表格范围可定位，但列语义仍需确认");
        }
        var tableModel = objectMapper.createObjectNode()
                .put("headerRange", source.path("headerRange").asText())
                .put("dataRange", source.path("dataRange").asText())
                .put("totalRange", source.path("totalRange").asText())
                .put("repeatAxis", source.path("repeatAxis").asText("ROW"))
                .put("recordHeight", source.path("recordHeight").asInt(1))
                .put("recordWidth", source.path("recordWidth").asInt(1))
                .put("recordStride", source.path("recordStride").asInt(1));
        tableModel.set("terminationRule", source.path("terminationRule").deepCopy());
        tableModel.set("headerTree", source.path("headerTree").deepCopy());
        tableModel.set("columns", columns.deepCopy());
        if (hasRuntimeSlots) tableModel.set("columnSlots", runtimeSlots.deepCopy());
        payload.set("tableModel", tableModel);
        if (!"MATRIX".equals(kind) && hasRuntimeSlots && canProjectColumns(source, kind, physicalFacts)) {
            payload.set("longTableModel", buildLongTableModel(source, kind, physicalFacts));
        }
        if ("MATRIX".equals(kind)) {
            var artifacts = matrixArtifacts(source, physicalFacts);
            if (artifacts != null) {
                payload.set("tableModel", artifacts.path("tableModel").deepCopy());
                payload.set("matrixModel", artifacts.path("matrixModel").deepCopy());
                payload.set("longTableModel", artifacts.path("longTableModel").deepCopy());
                payload.set("recordProjection", artifacts.path("recordProjection").deepCopy());
                payload.set("columnSlots", artifacts.path("columnSlots").deepCopy());
                payload.set("rowSlots", artifacts.path("rowSlots").deepCopy());
            } else {
                payload.set("matrixModel", objectMapper.createObjectNode()
                        .put("semanticMode", source.path("semanticMode").asText())
                        .put("headerRange", source.path("headerRange").asText())
                        .put("dataRange", source.path("dataRange").asText())
                        .put("rowHeaderRange", source.path("rowHeaderRange").asText())
                        .put("columnHeaderRange", source.path("columnHeaderRange").asText())
                        .put("crossDataRange", source.path("crossDataRange").asText())
                        .set("headerTree", source.path("headerTree").deepCopy()));
            }
        }
        attachBlock(payload, source.path("blockTemporaryId").asText(""), blocks);
        return new RecognitionModelClient.ModelSuggestion(
                kind, payload, confidence(source), objectMapper.createArrayNode()
        );
    }

    private ObjectNode matrixArtifacts(JsonNode source, JsonNode physicalFacts) {
        var range = source.path("range").asText(source.path("sourceRange").asText(""));
        var cornerRange = source.path("cornerRange").asText("");
        var rowHeaderRange = source.path("rowHeaderRange").asText("");
        var columnHeaderRange = source.path("columnHeaderRange").asText("");
        var crossDataRange = source.path("crossDataRange").asText(source.path("dataRange").asText(""));
        var rangeBounds = rangeBounds(range);
        var corner = rangeBounds(cornerRange);
        var rowHeader = rangeBounds(rowHeaderRange);
        var columnHeader = rangeBounds(columnHeaderRange);
        var crossData = rangeBounds(crossDataRange);
        if (rangeBounds == null || corner == null || rowHeader == null || columnHeader == null || crossData == null) {
            return null;
        }
        var sheetId = source.path("sheetId").asText("");
        var regionId = source.path("blockId").asText(source.path("blockTemporaryId").asText(""));
        var compiled = matrixCompiler.compile(physicalFacts,
                new CanonicalMatrixCompiler.CanonicalMatrixGeometry(
                        sheetId, regionId, range, cornerRange, rowHeaderRange,
                        columnHeaderRange, crossDataRange, source.path("recordAxis").asText("UNKNOWN"),
                        source.path("canonicalStatus").asText("PROVISIONAL")),
                new CanonicalMatrixCompiler.MatrixSemanticAssessment(
                        source.path("rowDimensions"), source.path("rowAttributes"), source.path("headerTree")));
        var result = objectMapper.createObjectNode();
        result.set("matrixModel", compiled.matrixModel());
        result.set("tableModel", compiled.tableModel());
        result.set("longTableModel", compiled.longTableModel());
        result.set("recordProjection", compiled.recordProjection());
        result.set("columnSlots", compiled.columnSlots());
        result.set("rowSlots", compiled.rowSlots());
        result.set("bindings", compiled.bindings());
        result.set("trainingSummary", compiled.trainingSummary());
        return result;
    }

    /** Creates independently reviewable and bindable fields for every named table column. */
    private List<RecognitionModelClient.ModelSuggestion> tableChildren(
            RecognitionModelClient.ModelSuggestion parent
    ) {
        var payload = parent.payload();
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var parentRelationId = payload.path("relationId").asText("");
        var parentFieldId = payload.path("fieldId").asText("");
        var parentBindingId = payload.path("bindingId").asText("");
        var parentKind = payload.path("kind").asText("ROW_TABLE");
        // A matrix is one reviewable cross-tab structure.  Its row dimensions,
        // runtime column members, and measure are persisted in matrixModel and
        // longTableModel; emitting scalar child cards here recreates the old
        // "外观 B6 / 粘度 B7" misclassification in the review UI.
        if ("MATRIX".equals(parentKind)) return List.of();
        var repeatAxis = payload.path("repeatAxis").asText(
                "ROW");
        for (var column : payload.path("columns")) {
            if (column.path("runtimeInputOnly").asBoolean(false)
                    || column.path("name").asText("").isBlank()) continue;
            var code = column.path("code").asText("column");
            var valueRange = column.path("valueRange").asText("");
            var childRelationId = parentRelationId + "|child|" + code + "|"
                    + RecognitionIdentity.normalizeRange(valueRange);
            var childFieldId = RecognitionIdentity.fieldId(childRelationId);
            var childLocatorType = "MATRIX".equals(parentKind) ? "MATRIX_REGION" : "CELL_RANGE";
            var childBindingId = RecognitionIdentity.bindingId(
                    childFieldId, childLocatorType,
                    payload.path("locator").path("sheetId").asText("") + "|" + valueRange
            );
            var childPayload = objectMapper.createObjectNode()
                    .put("kind", "SCALAR")
                    .put("suggestionLevel", "CHILD")
                    .put("mappingKind", "REPEAT_FIELD")
                    .put("relationId", childRelationId)
                    .put("modelRelationId", parentRelationId)
                    .put("fieldId", childFieldId.toString())
                    .put("bindingId", childBindingId.toString())
                    .put("parentRelationId", parentRelationId)
                    .put("parentFieldId", parentFieldId)
                    .put("parentBindingId", parentBindingId)
                    .put("fieldCode", column.path("fieldCode").asText("TABLE.COLUMN." + code))
                     .put("dataPath", column.path("dataPath").asText(""))
                    .put("fieldName", column.path("name").asText(code))
                    .put("groupName", payload.path("groupName").asText("基础信息"))
                    .put("valueType", column.path("valueType").asText("string"))
                    .put("required", column.path("required").asBoolean(false))
                    .put("role", "FIELD")
                    .put("locatorType", childLocatorType)
                    .put("editability", column.path("editability").asText("UNKNOWN"))
                     .put("valueSource", column.path("valueSource").asText("UNKNOWN"))
                    .put("unit", column.path("unit").asText(""))
                    .put("repeatAxis", repeatAxis)
                    .put("recordHeight", payload.path("recordHeight").asInt(1))
                    .put("recordWidth", payload.path("recordWidth").asInt(1))
                     .put("recordStride", payload.path("recordStride").asInt(1))
                     .put("candidateOnly", payload.path("candidateOnly").asBoolean(false))
                     .put("physicalStructureOnly", payload.path("physicalStructureOnly").asBoolean(false))
                     .put("reviewRequired", payload.path("reviewRequired").asBoolean(false))
                     .put("structureConflict", payload.path("structureConflict").asBoolean(false))
                     .put("canonicalStatus", payload.path("canonicalStatus").asText("PROVISIONAL"))
                     .put("structureStatus", payload.path("structureStatus").asText("PROVISIONAL"))
                     .put("parentStructurePending", payload.path("candidateOnly").asBoolean(false)
                             || payload.path("reviewRequired").asBoolean(false)
                             || payload.path("structureConflict").asBoolean(false))
                     .put("resolutionGroupId", payload.path("resolutionGroupId").asText(""))
                    .put("semanticConflict", column.path("semanticConflict").asBoolean(false))
                     .put("conflictCode", column.path("conflictCode").asText(""))
                     .put("conflictMessage", column.path("conflictMessage").asText(""))
                     .put("standardFieldId", column.path("standardFieldId").asText(""))
                     .put("standardFieldVersion", column.path("standardFieldVersion").asInt(0))
                     .put("standardFieldName", column.path("standardFieldName").asText(""))
                     .put("fieldOrigin", column.path("fieldOrigin").asText(""))
                     .put("standardSelectionStatus", column.path("standardSelectionStatus").asText(""))
                     .put("reason", "这是“" + payload.path("fieldName").asText("明细")
                            + "”中的独立明细字段，可单独确认和同步。")
                     .put("interpretation", "系统认为这里填写每条记录的“"
                             + column.path("name").asText(code) + "”。");
            childPayload.put("columnOffset", column.path("columnOffset").asInt(0))
                    .put("columnSpan", column.path("columnSpan").asInt(1));
            if (column.has("physicalColumnRanges")) {
                childPayload.set("physicalColumnRanges", column.path("physicalColumnRanges").deepCopy());
            }
            if (column.has("mergeRange")) childPayload.put("mergeRange", column.path("mergeRange").asText());
            var locator = locator(
                    payload.path("locator").path("sheetId").asText(""),
                    payload.path("locator").path("sheetName").asText(""),
                    column.path("labelRange").asText(""), valueRange, "ANCHOR"
            );
            locator.put("parentRange", payload.path("locator").path("dataRange").asText(
                    payload.path("locator").path("range").asText("")));
            locator.put("parentBindingId", parentBindingId);
            locator.put("valueMode", "ROW".equals(repeatAxis) ? "ARRAY_COLUMN" : "ARRAY_ROW");
            if (column.has("valueMode")) locator.put("valueMode", column.path("valueMode").asText());
            locator.put("anchorRange", firstColumnRange(valueRange));
            locator.put("columnOffset", column.path("columnOffset").asInt(0));
            locator.put("columnSpan", column.path("columnSpan").asInt(1));
            if (column.has("physicalColumnRanges")) {
                locator.set("physicalColumnRanges", column.path("physicalColumnRanges").deepCopy());
            }
            if (payload.path("terminationRule").isObject()) {
                locator.set("terminationRule", payload.path("terminationRule").deepCopy());
                childPayload.set("terminationRule", payload.path("terminationRule").deepCopy());
            }
            childPayload.set("locator", locator);
            if (column.has("semanticAlternatives")) {
                childPayload.set("semanticAlternatives", column.path("semanticAlternatives").deepCopy());
            }
            result.add(new RecognitionModelClient.ModelSuggestion(
                    "TABLE_CHILD_FIELD", childPayload, parent.confidence(),
                    objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                            .put("source", "TABLE_COLUMN")
                            .put("parentRelationId", parentRelationId)
                            .put("valueRange", valueRange))
            ));
        }
        return List.copyOf(result);
    }

    private ObjectNode runtimeSlot(ObjectNode column, JsonNode table, JsonNode physicalFacts) {
        var value = rangeBounds(column.path("valueRange").asText(""));
        var sheetId = table.path("sheetId").asText("");
        var regionId = table.path("blockId").asText(table.path("temporaryId").asText(
                "region-" + RecognitionIdentity.shortHash(sheetId + "|" + table.path("range").asText(), 16)));
        var sourceRange = table.path("range").asText(table.path("dataRange").asText(""));
        var identityRow = value == null ? 1 : Math.max(1, value[1] - 1);
        var endRow = value == null ? identityRow : value[3];
        var coordinate = value == null ? "" : columnName(value[0]);
        var label = value == null ? "" : physicalCellText(physicalFacts, sheetId, value[0], identityRow);
        var populated = !label.isBlank();
        return objectMapper.createObjectNode()
                .put("slotId", regionId + "|COLUMN|" + coordinate)
                .put("bindingInstanceId", "table-slot-"
                        + RecognitionIdentity.shortHash(sheetId + "|" + regionId + "|" + sourceRange
                        + "|COLUMN|" + coordinate, 16))
                .put("column", coordinate)
                .put("identityAddress", coordinate + identityRow)
                .put("recordRange", coordinate + identityRow + ":" + coordinate + endRow)
                .put("identityRange", coordinate + identityRow)
                .put("measureRange", value == null ? "" : coordinate + value[1] + ":" + coordinate + value[3])
                .put("templateStatus", populated ? "CONFIRMED" : "RUNTIME_INPUT")
                .put("instanceStatus", populated ? "POPULATED" : "EMPTY")
                .put("role", "COLUMN_MEMBER_INPUT")
                .put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT")
                .put("label", label);
    }

    private boolean canProjectColumns(JsonNode source, String kind, JsonNode physicalFacts) {
        var data = rangeBounds(source.path("dataRange").asText(""));
        var region = rangeBounds(source.path("range").asText(""));
        if (data == null || region == null || data[1] < region[1]) return false;
        if ("COLUMN_TABLE".equals(kind)) return true;
        return data[0] - region[0] >= 2 && source.path("columns").isArray()
                && source.path("columns").size() > 1;
    }

    private ObjectNode buildLongTableModel(JsonNode source, String kind, JsonNode physicalFacts) {
        var range = source.path("range").asText(source.path("sourceRange").asText(""));
        var dataRange = source.path("crossDataRange").asText(source.path("dataRange").asText(range));
        var data = rangeBounds(dataRange);
        var sourceBounds = rangeBounds(range);
        if (data == null || sourceBounds == null) {
            return matrixCompiler.compileLongTableModel(physicalFacts,
                    source.path("sheetId").asText(""), source.path("blockId").asText(source.path("blockTemporaryId").asText("")),
                    kind, range, "", source.path("rowHeaderRange").asText(""),
                    source.path("columnHeaderRange").asText(""), dataRange,
                    matrixCompiler.recordProjection(0, 0, 0, 0, 0, 0, "UNKNOWN"),
                    objectMapper.createArrayNode(), objectMapper.createArrayNode());
        }
        var sheetId = source.path("sheetId").asText("");
        var regionId = source.path("blockId").asText(source.path("blockTemporaryId").asText(""));
        var rowHeader = source.path("rowHeaderRange").asText(excelRange(sourceBounds[0], data[1], data[0] - 1, data[3]));
        var columnHeader = source.path("columnHeaderRange").asText(excelRange(data[0], Math.max(1, data[1] - 1), data[2], Math.max(1, data[1] - 1)));
        var axis = source.path("recordAxis").asText("");
        if (axis.isBlank() && "COLUMN_TABLE".equals(kind)) axis = "COLUMN";
        if (axis.isBlank() && "ROW_TABLE".equals(kind)) axis = "ROW";
        var projection = matrixCompiler.recordProjection(Math.max(1, data[1] - 1), data[0], data[2],
                sourceBounds[1], data[1], data[3], axis);
        var columns = "COLUMN".equals(axis)
                ? matrixCompiler.columnSlots(sheetId, regionId, range, data[0], data[2], Math.max(1, data[1] - 1), data[3])
                : objectMapper.createArrayNode();
        var rows = "ROW".equals(axis)
                ? matrixCompiler.rowSlots(sheetId, regionId, range, data[1], data[3], data[0], data[2])
                : objectMapper.createArrayNode();
        return matrixCompiler.compileLongTableModel(physicalFacts, sheetId, regionId, kind, range,
                source.path("cornerRange").asText(""), rowHeader, columnHeader, dataRange,
                projection, columns, rows);
    }

    private String physicalCellText(JsonNode physicalFacts, String sheetId, int column, int row) {
        var cell = physicalCell(physicalFacts, sheetId, column, row);
        return cell == null ? "" : cell.path("value").asText("").replaceAll("[\\r\\n]+", " ").strip();
    }

    private JsonNode physicalCell(JsonNode physicalFacts, String sheetId, int column, int row) {
        var address = excelAddress(column, row);
        for (var cell : semanticCells(physicalFacts)) {
            if (!sheetId.equals(cell.path("sheetId").asText(""))) continue;
            if (address.equalsIgnoreCase(cell.path("address").asText(""))) return cell;
            var merged = rangeBounds(cell.path("mergedRange").asText(""));
            if (merged != null && containsCell(merged, column, row)) return cell;
        }
        return null;
    }

    private String excelRange(int startColumn, int startRow, int endColumn, int endRow) {
        var start = excelAddress(startColumn, startRow);
        var end = excelAddress(endColumn, endRow);
        return start.equals(end) ? start : start + ":" + end;
    }

    private String excelAddress(int column, int row) {
        var current = Math.max(1, column);
        var result = new StringBuilder();
        while (current > 0) {
            current--;
            result.insert(0, (char) ('A' + current % 26));
            current /= 26;
        }
        return result + Integer.toString(Math.max(1, row));
    }

    private LinkedHashMap<String, ObjectNode> stableBlocks(JsonNode source) {
        var raw = new LinkedHashMap<String, JsonNode>();
        for (var block : source) raw.put(block.path("temporaryId").asText(), block);
        var result = new LinkedHashMap<String, ObjectNode>();
        for (var entry : raw.entrySet()) stableBlock(entry.getKey(), raw, result);
        return result;
    }

    private ObjectNode stableBlock(
            String temporaryId, Map<String, JsonNode> raw, Map<String, ObjectNode> result
    ) {
        if (result.containsKey(temporaryId)) return result.get(temporaryId);
        var source = raw.get(temporaryId);
        var parentTemporaryId = source.path("parentTemporaryId").asText("");
        var parent = parentTemporaryId.isBlank() ? null : stableBlock(parentTemporaryId, raw, result);
        var blockId = RecognitionIdentity.blockId(
                source.path("sheetId").asText(), source.path("range").asText(),
                source.path("type").asText(), parent == null ? "" : parent.path("blockId").asText()
        );
        var suggestedGroup = GroupNameNormalizer.normalizeModelSuggestion(
                source.path("groupNameSuggestion").asText(""))
                .orElseGet(() -> GroupNameNormalizer.inferFromBlock(
                        source.path("type").asText(""), source.path("businessName").asText("")));
        var normalizedType = "FORM_FIELDS".equals(source.path("type").asText())
                ? "FORM_REGION" : source.path("type").asText();
        var block = objectMapper.createObjectNode()
                .put("blockId", blockId).put("temporaryId", temporaryId)
                .put("sheetId", source.path("sheetId").asText())
                .put("range", source.path("range").asText())
                .put("type", normalizedType)
                .put("businessName", source.path("businessName").asText())
                .put("groupName", suggestedGroup);
        if (parent != null) block.put("parentBlockId", parent.path("blockId").asText());
        result.put(temporaryId, block);
        return block;
    }

    private ArrayNode remapAnnotations(JsonNode source, Map<String, ObjectNode> blocks) {
        var result = objectMapper.createArrayNode();
        for (var annotation : source) {
            var copy = (ObjectNode) annotation.deepCopy();
            var temporaryBlockId = copy.path("temporaryBlockRef").asText("");
            if (blocks.containsKey(temporaryBlockId)) {
                copy.put("blockId", blocks.get(temporaryBlockId).path("blockId").asText());
            }
            result.add(copy);
        }
        return result;
    }

    private ArrayNode staticRegions(ArrayNode annotations, ArrayNode blocks) {
        var result = objectMapper.createArrayNode();
        var seen = new java.util.HashSet<String>();
        for (JsonNode annotation : annotations) {
            var type = staticRegionType(annotation.path("role").asText(""));
            addStaticRegion(result, seen, annotation.path("sheetId").asText(""),
                    annotation.path("range").asText(""), type, "");
        }
        for (JsonNode block : blocks) {
            var type = staticRegionType(block.path("type").asText(""));
            addStaticRegion(result, seen, block.path("sheetId").asText(""),
                    block.path("range").asText(""), type, block.path("businessName").asText(""));
        }
        return result;
    }

    private void addStaticRegion(
            ArrayNode result, Set<String> seen, String sheetId, String address,
            String type, String displayName
    ) {
        if (type == null || sheetId.isBlank() || address.isBlank()) return;
        if (!seen.add(sheetId + "|" + address)) return;
        result.add(objectMapper.createObjectNode()
                .put("id", "baseline-" + RecognitionIdentity.shortHash(sheetId + "|" + address, 16))
                .put("sheetId", sheetId).put("address", address)
                .put("regionType", type)
                .put("displayName", displayName.isBlank() ? staticRegionName(type) : displayName)
                .put("source", "TEMPLATE_BASELINE").put("locked", true));
    }

    private String staticRegionType(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "STATIC_REFERENCE", "DOCUMENT_HEADER", "LOOKUP_TABLE" -> "STATIC_REFERENCE";
            case "INSTRUCTION", "INSTRUCTION_LIST" -> "INSTRUCTION";
            case "NOTE", "NOTE_BLOCK" -> "NOTE";
            default -> null;
        };
    }

    private String staticRegionName(String type) {
        return switch (type) {
            case "INSTRUCTION" -> "填写说明";
            case "NOTE" -> "备注说明";
            default -> "固定引用内容";
        };
    }

    private ObjectNode locator(
            String sheetId, String sheetName, String labelRange, String valueRange, String valueMode
    ) {
        return objectMapper.createObjectNode()
                .put("sheetId", sheetId).put("sheetName", sheetName)
                .put("labelAddress", firstCell(labelRange)).put("labelRange", labelRange)
                .put("address", valueRange).put("anchorAddress", firstCell(valueRange))
                .put("logicalInputRange", valueRange).put("valueMode", valueMode);
    }

    private void attachBlock(ObjectNode payload, String temporaryId, Map<String, ObjectNode> blocks) {
        if (!blocks.containsKey(temporaryId)) return;
        var block = blocks.get(temporaryId);
        payload.put("blockId", block.path("blockId").asText());
        payload.put("parentBlockId", block.path("parentBlockId").asText(""));
        payload.put("blockType", block.path("type").asText());
        payload.put("blockName", block.path("businessName").asText(""));
        payload.put("regionId", block.path("blockId").asText());
    }

    /**
     * A table is one structured business object. Its columns must not be promoted to duplicate
     * top-level scalar fields, but they are still emitted as explicit child-field metadata.
     * Static blocks never become a writable binding.
     */
    private boolean isFormalRelation(JsonNode relation, JsonNode tables, Map<String, ObjectNode> blocks) {
        var block = blocks.get(relation.path("blockTemporaryId").asText(""));
        if (block == null) return false;
        var allowedHeaderMetadata = "DOCUMENT_HEADER".equals(block.path("type").asText())
                && "INLINE_TEXT".equals(relation.path("relationType").asText());
        if (STATIC_BLOCK_TYPES.contains(block.path("type").asText()) && !allowedHeaderMetadata) return false;
        var sheetId = relation.path("sheetId").asText();
        var labelRange = relation.path("labelRange").asText();
        var valueRange = relation.path("valueRange").asText();
        for (var table : tables) {
            if (!sheetId.equals(table.path("sheetId").asText())) continue;
            var tableHeader = rangeBounds(table.path("headerRange").asText());
            var tableData = rangeBounds(table.path("dataRange").asText());
            if (tableHeader != null && tableData != null
                    && contains(tableHeader, rangeBounds(labelRange))
                    && contains(tableData, rangeBounds(valueRange))) {
                return false;
            }
            for (var column : table.path("columns")) {
                if (labelRange.equals(column.path("labelRange").asText())
                        && valueRange.equals(column.path("valueRange").asText())) {
                    return false;
                }
            }
        }
        return true;
    }

    private int[] rangeBounds(String range) {
        if (range == null || range.isBlank()) return null;
        var parts = range.toUpperCase(Locale.ROOT).split(":", 2);
        var start = cellBounds(parts[0]);
        var end = cellBounds(parts.length == 1 ? parts[0] : parts[1]);
        if (start == null || end == null) return null;
        return new int[]{Math.min(start[0], end[0]), Math.min(start[1], end[1]),
                Math.max(start[0], end[0]), Math.max(start[1], end[1])};
    }

    private int[] cellBounds(String cell) {
        var match = java.util.regex.Pattern.compile("^([A-Z]+)([0-9]+)$").matcher(cell);
        if (!match.matches()) return null;
        var column = 0;
        for (var letter : match.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return new int[]{column, Integer.parseInt(match.group(2))};
    }

    private boolean contains(int[] outer, int[] inner) {
        return outer != null && inner != null && outer[0] <= inner[0] && outer[1] <= inner[1]
                && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    private String groupName(JsonNode value, Map<String, ObjectNode> blocks) {
        var block = blocks.get(value.path("blockTemporaryId").asText(""));
        if (block != null) {
            var blockGroup = GroupNameNormalizer.normalizeModelSuggestion(block.path("groupName").asText(""));
            return blockGroup.orElseGet(() -> GroupNameNormalizer.inferFromBlock(
                    block.path("type").asText(""), block.path("businessName").asText("")));
        }
        return GroupNameNormalizer.normalizeModelSuggestion(value.path("groupNameSuggestion").asText(""))
                .orElse(GroupNameNormalizer.BASIC_INFORMATION);
    }

    private Map<String, String> sheetNames(JsonNode physicalFacts) {
        var result = new HashMap<String, String>();
        for (var sheet : physicalFacts.path("sheets")) {
            result.put(sheet.path("id").asText(), sheet.path("name").asText());
        }
        return result;
    }

    private double confidence(JsonNode value) {
        return "UNKNOWN".equals(value.path("editability").asText())
                || "UNKNOWN".equals(value.path("valueSource").asText()) ? 0.55 : 0.9;
    }

    private String fieldCode(String type, String relationId) {
        return "AUTO." + type + "_"
                + RecognitionIdentity.shortHash(relationId, 12).toUpperCase(Locale.ROOT);
    }

    private String dataPath(String type, String relationId) {
        return "/recognized/" + type.toLowerCase(Locale.ROOT) + "_"
                + RecognitionIdentity.shortHash(relationId, 12);
    }

    private boolean isFormulaTable(JsonNode source, JsonNode columns) {
        var name = source.path("businessName").asText("");
        if (name.contains("配方") || name.contains("投料")) return true;
        for (var column : columns) {
            var columnName = column.path("name").asText("");
            if (columnName.contains("原料编号") || columnName.contains("实际投料")
                    || columnName.contains("批号")) return true;
        }
        return false;
    }

    private ArrayNode inferColumns(JsonNode table, JsonNode physicalFacts) {
        var result = objectMapper.createArrayNode();
        var header = rangeBounds(table.path("headerRange").asText(""));
        var data = rangeBounds(table.path("dataRange").asText(""));
        if (header == null || data == null) return result;
        var sheetId = table.path("sheetId").asText("");
        for (int currentColumn = data[0]; currentColumn <= data[2]; currentColumn++) {
            var ordinal = currentColumn - data[0] + 1;
            var name = inferredHeaderName(physicalFacts, sheetId, header, currentColumn);
            var columnName = columnName(currentColumn);
            boolean formula = false;
            boolean numeric = false;
            for (var cell : semanticCells(physicalFacts)) {
                if (!sheetId.equals(cell.path("sheetId").asText(""))) continue;
                var bounds = rangeBounds(cell.path("address").asText(""));
                if (bounds == null || bounds[0] != currentColumn || bounds[1] < data[1] || bounds[1] > data[3]) {
                    continue;
                }
                formula |= "FORMULA".equals(cell.path("factType").asText(""))
                        || cell.path("formula").isTextual();
                numeric |= Set.of("number", "numeric", "integer", "decimal")
                        .contains(cell.path("physicalValueType").asText("").toLowerCase(Locale.ROOT));
            }
            var column = objectMapper.createObjectNode()
                    .put("temporaryId", "inferred-column-" + String.format(Locale.ROOT, "%02d", ordinal))
                    .put("name", name)
                    .put("labelRange", columnName + header[1] + ":" + columnName + header[3])
                    .put("valueRange", columnName + data[1] + ":" + columnName + data[3])
                    .put("valueType", numeric ? "number" : "string")
                    .put("editability", formula ? "READ_ONLY" : "EDITABLE")
                    .put("valueSource", formula ? "FORMULA" : "USER_INPUT")
                    .put("unit", "").put("condition", "")
                    .put("semanticKeySuggestion", "column_" + String.format(Locale.ROOT, "%02d", ordinal));
            result.add(column);
        }
        return result;
    }

    /**
     * Converts physical/model columns into logical business columns. A merged header/data region
     * is one business field even when the model describes every physical column separately.
     */
    private ArrayNode normalizeColumns(JsonNode table, JsonNode physicalFacts) {
        if ("MATRIX".equals(table.path("kind").asText(table.path("tableKind").asText("")))) {
            // A matrix is not a row table with unnamed columns. Its children are
            // axis/value projections and must retain their coordinates.
            return objectMapper.createArrayNode();
        }
        var raw = table.path("columns").isArray() && !table.path("columns").isEmpty()
                ? table.path("columns") : inferColumns(table, physicalFacts);
        var ordered = new ArrayList<ObjectNode>();
        for (var item : raw) {
            if (item.isObject()) ordered.add(item.deepCopy());
        }
        ordered.sort(Comparator.comparingInt(item -> {
            var bounds = rangeBounds(item.path("valueRange").asText(""));
            return bounds == null ? Integer.MAX_VALUE : bounds[0];
        }));

        var result = objectMapper.createArrayNode();
        for (var column : ordered) {
            var currentBounds = rangeBounds(column.path("valueRange").asText(""));
            if (currentBounds == null) {
                result.add(column);
                continue;
            }
            var mergeRange = physicalMergeRange(table, column, physicalFacts);
            var previous = result.isEmpty() ? null : (ObjectNode) result.get(result.size() - 1);
            if (previous != null && canMergeColumns(table, previous, column, mergeRange)) {
                mergeColumns(previous, column, mergeRange);
            } else {
                if (!mergeRange.isBlank()) {
                    column.put("mergeRange", mergeRange);
                }
                addColumnGeometry(column, currentBounds, mergeRange);
                result.add(column);
            }
        }
        return result;
    }

    private boolean canMergeColumns(
            JsonNode table, ObjectNode previous, ObjectNode current, String mergeRange
    ) {
        if (mergeRange.isBlank()) return false;
        var previousBounds = rangeBounds(previous.path("valueRange").asText(""));
        var currentBounds = rangeBounds(current.path("valueRange").asText(""));
        if (previousBounds == null || currentBounds == null
                || currentBounds[0] != previousBounds[2] + 1
                || previousBounds[1] != currentBounds[1]
                || previousBounds[3] != currentBounds[3]) return false;
        var previousMerge = previous.path("mergeRange").asText("");
        return mergeRange.equalsIgnoreCase(previousMerge)
                && normalizeColumnName(previous.path("name").asText(""))
                .equals(normalizeColumnName(current.path("name").asText("")))
                && table.path("sheetId").asText("").equals(current.path("sheetId").asText(
                        table.path("sheetId").asText("")));
    }

    private void mergeColumns(ObjectNode target, ObjectNode source, String mergeRange) {
        var left = rangeBounds(target.path("valueRange").asText(""));
        var right = rangeBounds(source.path("valueRange").asText(""));
        target.put("valueRange", columnName(left[0]) + left[1] + ":"
                + columnName(right[2]) + right[3]);
        target.put("labelRange", mergeRange);
        target.put("mergeRange", mergeRange);
        addColumnGeometry(target, new int[]{left[0], left[1], right[2], right[3]}, mergeRange);
        var physical = target.withArray("physicalColumnRanges");
        var sourcePhysical = source.path("physicalColumnRanges");
        if (sourcePhysical.isArray()) sourcePhysical.forEach(value -> physical.add(value));
        else physical.add(source.path("valueRange").asText(""));
        target.put("valueMode", "MERGED_ROW_RANGE");
    }

    private void addColumnGeometry(ObjectNode column, int[] bounds, String mergeRange) {
        column.put("columnOffset", Math.max(0, bounds[0] - 1));
        column.put("columnSpan", Math.max(1, bounds[2] - bounds[0] + 1));
        if (!column.has("physicalColumnRanges")) {
            var physical = column.putArray("physicalColumnRanges");
            for (int current = bounds[0]; current <= bounds[2]; current++) {
                physical.add(columnName(current) + bounds[1] + ":"
                        + columnName(current) + bounds[3]);
            }
        }
        if (!mergeRange.isBlank() && bounds[2] > bounds[0]) {
            column.put("valueMode", "MERGED_ROW_RANGE");
        }
    }

    private String physicalMergeRange(JsonNode table, JsonNode column, JsonNode physicalFacts) {
        var sheetId = table.path("sheetId").asText("");
        var label = rangeBounds(column.path("labelRange").asText(""));
        var value = rangeBounds(column.path("valueRange").asText(""));
        for (var merged : physicalFacts.path("mergedRanges")) {
            if (!sheetId.equals(merged.path("sheetId").asText(""))) continue;
            var bounds = rangeBounds(merged.path("address").asText(merged.path("range").asText("")));
            if (bounds == null) continue;
            if ((label != null && intersects(bounds, label))
                    || (value != null && containsCell(bounds, value[0], value[1]))) {
                return merged.path("address").asText(merged.path("range").asText(""));
            }
        }
        for (var cell : semanticCells(physicalFacts)) {
            if (!sheetId.equals(cell.path("sheetId").asText(""))) continue;
            var mergedRange = cell.path("mergedRange").asText("");
            if (mergedRange.isBlank()) continue;
            var bounds = rangeBounds(mergedRange);
            if (bounds != null && value != null && containsCell(bounds, value[0], value[1])) {
                return mergedRange;
            }
        }
        return "";
    }

    private boolean containsCell(int[] bounds, int column, int row) {
        return bounds[0] <= column && column <= bounds[2]
                && bounds[1] <= row && row <= bounds[3];
    }

    private boolean intersects(int[] left, int[] right) {
        return left[0] <= right[2] && right[0] <= left[2]
                && left[1] <= right[3] && right[1] <= left[3];
    }

    private String normalizeColumnName(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\u00a0]", "")
                .replace('：', ':').strip().toLowerCase(Locale.ROOT);
    }

    private boolean sameRange(String left, String right) {
        return left != null && right != null && !left.isBlank() && !right.isBlank()
                && left.replaceAll("\\s", "").equalsIgnoreCase(right.replaceAll("\\s", ""));
    }

    private String inferredHeaderName(JsonNode physicalFacts, String sheetId, int[] header, int column) {
        var values = new ArrayList<String>();
        for (var cell : semanticCells(physicalFacts)) {
            if (!sheetId.equals(cell.path("sheetId").asText(""))) continue;
            var bounds = rangeBounds(cell.path("address").asText(""));
            var mergedBounds = rangeBounds(cell.path("mergedRange").asText(""));
            if (mergedBounds != null) bounds = mergedBounds;
            if (bounds == null || bounds[2] < column || bounds[0] > column
                    || bounds[3] < header[1] || bounds[1] > header[3]) continue;
            var value = cell.path("value").asText("").replaceAll("[\\r\\n]+", " ").strip();
            if (!value.isBlank() && !values.contains(value)) values.add(value);
        }
        return String.join(" / ", values);
    }

    /** semanticCells is intentionally canonical at sheet level in physical facts. */
    private List<JsonNode> semanticCells(JsonNode physicalFacts) {
        var result = new ArrayList<JsonNode>();
        for (var sheet : physicalFacts.path("sheets")) {
            if (sheet.path("semanticCells").isArray()) sheet.path("semanticCells").forEach(result::add);
        }
        if (result.isEmpty() && physicalFacts.path("candidateCells").isArray()) {
            physicalFacts.path("candidateCells").forEach(result::add);
        }
        return result;
    }

    private ColumnIdentity columnIdentity(JsonNode sourceColumn, int ordinal, boolean formulaTable) {
        var name = sourceColumn.path("name").asText("").strip();
        if (formulaTable) {
            var standard = standard(name);
            if (standard != null) {
                return new ColumnIdentity(standard.pathSegment(), standard.fieldCode(),
                        standard.displayName(), standard.pathSegment());
            }
            if (name.contains("序号")) return new ColumnIdentity("sequence", "FORMULA.ITEM.SEQUENCE", "序号", "sequence");
            if (name.contains("投料阶段") || name.contains("阶段")) {
                return new ColumnIdentity("phase", "FORMULA.ITEM.PHASE", "投料阶段", "phase");
            }
            if (name.contains("原料编号") || name.contains("物料编号")) {
                return new ColumnIdentity("materialCode", "FORMULA.ITEM.MATERIAL_CODE", "原料编号", "materialCode");
            }
            if (name.contains("实际投料")) {
                return new ColumnIdentity("actualKg", "FORMULA.ITEM.ACTUAL_KG", "实际投料量", "actualKg");
            }
            if (name.contains("批号")) {
                return new ColumnIdentity("batchNo", "FORMULA.ITEM.BATCH_NO", "原料批号", "batchNo");
            }
            if (name.contains("备注")) return new ColumnIdentity("remark", "FORMULA.ITEM.REMARK", "备注", "remark");
            if (name.contains("比例") && !isMassUnit(sourceColumn)) {
                return new ColumnIdentity("ratio", "FORMULA.ITEM.RATIO", "配方比例", "ratio");
            }
            if (name.contains("比例") || name.contains("用量") || name.contains("投料量")) {
                return new ColumnIdentity("theoreticalKg", "FORMULA.ITEM.THEORETICAL_KG", "理论投料量", "theoreticalKg");
            }
        }
        var safe = name.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
        if (safe.isBlank()) safe = "column" + String.format(Locale.ROOT, "%02d", ordinal);
        return new ColumnIdentity(
                "column" + String.format(Locale.ROOT, "%02d", ordinal),
                "TABLE.COLUMN." + String.format(Locale.ROOT, "%02d", ordinal),
                name,
                Character.toLowerCase(safe.charAt(0)) + safe.substring(1)
        );
    }

    private ColumnIdentity uniqueColumnIdentity(
            JsonNode sourceColumn, int ordinal, boolean formulaTable, Set<String> usedPaths
    ) {
        var identity = columnIdentity(sourceColumn, ordinal, formulaTable);
        if (usedPaths.add(identity.pathSegment())) return identity;
        var offset = sourceColumn.path("columnOffset").asInt(
                Math.max(0, columnStart(sourceColumn.path("valueRange").asText()) - 1));
        var suffix = "_col" + (offset + 1);
        var unique = identity.pathSegment() + suffix;
        usedPaths.add(unique);
        return new ColumnIdentity(
                identity.code() + suffix,
                identity.fieldCode() + suffix,
                identity.name() + "（第" + (offset + 1) + "列）",
                unique
        );
    }

    private boolean isMassUnit(JsonNode sourceColumn) {
        var unit = sourceColumn.path("unit").asText("").toUpperCase(Locale.ROOT);
        return Set.of("KG", "KGS", "G", "克", "千克", "吨", "T").contains(unit.strip());
    }

    private ResolvedStandard standard(String label) {
        if (label == null || label.isBlank()) return null;
        var dictionaryMatch = StandardFieldDictionary.match(label).orElse(null);
        if (standardFieldRepository != null) {
            try {
                var normalizedLabel = normalizeStandardLabel(label);
                var match = standardFieldRepository.search(label, null).stream()
                        .filter(value -> normalizedLabel.equals(normalizeStandardLabel(value.displayName()))
                                || (dictionaryMatch != null
                                && dictionaryMatch.fieldCode().equals(value.fieldCode())))
                        .findFirst();
                if (match.isPresent()) {
                    var value = match.get();
                    return new ResolvedStandard(value.id(), value.fieldCode(), value.version(),
                            pathSegment(value.fieldCode()), value.displayName(), value.uiType());
                }
            } catch (RuntimeException ignored) {
                // Recognition must still be deterministic if an offline worker cannot reach the DB.
            }
        }
        if (dictionaryMatch == null) return null;
        return new ResolvedStandard(null, dictionaryMatch.fieldCode(), StandardFieldDictionary.VERSION,
                dictionaryMatch.pathSegment(), dictionaryMatch.displayName(), "TEXT");
    }

    private String normalizeStandardLabel(String value) {
        return value == null ? "" : value
                .replaceAll("[\\s\\u00a0]", "")
                .replace('：', ':')
                .replace(":", "")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    private String pathSegment(String fieldCode) {
        var value = fieldCode == null ? "field" : fieldCode.substring(fieldCode.lastIndexOf('.') + 1);
        var result = new StringBuilder();
        for (var token : value.toLowerCase(Locale.ROOT).split("_")) {
            if (token.isBlank()) continue;
            result.append(result.isEmpty() ? token : Character.toUpperCase(token.charAt(0)) + token.substring(1));
        }
        return result.isEmpty() ? "field" : result.toString();
    }

    private int firstRow(String range) {
        if (range == null || range.isBlank()) return 0;
        var cell = firstCell(range);
        var digits = cell.replaceAll("^[A-Z]+", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int row(String range) {
        var cell = firstCell(range);
        return Integer.parseInt(cell.replaceAll("^[A-Z]+", ""));
    }

    private int column(String range) {
        var letters = firstCell(range).replaceAll("[0-9]+$", "");
        var result = 0;
        for (var letter : letters.toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private String columnName(int value) {
        var current = Math.max(1, value);
        var result = new StringBuilder();
        while (current > 0) {
            current--;
            result.insert(0, (char) ('A' + current % 26));
            current /= 26;
        }
        return result.toString();
    }

    private int columnStart(String range) {
        var bounds = rangeBounds(range);
        return bounds == null ? 1 : bounds[0];
    }

    private String firstCell(String range) {
        return range.split(":", 2)[0].toUpperCase(Locale.ROOT);
    }

    private String firstColumnRange(String range) {
        var bounds = rangeBounds(range);
        if (bounds == null) return firstCell(range);
        return columnName(bounds[0]) + bounds[1] + ":" + columnName(bounds[0]) + bounds[3];
    }

    record Compiled(
            List<RecognitionModelClient.ModelSuggestion> suggestions,
            List<RecognitionModelClient.QualityIssueSuggestion> qualityIssues
    ) {
    }

    private record Unit(String type, JsonNode value, String sheetId, String range, String groupName) {
    }

    private record ColumnIdentity(String code, String fieldCode, String name, String pathSegment) {
    }

    private record ResolvedStandard(
            java.util.UUID id,
            String fieldCode,
            int version,
            String pathSegment,
            String displayName,
            String uiType
    ) {
    }
}
