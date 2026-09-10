package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.Comparator;
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
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;

/** Compiles the immutable execution contract consumed by the data center. */
@Component
public class TemplateImportContractCompiler {

    public static final int BASE_IMPORT_CONTRACT_VERSION = 8;
    public static final int EXPERIMENT_IMPORT_CONTRACT_VERSION = 9;
    /** Kept for source compatibility; ordinary templates still use V8. */
    public static final int IMPORT_CONTRACT_VERSION = BASE_IMPORT_CONTRACT_VERSION;
    public static final int CURRENT_LAYOUT_STRUCTURE_VERSION = 7;
    public static final String EXPERIMENT_IMPORT_SCHEMA_KEY = "x-jsd-experiment-import";

    private static final Map<String, Set<String>> EXPERIMENT_FIELDS = Map.of(
            "BASIC", Set.of("SOURCE_IDENTITY", "TITLE", "PURPOSE", "PLAN", "EXPERIMENT_DATE", "OWNER"),
            "FORMULA", Set.of("MATERIAL_ID", "MATERIAL_CODE", "MATERIAL_NAME", "RATIO", "ACTUAL_QTY", "UNIT", "RAW_VALUE", "RAW_UNIT"),
            "PROCESS", Set.of("STEP_NO", "OPERATION", "TEMPERATURE", "DURATION", "APPLICATION_CONDITION", "OTHER"),
            "TEST", Set.of("TEST_ITEM", "VALUE", "UNIT", "JUDGEMENT", "TEST_METHOD", "TEST_CONDITION", "SUBSTRATE"),
            "CONCLUSION", Set.of("RESULT_STATUS", "MAIN_CONCLUSION", "FAILURE_CATEGORY"),
            "OTHER", Set.of("DYNAMIC_VALUE")
    );

    private final ObjectMapper objectMapper;
    private final JsonCanonicalizer canonicalizer;

    public TemplateImportContractCompiler(ObjectMapper objectMapper, JsonCanonicalizer canonicalizer) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    public CompiledContract compile(JsonNode layoutSummary, JsonNode schema, JsonNode mappings) {
        var layoutVersion = layoutStructureVersion(layoutSummary);
        var experimentConfiguration = schema == null
                ? objectMapper.createObjectNode() : schema.path(EXPERIMENT_IMPORT_SCHEMA_KEY);
        var experimentTemplate = "EXPERIMENT_DATA".equalsIgnoreCase(
                experimentConfiguration.path("templateUsage").asText(""));
        var contractVersion = experimentTemplate
                ? EXPERIMENT_IMPORT_CONTRACT_VERSION : BASE_IMPORT_CONTRACT_VERSION;
        var contract = objectMapper.createObjectNode()
                .put("importContractVersion", contractVersion)
                .put("layoutStructureVersion", layoutVersion)
                .put("identityFallback", "IMPORT_SCOPED")
                .put("compatibilityPolicy", "STRICT_SIMPLE_REGIONS");
        contract.set("fields", contractFields(schema));
        var components = components(mappings, schema, experimentTemplate);
        attachSheetFingerprints(components, layoutSummary);
        if (experimentTemplate) {
            var experimentImport = compileExperimentImport(experimentConfiguration, components);
            markIdentitySemantics(components, experimentImport);
            validateSingleValueSemantics(components);
            summarizeBusinessTypes(components);
            contract.put("templateUsage", "EXPERIMENT_DATA")
                    .put("recordKeyPolicy", "STRUCTURAL");
            contract.set("experimentImport", experimentImport);
            contract.set("listProjections", compileListProjections(experimentConfiguration, components, experimentImport));
        }
        contract.set("components", components);
        var hash = canonicalizer.hash(contract);
        contract.put("contractHash", hash);
        return new CompiledContract(contractVersion, layoutVersion, hash, contract);
    }

    private int layoutStructureVersion(JsonNode layoutSummary) {
        for (var path : List.of(
                layoutSummary.path("structureVersion"),
                layoutSummary.path("structureSummary").path("structureVersion"),
                layoutSummary.path("initialSnapshot").path("structureVersion"),
                layoutSummary.path("initialSnapshot").path("structureSummary").path("structureVersion")
        )) {
            if (path.isInt() && path.asInt() > 0) return path.asInt();
        }
        return CURRENT_LAYOUT_STRUCTURE_VERSION;
    }

    private JsonNode contractFields(JsonNode schema) {
        var result = objectMapper.createArrayNode();
        var fields = schema.path(TemplateRecognitionCompiler.FIELD_MODEL_KEY).path("fields");
        if (!fields.isArray()) return result;
        var sorted = new ArrayList<JsonNode>();
        fields.forEach(sorted::add);
        sorted.sort(Comparator.comparing(item -> item.path("fieldCode").asText(item.path("name").asText(""))));
        for (var field : sorted) {
            var item = objectMapper.createObjectNode();
            copy(field, item, "fieldId", "fieldCode", "name", "description", "valueType", "unit",
                    "required", "identity", "trainingRole", "trainingEligible", "ragEligible", "dataPath",
                    "fieldType", "labelStatus", "pathSegments");
            result.add(item);
        }
        return result;
    }

    private ArrayNode components(JsonNode mappings, JsonNode schema, boolean includeExperimentSemantics) {
        var groups = new LinkedHashMap<String, ObjectNode>();
        if (mappings == null || !mappings.isArray()) return objectMapper.createArrayNode();
        var fieldNames = fieldNames(schema);
        for (var mapping : mappings) {
            var locator = mapping.path("locator");
            var componentId = componentId(mapping, locator);
            var component = groups.computeIfAbsent(componentId, id -> {
                var created = objectMapper.createObjectNode()
                        .put("componentId", id)
                        .put("sheetId", firstNonBlank(locator, "sheetId", "sheet"))
                        .put("structureType", componentStructureType(mapping))
                        .put("range", firstNonBlank(locator, "range", "recordRange", "dataRange", "valueRange"));
                var name = componentDisplayName(mapping);
                if (name.isBlank() && "FORM_REGION".equals(componentStructureType(mapping))) name = "基本信息";
                if (!name.isBlank()) created.put("name", name);
                var fingerprint = firstNonBlank(locator, "sheetStructureFingerprint", "structureFingerprint");
                if (!fingerprint.isBlank()) created.put("sheetStructureFingerprint", fingerprint);
                created.putArray("bindings");
                return created;
            });
            if (component.path("sheetStructureFingerprint").asText("").isBlank()) {
                var fingerprint = firstNonBlank(locator, "sheetStructureFingerprint", "structureFingerprint");
                if (!fingerprint.isBlank()) component.put("sheetStructureFingerprint", fingerprint);
            }
            if (isComponentRoot(mapping)) {
                component.put("sheetId", firstNonBlank(locator, "sheetId", "sheet"));
                component.put("structureType", componentStructureType(mapping));
                component.put("range", firstNonBlank(locator, "range", "recordRange", "dataRange", "valueRange"));
                copy(mapping, component, "recordAxis", "repeatAxis", "semanticMode");
                var name = componentName(mapping);
                if (!name.isBlank()) component.put("name", name);
            }
            if (component.path("name").asText("").isBlank()) {
                var groupName = userFacingText(mapping.path("diagnostic").path("groupName").asText(""));
                if (!groupName.isBlank()) component.put("name", groupName);
            }
            var binding = objectMapper.createObjectNode();
            copy(mapping, binding, "bindingId", "parentBindingId", "fieldCode", "dataPath", "mappingKind",
                    "repeatAxis", "recordHeight", "recordWidth", "recordStride", "required", "identity",
                    "trainingRole", "trainingEligible", "ragEligible", "valueSource", "valueType", "unit",
                     "valuePath", "formulaTrustStatus", "fieldType");
            if (includeExperimentSemantics) {
                var experimentField = normalizedExperimentField(mapping);
                if (experimentField == null && !isComponentRoot(mapping)
                        && !mapping.path("fieldCode").asText("").isBlank()) {
                    experimentField = objectMapper.createObjectNode()
                            .put("domain", "OTHER").put("field", "DYNAMIC_VALUE");
                }
                if (experimentField != null) {
                    binding.set("experimentField", experimentField);
                    binding.put("targetPath", targetPath(experimentField));
                    copy(mapping, binding, "experimentItemLabel", "experimentSemanticConfidence",
                            "experimentSemanticStatus", "experimentSemanticSource",
                            "experimentSemanticAlternatives", "experimentSemanticIssue");
                }
            }
            var labelPath = labelPath(mapping, fieldNames);
            if (!labelPath.isBlank()) binding.put("labelPath", labelPath);
            if (mapping.path("labelPathSegments").isArray()) {
                binding.set("labelPathSegments", mapping.path("labelPathSegments").deepCopy());
            }
            binding.set("locator", locator.isObject() ? locator.deepCopy() : objectMapper.createObjectNode());
            var terminationRule = mapping.path("terminationRule").isObject()
                    ? mapping.path("terminationRule") : mapping.path("termination");
            binding.set("terminationRule", terminationRule.isObject()
                    ? terminationRule.deepCopy() : objectMapper.createObjectNode());
            component.withArray("bindings").add(binding);
        }
        groups.values().forEach(component -> {
            refineComponentName(component);
            var required = false;
            for (var binding : component.withArray("bindings")) {
                required |= binding.path("required").asBoolean(false) || binding.path("identity").asBoolean(false);
            }
            component.put("requiredComponent", required)
                    .put("componentFingerprint", componentFingerprint(component));
        });
        var result = objectMapper.createArrayNode();
        groups.values().stream().sorted(Comparator.comparing(item -> item.path("componentId").asText()))
                .forEach(result::add);
        return result;
    }

    private void refineComponentName(ObjectNode component) {
        var names = new ArrayList<String>();
        for (var binding : component.withArray("bindings")) {
            var kind = binding.path("mappingKind").asText("").toUpperCase(java.util.Locale.ROOT);
            if (kind.contains("REGION") || kind.equals("ROW_TABLE") || kind.equals("COLUMN_TABLE")) continue;
            var label = binding.path("labelPath").asText("").trim();
            if (label.isBlank()) continue;
            var parts = label.split("\\s*(?:>|/|›)\\s*");
            var leaf = parts.length == 0 ? label : parts[parts.length - 1].trim();
            if (!leaf.isBlank()) names.add(leaf);
        }
        var type = component.path("structureType").asText("").toUpperCase(java.util.Locale.ROOT);
        if ("FORM_REGION".equals(type)) {
            var packagingCount = names.stream().filter(name -> name.contains("包装")).count();
            var hasSignoff = names.stream().anyMatch(name -> name.contains("制单") || name.contains("完成")
                    || name.contains("监管") || name.contains("签字") || name.contains("签章"));
            var hasSummary = names.stream().anyMatch(name -> name.contains("小结") || name.contains("结论")
                    || name.contains("结果"));
            var hasNote = names.stream().anyMatch(name -> name.contains("备注") || name.contains("说明"));
            if (hasSummary && hasNote) component.put("name", "小结与备注");
            else if (packagingCount >= 2 && hasSignoff) component.put("name", "包装与签字");
            else if (packagingCount >= 2) component.put("name", "包装信息");
            else if (hasSignoff) component.put("name", "审核信息");
            else component.put("name", "基本信息");
            return;
        }
        if (!isRepeatedStructure(type)) return;
        if (!type.contains("COLUMN")
                && names.size() == 1 && names.getFirst().length() <= 20
                && !names.getFirst().contains("／")) {
            component.put("name", "操作程序".equals(names.getFirst()) ? "操作步骤" : names.getFirst());
            return;
        }
        var hasMaterial = names.stream().anyMatch(name -> name.contains("原料") || name.contains("物料"));
        var hasFormulaValue = names.stream().anyMatch(name -> name.contains("配方") || name.contains("投料"));
        if (hasMaterial && hasFormulaValue) {
            component.put("name", "配方明细");
            return;
        }
        var current = component.path("name").asText("");
        if (current.isBlank() || "基础信息".equals(current) || "重复记录区域".equals(current)) {
            component.put("name", type.contains("COLUMN") ? "测试数据" : "明细数据");
        }
    }

    private boolean isRepeatedStructure(String type) {
        return type.contains("REPEAT") || type.contains("TABLE") || type.contains("ROW")
                || type.contains("COLUMN");
    }

    private String componentId(JsonNode mapping, JsonNode locator) {
        var explicit = firstNonBlank(mapping, "componentId", "parentBindingId");
        if (explicit.isBlank()) explicit = firstNonBlank(locator, "componentId", "regionId", "blockId");
        if (!explicit.isBlank()) return explicit;
        if (isComponentRoot(mapping)) {
            var rootId = firstNonBlank(mapping, "bindingId");
            return rootId.isBlank() ? generatedComponentId(mapping) : rootId;
        }
        // Unparented scalar fields use the stable recognition block identity.
        // Repeated children already carry parentBindingId and therefore stay
        // with their explicit table root above.
        var blockId = mapping.path("diagnostic").path("blockId").asText("");
        if (!blockId.isBlank()) return blockId;
        var basis = objectMapper.createObjectNode()
                .put("sheetId", firstNonBlank(locator, "sheetId", "sheet"))
                .put("structureType", componentStructureType(mapping))
                .put("groupName", firstNonBlankName(componentGroupingName(mapping), "基本信息"));
        return generatedComponentId(basis);
    }

    private String componentDisplayName(JsonNode mapping) {
        if (isComponentRoot(mapping)) return componentName(mapping);
        var grouped = componentGroupingName(mapping);
        if (!grouped.isBlank()) return grouped;
        return "FORM_REGION".equals(componentStructureType(mapping)) ? "基本信息" : componentName(mapping);
    }

    private String componentGroupingName(JsonNode mapping) {
        for (var value : List.of(
                mapping.path("groupName").asText(""),
                mapping.path("diagnostic").path("groupName").asText(""))) {
            var result = userFacingText(value);
            if (!result.isBlank()) return result;
        }
        return "";
    }

    private String componentStructureType(JsonNode mapping) {
        var kind = firstNonBlank(mapping, "mappingKind", "role");
        var diagnosticKind = mapping.path("diagnostic").path("kind").asText("");
        if ((kind.isBlank() || "REPEAT_REGION".equalsIgnoreCase(kind))
                && Set.of("FORM_REGION", "ROW_TABLE", "COLUMN_TABLE")
                .contains(diagnosticKind.toUpperCase(java.util.Locale.ROOT))) {
            return diagnosticKind.toUpperCase(java.util.Locale.ROOT);
        }
        if (kind.isBlank() || "SCALAR".equalsIgnoreCase(kind) || "FIELD".equalsIgnoreCase(kind)) {
            return "FORM_REGION";
        }
        return kind;
    }

    private String firstNonBlankName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void attachSheetFingerprints(JsonNode components, JsonNode layoutSummary) {
        var sheets = layoutSummary.path("sheets");
        if (!sheets.isArray()) sheets = layoutSummary.path("structureSummary").path("sheets");
        if (!sheets.isArray()) sheets = layoutSummary.path("initialSnapshot").path("structureSummary").path("sheets");
        if (!sheets.isArray()) return;
        for (var component : components) {
            var sheetId = component.path("sheetId").asText("");
            for (var sheet : sheets) {
                var candidateId = sheet.path("sheetId").asText(sheet.path("id").asText(""));
                var candidateName = sheet.path("sheetName").asText(sheet.path("name").asText(""));
                if (!sheetId.equals(candidateId) && !sheetId.equals(candidateName)) continue;
                var fingerprint = sheet.path("structureFingerprint").asText("");
                if (!fingerprint.isBlank() && component instanceof ObjectNode target) {
                    target.put("sheetStructureFingerprint", fingerprint);
                }
                break;
            }
        }
    }

    private String labelPath(JsonNode mapping, Map<String, String> fieldNames) {
        var direct = userFacingText(mapping.path("labelPath").asText(""));
        if (!direct.isBlank()) return direct;
        var diagnostic = mapping.path("diagnostic").path("labelPath");
        if (diagnostic.isArray()) {
            var values = new ArrayList<String>();
            diagnostic.forEach(value -> {
                var visible = userFacingText(value.asText(""));
                if (!visible.isBlank()) values.add(visible);
            });
            if (!values.isEmpty()) return String.join(" > ", values);
        }
        for (var key : List.of(mapping.path("fieldId").asText(""), mapping.path("fieldCode").asText(""))) {
            var name = fieldNames.getOrDefault(key, "");
            if (!name.isBlank()) return name;
        }
        return userFacingText(mapping.path("fieldName").asText(mapping.path("fieldCode").asText("")));
    }

    private Map<String, String> fieldNames(JsonNode schema) {
        var names = new LinkedHashMap<String, String>();
        for (var field : schema.path(TemplateRecognitionCompiler.FIELD_MODEL_KEY).path("fields")) {
            var name = userFacingText(field.path("name").asText(""));
            if (name.isBlank()) continue;
            for (var key : List.of(field.path("fieldId").asText(field.path("id").asText("")),
                    field.path("fieldCode").asText(""))) {
                if (!key.isBlank()) names.put(key, name);
            }
        }
        return names;
    }

    private ObjectNode compileExperimentImport(JsonNode configuration, ArrayNode components) {
        var recordMode = configuration.path("recordMode").asText("").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SINGLE_FILE", "BY_IDENTITY").contains(recordMode)) {
            throw experimentError("实验数据模板必须选择有效的实验拆分方式");
        }
        var compiled = objectMapper.createObjectNode().put("recordMode", recordMode);
        var identities = objectMapper.createArrayNode();
        var componentIdentities = new HashSet<String>();
        if (configuration.path("identities").isArray()) {
            for (var source : configuration.path("identities")) {
                var componentId = source.path("componentId").asText("").trim();
                var sourceKind = source.path("sourceKind").asText("BINDING").trim().toUpperCase(Locale.ROOT);
                var identityType = source.path("identityType").asText("").trim().toUpperCase(Locale.ROOT);
                if (!Set.of("EXPERIMENT_NO", "SAMPLE_NO", "FORMULA_NO", "BATCH_NO").contains(identityType)) {
                    throw experimentError("来源标识类型无效：" + identityType);
                }
                if (!Set.of("BINDING", "RECORD_IDENTITY").contains(sourceKind)) {
                    throw experimentError("来源标识方式无效：" + sourceKind);
                }
                var component = findComponent(components, componentId);
                if (component == null) throw experimentError("来源标识引用的数据区域不存在：" + componentId);
                var bindingId = source.path("bindingId").asText("").trim();
                if ("RECORD_IDENTITY".equals(sourceKind) && bindingId.isBlank()) {
                    bindingId = firstIdentityBinding(component);
                }
                if (bindingId.isBlank() || findBinding(component, bindingId) == null) {
                    throw experimentError("来源标识引用的字段不存在：" + bindingId);
                }
                if (!componentIdentities.add(componentId)) {
                    throw experimentError("同一个数据区域只能配置一个主来源标识：" + componentId);
                }
                identities.add(objectMapper.createObjectNode()
                        .put("identityType", identityType)
                        .put("sourceKind", sourceKind)
                        .put("componentId", componentId)
                        .put("bindingId", bindingId));
            }
        }
        if ("BY_IDENTITY".equals(recordMode) && identities.isEmpty()) {
            throw experimentError("按来源标识拆分时至少需要配置一个有效标识字段");
        }
        var sorted = new ArrayList<JsonNode>();
        identities.forEach(sorted::add);
        sorted.sort(Comparator.comparing(item -> item.path("componentId").asText("")
                + "|" + item.path("bindingId").asText("")));
        var stable = objectMapper.createArrayNode();
        sorted.forEach(stable::add);
        compiled.set("identities", stable);
        return compiled;
    }

    private void markIdentitySemantics(ArrayNode components, ObjectNode experimentImport) {
        for (var identity : experimentImport.path("identities")) {
            var component = findComponent(components, identity.path("componentId").asText(""));
            var binding = component == null ? null : findBinding(component, identity.path("bindingId").asText(""));
            if (binding == null) continue;
            var current = binding.path("experimentField");
            if (current.isObject() && !"OTHER".equals(current.path("domain").asText(""))
                    && !("BASIC".equals(current.path("domain").asText(""))
                    && "SOURCE_IDENTITY".equals(current.path("field").asText("")))) {
                throw experimentError("来源标识字段不能同时映射为其他实验字段："
                        + binding.path("bindingId").asText(""));
            }
            var semantic = objectMapper.createObjectNode()
                    .put("domain", "BASIC").put("field", "SOURCE_IDENTITY");
            ((ObjectNode) binding).set("experimentField", semantic);
            ((ObjectNode) binding).put("targetPath", targetPath(semantic))
                    .put("sourceIdentity", true)
                    .put("sourceIdentityType", identity.path("identityType").asText(""));
        }
    }

    private ArrayNode compileListProjections(JsonNode configuration, ArrayNode components,
                                             ObjectNode experimentImport) {
        var result = objectMapper.createArrayNode();
        var ids = new HashSet<String>();
        if (!configuration.path("listProjections").isArray()) return result;
        for (var source : configuration.path("listProjections")) {
            var componentId = source.path("componentId").asText("").trim();
            var component = findComponent(components, componentId);
            if (component == null) throw experimentError("列表投影引用的数据区域不存在：" + componentId);
            var domain = source.path("domain").asText("").trim().toUpperCase(Locale.ROOT);
            var labelSemantic = source.path("labelSemantic").asText("").trim().toUpperCase(Locale.ROOT);
            var valueSemantic = source.path("valueSemantic").asText("").trim().toUpperCase(Locale.ROOT);
            validateExperimentField(domain, labelSemantic);
            validateExperimentField(domain, valueSemantic);
            var recordAxis = source.path("recordAxis").asText("").trim().toUpperCase(Locale.ROOT);
            var itemAxis = source.path("itemAxis").asText("").trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ROW", "COLUMN").contains(recordAxis)
                    || !Set.of("ROW", "COLUMN").contains(itemAxis) || recordAxis.equals(itemAxis)) {
                throw experimentError("列表投影的记录方向和明细方向必须是互相垂直的行/列");
            }
            var labelRangeText = source.path("labelRange").asText("").trim().toUpperCase(Locale.ROOT);
            var valueRangeText = source.path("valueRange").asText("").trim().toUpperCase(Locale.ROOT);
            var labelRange = parseA1Range(labelRangeText);
            var valueRange = parseA1Range(valueRangeText);
            var componentRange = parseA1Range(component.path("range").asText(""));
            if (labelRange == null || valueRange == null || componentRange == null) {
                throw experimentError("列表投影必须配置有效的标签、数值和所属区域范围");
            }
            if (!componentRange.contains(labelRange) || !componentRange.contains(valueRange)) {
                throw experimentError("列表投影范围必须位于所属数据区域内：" + componentId);
            }
            if ("ROW".equals(itemAxis)) {
                if (labelRange.rows() != valueRange.rows() || labelRange.columns() != 1) {
                    throw experimentError("按行排列的明细标签必须是单列，并与数值区域行数一致");
                }
            } else if (labelRange.columns() != valueRange.columns() || labelRange.rows() != 1) {
                throw experimentError("按列排列的明细标签必须是单行，并与数值区域列数一致");
            }
            if (overlapsIdentity(valueRange, component, experimentImport)
                    || overlapsIdentity(labelRange, component, experimentImport)) {
                throw experimentError("列表投影不能覆盖来源标识单元格");
            }
            var parentBindingId = source.path("parentBindingId").asText(componentId).trim();
            if (parentBindingId.isBlank() || findBinding(component, parentBindingId) == null) {
                throw experimentError("列表投影引用的父区域字段不存在：" + parentBindingId);
            }
            var unitRangeText = source.path("unitRange").asText("").trim().toUpperCase(Locale.ROOT);
            if (!unitRangeText.isBlank()) {
                var unitRange = parseA1Range(unitRangeText);
                if (unitRange == null || !componentRange.contains(unitRange)) {
                    throw experimentError("列表投影单位区域必须位于所属数据区域内：" + componentId);
                }
                var scalarUnit = unitRange.rows() == 1 && unitRange.columns() == 1;
                var alignedUnit = "ROW".equals(itemAxis)
                        ? unitRange.rows() == labelRange.rows() && unitRange.columns() == 1
                        : unitRange.columns() == labelRange.columns() && unitRange.rows() == 1;
                if (!scalarUnit && !alignedUnit) {
                    throw experimentError("列表投影单位区域必须是单个共用单位，或与标签逐项对应");
                }
                if (overlapsIdentity(unitRange, component, experimentImport)) {
                    throw experimentError("列表投影单位区域不能覆盖来源标识单元格");
                }
            }
            var projectionId = source.path("listProjectionId").asText("").trim();
            if (projectionId.isBlank()) {
                var basis = objectMapper.createObjectNode().put("componentId", componentId)
                        .put("domain", domain).put("labelRange", labelRangeText).put("valueRange", valueRangeText);
                projectionId = "list-" + canonicalizer.hash(basis).substring(0, 16);
            }
            if (!ids.add(projectionId)) throw experimentError("listProjectionId必须唯一：" + projectionId);
            var projection = objectMapper.createObjectNode()
                    .put("listProjectionId", projectionId)
                    .put("domain", domain)
                    .put("componentId", componentId)
                    .put("parentBindingId", parentBindingId)
                    .put("recordAxis", recordAxis)
                    .put("itemAxis", itemAxis)
                    .put("labelRange", labelRangeText)
                    .put("valueRange", valueRangeText)
                    .put("labelSemantic", labelSemantic)
                    .put("valueSemantic", valueSemantic)
                    .put("itemSourceKeyPattern", "MATRIX:" + projectionId + ":{relativeItemOrdinal}");
            if (!unitRangeText.isBlank()) projection.put("unitRange", unitRangeText);
            var fingerprintBasis = projection.deepCopy();
            projection.put("projectionFingerprint", canonicalizer.hash(fingerprintBasis));
            result.add(projection);
        }
        var sorted = new ArrayList<JsonNode>();
        result.forEach(sorted::add);
        sorted.sort(Comparator.comparing(item -> item.path("listProjectionId").asText("")));
        var stable = objectMapper.createArrayNode();
        sorted.forEach(stable::add);
        return stable;
    }

    private boolean overlapsIdentity(A1Range range, ObjectNode component, ObjectNode experimentImport) {
        for (var identity : experimentImport.path("identities")) {
            if (!component.path("componentId").asText("").equals(identity.path("componentId").asText(""))) continue;
            var binding = findBinding(component, identity.path("bindingId").asText(""));
            if (binding == null) continue;
            var identityRange = locatorRange(binding.path("locator"));
            if (identityRange != null && range.overlaps(identityRange)) return true;
        }
        return false;
    }

    private void validateSingleValueSemantics(ArrayNode components) {
        for (var component : components) {
            var seen = new HashSet<String>();
            for (var binding : component.path("bindings")) {
                var semantic = binding.path("experimentField");
                var domain = semantic.path("domain").asText("");
                var field = semantic.path("field").asText("");
                if (!Set.of("BASIC", "CONCLUSION").contains(domain)) continue;
                var key = domain + "." + field;
                if (!seen.add(key)) throw experimentError("同一数据区域的单值实验字段重复映射：" + key);
            }
        }
    }

    private void summarizeBusinessTypes(ArrayNode components) {
        for (var component : components) {
            var values = new java.util.TreeSet<String>();
            component.path("bindings").forEach(binding -> {
                var domain = binding.path("experimentField").path("domain").asText("");
                if (!domain.isBlank()) values.add(domain);
            });
            var array = ((ObjectNode) component).putArray("businessTypes");
            values.forEach(array::add);
        }
    }

    private ObjectNode normalizedExperimentField(JsonNode mapping) {
        var source = mapping.path("experimentField").isObject()
                ? mapping.path("experimentField") : mapping.path("diagnostic").path("experimentField");
        if (!source.isObject()) return null;
        var domain = source.path("domain").asText("").trim().toUpperCase(Locale.ROOT);
        var field = source.path("field").asText("").trim().toUpperCase(Locale.ROOT);
        validateExperimentField(domain, field);
        return objectMapper.createObjectNode().put("domain", domain).put("field", field);
    }

    private void validateExperimentField(String domain, String field) {
        if (!EXPERIMENT_FIELDS.containsKey(domain) || !EXPERIMENT_FIELDS.get(domain).contains(field)) {
            throw experimentError("实验字段语义无效：" + domain + "." + field);
        }
    }

    private String targetPath(JsonNode semantic) {
        var domain = semantic.path("domain").asText("");
        var field = semantic.path("field").asText("");
        if ("BASIC".equals(domain)) return switch (field) {
            case "SOURCE_IDENTITY" -> "/sourceIdentity";
            case "TITLE" -> "/title";
            case "OWNER" -> "/ownerName";
            case "EXPERIMENT_DATE" -> "/experimentDate";
            case "PURPOSE" -> "/editModel/purpose";
            case "PLAN" -> "/editModel/plan";
            default -> "/editModel/dynamicValues/*";
        };
        if ("FORMULA".equals(domain)) return "/editModel/formulaItems/*/" + lowerCamel(field);
        if ("PROCESS".equals(domain)) return "/editModel/processSteps/*/" + lowerCamel(field);
        if ("TEST".equals(domain)) return "/editModel/testResults/*/" + lowerCamel(field);
        if ("CONCLUSION".equals(domain)) return "/editModel/conclusion/" + lowerCamel(field);
        return "/editModel/dynamicValues/*";
    }

    private String lowerCamel(String value) {
        var parts = value.toLowerCase(Locale.ROOT).split("_");
        var result = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            if (parts[index].isBlank()) continue;
            result.append(Character.toUpperCase(parts[index].charAt(0))).append(parts[index].substring(1));
        }
        return result.toString();
    }

    private ObjectNode findComponent(ArrayNode components, String componentId) {
        for (var component : components) {
            if (componentId.equals(component.path("componentId").asText(""))) return (ObjectNode) component;
        }
        return null;
    }

    private ObjectNode findBinding(ObjectNode component, String bindingId) {
        for (var binding : component.path("bindings")) {
            if (bindingId.equals(binding.path("bindingId").asText(""))) return (ObjectNode) binding;
        }
        return null;
    }

    private String firstIdentityBinding(ObjectNode component) {
        for (var binding : component.path("bindings")) {
            if (binding.path("identity").asBoolean(false)) return binding.path("bindingId").asText("");
        }
        return "";
    }

    private A1Range locatorRange(JsonNode locator) {
        for (var key : List.of("logicalInputRange", "valueRange", "recordRange", "dataRange", "address", "range", "sourceRange")) {
            var parsed = parseA1Range(locator.path(key).asText(""));
            if (parsed != null) return parsed;
        }
        return null;
    }

    private A1Range parseA1Range(String source) {
        if (source == null || source.isBlank()) return null;
        var normalized = source.replace("$", "").replace(" ", "").toUpperCase(Locale.ROOT);
        var bang = normalized.lastIndexOf('!');
        if (bang >= 0) normalized = normalized.substring(bang + 1);
        var parts = normalized.split(":", 2);
        var first = parseA1Cell(parts[0]);
        var last = parseA1Cell(parts.length == 1 ? parts[0] : parts[1]);
        if (first == null || last == null) return null;
        return new A1Range(Math.min(first.row(), last.row()), Math.max(first.row(), last.row()),
                Math.min(first.column(), last.column()), Math.max(first.column(), last.column()));
    }

    private A1Cell parseA1Cell(String source) {
        var matcher = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$").matcher(source);
        if (!matcher.matches()) return null;
        var column = 0;
        for (var letter : matcher.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return new A1Cell(Integer.parseInt(matcher.group(2)), column);
    }

    private ApiException experimentError(String message) {
        return new ApiException(ApiErrorCode.TEMPLATE_EXPERIMENT_SEMANTICS_INVALID, message);
    }

    private record A1Cell(int row, int column) {}

    private record A1Range(int startRow, int endRow, int startColumn, int endColumn) {
        int rows() { return endRow - startRow + 1; }
        int columns() { return endColumn - startColumn + 1; }
        boolean contains(A1Range other) {
            return other.startRow >= startRow && other.endRow <= endRow
                    && other.startColumn >= startColumn && other.endColumn <= endColumn;
        }
        boolean overlaps(A1Range other) {
            return startRow <= other.endRow && endRow >= other.startRow
                    && startColumn <= other.endColumn && endColumn >= other.startColumn;
        }
    }

    private String componentFingerprint(JsonNode component) {
        var basis = objectMapper.createObjectNode()
                .put("structureType", component.path("structureType").asText(""))
                .put("range", component.path("range").asText(""));
        var bindings = basis.putArray("bindings");
        var sorted = new ArrayList<JsonNode>();
        component.path("bindings").forEach(sorted::add);
        sorted.sort(Comparator.comparing(item -> item.path("bindingId").asText("")));
        sorted.forEach(item -> bindings.add(objectMapper.createObjectNode()
                .put("bindingId", item.path("bindingId").asText(""))
                .put("labelPath", item.path("labelPath").asText(""))
                .put("mappingKind", item.path("mappingKind").asText(""))
                .put("valueSource", item.path("valueSource").asText("INPUT"))
                .set("locator", item.path("locator").deepCopy())));
        return canonicalizer.hash(basis);
    }

    private void copy(JsonNode source, ObjectNode target, String... keys) {
        for (var key : keys) if (source.has(key) && !source.path(key).isNull()) target.set(key, source.path(key).deepCopy());
    }

    private String firstNonBlank(JsonNode source, String... keys) {
        for (var key : keys) {
            var value = source.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String generatedComponentId(JsonNode source) {
        return "component-" + canonicalizer.hash(source).substring(0, 16);
    }

    private boolean isComponentRoot(JsonNode mapping) {
        if (!firstNonBlank(mapping, "parentBindingId").isBlank()) return false;
        var kind = firstNonBlank(mapping, "mappingKind", "role").toUpperCase(java.util.Locale.ROOT);
        return kind.contains("REGION") || kind.contains("TABLE");
    }

    private String componentName(JsonNode mapping) {
        for (var value : List.of(
                mapping.path("groupName").asText(""),
                mapping.path("name").asText(""),
                mapping.path("displayName").asText(""),
                mapping.path("diagnostic").path("groupName").asText(""),
                mapping.path("diagnostic").path("displayName").asText(""),
                mapping.path("diagnostic").path("title").asText(""))) {
            var result = userFacingText(value);
            if (!result.isBlank()) return result;
        }
        return "";
    }

    private String userFacingText(String value) {
        if (value == null) return "";
        var normalized = value.trim();
        if (normalized.isBlank()
                || normalized.matches("(?i)^(?:AUTO|TABLE|DATA|MATERIAL|PRODUCTION|WORKFLOW|FIELD)\\..+$")
                || normalized.matches("[A-Z0-9_.:/@\\-]+")
                || normalized.matches("b_[a-fA-F0-9]+")) return "";
        return normalized;
    }

    public record CompiledContract(int importContractVersion, int layoutStructureVersion,
                                   String contractHash, JsonNode contract) {}
}
