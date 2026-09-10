package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Materializes backend-confirmed experiment semantics into a new template draft. */
final class ExperimentTemplateSemanticCompiler {

    private final ObjectMapper objectMapper;
    private final ExperimentSemanticResolver resolver;

    ExperimentTemplateSemanticCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.resolver = new ExperimentSemanticResolver(objectMapper);
    }

    void apply(ObjectNode schema, ArrayNode mappings, ObjectNode fieldModel) {
        var groups = new LinkedHashMap<String, String>();
        for (var group : fieldModel.path("groups")) {
            groups.put(group.path("id").asText(""), group.path("name").asText(""));
        }
        var mappingsByBinding = new LinkedHashMap<String, ObjectNode>();
        for (var value : mappings) {
            if (value instanceof ObjectNode mapping) {
                mappingsByBinding.put(mapping.path("bindingId").asText(""), mapping);
            }
        }

        var identities = new LinkedHashMap<String, ObjectNode>();
        var projections = new LinkedHashMap<String, ObjectNode>();
        var domains = new LinkedHashSet<String>();
        var autoConfirmed = 0;
        var needsReview = 0;
        for (var value : fieldModel.path("fields")) {
            if (!(value instanceof ObjectNode field)) continue;
            var bindingId = field.path("bindingId").asText("");
            var mapping = mappingsByBinding.get(bindingId);
            if (!isRegion(field)) {
                if (!field.path("experimentField").isObject()) {
                    var relation = objectMapper.createObjectNode()
                            .put("fieldName", field.path("name").asText(""))
                            .put("businessName", field.path("name").asText(""))
                            .put("groupName", groups.getOrDefault(field.path("groupId").asText(""), ""))
                            .put("unit", field.path("unit").asText(""));
                    copyIfPresent(field, relation, "labelPath", "labelPathSegments");
                    if (mapping != null) copyIfPresent(mapping, relation, "labelPath", "labelPathSegments");
                    resolver.resolveField(relation, relation, confirmedRegion());
                    copyResolution(relation, field);
                }
                var semantic = field.path("experimentField");
                if (semantic.isObject()) {
                    domains.add(semantic.path("domain").asText(""));
                    if (mapping != null) {
                        mapping.set("experimentField", semantic.deepCopy());
                        copyIfPresent(field, mapping, "experimentItemLabel", "experimentSemanticConfidence",
                                "experimentSemanticStatus", "experimentSemanticSource",
                                "experimentSemanticAlternatives", "experimentSemanticIssue");
                    }
                    var semanticStatus = field.path("experimentSemanticStatus").asText("");
                    if ("AUTO_CONFIRMED".equals(semanticStatus)) autoConfirmed++;
                    else if ("NEEDS_REVIEW".equals(semanticStatus)
                            || !field.path("experimentSemanticIssue").asText("").isBlank()) needsReview++;
                    if ("BASIC".equals(semantic.path("domain").asText(""))
                            && "SOURCE_IDENTITY".equals(semantic.path("field").asText(""))
                            && mapping != null && !bindingId.isBlank()) {
                        var componentId = componentId(mapping, field);
                        var identity = objectMapper.createObjectNode()
                                .put("identityType", identityType(field.path("name").asText("")))
                                .put("sourceKind", "BINDING")
                                .put("componentId", componentId)
                                .put("bindingId", bindingId);
                        identities.put(componentId + "|" + bindingId, identity);
                        mapping.put("identity", true);
                    }
                }
            }

            if (field.path("listProjections").isArray()) {
                for (var valueProjection : field.path("listProjections")) {
                    if (!(valueProjection instanceof ObjectNode projection)) continue;
                    var copy = projection.deepCopy();
                    var rootMapping = mappingsByBinding.get(field.path("bindingId").asText(""));
                    var componentId = rootMapping == null
                            ? projection.path("componentId").asText("") : componentId(rootMapping, field);
                    copy.put("componentId", componentId);
                    if (rootMapping != null) copy.put("parentBindingId", rootMapping.path("bindingId").asText(""));
                    var id = copy.path("listProjectionId").asText("");
                    if (!id.isBlank()) projections.put(id, copy);
                }
            }
        }

        var existing = schema.path(TemplateImportContractCompiler.EXPERIMENT_IMPORT_SCHEMA_KEY);
        if (existing.path("listProjections").isArray()) {
            for (var value : existing.path("listProjections")) {
                if (!(value instanceof ObjectNode projection)) continue;
                var id = projection.path("listProjectionId").asText("");
                if (!id.isBlank()) {
                    var copy = projection.deepCopy();
                    var parentBindingId = copy.path("parentBindingId").asText("");
                    if (!parentBindingId.isBlank()) copy.put("componentId", parentBindingId);
                    projections.putIfAbsent(id, copy);
                }
            }
        }
        var experimentEvidence = !identities.isEmpty()
                && (!projections.isEmpty() || domains.contains("FORMULA") || domains.contains("TEST"));
        if (!experimentEvidence && !"EXPERIMENT_DATA".equals(existing.path("templateUsage").asText(""))) return;

        var configuration = objectMapper.createObjectNode()
                .put("templateUsage", "EXPERIMENT_DATA")
                .put("recordMode", "SINGLE_FILE");
        var identityArray = configuration.putArray("identities");
        identities.values().stream()
                .sorted(Comparator.comparing(item -> item.path("componentId").asText("")))
                .forEach(identityArray::add);
        var projectionArray = configuration.putArray("listProjections");
        projections.values().stream()
                .sorted(Comparator.comparing(item -> item.path("listProjectionId").asText("")))
                .forEach(projectionArray::add);
        var summary = configuration.putObject("recognitionSummary")
                .put("status", needsReview == 0 ? "AUTO_CONFIRMED" : "NEEDS_REVIEW")
                .put("autoConfirmedCount", autoConfirmed)
                .put("needsReviewCount", needsReview)
                .put("identityCount", identities.size())
                .put("matrixCount", projections.size());
        var domainArray = summary.putArray("domains");
        domains.stream().filter(value -> !value.isBlank()).sorted().forEach(domainArray::add);
        schema.set(TemplateImportContractCompiler.EXPERIMENT_IMPORT_SCHEMA_KEY, configuration);
    }

    private void copyResolution(JsonNode source, ObjectNode target) {
        copyIfPresent(source, target, "experimentField", "experimentItemLabel", "experimentSemanticConfidence",
                "experimentSemanticStatus", "experimentSemanticSource", "experimentSemanticAlternatives",
                "experimentSemanticIssue");
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String... keys) {
        for (var key : keys) if (source.has(key)) target.set(key, source.path(key).deepCopy());
    }

    private ObjectNode confirmedRegion() {
        return objectMapper.createObjectNode().put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED").put("structureConflict", false);
    }

    private boolean isRegion(JsonNode field) {
        return "REGION".equals(field.path("displayRole").asText(""))
                || "REPEAT_REGION".equals(field.path("mappingKind").asText(""))
                || List.of("FORM_REGION", "ROW_TABLE", "COLUMN_TABLE").contains(field.path("kind").asText(""));
    }

    private String componentId(JsonNode mapping, JsonNode field) {
        // The published contract groups repeated child bindings under their existing
        // repeat-region root. Recognition block ids are useful evidence, but they are
        // not the persisted component identity and must not leak into V9 identities.
        var parentBindingId = mapping.path("parentBindingId").asText("");
        if (!parentBindingId.isBlank()) return parentBindingId;
        for (var value : List.of(
                mapping.path("componentId").asText(""),
                mapping.path("locator").path("componentId").asText(""),
                mapping.path("locator").path("regionId").asText(""),
                field.path("regionId").asText(""),
                field.path("blockId").asText(""),
                mapping.path("bindingId").asText(""))) {
            if (!value.isBlank()) return value;
        }
        return "component-unknown";
    }

    private String identityType(String fieldName) {
        var normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (normalized.contains("样品")) return "SAMPLE_NO";
        if (normalized.contains("配方")) return "FORMULA_NO";
        if (normalized.contains("批次") || normalized.contains("批号")) return "BATCH_NO";
        return "EXPERIMENT_NO";
    }
}
