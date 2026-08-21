package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import org.springframework.stereotype.Component;

/** Compiles the immutable execution contract consumed by the data center. */
@Component
public class TemplateImportContractCompiler {

    public static final int IMPORT_CONTRACT_VERSION = 7;
    public static final int LEGACY_LAYOUT_STRUCTURE_VERSION = 6;

    private final ObjectMapper objectMapper;
    private final JsonCanonicalizer canonicalizer;

    public TemplateImportContractCompiler(ObjectMapper objectMapper, JsonCanonicalizer canonicalizer) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    public CompiledContract compile(JsonNode layoutSummary, JsonNode schema, JsonNode mappings) {
        var layoutVersion = layoutStructureVersion(layoutSummary);
        var contract = objectMapper.createObjectNode()
                .put("importContractVersion", IMPORT_CONTRACT_VERSION)
                .put("layoutStructureVersion", layoutVersion)
                .put("identityFallback", "IMPORT_SCOPED")
                .put("compatibilityPolicy", "CONTROLLED");
        contract.set("fields", contractFields(schema));
        var components = components(mappings, schema);
        attachSheetFingerprints(components, layoutSummary);
        contract.set("components", components);
        var hash = canonicalizer.hash(contract);
        contract.put("contractHash", hash);
        return new CompiledContract(IMPORT_CONTRACT_VERSION, layoutVersion, hash, contract);
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
        return LEGACY_LAYOUT_STRUCTURE_VERSION;
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

    private JsonNode components(JsonNode mappings, JsonNode schema) {
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
                for (var key : List.of("recordIdentity", "rowAttributeColumns", "recordProjection",
                        "fieldGroups", "fieldRows", "longTableModel")) {
                    if (mapping.path(key).isContainerNode()) component.set(key, mapping.path(key).deepCopy());
                }
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
            var labelPath = labelPath(mapping, fieldNames);
            if (!labelPath.isBlank()) binding.put("labelPath", labelPath);
            if (mapping.path("labelPathSegments").isArray()) {
                binding.set("labelPathSegments", mapping.path("labelPathSegments").deepCopy());
            }
            if (mapping.path("rowAttributes").isArray()) {
                binding.set("rowAttributes", mapping.path("rowAttributes").deepCopy());
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
            if (kind.contains("REGION") || kind.equals("ROW_TABLE") || kind.equals("COLUMN_TABLE")
                    || kind.equals("MATRIX")) continue;
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
        if (!type.contains("COLUMN") && !type.contains("MATRIX")
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
            component.put("name", type.contains("MATRIX") ? "矩阵数据"
                    : type.contains("COLUMN") ? "测试数据" : "明细数据");
        }
    }

    private boolean isRepeatedStructure(String type) {
        return type.contains("REPEAT") || type.contains("TABLE") || type.contains("ROW")
                || type.contains("COLUMN") || type.contains("MATRIX");
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
                && Set.of("FORM_REGION", "ROW_TABLE", "COLUMN_TABLE", "MATRIX", "MATRIX_FIELD")
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
        return kind.contains("REGION") || kind.contains("TABLE") || kind.contains("MATRIX");
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
                || normalized.matches("(?i)^(?:AUTO|TABLE|MATRIX|DATA|MATERIAL|PRODUCTION|WORKFLOW|FIELD)\\..+$")
                || normalized.matches("[A-Z0-9_.:/@\\-]+")
                || normalized.matches("b_[a-fA-F0-9]+")) return "";
        return normalized;
    }

    public record CompiledContract(int importContractVersion, int layoutStructureVersion,
                                   String contractHash, JsonNode contract) {}
}
