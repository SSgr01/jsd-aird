package com.jsd.aird.ai.rnd.eligibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.jsd.aird.ai.rnd.eligibility.EligibilityContracts.*;
import static com.jsd.aird.ai.rnd.eligibility.EligibilityRepository.*;

/** Deterministic, configuration-driven qualification rules. No scientific defaults live here. */
public class EligibilityRuleEvaluator {
    private final ObjectMapper json;

    public EligibilityRuleEvaluator(ObjectMapper json) { this.json = json; }

    public Result evaluate(Configuration configuration, SampleRow sample) {
        var reasons = new ArrayList<QualificationReason>();
        var warnings = new ArrayList<QualificationReason>();
        var evidence = json.createObjectNode().put("sampleRevisionId", sample.revisionId().toString());
        if (!"ACTIVE".equals(sample.sampleStatus()) && !"TAKEN_OVER".equals(sample.sampleStatus()))
            exclude(reasons, "SOURCE_INVALIDATED", "当前逻辑样本不可供训练", null, sample.sourceStatus());
        if (!"CURRENT".equals(sample.sourceStatus()) && !"TAKEN_OVER".equals(sample.sourceStatus()))
            exclude(reasons, "SOURCE_NOT_CONFIRMED", "当前来源版本不是有效正式事实", null, sample.sourceStatus());
        var mapping = configuration.mappings().get(sample.sourceType());
        if (mapping == null) exclude(reasons, "SOURCE_MAPPING_REQUIRED", "当前来源尚未配置已发布语义映射", null, sample.sourceType());
        if (sample.identityConflict()) review(reasons, "SAMPLE_IDENTITY_CONFLICT", "样本身份存在待审查冲突", null, sample.sampleId());

        if (mapping != null) {
            var matches = targetValues(sample, mapping.mapping());
            if (matches.isEmpty()) exclude(reasons, "TARGET_SEMANTIC_MISMATCH", "未找到唯一匹配的Y观测", null, mapping.mapping());
            else if (matches.size() > 1) {
                var repeated = resolveTechnicalReplicates(configuration, matches);
                if (repeated == null) {
                    exclude(reasons, "TARGET_AMBIGUOUS", "找到多个无法唯一确定的Y观测", null,
                            json.valueToTree(matches.stream().map(TargetValue::value).toList()));
                } else {
                    evidence.set("targetValue", repeated.value());
                    evidence.set("targetValues", repeated.values());
                    evidence.put("replicateHandling", repeated.handling());
                }
            }
            else {
                var target = matches.getFirst();
                if (!validTargetValue(configuration, target)) {
                    var code = target.reasonCode() == null ? "INVALID_Y_VALUE" : target.reasonCode();
                    var message = target.reasonMessage() == null ? "Y观测值或观测类型不符合目标定义" : target.reasonMessage();
                    exclude(reasons, code, message, null, target.metadata());
                }
                else evidence.set("targetValue", target.value());
            }
        }

        for (var field : configuration.fields()) {
            if ("POST_EXPERIMENT".equals(field.availabilityStage())) {
                exclude(reasons, "POST_EXPERIMENT_FIELD_NOT_ALLOWED", "实验后字段不能进入实验前输入方案", field.code(), field.id());
                continue;
            }
            var value = fieldValue(sample, field);
            if (isBlankValue(value)) {
                if (field.required()) exclude(reasons, "MISSING_REQUIRED_X", "缺少模型要求的输入字段", field.code(), field.id());
                continue;
            }
            if ("FORMULA".equals(field.code()) || "COMPOSITION".equals(field.valueType())) {
                checkFormula(sample.composition(), field, reasons, warnings);
            }
            if (field.unit() != null && !field.unit().isBlank() && value.isTextual()
                    && value.asText().matches(".*[A-Za-z%°].*")) {
                var rawUnit = value.asText().replaceAll("[-+0-9.\\s]", "");
                if (!rawUnit.isBlank() && !rawUnit.equalsIgnoreCase(field.unit().replace(" ", "")))
                    exclude(reasons, "UNIT_UNKNOWN", "输入字段单位无法确认", field.code(), rawUnit);
            }
        }
        checkMaterials(configuration, sample, reasons);
        checkAnomaly(configuration, sample, reasons);
        var state = reasons.stream().anyMatch(r -> hardExclusion(r.code())) ? State.EXCLUDED
                : reasons.isEmpty() ? State.TRAINABLE : State.REVIEW_REQUIRED;
        if (state == State.REVIEW_REQUIRED && reasons.stream().anyMatch(r -> hardExclusion(r.code()))) state = State.EXCLUDED;
        var primary = reasons.stream().map(QualificationReason::code)
                .min(java.util.Comparator.comparingInt(this::reasonPriority)).orElse(null);
        return new Result(state, primary, List.copyOf(reasons), List.copyOf(warnings), evidence);
    }

    private List<TargetValue> targetValues(SampleRow sample, JsonNode mapping) {
        var aliases = new ArrayList<String>();
        aliases.add(mapping.path("targetFieldCode").asText(""));
        mapping.path("sourceAliases").forEach(n -> aliases.add(n.asText()));
        mapping.path("includeAny").forEach(n -> aliases.add(n.asText()));
        aliases.removeIf(String::isBlank);
        var found = new ArrayList<TargetValue>();
        collectObservations(sample.observations(), aliases, mapping, found, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        collectObservations(sample.facts(), aliases, mapping, found, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        return found;
    }

    private void collectObservations(JsonNode node, List<String> aliases, JsonNode mapping, List<TargetValue> found, Set<JsonNode> visited) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (!visited.add(node)) return;
        if (node.isArray()) { for (var item : node) collectObservations(item, aliases, mapping, found, visited); return; }
        if (!node.isObject()) return;
        for (var entry : iterable(node.fields())) {
            var key = entry.getKey();
            var semanticKey = key.startsWith("TABLE.COLUMN.") ? key.substring("TABLE.COLUMN.".length()) : key;
            if (aliases.stream().anyMatch(a -> key.equalsIgnoreCase(a)
                    || key.toLowerCase(Locale.ROOT).contains(a.toLowerCase(Locale.ROOT))
                    || semanticKey.equalsIgnoreCase(a))) {
                var value = entry.getValue();
                var candidate = extractTargetValue(value);
                if (candidate != null) {
                    // A normalized fact can be reached once through its
                    // TABLE.COLUMN key and once through its labelPath.  Mark
                    // the value node before adding it so the same physical
                    // observation is not mistaken for a technical replicate.
                    if (visited.add(value)) found.add(candidate);
                }
                else collectObservations(value, aliases, mapping, found, visited);
            }
        }
        var label = text(node,"name","label","labelPath","fieldCode","targetCode","testMethod","item","sourceFieldCode");
        if (!label.isBlank() && matches(label, aliases, mapping) && (node.has("value") || node.has("rawValue") || node.has("result")))
            found.add(extractTargetValue(node));
        for (var entry : iterable(node.fields())) collectObservations(entry.getValue(), aliases, mapping, found, visited);
    }

    private TargetValue extractTargetValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isValueNode()) return new TargetValue(node, node, null, null);
        if (!node.isObject()) return null;
        JsonNode value = node.has("value") ? node.get("value") : node.has("normalizedValue") ? node.get("normalizedValue") : node.has("rawNumericValue") ? node.get("rawNumericValue") : node.has("rawValue") ? node.get("rawValue") : node.has("result") ? node.get("result") : null;
        if (value == null) return null;
        // Customer workbooks often keep a numeric result with a human suffix
        // (for example, "1500次无明显划痕").  Preserve the original metadata,
        // but expose the leading measured number to the typed Y validator.
        if (value.isTextual()) {
            var m = java.util.regex.Pattern.compile("^\\s*([-+]?\\d+(?:\\.\\d+)?)").matcher(value.asText());
            if (m.find()) {
                try { value = json.getNodeFactory().numberNode(new BigDecimal(m.group(1))); }
                catch (NumberFormatException ignored) { }
            }
        }
        var observationType = text(node, "observationType", "observationKind", "measurementType", "boundType", "comparison").toUpperCase(Locale.ROOT);
        if (Set.of("LOWER_BOUND", "UPPER_BOUND", "INTERVAL", "CENSORED", "TEXT").contains(observationType))
            return new TargetValue(value, node, "UNSUPPORTED_OBSERVATION_TYPE", "观测为文本、下界或区间，不能作为普通连续值");
        if (node.path("valueType").asText("").equalsIgnoreCase("TEXT"))
            return new TargetValue(value, node, "UNSUPPORTED_OBSERVATION_TYPE", "文本观测不能作为普通连续值");
        return new TargetValue(value, node, null, null);
    }

    private boolean matches(String label,List<String> aliases,JsonNode mapping){
        var normalized=label.toLowerCase(Locale.ROOT);for(var alias:aliases)if(normalized.contains(alias.toLowerCase(Locale.ROOT)))return true;
        for(var required:mapping.path("includeAll"))if(!normalized.contains(required.asText().toLowerCase(Locale.ROOT)))return false;
        for(var excluded:mapping.path("exclude"))if(normalized.contains(excluded.asText().toLowerCase(Locale.ROOT)))return false;
        return aliases.isEmpty();
    }

    private boolean validTargetValue(Configuration c, TargetValue target) {
        if (target.reasonCode() != null) return false;
        var value = target.value();
        return switch (c.valueType()) {
            case "CONTINUOUS" -> value.isNumber() || value.asText().matches("[-+]?\\d+(\\.\\d+)?%?");
            case "ORDINAL","BINARY","CATEGORICAL" -> c.classes().isEmpty() || c.classes().stream().anyMatch(item -> item.equalsIgnoreCase(value.asText()));
            default -> false;
        };
    }

    private ResolvedReplicates resolveTechnicalReplicates(Configuration c, List<TargetValue> matches) {
        if (c.policy() == null) return null;
        var handling = c.policy().training().path("replicateHandling").asText("");
        if (handling.isBlank()) return null;
        // Repeated values are only treated as technical measurements when the
        // source preserved explicit observation/replicate identity.
        if (matches.stream().anyMatch(item -> !hasReplicateIdentity(item.metadata()))) return null;
        if (matches.stream().anyMatch(item -> !validTargetValue(c, item))) return null;
        var values = json.createArrayNode();
        matches.forEach(item -> {
            var entry=json.createObjectNode();entry.set("value",item.value());entry.set("metadata",item.metadata());values.add(entry);
        });
        if ("KEEP_GROUPED".equals(handling)) return new ResolvedReplicates(matches.getFirst().value(), values, handling);
        if ("CONTINUOUS".equals(c.valueType())) {
            var numbers = matches.stream().map(item -> item.value().asDouble()).sorted().toList();
            if ("MEAN".equals(handling)) return new ResolvedReplicates(json.getNodeFactory().numberNode(numbers.stream().mapToDouble(Double::doubleValue).average().orElseThrow()), values, handling);
            if ("MEDIAN".equals(handling)) {
                var middle = numbers.size()/2;
                var median = numbers.size()%2==1 ? numbers.get(middle) : (numbers.get(middle-1)+numbers.get(middle))/2d;
                return new ResolvedReplicates(json.getNodeFactory().numberNode(median), values, handling);
            }
        }
        if ("ORDINAL".equals(c.valueType()) && "MEDIAN_GRADE".equals(handling)) {
            var sorted = matches.stream().map(item -> item.value().asText()).sorted(java.util.Comparator.comparingInt(c.classes()::indexOf)).toList();
            return new ResolvedReplicates(json.getNodeFactory().textNode(sorted.get((sorted.size()-1)/2)), values, handling);
        }
        if (("BINARY".equals(c.valueType()) || "CATEGORICAL".equals(c.valueType()))) {
            var counts = new java.util.LinkedHashMap<String,Long>();
            matches.forEach(item -> counts.merge(item.value().asText(),1L,Long::sum));
            if ("CONSENSUS_ONLY".equals(handling) && counts.size()!=1) return null;
            if ("MAJORITY".equals(handling) || "CONSENSUS_ONLY".equals(handling)) {
                var winner = counts.entrySet().stream().sorted(java.util.Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(java.util.Map.Entry.comparingByKey())).findFirst().orElseThrow().getKey();
                return new ResolvedReplicates(json.getNodeFactory().textNode(winner), values, handling);
            }
        }
        return null;
    }

    private boolean hasReplicateIdentity(JsonNode metadata) {
        return metadata != null && metadata.isObject() && (metadata.hasNonNull("observationId")
                || metadata.hasNonNull("replicateGroupKey") || metadata.hasNonNull("measurementIndex"));
    }

    private JsonNode fieldValue(SampleRow sample, FieldRow field) {
        if ("FORMULA".equals(field.code()) || "COMPOSITION".equals(field.valueType())) return sample.composition();
        var paths = field.definition().path("sourcePaths");
        for (var path : paths.isArray() ? paths : json.createArrayNode().add(field.code())) {
            var value=pointer(sample,path.asText()); if(value!=null&&!value.isMissingNode()&&!value.isNull())return value;
        }
        // Legacy published X definitions predate explicit sourcePaths. Resolve
        // their standard semantic aliases across the immutable fact sections,
        // while still preferring an exact field-code match.
        for (var node : List.of(sample.facts(), sample.conditions(), sample.process(), sample.observations())) {
            var exact = findKey(node, field.code());
            if (exact != null && !exact.isNull()) return exact;
        }
        var aliases = switch (field.code()) {
            case "TEST_TEMPERATURE" -> List.of("temperature", "cureTemperature", "testTemperature");
            case "TEST_HUMIDITY" -> List.of("humidity", "ambientHumidity");
            default -> List.<String>of();
        };
        for (var alias : aliases) {
            for (var node : List.of(sample.conditions(), sample.process(), sample.facts())) {
                var value = findKey(node, alias);
                if (value != null && !value.isNull()) return value;
            }
        }
        // Data-center/template facts preserve the original workbook identity
        // as TABLE.COLUMN.* plus labelPath/rawValue.  Resolve those labels to
        // the published X definition before declaring the field missing.
        for (var node : List.of(sample.facts(), sample.conditions(), sample.process(), sample.observations())) {
            var value = findLabeledValue(node, field.code(), field.name());
            if (value != null && !value.isNull()) return value;
        }
        return null;
    }

    private JsonNode findLabeledValue(JsonNode node, String code, String name) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) { for (var item : node) { var v = findLabeledValue(item, code, name); if (v != null) return v; } return null; }
        if (!node.isObject()) return null;
        var label = text(node, "labelPath", "label", "name", "fieldCode");
        if (sameSemantic(label, code, name) && (node.has("rawValue") || node.has("normalizedValue") || node.has("rawNumericValue") || node.has("displayValue") || node.has("value"))) {
            if (node.hasNonNull("normalizedValue") && !node.path("normalizedValue").asText().isBlank()) return node.get("normalizedValue");
            if (node.hasNonNull("rawNumericValue")) return node.get("rawNumericValue");
            if (node.hasNonNull("rawValue")) return node.get("rawValue");
            if (node.hasNonNull("value")) return node.get("value");
            return node.get("displayValue");
        }
        for (var entry : iterable(node.fields())) {
            var key = entry.getKey();
            var semanticKey = key.startsWith("TABLE.COLUMN.") ? key.substring("TABLE.COLUMN.".length()) : key;
            if (sameSemantic(semanticKey, code, name)) {
                var v = extractFieldValue(entry.getValue());
                if (v != null) return v;
            }
            var nested = findLabeledValue(entry.getValue(), code, name);
            if (nested != null) return nested;
        }
        return null;
    }

    private JsonNode extractFieldValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) return node;
        for (var key : List.of("normalizedValue", "rawNumericValue", "rawValue", "value", "displayValue"))
            if (node.hasNonNull(key) && !node.path(key).asText().isBlank()) return node.get(key);
        return null;
    }

    private boolean sameSemantic(String value, String code, String name) {
        if (value == null || value.isBlank()) return false;
        var v = value.replace("TABLE.COLUMN.", "").replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT);
        var c = code == null ? "" : code.replace("_", "").toLowerCase(Locale.ROOT);
        var n = name == null ? "" : name.replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT);
        return (!c.isBlank() && v.equals(c)) || (!n.isBlank() && (v.equals(n) || v.contains(n)));
    }

    private boolean isBlankValue(JsonNode value) {
        return value == null || value.isNull()
                || (value.isValueNode() && value.asText().isBlank())
                || (value.isObject() && value.isEmpty())
                || (value.isArray() && value.isEmpty());
    }

    private JsonNode pointer(SampleRow sample,String path){
        if(path==null||path.isBlank())return null;for(var node:List.of(sample.facts(),sample.conditions(),sample.process(),sample.composition(),sample.observations())){var value=node.at(path.startsWith("/")?path:"/"+path);if(!value.isMissingNode()&&!value.isNull())return value;}return null;
    }

    private JsonNode findKey(JsonNode node,String key){
        if(node==null||node.isNull())return null;if(node.isArray()){for(var x:node){var v=findKey(x,key);if(v!=null)return v;}return null;}if(!node.isObject())return null;
        var direct=node.get(key);if(direct!=null&&!direct.isNull())return direct;
        for(var e:iterable(node.fields())){var v=findKey(e.getValue(),key);if(v!=null)return v;}return null;
    }

    private void checkFormula(JsonNode composition,FieldRow field,List<QualificationReason> reasons,List<QualificationReason> warnings){
        var items=composition==null?null:composition.path("items");if(items==null||!items.isArray()||items.isEmpty()){exclude(reasons,"FORMULA_BASIS_UNRESOLVED","配方组成或比例基准无法确定",field.code(),composition);return;}
        BigDecimal total=BigDecimal.ZERO;boolean knownTotal=true;
        for(var item:items){if(item.path("amountKnown").isBoolean()&&!item.path("amountKnown").asBoolean())knownTotal=false;var ratio=item.path("ratio");if(ratio.isNumber())total=total.add(ratio.decimalValue());else knownTotal=false;}
        if(!knownTotal){exclude(reasons,"FORMULA_BASIS_UNRESOLVED","配方中存在缺失用量，不能补填为0",field.code(),items);return;}
        if(total.subtract(BigDecimal.valueOf(100)).abs().compareTo(new BigDecimal("0.0001"))>0)
            warnings.add(reason("FORMULA_TOTAL_WARNING","保留原始配方合计"+total.stripTrailingZeros().toPlainString()+"%，不会自动归一化",field.code(),total));
    }

    private void checkMaterials(Configuration c,SampleRow sample,List<QualificationReason> reasons){
        var items=sample.composition()==null?null:sample.composition().path("items");if(items==null||!items.isArray())return;
        var dictionaryIds=new HashSet<String>();if(c.dictionary()!=null&&c.dictionary().isArray())for(var d:c.dictionary()) {if(d.hasNonNull("materialId"))dictionaryIds.add(d.path("materialId").asText());if(d.hasNonNull("token"))dictionaryIds.add(d.path("token").asText().toLowerCase(Locale.ROOT));}
        for(var item:items){var material=item.path("materialId").asText(item.path("materialCode").asText(item.path("name").asText("")));if(material.isBlank())review(reasons,"UNKNOWN_MATERIAL","无法识别配方材料身份","FORMULA",item);else if(!dictionaryIds.isEmpty()&&!dictionaryIds.contains(material)&&!dictionaryIds.contains(material.toLowerCase(Locale.ROOT)))exclude(reasons,"MATERIAL_NOT_IN_MODEL","材料不在当前冻结材料字典中","FORMULA",material);}
    }

    private void checkAnomaly(Configuration c,SampleRow sample,List<QualificationReason> reasons){
        var rules=c.policy()==null?null:c.policy().qualification().path("anomalyRules");if(rules==null||!rules.isArray())return;
        for(var rule:rules){var field=rule.path("fieldCode").asText();var value=findKey(sample.facts(),field);if(value==null||!value.isNumber())continue;var n=value.decimalValue();if(rule.has("minimum")&&n.compareTo(rule.path("minimum").decimalValue())<0||rule.has("maximum")&&n.compareTo(rule.path("maximum").decimalValue())>0)review(reasons,"ANOMALY_REVIEW_REQUIRED","样本值命中已发布异常规则",field,rule);}
    }

    private boolean hardExclusion(String code){return Set.of("SOURCE_INVALIDATED","SOURCE_NOT_CONFIRMED","SOURCE_MAPPING_REQUIRED","TARGET_SEMANTIC_MISMATCH","TARGET_AMBIGUOUS","INVALID_Y_VALUE","UNSUPPORTED_OBSERVATION_TYPE","MISSING_REQUIRED_X","FORMULA_BASIS_UNRESOLVED","POST_EXPERIMENT_FIELD_NOT_ALLOWED","UNIT_UNKNOWN","MATERIAL_NOT_IN_MODEL").contains(code);}
    private int reasonPriority(String code) {
        return Map.ofEntries(
                Map.entry("SOURCE_INVALIDATED", 10), Map.entry("SOURCE_NOT_CONFIRMED", 10),
                Map.entry("SOURCE_MAPPING_REQUIRED", 20), Map.entry("SAMPLE_IDENTITY_CONFLICT", 30),
                Map.entry("TARGET_SEMANTIC_MISMATCH", 40), Map.entry("TARGET_AMBIGUOUS", 40),
                Map.entry("INVALID_Y_VALUE", 50), Map.entry("UNSUPPORTED_OBSERVATION_TYPE", 50),
                Map.entry("MISSING_REQUIRED_X", 60), Map.entry("FORMULA_BASIS_UNRESOLVED", 60),
                Map.entry("POST_EXPERIMENT_FIELD_NOT_ALLOWED", 60), Map.entry("UNIT_UNKNOWN", 60),
                Map.entry("UNKNOWN_MATERIAL", 70), Map.entry("MATERIAL_NOT_IN_MODEL", 70),
                Map.entry("ANOMALY_REVIEW_REQUIRED", 80)).getOrDefault(code, 1000);
    }
    private void exclude(List<QualificationReason> reasons,String code,String message,String field,Object evidence){reasons.add(reason(code,message,field,evidence));}
    private void review(List<QualificationReason> reasons,String code,String message,String field,Object evidence){reasons.add(reason(code,message,field,evidence));}
    private QualificationReason reason(String code,String message,String field,Object evidence){return new QualificationReason(code,message,field,json.valueToTree(evidence));}
    private String text(JsonNode node,String... keys){for(var key:keys)if(node.hasNonNull(key))return node.path(key).asText();return "";}
    private <T> Iterable<T> iterable(Iterator<T> iterator){return () -> iterator;}

    private record TargetValue(JsonNode value, JsonNode metadata, String reasonCode, String reasonMessage) { }
    private record ResolvedReplicates(JsonNode value, JsonNode values, String handling) { }

    public record Result(State state,String primaryReason,List<QualificationReason> reasons,List<QualificationReason> warnings,JsonNode evidence) { }
}
