package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.port.ExperimentAssemblyRepository.SourceImport;
import com.jsd.aird.data.application.port.ExperimentAssemblyRepository.SourceRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Deterministic V9 source-fact to ELN V2 draft projection. */
@Component
public class ExperimentImportAssembler {
    private final ObjectMapper json;

    public ExperimentImportAssembler(ObjectMapper json) {
        this.json = json;
    }

    /**
     * An experiment spreadsheet is one ELN aggregate. recordKey remains the physical
     * source-record boundary. sourceIdentity is only a non-unique business label;
     * cross-sheet logical sample joins require an explicit later rule.
     */
    public AssemblyPlan assemble(SourceImport source, JsonNode experimentImport) {
        var records = List.copyOf(source.records());
        var contextKeys = records.stream().filter(record -> identity(record).isBlank())
                .map(SourceRecord::recordKey).sorted().toList();
        var recordKeys = records.stream().map(SourceRecord::recordKey).sorted().toList();
        var conflicts = json.createArrayNode();
        var warnings = json.createArrayNode();
        var status = records.isEmpty() ? "BLOCKED" : "READY";
        if (records.isEmpty()) {
            conflicts.add(issue("SOURCE_RECORD_MISSING", "没有可用于生成实验草稿的已提交来源记录"));
        }

        var editModel = editModel(source, records, experimentImport, warnings);
        var assemblyKey = assemblyKey("FILE", source.id().toString());
        var contentHash = sourceContentHash(source, records);
        var planNode = json.createObjectNode()
                .put("assemblyKey", assemblyKey)
                .put("contentHash", contentHash)
                .put("assemblerVersion", "T04_SOURCE_GROUP_V2")
                .put("editModelHash", hash(editModel));
        planNode.set("sourceRecordKeys", json.valueToTree(recordKeys));
        var candidate = new Candidate(assemblyKey, null, null, "FILE", status, recordKeys, contextKeys,
                conflicts, warnings, editModel, hash(planNode), contentHash);
        var candidates = List.of(candidate);
        return new AssemblyPlan(source.id(), candidates,
                "READY".equals(status) ? 1 : 0,
                0,
                "BLOCKED".equals(status) ? 1 : 0,
                records.size(),
                editModel.path("sampleGroups").size(),
                editModel.path("sourceGroups").size(),
                editModel.path("sourceContexts").size(),
                distinctSourceIdentityCount(editModel.path("sourceGroups")),
                0);
    }

    private ObjectNode editModel(SourceImport source, List<SourceRecord> records, JsonNode experimentImport,
                                 ArrayNode warnings) {
        var model = json.createObjectNode().put("schemaVersion", 2);
        var title = consensusText(records, "BASIC", "TITLE", warnings);
        if (title.isBlank()) title = source.sourceFileName();
        model.put("title", title);
        model.put("documentFormat", "excel");
        // A file is the experiment aggregate. Source identities are child sample/formula keys.
        model.put("sourceExperimentNo", "");
        model.put("purpose", consensusText(records, "BASIC", "PURPOSE", warnings));
        model.put("plan", consensusText(records, "BASIC", "PLAN", warnings));
        model.put("sourceOwnerName", consensusText(records, "BASIC", "OWNER", warnings));
        model.put("sourceExperimentDate", consensusText(records, "BASIC", "EXPERIMENT_DATE", warnings));
        model.put("importedBy", source.importedBy() == null ? "" : source.importedBy().toString());
        model.put("importedAt", source.importedAt() == null ? "" : source.importedAt().toString());
        model.put("sourceImportJobId", source.id().toString());
        model.put("sourceFileId", source.sourceFileId().toString());
        model.put("sourceFileName", source.sourceFileName());
        model.put("sourceFileHash", source.sourceSha256());
        model.set("sourceRecordKeys", json.valueToTree(records.stream().map(SourceRecord::recordKey).sorted().toList()));
        var sourceGroups = sourceGroups(source, records, experimentImport);
        model.set("sourceGroups", sourceGroups);
        // Backward-compatible alias. It now represents safe physical source groups,
        // never an inferred cross-sheet logical sample.
        model.set("sampleGroups", sourceGroups.deepCopy());
        model.set("sourceContexts", sourceContexts(source, records, experimentImport));
        model.set("formulaItems", items(source, records, "FORMULA", experimentImport));
        model.set("processSteps", items(source, records, "PROCESS", experimentImport));
        model.set("testResults", items(source, records, "TEST", experimentImport));
        model.set("tables", json.createArrayNode());
        model.set("events", json.createArrayNode());
        var conclusion = json.createObjectNode()
                .put("resultStatus", consensusText(records, "CONCLUSION", "RESULT_STATUS", warnings))
                .put("mainConclusion", consensusText(records, "CONCLUSION", "MAIN_CONCLUSION", warnings))
                .put("failureCategory", consensusText(records, "CONCLUSION", "FAILURE_CATEGORY", warnings));
        model.set("conclusion", conclusion);
        var dynamic = json.createObjectNode();
        records.forEach(record -> fields(record).forEach(field -> {
            if ("OTHER".equals(domain(field.getValue()))) {
                dynamic.set(record.recordKey() + ":" + field.getKey(), raw(field.getValue()));
            }
        }));
        model.set("dynamicValues", dynamic);
        return model;
    }

    private ArrayNode sourceGroups(SourceImport source, List<SourceRecord> records, JsonNode experimentImport) {
        var result = json.createArrayNode();
        records.stream().sorted(Comparator.comparing(SourceRecord::recordKey)).forEach(record -> {
            var sourceIdentity = identity(record);
            if (sourceIdentity.isBlank()) return;
            var sourceIdentityType = identityType(record, experimentImport);
            var sourceGroupKey = sourceGroupKey(source, record);
            var node = json.createObjectNode()
                    .put("sourceGroupKey", sourceGroupKey)
                    .put("sampleKey", sourceGroupKey)
                    .putNull("logicalSampleKey")
                    .put("sourceIdentity", sourceIdentity)
                    .put("sourceIdentityType", sourceIdentityType)
                    .put("sourceContextKey", sourceContextKey(source, record));
            node.set("sourceRecordKeys", json.valueToTree(List.of(record.recordKey())));
            node.set("sourceSheets", json.valueToTree(List.of(record.sheetName())));
            result.add(node);
        });
        return result;
    }

    private ArrayNode sourceContexts(SourceImport source, List<SourceRecord> records, JsonNode experimentImport) {
        var grouped = new TreeMap<String, List<SourceRecord>>();
        records.forEach(record -> grouped.computeIfAbsent(sheetBoundary(record), ignored -> new ArrayList<>()).add(record));
        var result = json.createArrayNode();
        grouped.values().forEach(sheetRecords -> {
            sheetRecords.sort(Comparator.comparing(SourceRecord::recordKey));
            var first = sheetRecords.getFirst();
            var node = json.createObjectNode()
                    .put("sourceContextKey", sourceContextKey(source, first))
                    .put("sheetId", first.sheetId())
                    .put("sheetName", first.sheetName());
            node.set("sourceRecordKeys", json.valueToTree(sheetRecords.stream().map(SourceRecord::recordKey).toList()));
            var sharedRecords = sheetRecords.stream().filter(record -> identity(record).isBlank()).toList();
            node.set("sharedContextRecordKeys", json.valueToTree(sharedRecords.stream().map(SourceRecord::recordKey).toList()));
            var facts = json.createArrayNode();
            for (var record : sharedRecords) {
                for (var entry : fields(record)) {
                    var wrapper = entry.getValue();
                    if ("SOURCE_IDENTITY".equals(field(wrapper))) continue;
                    var fact = json.createObjectNode()
                            .put("domain", domain(wrapper))
                            .put("field", field(wrapper))
                            .put("itemLabel", semanticLabel(wrapper))
                            .put("labelPath", wrapper.path("labelPath").asText(""))
                            .put("bindingId", wrapper.path("bindingId").asText(""))
                            .put("valuePath", wrapper.path("valuePath").asText(""))
                            .put("unit", wrapper.path("normalizedUnit").asText(""))
                            .put("sourceRecordKey", record.recordKey());
                    fact.set("labelPathSegments", labelPathSegments(wrapper));
                    fact.set("value", missing(value(wrapper)) ? json.nullNode() : value(wrapper).deepCopy());
                    fact.set("rawValue", raw(wrapper).deepCopy());
                    fact.set("sourceRefs", json.createArrayNode().add(sourceRef(source, record, wrapper,
                            "", "", "", sourceContextKey(source, record))));
                    facts.add(fact);
                }
            }
            node.set("facts", facts);
            result.add(node);
        });
        return result;
    }

    private ArrayNode items(SourceImport source, List<SourceRecord> records, String wantedDomain,
                            JsonNode experimentImport) {
        var result = json.createArrayNode();
        for (var record : records) {
            var sourceIdentity = identity(record);
            var sourceIdentityType = sourceIdentity.isBlank() ? "" : identityType(record, experimentImport);
            var sourceGroupKey = sourceIdentity.isBlank() ? "" : sourceGroupKey(source, record);
            var sampleKey = sourceGroupKey;
            var sourceContextKey = sourceContextKey(source, record);
            var grouped = new LinkedHashMap<String, List<JsonNode>>();
            fields(record).forEach(entry -> {
                var wrapper = entry.getValue();
                if (wantedDomain.equals(domain(wrapper))) {
                    var key = wrapper.path("itemSourceKey").asText(wrapper.path("bindingId").asText(entry.getKey()));
                    grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(wrapper);
                }
            });
            for (var group : grouped.entrySet()) {
                var item = json.createObjectNode();
                var seed = source.id() + ":" + record.recordKey() + ":" + wantedDomain + ":" + group.getKey();
                item.put("itemId", UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString());
                item.put("itemSourceKey", group.getKey());
                item.put("sourceGroupKey", sourceGroupKey);
                item.put("sampleKey", sampleKey);
                item.putNull("logicalSampleKey");
                item.put("sourceIdentity", sourceIdentity);
                item.put("sourceIdentityType", sourceIdentityType);
                item.put("sourceRecordKey", record.recordKey());
                item.put("sourceContextKey", sourceContextKey);
                var refs = json.createArrayNode();
                for (var wrapper : group.getValue()) {
                    var field = field(wrapper);
                    var value = value(wrapper);
                    var raw = raw(wrapper);
                    var label = semanticLabel(wrapper);
                    item.put("labelPath", wrapper.path("labelPath").asText(""));
                    item.set("labelPathSegments", labelPathSegments(wrapper));
                    if ("FORMULA".equals(wantedDomain)) {
                        item.put("materialName", label);
                        item.putNull("materialId");
                        item.put("materialCode", "");
                        if ("RATIO".equals(field)) {
                            putNumberOrText(item, "ratio", value);
                            item.set("rawValue", raw);
                            item.put("rawUnit", wrapper.path("rawUnit").asText(""));
                        }
                    } else if ("PROCESS".equals(wantedDomain)) {
                        item.put("operation", label);
                        item.put("field", field);
                        item.set("value", missing(value) ? json.nullNode() : value);
                        item.put("unit", wrapper.path("normalizedUnit").asText(""));
                    } else {
                        item.put("testItem", label);
                        item.set("value", missing(value) ? json.nullNode() : value);
                        item.set("rawValue", raw);
                        item.put("unit", wrapper.path("normalizedUnit").asText(""));
                        item.put("testMethod", "");
                        item.put("testCondition", label);
                        item.put("substrate", substrate(label));
                    }
                    refs.add(sourceRef(source, record, wrapper, sourceGroupKey, sampleKey,
                            sourceIdentityType, sourceContextKey));
                }
                item.set("sourceRefs", refs);
                result.add(item);
            }
        }
        return result;
    }

    private ObjectNode sourceRef(SourceImport source, SourceRecord record, JsonNode wrapper,
                                 String sourceGroupKey, String sampleKey, String sourceIdentityType,
                                 String sourceContextKey) {
        var ref = json.createObjectNode().put("sourceType", "DATA_IMPORT")
                .put("sourceFileId", source.sourceFileId().toString()).put("sourceFileHash", source.sourceSha256())
                .put("templateVersionId", source.templateVersionId().toString()).put("importJobId", source.id().toString())
                .put("dataRecordId", record.id().toString()).put("recordKey", record.recordKey())
                .put("sourceIdentity", identity(record)).put("sourceIdentityType", sourceIdentityType)
                .put("sourceGroupKey", sourceGroupKey)
                .put("sampleKey", sampleKey)
                .putNull("logicalSampleKey")
                .put("sourceContextKey", sourceContextKey)
                .put("sheetId", record.sheetId()).put("sheetName", record.sheetName())
                .put("bindingId", wrapper.path("bindingId").asText(""))
                .put("valuePath", wrapper.path("valuePath").asText(""))
                .put("labelPath", wrapper.path("labelPath").asText(""))
                .put("itemSourceKey", wrapper.path("itemSourceKey").asText(""));
        ref.set("labelPathSegments", labelPathSegments(wrapper));
        record.anchors().stream().filter(anchor -> Objects.equals(anchor.bindingId(), wrapper.path("bindingId").asText(null))
                && Objects.equals(anchor.valuePath(), wrapper.path("valuePath").asText(null))).findFirst().ifPresent(anchor -> {
            ref.put("cell", anchor.address());
            ref.put("row", anchor.rowNumber());
            ref.put("column", anchor.columnNumber());
        });
        if (wrapper.path("labelSource").isObject()) ref.set("labelSource", wrapper.path("labelSource").deepCopy());
        for (var key : List.of("cellValueType", "rawNumericValue", "displayValue", "numberFormat",
                "fractionRepresentation")) {
            if (wrapper.has(key)) ref.set(key, wrapper.path(key).deepCopy());
        }
        return ref;
    }

    private String identity(SourceRecord record) {
        return identityWrapper(record).map(wrapper -> value(wrapper).asText("").strip()).orElse("");
    }

    private Optional<JsonNode> identityWrapper(SourceRecord record) {
        return fields(record).stream().map(Map.Entry::getValue)
                .filter(wrapper -> "BASIC".equals(domain(wrapper)) && "SOURCE_IDENTITY".equals(field(wrapper)))
                .filter(wrapper -> !missing(value(wrapper))).findFirst();
    }

    private String identityType(SourceRecord record, JsonNode experimentImport) {
        var bindingId = identityWrapper(record).map(wrapper -> wrapper.path("bindingId").asText("")).orElse("");
        if (experimentImport != null && experimentImport.path("identities").isArray()) {
            for (var rule : experimentImport.path("identities")) {
                if (bindingId.equals(rule.path("bindingId").asText(""))) {
                    var type = rule.path("identityType").asText("").strip();
                    if (!type.isBlank()) return type;
                }
            }
        }
        return "SOURCE_IDENTITY";
    }

    private String sourceGroupKey(SourceImport source, SourceRecord record) {
        return "SOURCE_GROUP:" + hashText(source.id() + ":" + record.recordKey()).substring(0, 24);
    }

    private String sourceContextKey(SourceImport source, SourceRecord record) {
        return "SOURCE_CONTEXT:" + hashText(source.id() + ":" + sheetBoundary(record)).substring(0, 24);
    }

    private String sheetBoundary(SourceRecord record) {
        if (record.sheetId() != null && !record.sheetId().isBlank()) return record.sheetId();
        if (record.sheetName() != null && !record.sheetName().isBlank()) return record.sheetName();
        return record.recordKey();
    }

    private String consensusText(List<SourceRecord> records, String domain, String field, ArrayNode warnings) {
        var values = records.stream().flatMap(record -> fields(record).stream()).map(Map.Entry::getValue)
                .filter(wrapper -> domain.equals(domain(wrapper)) && field.equals(field(wrapper)))
                .map(this::value).map(value -> value.asText("").strip()).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (values.size() == 1) return values.getFirst();
        if (values.size() > 1) {
            warnings.add(issue("SOURCE_CONTEXT_CONFLICT",
                    "不同来源区域中的" + domain + "." + field + "不一致，已保留分Sheet来源事实，实验顶层字段暂不自动填写"));
        }
        return "";
    }

    private int distinctSourceIdentityCount(JsonNode sourceGroups) {
        var identities = new HashSet<String>();
        sourceGroups.forEach(group -> identities.add(group.path("sourceIdentityType").asText("") + ":"
                + group.path("sourceIdentity").asText("")));
        return identities.size();
    }

    private List<Map.Entry<String, JsonNode>> fields(SourceRecord record) {
        var out = new ArrayList<Map.Entry<String, JsonNode>>();
        if (record.effectiveData() != null && record.effectiveData().isObject()) {
            record.effectiveData().fields().forEachRemaining(out::add);
        }
        return out;
    }

    private String domain(JsonNode wrapper) {
        return wrapper.path("experimentField").path("domain").asText("");
    }

    private String field(JsonNode wrapper) {
        return wrapper.path("experimentField").path("field").asText("");
    }

    private JsonNode value(JsonNode wrapper) {
        return wrapper.has("normalizedValue") ? wrapper.get("normalizedValue") : json.nullNode();
    }

    private JsonNode raw(JsonNode wrapper) {
        return wrapper.has("rawValue") ? wrapper.get("rawValue") : json.nullNode();
    }

    private boolean missing(JsonNode value) {
        return value == null || value.isNull() || value.asText("").isBlank() || "/".equals(value.asText().strip());
    }

    private void putNumberOrText(ObjectNode object, String key, JsonNode value) {
        if (missing(value)) {
            object.putNull(key);
            return;
        }
        try {
            object.put(key, new BigDecimal(value.asText().replace("%", "").strip()));
        } catch (Exception ignored) {
            object.set(key, value.deepCopy());
        }
    }

    private String substrate(String label) {
        var upper = label.toUpperCase(Locale.ROOT).replace(" ", "");
        if (upper.contains("PMMA/PC") || upper.contains("PMMA-PC") || upper.contains("PMMAPC复合")) {
            return upper.contains("0.64") ? "PMMA_PC_COMPOSITE_0_64MM" : "PMMA_PC_COMPOSITE";
        }
        if (upper.contains("PC")) return upper.contains("170") ? "PC_FILM_170UM" : "PC_FILM";
        if (upper.contains("PET")) {
            return upper.contains("100") || upper.contains("光学") ? "PET_100UM_OPTICAL" : "PET_FILM";
        }
        return "";
    }

    private String semanticLabel(JsonNode wrapper) {
        var segments = labelPathSegments(wrapper);
        if (!segments.isEmpty()) {
            var labels = new ArrayList<String>();
            segments.forEach(segment -> labels.add(segment.asText("")));
            return String.join(" > ", labels);
        }
        return wrapper.path("itemLabel").asText(wrapper.path("labelPath").asText(""));
    }

    private ArrayNode labelPathSegments(JsonNode wrapper) {
        if (wrapper.path("labelPathSegments").isArray()) {
            return (ArrayNode) wrapper.path("labelPathSegments").deepCopy();
        }
        var result = json.createArrayNode();
        var path = wrapper.path("labelPath").asText("").strip();
        if (!path.isBlank()) {
            for (var segment : path.split("\\s*>\\s*")) {
                if (!segment.isBlank()) result.add(segment.strip());
            }
        }
        return result;
    }

    private ObjectNode issue(String code, String message) {
        return json.createObjectNode().put("code", code).put("message", message);
    }

    private String assemblyKey(String kind, String value) {
        return kind + ":" + hashText(value).substring(0, 24);
    }

    private String sourceContentHash(SourceImport source, List<SourceRecord> records) {
        var root = json.createObjectNode()
                .put("sourceFileHash", source.sourceSha256())
                .put("templateVersionId", source.templateVersionId().toString())
                .put("contractHash", source.contractHash());
        var facts = root.putArray("records");
        records.stream().sorted(Comparator.comparing(SourceRecord::recordKey)).forEach(record -> {
            var fact = json.createObjectNode().put("recordKey", record.recordKey());
            fact.set("effectiveData", record.effectiveData());
            facts.add(fact);
        });
        return hash(root);
    }

    private String hash(JsonNode node) {
        try {
            return hashBytes(json.writeValueAsBytes(node));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String hashText(String text) {
        return hashBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private String hashBytes(byte[] bytes) {
        try {
            var raw = MessageDigest.getInstance("SHA-256").digest(bytes);
            var result = new StringBuilder();
            for (byte value : raw) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public record AssemblyPlan(UUID importJobId, List<Candidate> candidates, int readyCount, int reviewCount,
                               int blockedCount, int sourceRecordCount, int sampleGroupCount,
                               int sourceGroupCount, int sourceContextCount, int sourceIdentityCount,
                               int logicalSampleCount) {}

    public record Candidate(String assemblyKey, String parentAssemblyKey, String sourceIdentity,
                            String sourceIdentityType, String status, List<String> sourceRecordKeys,
                            List<String> sharedContextRecordKeys, JsonNode conflicts, JsonNode warnings,
                            JsonNode editModel, String planHash, String contentHash) {}
}
