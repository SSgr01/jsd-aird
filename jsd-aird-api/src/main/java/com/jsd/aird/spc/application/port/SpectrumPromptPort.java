package com.jsd.aird.spc.application.port;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface SpectrumPromptPort {

    String V3_VERSION = "spectrum-chat-v3-evidence-claims";

    String build(SpectrumAnalysisPromptContext context);

    ObjectNode emptyResult(String answer, String warning);

    record SpectrumAnalysisPromptContext(
            String question,
            String chartContext,
            String pageContext,
            String requestedIntent,
            String resolvedIntent,
            String routingResolution,
            String routingReasonCode,
            List<String> spectrumProtocols,
            List<String> categoryHints,
            Map<String, String> evidenceSourceTypes,
            List<String> referenceChartIds,
            List<String> explicitSampleRelations,
            List<String> sampleNames) {

        public SpectrumAnalysisPromptContext {
            spectrumProtocols = spectrumProtocols == null ? List.of() : List.copyOf(spectrumProtocols);
            categoryHints = categoryHints == null ? List.of() : List.copyOf(categoryHints);
            evidenceSourceTypes = evidenceSourceTypes == null ? Map.of() : Map.copyOf(evidenceSourceTypes);
            referenceChartIds = referenceChartIds == null ? List.of() : List.copyOf(referenceChartIds);
            explicitSampleRelations = explicitSampleRelations == null ? List.of() : List.copyOf(explicitSampleRelations);
            sampleNames = sampleNames == null ? List.of() : List.copyOf(sampleNames);
        }
    }
}
