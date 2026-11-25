package com.jsd.aird.shared.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Data review follows business content, not canvas layout or workflow revisions. */
public final class WorkbookReviewContent {
    private WorkbookReviewContent() {}

    public static JsonNode workbook(ObjectMapper json, JsonNode snapshot) {
        var result = json.createObjectNode();
        snapshot.path("sheets").fields().forEachRemaining(entry -> {
            var sheet = entry.getValue();
            if (sheet.path("name").asText().startsWith("_JSD")) return;
            var target = result.putObject(entry.getKey());
            var cells = target.putObject("cells");
            sheet.path("cellData").fields().forEachRemaining(row -> row.getValue().fields().forEachRemaining(column -> {
                var cell = column.getValue();
                var value = json.createObjectNode();
                // Formula result caches can change when the renderer initializes.
                if (!cell.path("f").asText().isBlank()) value.set("f", cell.path("f"));
                else if (cell.hasNonNull("v") && !cell.path("v").asText().isEmpty()) value.set("v", cell.path("v"));
                else if (!cell.path("p").path("body").path("dataStream").asText().isBlank())
                    value.put("v", cell.path("p").path("body").path("dataStream").asText().stripTrailing());
                if (!value.isEmpty()) cells.set(row.getKey() + ":" + column.getKey(), value);
            }));
            var instance = sheet.path("custom").path("templateInstance");
            if (instance.isObject()) target.set("structure", instance(json, instance));
            else if (sheet.path("custom").path("labTemplate").isObject())
                target.set("definition", sheet.path("custom").path("labTemplate"));
        });
        return result;
    }

    public static JsonNode instance(ObjectMapper json, JsonNode source) {
        var result = json.createObjectNode();
        for (var key : List.of("model", "mapping", "execution", "records", "items"))
            if (source.has(key)) result.set(key, source.path(key));
        return result;
    }

    /** A first save may add a generic projection of an already reviewed lab definition. */
    public static boolean equivalent(ObjectMapper json, JsonNode left, JsonNode right) {
        if(workbook(json,left).equals(workbook(json,right)))return true;
        boolean leftStructured=WorkbookStructure.instance(left).isObject();
        boolean rightStructured=WorkbookStructure.instance(right).isObject();
        if(leftStructured==rightStructured)return false;
        var a=labContent(json,left);var b=labContent(json,right);
        return a!=null && a.equals(b);
    }

    private static JsonNode labContent(ObjectMapper json,JsonNode snapshot) {
        if(!com.jsd.aird.shared.lab.LabWorkbook.supported(snapshot))return null;
        var copy=snapshot.deepCopy();
        com.jsd.aird.shared.lab.LabWorkbook.applyStructure(json,copy);
        var content=(ObjectNode)workbook(json,copy);
        for(var entry:iterable(copy.path("sheets").fields())) {
            var sheet=entry.getValue();if(sheet.path("name").asText().startsWith("_JSD"))continue;
            var definition=sheet.path("custom").path("labTemplate");
            if(!definition.isObject())return null;
            for(var value:definition.path("fields"))if(value instanceof ObjectNode field) {
                if(!field.hasNonNull("valueType"))field.put("valueType","NUMBER".equals(field.path("resultKind").asText())?"number":"string");
                field.remove("confirmed");
                for(var key:List.of("testCondition","baseMetric"))if(field.path(key).isNull())field.remove(key);
            }
            var target=(ObjectNode)content.path(entry.getKey());target.remove("structure");target.set("definition",definition);
        }
        return content;
    }
    private static <T> Iterable<T> iterable(java.util.Iterator<T> values) { return ()->values; }
}

