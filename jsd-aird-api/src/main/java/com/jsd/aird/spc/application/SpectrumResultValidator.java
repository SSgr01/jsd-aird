package com.jsd.aird.spc.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.spc.application.port.SpectrumPromptPort.SpectrumAnalysisPromptContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SpectrumResultValidator {

    private static final Set<String> EVIDENCE_TYPES = Set.of(
            "OBSERVED_DATA", "DOCUMENT_METADATA", "DOMAIN_KNOWLEDGE", "COMPANY_SOP",
            "REFERENCE_SPECTRUM", "EXPERIMENT_RECORD");
    private static final Map<String, String> CANONICAL_LEVELS = Map.ofEntries(
            Map.entry("OBSERVATION", "L1"),
            Map.entry("REPORTED_METADATA", "L1"),
            Map.entry("METHOD_ASSESSMENT", "L2"),
            Map.entry("DATA_QUALITY_ASSESSMENT", "L2"),
            Map.entry("CANDIDATE_EXPLANATION", "L3"),
            Map.entry("CAUSE_HYPOTHESIS", "L3"),
            Map.entry("CONCENTRATION_CONFIRMATION", "L4"),
            Map.entry("IDENTITY_CONFIRMATION", "L4"),
            Map.entry("PURITY_CONFIRMATION", "L4"),
            Map.entry("REFERENCE_MAPPING_CONFIRMATION", "L4"),
            Map.entry("SAMPLE_RELATION_CONFIRMATION", "L4"));

    private final ObjectMapper objectMapper;
    private final SpectrumKnowledgeRuleRegistry knowledgeRules;

    @Autowired
    public SpectrumResultValidator(ObjectMapper objectMapper, SpectrumKnowledgeRuleRegistry knowledgeRules) {
        this.objectMapper = objectMapper;
        this.knowledgeRules = knowledgeRules;
    }

    public SpectrumResultValidator(ObjectMapper objectMapper) {
        this(objectMapper, new SpectrumKnowledgeRuleRegistry());
    }

    public ValidationResult validate(ObjectNode source, SpectrumAnalysisPromptContext context) {
        var result = source == null ? objectMapper.createObjectNode() : source.deepCopy();
        result.put("schemaVersion", 3);
        result.put("analysisIntent", context.resolvedIntent());
        result.set("spectrumProtocols", objectMapper.valueToTree(context.spectrumProtocols()));
        var routing = result.putObject("routing");
        routing.put("requestedIntent", context.requestedIntent());
        routing.put("resolvedIntent", context.resolvedIntent());
        routing.put("resolution", context.routingResolution());
        routing.put("reasonCode", context.routingReasonCode());

        var warnings = new ArrayList<String>();
        var changed = new boolean[] { false };
        var claims = sanitizeClaims(result.path("claims"), context, changed);
        var byId = new LinkedHashMap<String, ObjectNode>();
        claims.forEach(item -> byId.put(item.path("claimId").asText(), item));

        var pending = new LinkedHashSet<>(byId.keySet());
        for (var pass = 0; pass < Math.max(1, claims.size() + 1) && !pending.isEmpty(); pass++) {
            var progressed = false;
            for (var claimId : List.copyOf(pending)) {
                var claim = byId.get(claimId);
                if (!dependenciesResolved(claim, byId)) continue;
                validateClaim(claim, byId, context, changed);
                pending.remove(claimId);
                progressed = true;
            }
            if (!progressed) break;
        }
        for (var claimId : pending) {
            reject(byId.get(claimId), "MISSING_DEPENDENCY_CLAIM", changed);
        }

        var validatedClaims = objectMapper.createArrayNode();
        claims.forEach(validatedClaims::add);
        result.set("claims", validatedClaims);

        var assessment = sanitizeAssessment(result.path("assessment"), byId, context, changed);
        result.set("assessment", assessment);

        var limitations = sanitizeLimitations(result.path("limitations"));
        for (var claim : claims) {
            if ("REJECTED_TO_LIMITATION".equals(claim.path("status").asText())) {
                limitations.add(limitationFor(claim, assessment));
            }
        }
        if ("USER_SELECTED".equals(context.routingResolution()) && !supportsIntent(context)) {
            changed[0] = true;
            limitations.add(intentMismatchLimitation(context));
            markAssessmentUndetermined(assessment, context.resolvedIntent());
            result.putArray("validationEvents").addObject()
                    .put("action", "ADD_LIMITATION")
                    .put("reasonCode", "INTENT_PROTOCOL_MISMATCH");
        }
        result.set("limitations", deduplicateLimitations(limitations));
        result.put("confidence", normalizeConfidence(result.path("confidence")));
        result.put("analysisStatus", changed[0] ? "PARTIAL" : "SUCCEEDED");
        if (changed[0]) warnings.add("部分结论因依据不足未列入最终结果。仍可查看已确认内容和后续建议。");
        return new ValidationResult(result, List.copyOf(new LinkedHashSet<>(warnings)));
    }

    private ObjectNode sanitizeAssessment(JsonNode source, Map<String, ObjectNode> claims,
                                          SpectrumAnalysisPromptContext context, boolean[] changed) {
        var result = source.isObject() ? (ObjectNode) source.deepCopy() : objectMapper.createObjectNode();
        result.put("type", context.resolvedIntent());
        var nominal = result.path("nominalConcentration");
        if (nominal.isObject()) {
            var sourceType = nominal.path("sourceType").asText("");
            var evidenceId = nominal.path("evidenceId").asText("");
            var valid = "DOCUMENT_METADATA".equals(sourceType)
                    && List.of(context.evidenceSourceTypes().getOrDefault(evidenceId, "").split(","))
                    .contains("DOCUMENT_METADATA");
            if (!valid) {
                result.remove("nominalConcentration");
                changed[0] = true;
            }
        }
        for (var field : List.of("supportingClaimIds", "candidateClaimIds", "mappingClaimIds",
                "methodRiskClaimIds", "anomalyClaimIds", "causeClaimIds")) {
            if (!result.path(field).isArray()) continue;
            var safe = objectMapper.createArrayNode();
            result.path(field).forEach(item -> {
                var claim = claims.get(item.asText());
                if (claim != null && Set.of("VALID", "DEMOTED").contains(claim.path("status").asText())) {
                    safe.add(item.asText());
                }
            });
            result.set(field, safe);
        }
        var declaredSupportingClaims = source.path("supportingClaimIds");
        var validatedSupportingClaims = result.path("supportingClaimIds");
        var allSupportingClaimsValid = declaredSupportingClaims.isArray()
                && !declaredSupportingClaims.isEmpty()
                && validatedSupportingClaims.isArray()
                && declaredSupportingClaims.size() == validatedSupportingClaims.size();
        if (!result.path("focus").asText("").isBlank() && !allSupportingClaimsValid) {
            result.remove("focus");
            changed[0] = true;
        }
        if ("CONCENTRATION_CHECK".equals(context.resolvedIntent())) {
            var concentrationVerified = hasValidClaimType(claims, "CONCENTRATION_CONFIRMATION", "L4");
            if (!concentrationVerified) result.put("actualConcentrationAssessment", "UNVERIFIED");
            var methodSupported = hasValidLevel(claims, "L2");
            var suitability = result.path("measurementSuitability").asText("");
            if (!methodSupported && Set.of("SUITABLE", "CAUTION", "UNSUITABLE").contains(suitability)) {
                result.put("measurementSuitability", "UNDETERMINED");
                result.remove("suitabilityReason");
                changed[0] = true;
            }
        }
        if ("PURITY_CHECK".equals(context.resolvedIntent())
                && !hasValidClaimType(claims, "PURITY_CONFIRMATION", "L4")) {
            result.put("quantificationStatus", "UNDETERMINED");
        }
        if ("IDENTIFICATION".equals(context.resolvedIntent())
                && !hasValidClaimType(claims, "IDENTITY_CONFIRMATION", "L4")) {
            result.put("identityAssessment", "UNDETERMINED");
        }
        if ("REFERENCE_MAPPING".equals(context.resolvedIntent())
                && !hasValidClaimType(claims, "REFERENCE_MAPPING_CONFIRMATION", "L4")) {
            result.put("mappingStatus", "UNDETERMINED");
        }
        return result;
    }

    private boolean hasValidClaimType(Map<String, ObjectNode> claims, String type, String level) {
        return claims.values().stream().anyMatch(claim -> type.equals(claim.path("claimType").asText())
                && level.equals(claim.path("effectiveLevel").asText())
                && Set.of("VALID", "DEMOTED").contains(claim.path("status").asText()));
    }

    private boolean hasValidLevel(Map<String, ObjectNode> claims, String level) {
        return claims.values().stream().anyMatch(claim -> level.equals(claim.path("effectiveLevel").asText())
                && Set.of("VALID", "DEMOTED").contains(claim.path("status").asText()));
    }

    private List<ObjectNode> sanitizeClaims(JsonNode source, SpectrumAnalysisPromptContext context, boolean[] changed) {
        var result = new ArrayList<ObjectNode>();
        if (!source.isArray()) {
            changed[0] = true;
            return result;
        }
        var ids = new LinkedHashSet<String>();
        var index = 0;
        for (var item : source) {
            index++;
            if (!item.isObject()) {
                changed[0] = true;
                continue;
            }
            var input = (ObjectNode) item;
            var claim = objectMapper.createObjectNode();
            var id = clean(input.path("claimId").asText());
            if (id.isBlank() || ids.contains(id)) id = "C" + index;
            ids.add(id);
            var type = clean(input.path("claimType").asText()).toUpperCase(Locale.ROOT);
            var statement = clean(input.path("statement").asText());
            var level = clean(input.path("level").asText()).toUpperCase(Locale.ROOT);
            claim.put("claimId", id);
            claim.put("claimType", type);
            claim.put("originalStatement", statement);
            claim.put("effectiveStatement", statement);
            claim.put("candidateStatement", clean(input.path("candidateStatement").asText()));
            claim.put("limitationStatement", clean(input.path("limitationStatement").asText()));
            claim.put("originalLevel", level);
            claim.put("effectiveLevel", level);
            claim.put("confidence", normalizeConfidence(input.path("confidence")));
            claim.put("uncertainty", clean(input.path("uncertainty").asText()));
            claim.put("status", "PENDING");
            claim.put("validationAction", "PENDING");
            claim.set("validationReasonCodes", objectMapper.createArrayNode());
            claim.set("effectiveRequiredEvidenceTypes", objectMapper.createArrayNode());
            claim.set("effectiveRequiredEvidenceRoles", objectMapper.createArrayNode());
            claim.set("dependsOnClaimIds", stringArray(input.path("dependsOnClaimIds")));
            claim.set("structuredFacts", input.path("structuredFacts").isArray()
                    ? input.path("structuredFacts").deepCopy() : objectMapper.createArrayNode());
            claim.set("knowledgeRuleIds", stringArray(input.path("knowledgeRuleIds")));
            claim.set("evidenceRefs", sanitizeEvidence(input.path("evidenceRefs"), context, claim, changed));
            if (input.has("requiredEvidenceTypes") || input.has("effectiveRequiredEvidenceTypes")
                    || input.has("status") || input.has("validationAction")) changed[0] = true;
            result.add(claim);
        }
        return result;
    }

    private ArrayNode sanitizeEvidence(JsonNode source, SpectrumAnalysisPromptContext context,
                                       ObjectNode claim, boolean[] changed) {
        var result = objectMapper.createArrayNode();
        if (!source.isArray()) return result;
        for (var item : source) {
            if (!item.isObject()) {
                changed[0] = true;
                reason(claim, "INVALID_EVIDENCE_REFERENCE");
                continue;
            }
            var sourceType = item.path("sourceType").asText("").toUpperCase(Locale.ROOT);
            if (!EVIDENCE_TYPES.contains(sourceType)) {
                changed[0] = true;
                reason(claim, "INVALID_EVIDENCE_REFERENCE");
                continue;
            }
            if ("DOMAIN_KNOWLEDGE".equals(sourceType)) {
                var ruleId = item.path("knowledgeRuleId").asText("");
                if (knowledgeRules.find(ruleId).isEmpty()) {
                    changed[0] = true;
                    reason(claim, "UNKNOWN_KNOWLEDGE_RULE");
                    continue;
                }
                var safe = objectMapper.createObjectNode();
                safe.put("sourceType", sourceType).put("knowledgeRuleId", ruleId);
                result.add(safe);
                continue;
            }
            var evidenceId = item.path("evidenceId").asText("");
            var allowedTypes = context.evidenceSourceTypes().getOrDefault(evidenceId, "");
            if (evidenceId.isBlank() || !List.of(allowedTypes.split(",")).contains(sourceType)) {
                changed[0] = true;
                reason(claim, "INVALID_EVIDENCE_REFERENCE");
                continue;
            }
            var safe = objectMapper.createObjectNode();
            safe.put("sourceType", sourceType).put("evidenceId", evidenceId);
            copyText(item, safe, "region");
            copyText(item, safe, "role");
            result.add(safe);
        }
        return result;
    }

    private void validateClaim(ObjectNode claim, Map<String, ObjectNode> byId,
                               SpectrumAnalysisPromptContext context, boolean[] changed) {
        var type = claim.path("claimType").asText();
        var canonicalLevel = CANONICAL_LEVELS.get(type);
        if (canonicalLevel == null || claim.path("effectiveStatement").asText().isBlank()) {
            reject(claim, "CLAIM_LEVEL_NOT_ALLOWED", changed);
            return;
        }
        setRequirements(claim, canonicalLevel, type);
        var originalLevel = claim.path("originalLevel").asText();
        var dependencies = validDependencies(claim, byId);
        if (!canonicalLevel.equals(originalLevel)) {
            changed[0] = true;
            reason(claim, "CLAIM_LEVEL_NOT_ALLOWED");
            if (("L2".equals(canonicalLevel) || "L3".equals(canonicalLevel))
                    && StringUtils.hasText(claim.path("candidateStatement").asText())) {
                claim.put("effectiveStatement", claim.path("candidateStatement").asText());
                claim.put("effectiveLevel", canonicalLevel);
                claim.put("status", "DEMOTED");
                claim.put("validationAction", "DEMOTE");
                reason(claim, "CANDIDATE_STATEMENT_USED");
            } else {
                reject(claim, "CLAIM_LEVEL_NOT_ALLOWED", changed);
                return;
            }
        } else {
            claim.put("effectiveLevel", canonicalLevel);
        }

        var supported = switch (canonicalLevel) {
            case "L1" -> supportsL1(claim);
            case "L2" -> supportsL2(claim, dependencies, context);
            case "L3" -> supportsL3(claim, dependencies);
            case "L4" -> supportsL4(claim, dependencies, type);
            default -> false;
        };
        if (!supported) {
            if ("L4".equals(canonicalLevel)) reason(claim, "MISSING_STRONG_EVIDENCE");
            reject(claim, supportReason(canonicalLevel, type, claim), changed);
            return;
        }
        if (!"DEMOTED".equals(claim.path("status").asText())) {
            claim.put("status", "VALID");
            claim.put("validationAction", "ACCEPT");
        }
    }

    private boolean supportsL1(ObjectNode claim) {
        return evidenceTypes(claim).stream().anyMatch(Set.of("OBSERVED_DATA", "DOCUMENT_METADATA")::contains);
    }

    private boolean supportsL2(ObjectNode claim, Map<String, JsonNode> dependencies,
                               SpectrumAnalysisPromptContext context) {
        if (dependencies.values().stream().noneMatch(item -> "L1".equals(item.path("effectiveLevel").asText()))) {
            reason(claim, "MISSING_DEPENDENCY_CLAIM");
            return false;
        }
        var ids = new LinkedHashSet<String>();
        claim.path("knowledgeRuleIds").forEach(item -> ids.add(item.asText()));
        claim.path("evidenceRefs").forEach(item -> {
            if ("DOMAIN_KNOWLEDGE".equals(item.path("sourceType").asText())) {
                ids.add(item.path("knowledgeRuleId").asText());
            }
        });
        for (var id : ids) {
            var rule = knowledgeRules.find(id).orElse(null);
            if (rule == null || !context.spectrumProtocols().contains(rule.protocol())
                    || !rule.allowedClaimTypes().contains(claim.path("claimType").asText())
                    || !knowledgeRules.applicable(rule, context.resolvedIntent(), claim, dependencies)) continue;
            return true;
        }
        reason(claim, "KNOWLEDGE_RULE_NOT_APPLICABLE");
        return false;
    }

    private boolean supportsL3(ObjectNode claim, Map<String, JsonNode> dependencies) {
        if (dependencies.values().stream().noneMatch(item -> Set.of("L1", "L2")
                .contains(item.path("effectiveLevel").asText()))) {
            reason(claim, "MISSING_DEPENDENCY_CLAIM");
            return false;
        }
        return StringUtils.hasText(claim.path("uncertainty").asText());
    }

    private boolean supportsL4(ObjectNode claim, Map<String, JsonNode> dependencies, String type) {
        var types = new LinkedHashSet<>(evidenceTypes(claim));
        dependencies.values().forEach(item -> types.addAll(evidenceTypes((ObjectNode) item)));
        return switch (type) {
            case "CONCENTRATION_CONFIRMATION" -> types.containsAll(List.of("OBSERVED_DATA", "EXPERIMENT_RECORD"))
                    && hasRole(claim, dependencies, "SAMPLE_PREPARATION")
                    && hasRole(claim, dependencies, "CALIBRATION_CURVE");
            case "IDENTITY_CONFIRMATION", "REFERENCE_MAPPING_CONFIRMATION" ->
                    types.containsAll(List.of("OBSERVED_DATA", "REFERENCE_SPECTRUM"));
            case "PURITY_CONFIRMATION" -> types.containsAll(List.of("OBSERVED_DATA", "COMPANY_SOP"));
            case "SAMPLE_RELATION_CONFIRMATION" -> types.contains("EXPERIMENT_RECORD")
                    || types.contains("DOCUMENT_METADATA");
            default -> false;
        };
    }

    private boolean hasRole(ObjectNode claim, Map<String, JsonNode> dependencies, String role) {
        if (hasRole(claim.path("evidenceRefs"), role)) return true;
        return dependencies.values().stream().anyMatch(item -> hasRole(item.path("evidenceRefs"), role));
    }

    private boolean hasRole(JsonNode refs, String role) {
        if (!refs.isArray()) return false;
        for (var ref : refs) if (role.equals(ref.path("role").asText())) return true;
        return false;
    }

    private void setRequirements(ObjectNode claim, String level, String type) {
        var types = claim.putArray("effectiveRequiredEvidenceTypes");
        var roles = claim.putArray("effectiveRequiredEvidenceRoles");
        switch (level) {
            case "L1" -> types.add("OBSERVED_DATA_OR_DOCUMENT_METADATA");
            case "L2" -> {
                types.add("OBSERVED_DATA");
                types.add("DOMAIN_KNOWLEDGE_OR_COMPANY_SOP");
            }
            case "L3" -> types.add("VALID_DEPENDENCY_CLAIM");
            case "L4" -> {
                switch (type) {
                    case "CONCENTRATION_CONFIRMATION" -> {
                        types.add("OBSERVED_DATA"); types.add("EXPERIMENT_RECORD");
                        roles.add("SAMPLE_PREPARATION"); roles.add("CALIBRATION_CURVE");
                    }
                    case "IDENTITY_CONFIRMATION", "REFERENCE_MAPPING_CONFIRMATION" -> {
                        types.add("OBSERVED_DATA"); types.add("REFERENCE_SPECTRUM");
                    }
                    case "PURITY_CONFIRMATION" -> {
                        types.add("OBSERVED_DATA"); types.add("COMPANY_SOP");
                    }
                    case "SAMPLE_RELATION_CONFIRMATION" -> types.add("DOCUMENT_METADATA_OR_EXPERIMENT_RECORD");
                    default -> { }
                }
            }
            default -> { }
        }
    }

    private String supportReason(String level, String type, ObjectNode claim) {
        if (hasReason(claim, "KNOWLEDGE_RULE_NOT_APPLICABLE")) return "KNOWLEDGE_RULE_NOT_APPLICABLE";
        if (hasReason(claim, "MISSING_DEPENDENCY_CLAIM")) return "MISSING_DEPENDENCY_CLAIM";
        if (!"L4".equals(level)) return "CLAIM_LEVEL_NOT_ALLOWED";
        return switch (type) {
            case "CONCENTRATION_CONFIRMATION" -> "MISSING_CALIBRATION_CURVE";
            case "IDENTITY_CONFIRMATION", "REFERENCE_MAPPING_CONFIRMATION" -> "MISSING_REFERENCE_SPECTRUM";
            case "PURITY_CONFIRMATION" -> "MISSING_COMPANY_SOP";
            case "SAMPLE_RELATION_CONFIRMATION" -> "UNCONFIRMED_SAMPLE_RELATION";
            default -> "CLAIM_LEVEL_NOT_ALLOWED";
        };
    }

    private void reject(ObjectNode claim, String reasonCode, boolean[] changed) {
        changed[0] = true;
        reason(claim, reasonCode);
        claim.put("effectiveLevel", "");
        claim.put("status", "REJECTED_TO_LIMITATION");
        claim.put("validationAction", "REJECT_TO_LIMITATION");
    }

    private ObjectNode limitationFor(ObjectNode claim, JsonNode assessment) {
        var limitation = objectMapper.createObjectNode();
        limitation.put("type", "REJECTED_CLAIM");
        var provided = claim.path("limitationStatement").asText("");
        limitation.put("statement", provided.isBlank() ? limitationTemplate(claim, assessment) : provided);
        limitation.putArray("relatedClaimIds").add(claim.path("claimId").asText());
        var missing = limitation.putArray("missingEvidence");
        claim.path("effectiveRequiredEvidenceTypes").forEach(item -> {
            var entry = missing.addObject();
            entry.put("sourceType", item.asText());
            entry.put("label", evidenceLabel(item.asText()));
        });
        claim.path("effectiveRequiredEvidenceRoles").forEach(item -> {
            var entry = missing.addObject();
            entry.put("role", item.asText());
            entry.put("label", evidenceLabel(item.asText()));
        });
        limitation.putArray("nextSteps").add(nextStep(claim.path("claimType").asText()));
        return limitation;
    }

    private String limitationTemplate(ObjectNode claim, JsonNode assessment) {
        return switch (claim.path("claimType").asText()) {
            case "CONCENTRATION_CONFIRMATION" -> {
                var nominal = assessment.path("nominalConcentration").path("value").asText("");
                yield nominal.isBlank() ? "当前不能确认样品的实际浓度。" : "当前不能确认实际浓度是否为" + nominal + "。";
            }
            case "IDENTITY_CONFIRMATION" -> "当前证据不足以确认样品身份。";
            case "PURITY_CONFIRMATION" -> "当前证据不足以确认样品纯度。";
            case "REFERENCE_MAPPING_CONFIRMATION" -> "当前证据不足以确认样品峰与参考谱的对应关系。";
            case "SAMPLE_RELATION_CONFIRMATION" -> "当前资料未确认这些样品之间的关系。";
            default -> "当前证据不足以支持该结论。";
        };
    }

    private String nextStep(String type) {
        return switch (type) {
            case "CONCENTRATION_CONFIRMATION" -> "核对配液记录，并使用标准曲线或适当稀释复测。";
            case "IDENTITY_CONFIRMATION" -> "补充可靠参考谱或正交分析结果。";
            case "PURITY_CONFIRMATION" -> "结合经过验证的定量方法和原始数据复核。";
            case "REFERENCE_MAPPING_CONFIRMATION" -> "补充明确标记的参考谱后再进行对应分析。";
            case "SAMPLE_RELATION_CONFIRMATION" -> "补充样品来源或实验关联记录。";
            default -> "补充相关原始记录后重新分析。";
        };
    }

    private ArrayNode sanitizeLimitations(JsonNode source) {
        var result = objectMapper.createArrayNode();
        if (!source.isArray()) return result;
        for (var item : source) {
            if (!item.isObject() || item.path("statement").asText("").isBlank()) continue;
            var safe = objectMapper.createObjectNode();
            copyText(item, safe, "type");
            copyText(item, safe, "statement");
            safe.set("relatedClaimIds", stringArray(item.path("relatedClaimIds")));
            safe.set("missingEvidence", item.path("missingEvidence").isArray()
                    ? item.path("missingEvidence").deepCopy() : objectMapper.createArrayNode());
            safe.set("nextSteps", stringArrayOrSingle(item.path("nextSteps")));
            result.add(safe);
        }
        return result;
    }

    private ArrayNode stringArrayOrSingle(JsonNode source) {
        if (source.isTextual()) {
            var value = source.asText().trim();
            var result = objectMapper.createArrayNode();
            if (!value.isBlank()) result.add(value);
            return result;
        }
        return stringArray(source);
    }

    private ArrayNode deduplicateLimitations(ArrayNode source) {
        var result = objectMapper.createArrayNode();
        var statements = new LinkedHashSet<String>();
        source.forEach(item -> {
            var statement = item.path("statement").asText("");
            if (!statement.isBlank() && statements.add(statement)) result.add(item);
        });
        return result;
    }

    private boolean supportsIntent(SpectrumAnalysisPromptContext context) {
        var protocols = Set.copyOf(context.spectrumProtocols());
        return switch (context.resolvedIntent()) {
            case "PURITY_CHECK" -> containsAny(protocols, "HPLC", "GC");
            case "CONCENTRATION_CHECK" -> containsAny(protocols, "UV", "HPLC", "GC");
            case "IDENTIFICATION" -> containsAny(protocols, "IR", "UV", "HPLC", "GC");
            case "REFERENCE_MAPPING" -> containsAny(protocols, "IR", "UV", "HPLC", "GPC", "GC");
            case "PEAK_INTERPRETATION" -> containsAny(protocols, "IR", "UV", "HPLC", "GPC", "GC", "HPLC_GPC_GENERIC");
            default -> true;
        };
    }

    private boolean containsAny(Set<String> values, String... candidates) {
        for (var candidate : candidates) if (values.contains(candidate)) return true;
        return false;
    }

    private ObjectNode intentMismatchLimitation(SpectrumAnalysisPromptContext context) {
        var limitation = objectMapper.createObjectNode();
        limitation.put("type", "INTENT_PROTOCOL_MISMATCH");
        limitation.put("statement", "当前图谱类型不适合完成所选择的分析目的。");
        limitation.putArray("relatedClaimIds");
        limitation.putArray("missingEvidence").addObject()
                .put("label", "能够支持该分析目的的图谱或实验数据");
        limitation.putArray("nextSteps").add("补充适合该分析目的的数据后继续分析，当前已保留可直接观察的内容。");
        return limitation;
    }

    private void markAssessmentUndetermined(ObjectNode assessment, String intent) {
        assessment.put("resultStatus", "UNDETERMINED");
        switch (intent) {
            case "PURITY_CHECK" -> {
                assessment.put("purityAssessment", "UNDETERMINED");
                assessment.put("quantificationStatus", "UNDETERMINED");
            }
            case "CONCENTRATION_CHECK" -> {
                assessment.put("actualConcentrationAssessment", "UNVERIFIED");
                assessment.put("measurementSuitability", "UNDETERMINED");
            }
            case "IDENTIFICATION" -> assessment.put("identityAssessment", "UNDETERMINED");
            case "REFERENCE_MAPPING" -> assessment.put("mappingStatus", "UNDETERMINED");
            case "METHOD_VALIDATION" -> assessment.put("methodSuitability", "UNDETERMINED");
            default -> { }
        }
    }

    private Map<String, JsonNode> validDependencies(ObjectNode claim, Map<String, ObjectNode> byId) {
        var result = new LinkedHashMap<String, JsonNode>();
        claim.path("dependsOnClaimIds").forEach(item -> {
            var dependency = byId.get(item.asText());
            if (dependency != null && Set.of("VALID", "DEMOTED").contains(dependency.path("status").asText())) {
                result.put(item.asText(), dependency);
            }
        });
        return result;
    }

    private boolean dependenciesResolved(ObjectNode claim, Map<String, ObjectNode> byId) {
        for (var id : claim.path("dependsOnClaimIds")) {
            var dependency = byId.get(id.asText());
            if (dependency == null) return true;
            if ("PENDING".equals(dependency.path("status").asText())) return false;
        }
        return true;
    }

    private Set<String> evidenceTypes(ObjectNode claim) {
        var result = new LinkedHashSet<String>();
        claim.path("evidenceRefs").forEach(item -> result.add(item.path("sourceType").asText()));
        return result;
    }

    private void reason(ObjectNode claim, String reason) {
        var reasons = claim.withArray("validationReasonCodes");
        for (var item : reasons) if (reason.equals(item.asText())) return;
        reasons.add(reason);
    }

    private boolean hasReason(ObjectNode claim, String reason) {
        for (var item : claim.path("validationReasonCodes")) if (reason.equals(item.asText())) return true;
        return false;
    }

    private ArrayNode stringArray(JsonNode source) {
        var result = objectMapper.createArrayNode();
        if (source.isArray()) source.forEach(item -> {
            if (item.isValueNode() && !item.asText("").isBlank()) result.add(item.asText());
        });
        return result;
    }

    private String normalizeConfidence(JsonNode value) {
        var normalized = value == null ? "" : value.asText("").toUpperCase(Locale.ROOT);
        return Set.of("LOW", "MEDIUM", "HIGH").contains(normalized) ? normalized : "LOW";
    }

    private void copyText(JsonNode source, ObjectNode target, String field) {
        var value = source.path(field).asText("");
        if (!value.isBlank()) target.put(field, value);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String evidenceLabel(String value) {
        return switch (value) {
            case "OBSERVED_DATA" -> "图谱或报告数据";
            case "DOCUMENT_METADATA" -> "文档信息";
            case "DOMAIN_KNOWLEDGE", "DOMAIN_KNOWLEDGE_OR_COMPANY_SOP" -> "适用的方法学依据";
            case "COMPANY_SOP", "MISSING_COMPANY_SOP" -> "适用的公司规范";
            case "REFERENCE_SPECTRUM" -> "可靠参考谱";
            case "EXPERIMENT_RECORD" -> "实验记录";
            case "SAMPLE_PREPARATION" -> "配液或样品制备记录";
            case "CALIBRATION_CURVE" -> "标准曲线或已验证的方法范围";
            default -> "相关原始依据";
        };
    }

    public record ValidationResult(ObjectNode result, List<String> warnings) { }
}
