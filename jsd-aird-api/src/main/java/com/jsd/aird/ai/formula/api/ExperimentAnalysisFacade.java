package com.jsd.aird.ai.formula.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Public, read-only analysis boundary used by the case and snapshot stages. */
public interface ExperimentAnalysisFacade {

    ExperimentAnalysisPage query(ExperimentAnalysisQuery query);

    record ExperimentAnalysisQuery(
            String taskProfileCode,
            Set<UUID> experimentIds,
            UUID projectId,
            UUID categoryId,
            int page,
            int size
    ) {
        public ExperimentAnalysisQuery {
            experimentIds = experimentIds == null ? Set.of() : Set.copyOf(experimentIds);
            page = Math.max(1, page);
            size = Math.min(200, Math.max(1, size));
        }
    }

    record ExperimentAnalysisPage(
            String taskProfileCode,
            String analysisProfileVersion,
            List<AnalysisRow> items,
            int page,
            int size,
            long total,
            long totalPages,
            AnalysisQualitySummary quality
    ) {
        public ExperimentAnalysisPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record AnalysisQualitySummary(
            long rowCount,
            long structuredReadyCount,
            Map<String, Long> caseEligibleByTarget,
            Map<String, Long> modelEligibleByTarget,
            Map<String, Long> reasonCounts
    ) {
    }

    record AnalysisRow(
            String analysisRowId,
            UUID experimentId,
            UUID experimentVersionId,
            String experimentNo,
            String sourceType,
            String sourceGroupKey,
            String sourceContextKey,
            String sourceIdentity,
            String sourceIdentityType,
            String logicalSampleKey,
            List<FormulaComponent> formula,
            BigDecimal rawFormulaTotal,
            boolean normalizationApplied,
            BigDecimal normalizationFactor,
            String normalizationRuleVersion,
            Map<String, StandardFact> process,
            Map<String, StandardFact> context,
            Map<String, ResinBatchProperty> resinBatchProperties,
            Map<String, TargetObservation> targets,
            boolean structuredReadiness,
            List<EligibilityReason> structuredReasons,
            Map<String, Eligibility> caseEligibilityByTarget,
            Map<String, Eligibility> modelEligibilityByTarget,
            String formulaSignature,
            String formulaLineageGroup,
            List<String> leakageGroupKeys,
            String analysisRowContentHash
    ) {
        public AnalysisRow(
                String analysisRowId, UUID experimentId, UUID experimentVersionId, String experimentNo,
                String sourceType, String sourceGroupKey, String sourceContextKey, String sourceIdentity,
                String sourceIdentityType, String logicalSampleKey, List<FormulaComponent> formula,
                BigDecimal rawFormulaTotal, boolean normalizationApplied, BigDecimal normalizationFactor,
                String normalizationRuleVersion, Map<String, StandardFact> process, Map<String, StandardFact> context,
                Map<String, TargetObservation> targets, boolean structuredReadiness,
                List<EligibilityReason> structuredReasons, Map<String, Eligibility> caseEligibilityByTarget,
                Map<String, Eligibility> modelEligibilityByTarget, String formulaSignature,
                String formulaLineageGroup, List<String> leakageGroupKeys, String analysisRowContentHash
        ) {
            this(analysisRowId, experimentId, experimentVersionId, experimentNo, sourceType, sourceGroupKey,
                    sourceContextKey, sourceIdentity, sourceIdentityType, logicalSampleKey, formula, rawFormulaTotal,
                    normalizationApplied, normalizationFactor, normalizationRuleVersion, process, context, Map.of(),
                    targets, structuredReadiness, structuredReasons, caseEligibilityByTarget,
                    modelEligibilityByTarget, formulaSignature, formulaLineageGroup, leakageGroupKeys,
                    analysisRowContentHash);
        }

        public AnalysisRow {
            resinBatchProperties = resinBatchProperties == null ? Map.of() : Map.copyOf(resinBatchProperties);
        }
    }

    record FormulaComponent(
            String itemId,
            String materialCode,
            String materialName,
            String materialRole,
            BigDecimal rawRatio,
            BigDecimal ratioPercent,
            JsonNode rawValue,
            String rawUnit,
            String ratioDerivation,
            String ratioRuleVersion,
            String mappingStatus,
            JsonNode sourceRefs
    ) {
    }

    record StandardFact(
            String code,
            BigDecimal numericValue,
            String textValue,
            BigDecimal minimum,
            BigDecimal maximum,
            String standardUnit,
            String rawValue,
            String sourceItemId,
            JsonNode sourceRefs,
            String parserRuleVersion,
            String status,
            String derivationRule
    ) {
    }

    record ResinBatchProperty(
            String propertyCode,
            String materialCode,
            BigDecimal numericValue,
            String textValue,
            String standardUnit,
            String rawValue,
            String sourceItemId,
            JsonNode sourceRefs,
            String bindingStatus,
            String parserRuleVersion
    ) {
    }

    record TargetObservation(
            String targetKey,
            String valueType,
            BigDecimal numericValue,
            BigDecimal minimum,
            BigDecimal maximum,
            String ordinalValue,
            String direction,
            String standardUnit,
            String observationType,
            String derivationRule,
            String rawValue,
            String sourceItemId,
            JsonNode sourceRefs,
            String status,
            List<EligibilityReason> reasons
    ) {
        public TargetObservation(
                String targetKey, String valueType, BigDecimal numericValue, String ordinalValue,
                String direction, String standardUnit, String observationType, String rawValue,
                String sourceItemId, JsonNode sourceRefs, String status, List<EligibilityReason> reasons
        ) {
            this(targetKey, valueType, numericValue, null, null, ordinalValue, direction, standardUnit,
                    observationType, "SOURCE_REPORTED", rawValue, sourceItemId, sourceRefs, status, reasons);
        }

        public TargetObservation {
            derivationRule = derivationRule == null ? "" : derivationRule;
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    record Eligibility(boolean eligible, List<EligibilityReason> reasons) {
        public Eligibility {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    record EligibilityReason(String code, String message, String sourceItemId) {
    }
}
