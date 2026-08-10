package com.jsd.aird.tpl.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

/**
 * Normalizes the logical data path of an already identified binding.
 *
 * A locator describes where a value is in an Office document.  A data path
 * describes where the same value lives in the template data model.  Model
 * output may omit the latter for a structural parent, so this class owns the
 * deterministic, format-independent fallback instead of making every caller
 * invent a template-specific rule.
 */
public final class MappingPathNormalizer {

    private MappingPathNormalizer() {
    }

    public static JsonNode normalize(JsonNode mapping) {
        if (mapping == null || !mapping.isArray()) return mapping;
        var result = (ArrayNode) mapping.deepCopy();
        var paths = new HashMap<String, String>();
        var usedPaths = new HashSet<String>();
        var inferredParentPaths = inferParentPaths(result);

        // Keep every explicit path exactly as the user or compiler supplied it.
        for (JsonNode node : result) {
            if (!(node instanceof ObjectNode object)) continue;
            var bindingId = object.path("bindingId").asText("");
            var path = object.path("dataPath").asText("");
            if (StringUtils.hasText(bindingId) && StringUtils.hasText(path)) {
                paths.put(bindingId, path);
                usedPaths.add(path);
            }
        }

        // Parent mappings normally precede children, but do not rely on the
        // client array order: resolve children in a few deterministic passes.
        for (int pass = 0; pass < result.size(); pass++) {
            var changed = false;
            for (JsonNode node : result) {
                if (!(node instanceof ObjectNode object)
                        || StringUtils.hasText(object.path("dataPath").asText(""))) continue;

                var bindingId = object.path("bindingId").asText("");
                var key = stableKey(object, bindingId);
                var mappingKind = mappingKind(object);
                var parentId = object.path("parentBindingId").asText("");
                var parentPath = paths.get(parentId);
                // A child with a declared parent must wait until that parent
                // has a path; otherwise validateRepeatMappings would receive
                // a misleading top-level path.
                if (isChild(mappingKind) && StringUtils.hasText(parentId)
                        && !StringUtils.hasText(parentPath)) continue;
                String generated;
                if (isChild(mappingKind) && StringUtils.hasText(parentPath)) {
                    generated = parentPath + "/*/" + key;
                } else if (isRegion(mappingKind)) {
                    // If a child already carries a valid collection path such as
                    // /formulaItems/*/sequence, that path is stronger evidence
                    // than a generic /records/<fieldCode> fallback. This keeps
                    // legacy structural parents compatible with their children.
                    generated = inferredParentPaths.getOrDefault(bindingId, "");
                    if (!StringUtils.hasText(generated)) generated = "/records/" + key;
                } else {
                    generated = "/recognized/" + key;
                }
                generated = uniquePath(generated, bindingId, usedPaths);
                object.put("dataPath", generated);
                object.withObject("diagnostic")
                        .put("dataPathSource", "BACKEND_STABLE_FALLBACK");
                if (StringUtils.hasText(bindingId)) paths.put(bindingId, generated);
                changed = true;
            }
            if (!changed) break;
        }
        return result;
    }

    private static Map<String, String> inferParentPaths(ArrayNode mappings) {
        var candidates = new HashMap<String, Set<String>>();
        for (JsonNode node : mappings) {
            if (!(node instanceof ObjectNode object)) continue;
            var parentId = object.path("parentBindingId").asText("");
            var childPath = object.path("dataPath").asText("");
            if (!StringUtils.hasText(parentId) || !isChild(mappingKind(object))) continue;
            var marker = childPath.indexOf("/*/");
            if (marker <= 0) continue;
            var parentPath = childPath.substring(0, marker);
            if (parentPath.startsWith("/")) {
                candidates.computeIfAbsent(parentId, ignored -> new HashSet<>()).add(parentPath);
            }
        }
        var inferred = new HashMap<String, String>();
        candidates.forEach((parentId, paths) -> {
            if (paths.size() == 1) inferred.put(parentId, paths.iterator().next());
        });
        return inferred;
    }

    private static String mappingKind(ObjectNode object) {
        var explicit = object.path("mappingKind").asText("");
        if (StringUtils.hasText(explicit)) return explicit;
        return object.path("role").asText("");
    }

    private static boolean isRegion(String mappingKind) {
        return "REPEAT_REGION".equals(mappingKind) || "MATRIX_REGION".equals(mappingKind)
                || "REPEAT_REGION".equalsIgnoreCase(mappingKind);
    }

    private static boolean isChild(String mappingKind) {
        return "REPEAT_FIELD".equals(mappingKind) || "MATRIX_FIELD".equals(mappingKind);
    }

    private static String stableKey(ObjectNode object, String bindingId) {
        var fieldCode = object.path("fieldCode").asText("");
        if (!StringUtils.hasText(fieldCode)) {
            fieldCode = object.path("relationId").asText("");
        }
        if (!StringUtils.hasText(fieldCode)) {
            fieldCode = "binding_" + RecognitionIdentity.shortHash(bindingId, 12);
        }
        var normalized = fieldCode.replaceAll("[^A-Za-z0-9_\\-]", "_")
                .replaceAll("_+", "_");
        return StringUtils.hasText(normalized)
                ? normalized
                : "binding_" + RecognitionIdentity.shortHash(bindingId, 12);
    }

    private static String uniquePath(String base, String bindingId, Set<String> usedPaths) {
        if (usedPaths.add(base)) return base;
        var suffix = RecognitionIdentity.shortHash(bindingId + "|" + base, 10);
        var candidate = base + "__" + suffix;
        var ordinal = 2;
        while (!usedPaths.add(candidate)) candidate = base + "__" + suffix + "_" + ordinal++;
        return candidate;
    }
}
