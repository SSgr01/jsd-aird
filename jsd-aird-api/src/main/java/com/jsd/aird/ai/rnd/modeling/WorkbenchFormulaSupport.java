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
        if (!"MATERIAL_RATIO".equals(field.path("semanticType").asText())
                || !"MAPPED".equals(field.path("mappingStatus").asText())
                || !Set.of("NUMERIC", "NUMBER").contains(field.path("kind").asText(field.path("valueType").asText()))) return null;
        var unit = field.path("unit").asText("");
        if (!Set.of("%", "PERCENT").contains(unit)) return null;
        var id = field.path("materialId").asText();
        try {
            var canonical = UUID.fromString(id).toString();
            return canonical.equals(id) && code.equals("formula:" + id)
                    && "MASS_PERCENT".equals(field.path("compositionBasis").asText()) ? id : null;
        }
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
            materials.addObject().put("materialId", id).put("code", field.path("materialCode").asText(id)).put("name", name).put("encoderIndex", materials.size() - 1);
            var observed=new java.util.LinkedHashSet<java.math.BigDecimal>();int filled=0;
            for(var row:rows){var value=row.path("values").path(field.path("code").asText());if(value.isNumber()){observed.add(value.decimalValue().stripTrailingZeros());filled++;}}
            if(rows.size()>=6&&filled==rows.size()&&observed.size()==1)((ObjectNode)materials.get(materials.size()-1)).put("fixedValue",observed.iterator().next());
        }
        boolean hasRatioColumns=false, hasUnusableRatio=false;
        for(var field:scheme.path("fields"))if("MATERIAL_RATIO".equals(field.path("semanticType").asText())){
            hasRatioColumns=true;
            if(materialId(field)==null)hasUnusableRatio=true;
        }
        boolean complete = rows.isArray() && !rows.isEmpty() && !materials.isEmpty() && !hasUnusableRatio;
        var issues=dictionary.putArray("compositionIssues");
        for (var row : rows) {
            if(materials.isEmpty())break;
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            boolean rowComplete=true;
            for (var field : scheme.path("fields")) if (materialId(field) != null) {
                var value = row.path("values").path(field.path("code").asText());
                boolean fieldComplete=true;
                try {
                    var number = new java.math.BigDecimal(value.asText().strip());
                    if (!Double.isFinite(number.doubleValue()) || number.signum() < 0) { complete = false;fieldComplete=false; }
                    total = total.add(number);
                } catch (NumberFormatException ignored) { complete = false;fieldComplete=false; }
                if(!fieldComplete){rowComplete=false;issues.add("样品“"+row.path("label").asText(row.path("id").asText())+"”的“"+field.path("name").asText(field.path("code").asText())+"”比例缺失或无效");}
            }
            if (rowComplete&&total.subtract(new java.math.BigDecimal("100")).abs().compareTo(new java.math.BigDecimal("0.001"))>0) {complete = false;issues.add("样品“"+row.path("label").asText(row.path("id").asText())+"”的材料比例合计为"+total.stripTrailingZeros().toPlainString()+"%");}
        }
        dictionary.put("compositionStatus", materials.isEmpty() ? (hasRatioColumns?"INCOMPLETE":"NOT_USED") : complete ? "COMPLETE" : "INCOMPLETE");
        dictionary.put("sha256", new ConfigurationHashing(json).hash(dictionary));
        return dictionary;
    }
}
