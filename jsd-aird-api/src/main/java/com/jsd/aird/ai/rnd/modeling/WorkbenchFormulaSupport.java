package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Bridges immutable table-column identities to composition inputs without renaming model features. */
public final class WorkbenchFormulaSupport {
    private WorkbenchFormulaSupport() {}

    public static boolean supports(String contract) { return "modeling-workbench.v1".equals(contract); }

    public static String materialId(JsonNode field) {
        var code = field.path("code").asText();
        if (!code.startsWith("formula:") || !Set.of("NUMERIC", "NUMBER").contains(field.path("kind").asText(field.path("valueType").asText()))) return null;
        var unit = field.path("unit").asText("");
        if (!unit.isBlank() && !Set.of("%", "PERCENT").contains(unit)) return null;
        var id = code.substring("formula:".length());
        try { return UUID.fromString(id).toString().equals(id) ? id : null; }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public static Set<String> materialIds(JsonNode scheme) {
        var ids = new LinkedHashSet<String>();
        for (var field : scheme.path("fields")) { var id = materialId(field); if (id != null) ids.add(id); }
        return ids;
    }

    public static ObjectNode dictionary(ObjectMapper json, JsonNode scheme, JsonNode rows) {
        var dictionary = json.createObjectNode().put("id", "workbench-materials:" + scheme.path("id").asText("columns")).put("version", 1);
        var materials = dictionary.putArray("materials");
        for (var field : scheme.path("fields")) {
            var id = materialId(field);
            if (id == null) continue;
            var name = field.path("name").asText(id);
            if (name.endsWith("比例")) name = name.substring(0, name.length() - 2);
            materials.addObject().put("materialId", id).put("code", id).put("name", name).put("encoderIndex", materials.size() - 1);
        }
        boolean complete = rows.isArray() && !rows.isEmpty() && !materials.isEmpty();
        for (var row : rows) {
            double total = 0;
            for (var field : scheme.path("fields")) if (materialId(field) != null) {
                var value = row.path("values").path(field.path("code").asText());
                try {
                    var number = Double.parseDouble(value.asText().strip());
                    if (!Double.isFinite(number) || number < 0) complete = false;
                    total += number;
                } catch (NumberFormatException ignored) { complete = false; }
            }
            if (!Double.isFinite(total) || Math.abs(total - 100) > 1e-4) complete = false;
        }
        dictionary.put("compositionStatus", materials.isEmpty() ? "NOT_USED" : complete ? "COMPLETE" : "INCOMPLETE");
        dictionary.put("sha256", new ConfigurationHashing(json).hash(dictionary));
        return dictionary;
    }
}
