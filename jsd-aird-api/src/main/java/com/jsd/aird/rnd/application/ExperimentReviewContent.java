package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Data review follows business content, not canvas layout or workflow revisions. */
final class ExperimentReviewContent {
    private ExperimentReviewContent() {}

    static JsonNode workbook(ObjectMapper json, JsonNode snapshot) {
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

    static JsonNode instance(ObjectMapper json, JsonNode source) {
        var result = json.createObjectNode();
        for (var key : List.of("model", "mapping", "execution", "records", "items"))
            if (source.has(key)) result.set(key, source.path(key));
        return result;
    }
}
