package com.jsd.aird.shared.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.excel.WorkbookStructure;
import java.util.*;

/** Keeps each physical laboratory sheet's fields, records and context independent. */
public final class LabWorkbookSheets {
    private LabWorkbookSheets() {}

    public static List<JsonNode> businessSheets(JsonNode snapshot) {
        var result = new ArrayList<JsonNode>();
        for (var id : snapshot.path("sheetOrder")) {
            var sheet = snapshot.path("sheets").path(id.asText());
            if (sheet.isObject() && !sheet.path("name").asText().startsWith("_JSD")) result.add(sheet);
        }
        if (result.isEmpty()) for (var sheet : snapshot.path("sheets"))
            if (!sheet.path("name").asText().startsWith("_JSD")) result.add(sheet);
        return result;
    }

    public static boolean multiple(JsonNode snapshot) { return businessSheets(snapshot).size() > 1; }

    public static ObjectNode single(ObjectMapper json, JsonNode source, JsonNode sheet) {
        return single(json, source, sheet, true);
    }

    private static ObjectNode single(ObjectMapper json, JsonNode source, JsonNode sheet, boolean includeInstance) {
        var out = json.createObjectNode();
        source.fields().forEachRemaining(e -> { if (!Set.of("sheets", "sheetOrder", "resources").contains(e.getKey())) out.set(e.getKey(), e.getValue()); });
        var copy = json.createObjectNode();
        sheet.fields().forEachRemaining(e -> {
            if (!e.getKey().equals("custom")) copy.set(e.getKey(), e.getValue().deepCopy());
        });
        var custom = copy.putObject("custom");
        sheet.path("custom").fields().forEachRemaining(e -> {
            if (!e.getKey().equals("templateInstance")) custom.set(e.getKey(), e.getValue().deepCopy());
        });
        var id = sheet.path("id").asText();
        out.putObject("sheets").set(id, copy);
        out.putArray("sheetOrder").add(id);
        var instance = WorkbookStructure.instance(source);
        if (includeInstance && instance.isObject()) {
            var local = localInstance(json, instance, id);
            if (!local.path("mapping").isEmpty()) WorkbookStructure.attach(out, local);
        }
        return out;
    }

    public static ObjectNode prepare(ObjectMapper json, JsonNode source, JsonNode baseline, String seed) {
        var out = (ObjectNode) source.deepCopy();
        var locals = new ArrayList<JsonNode>();
        for (var sheet : businessSheets(source)) {
            var id = sheet.path("id").asText();
            var local = LabWorkbook.prepare(json, single(json, source, sheet), baseline, seed + ":" + id);
            ((ObjectNode) out.path("sheets")).set(id, local.path("sheets").elements().next());
            var instance = WorkbookStructure.instance(local);
            if (instance.isObject()) locals.add(instance);
        }
        if (locals.size() == businessSheets(source).size()) WorkbookStructure.attach(out, combine(json, locals));
        return out;
    }

    /** Namespaces only structural identifiers; business labels, values and units stay intact. */
    public static ObjectNode combine(ObjectMapper json, List<JsonNode> locals) {
        var out = json.createObjectNode().put("version", 2).put("labMultiSheet", true);
        var model = out.putObject("model").put("modelVersion", 4);
        for (var key : List.of("groups", "fields", "blocks", "semanticAnnotations")) model.putArray(key);
        for (var key : List.of("mapping", "records", "items", "issues", "labSheets")) out.putArray(key);
        var execution = out.putObject("execution").put("templateUsage", "EXPERIMENT_DATA").put("recordMode", "SINGLE_FILE");
        execution.putArray("identities"); execution.putArray("listProjections");
        for (var original : locals) {
            String sheetId = "";
            for (var binding : original.path("mapping")) if (!binding.path("locator").path("sheetId").asText().isBlank()) {
                sheetId = binding.path("locator").path("sheetId").asText(); break;
            }
            if (sheetId.isBlank()) continue;
            var prefix = "s" + LabWorkbook.id("lab-sheet", sheetId).replace("-", "").substring(0, 16) + ".";
            var local = (ObjectNode) original.deepCopy();
            var groupByBinding=new HashMap<String,String>();
            for(var field:local.path("model").path("fields"))groupByBinding.put(field.path("bindingId").asText(),field.path("groupId").asText("basic"));
            for(var binding:local.path("mapping"))if(binding instanceof ObjectNode object&&binding.path("mappingKind").asText().equals("SCALAR"))
                object.put("componentId","shared."+groupByBinding.getOrDefault(binding.path("bindingId").asText(),"basic"));
            namespace(local, prefix, false);
            for(var key:List.of("records","items"))for(int i=0;i<local.path(key).size();i++)
                ((ObjectNode)local.path(key).get(i)).set("id",original.path(key).get(i).path("id"));
            out.withArray("/labSheets").addObject().put("sheetId", sheetId).put("prefix", prefix);
            for (var key : List.of("groups", "fields", "blocks", "semanticAnnotations"))
                for (var item : local.path("model").path(key)) model.withArray("/" + key).add(item);
            for (var key : List.of("mapping", "records", "items", "issues"))
                for (var item : local.path(key)) out.withArray("/" + key).add(item);
            for (var key : List.of("identities", "listProjections"))
                for (var item : local.path("execution").path(key)) execution.withArray("/" + key).add(item);
        }
        return out;
    }

    private static final Set<String> IDS = Set.of("id", "bindingId", "fieldId", "fieldCode", "groupId", "parentId", "parentBindingId", "componentId", "regionId", "listProjectionId", "originalFieldCode");

    private static void namespace(JsonNode node, String prefix, boolean reverse) {
        if (node instanceof ObjectNode object) {
            // A physical sheet id and a source cell address never belong to this namespace.
            var entries = new ArrayList<Map.Entry<String, JsonNode>>(); object.fields().forEachRemaining(entries::add);
            for (var entry : entries) {
                var key = entry.getKey(); var value = entry.getValue();
                if (value.isTextual() && IDS.contains(key) && !value.asText().isBlank()) {
                    var text = value.asText();
                    object.put(key, reverse ? text.startsWith(prefix) ? text.substring(prefix.length()) : text : prefix + text);
                } else if (key.equals("dataPath") && value.isTextual()) {
                    var text = value.asText();
                    if (text.startsWith("/fields/")) {
                        var code = text.substring(8);
                        object.put(key, "/fields/" + (reverse ? code.startsWith(prefix) ? code.substring(prefix.length()) : code : prefix + code));
                    }
                } else namespace(value, prefix, reverse);
            }
        } else if (node.isArray()) for (var child : node) namespace(child, prefix, reverse);
    }

    public static ObjectNode localInstance(ObjectMapper json, JsonNode instance, String sheetId) {
        var out = json.createObjectNode();
        instance.fields().forEachRemaining(e -> { if (!Set.of("model", "mapping", "records", "items", "issues", "execution").contains(e.getKey())) out.set(e.getKey(), e.getValue().deepCopy()); });
        out.putObject("model").put("modelVersion", instance.path("model").path("modelVersion").asInt(4));
        out.putObject("execution").put("templateUsage", "EXPERIMENT_DATA").put("recordMode", "SINGLE_FILE");
        var bindings = out.putArray("mapping"); var bindingIds = new HashSet<String>(); var groups = new HashSet<String>();
        for (var binding : instance.path("mapping")) if (sheetId.equals(binding.path("locator").path("sheetId").asText())) {
            bindings.add(binding.deepCopy()); bindingIds.add(binding.path("bindingId").asText()); bindingIds.add(binding.path("componentId").asText());
        }
        var fields = out.withObject("/model").putArray("fields");
        for (var field : instance.path("model").path("fields")) if (bindingIds.contains(field.path("bindingId").asText())) {
            fields.add(field.deepCopy()); groups.add(field.path("groupId").asText());
        }
        var groupNodes = out.withObject("/model").putArray("groups");
        for (var group : instance.path("model").path("groups")) if (groups.contains(group.path("id").asText())) groupNodes.add(group.deepCopy());
        for (var key : List.of("records", "items", "issues")) {
            var values = out.putArray(key);
            for (var item : instance.path(key)) if (sheetId.equals(item.path("sheetId").asText())) values.add(item.deepCopy());
        }
        for (var key : List.of("identities", "listProjections")) {
            var values = out.withObject("/execution").putArray(key);
            for (var item : instance.path("execution").path(key)) if (bindingIds.contains(item.path("componentId").asText())) values.add(item.deepCopy());
        }
        String prefix = "";
        for (var item : instance.path("labSheets")) if (sheetId.equals(item.path("sheetId").asText())) prefix = item.path("prefix").asText();
        if (!prefix.isBlank()) namespace(out, prefix, true);
        out.remove(List.of("labMultiSheet", "labSheets"));
        return out;
    }

    static void seed(ObjectMapper json, JsonNode snapshot, ObjectNode instance, boolean fresh) {
        var locals = new ArrayList<JsonNode>();
        for (var sheet : businessSheets(snapshot)) {
            var localSnapshot = single(json, snapshot, sheet);
            var local = localInstance(json, instance, sheet.path("id").asText());
            boolean missing = local.path("mapping").isEmpty();
            if (missing) {
                // Reuse structural regions and standard fields only. Local
                // observations on another physical sheet are not evidence here.
                var prototypeSheet="";
                for(var binding:instance.path("mapping"))if(!binding.path("locator").path("sheetId").asText().isBlank()) {
                    prototypeSheet=binding.path("locator").path("sheetId").asText();break;
                }
                local=prototypeSheet.isBlank()?(ObjectNode)instance.deepCopy():localInstance(json,instance,prototypeSheet);
                var targetFields=LabWorkbook.definition(localSnapshot).path("fields");
                var fields=(ArrayNode)local.path("model").path("fields");
                for(int n=fields.size()-1;n>=0;n--)if(!fields.get(n).path("displayRole").asText().equals("REGION")
                        &&!targetFields.has(fields.get(n).path("fieldCode").asText()))fields.remove(n);
                var mappings=(ArrayNode)local.path("mapping");
                for(int n=mappings.size()-1;n>=0;n--)if(!mappings.get(n).path("mappingKind").asText().equals("REPEAT_REGION")
                        &&!targetFields.has(mappings.get(n).path("fieldCode").asText()))mappings.remove(n);
                local.putArray("records");local.putArray("items");local.putArray("issues");
                for (var binding : local.path("mapping")) if (binding.path("locator") instanceof ObjectNode locator)
                    locator.put("sheetId", sheet.path("id").asText()).put("sheetName", sheet.path("name").asText());
            }
            LabStructureAdapter.seed(json, localSnapshot, local, fresh || missing);
            locals.add(local);
        }
        instance.removeAll(); instance.setAll(combine(json, locals));
    }

    static ObjectNode extract(ObjectMapper json, JsonNode snapshot, JsonNode previous, String seed) {
        var model = (ObjectNode) previous.deepCopy();
        var originalInstance = WorkbookStructure.instance(snapshot).deepCopy();
        var updatedSheets = new LinkedHashMap<String,JsonNode>();
        for (var key : List.of("sourceGroups", "sampleGroups", "formulaItems", "processSteps", "testResults")) model.putArray(key);
        var contexts = model.putArray("sourceContexts"); var dynamic = model.putObject("dynamicValues"); var issues = json.createArrayNode();
        for (var sheet : businessSheets(snapshot)) {
            var sid = sheet.path("id").asText();
            var localSnapshot = single(json, snapshot, sheet);
            var local = LabWorkbook.extract(json, localSnapshot, json.createObjectNode(), seed + ":" + sid);
            for (var key : List.of("sourceGroups", "sampleGroups", "formulaItems", "processSteps", "testResults"))
                for (var item : local.path(key)) model.withArray("/" + key).add(item);
            var context = contexts.addObject().put("sourceContextKey", seed + ":" + sid).put("sheetId", sid).put("sheetName", sheet.path("name").asText());
            context.set("fields", local.path("dynamicValues").path("fields").deepCopy());
            dynamic.set(sid, local.path("dynamicValues").deepCopy());
            for (var issue : local.path("templateInstance").path("issues")) {
                var copy = (ObjectNode) issue.deepCopy(); copy.put("sheetId", sid); issues.add(copy);
            }
            // Preserve recalculated caches in the full physical snapshot.
            var updated = (ObjectNode) localSnapshot.path("sheets").path(sid);
            updated.withObject("/custom").remove("templateInstance");
            updatedSheets.put(sid,updated);
        }
        updatedSheets.forEach((id,sheet)->((ObjectNode)snapshot.path("sheets")).set(id,sheet));
        var generic = originalInstance;
        var instance = generic.isObject() ? (ObjectNode) generic.deepCopy() : json.createObjectNode().put("version", 1);
        instance.set("issues", issues); instance.put("labMultiSheet", true);
        if (originalInstance.isObject()) WorkbookStructure.attach(snapshot, originalInstance);
        model.set("templateInstance", instance); model.set("documentSnapshot", snapshot.deepCopy()); model.put("documentFormat", "excel");
        return model;
    }

    public static ObjectNode validate(ObjectMapper json,JsonNode snapshot,JsonNode provided) {
        var locals=new ArrayList<JsonNode>();
        for(var sheet:businessSheets(snapshot)) {
            var local=localInstance(json,provided,sheet.path("id").asText());
            if(local.path("mapping").isEmpty())continue;
            // The local instance is already selected. Do not select/copy it again,
            // or deep-copy the full workbook instance embedded in the first sheet.
            locals.add(WorkbookStructure.validate(json,single(json,snapshot,sheet,false),local));
        }
        var merged=combine(json,locals);
        WorkbookStructure.attach(snapshot,merged);
        return merged;
    }
}
