package com.jsd.aird.spc.application.port;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface SpectrumPromptPort {

    String GENERIC_VERSION = "spectrum-chat-v1";
    String COMPETITOR_VERSION = "competitor-decomposition-v1";

    String build(SpectrumAnalysisPromptContext context);

    ObjectNode emptyResult(String answer, String warning);

    record SpectrumAnalysisPromptContext(
            String question,
            String chartContext,
            String pageContext,
            String scenarioTemplate,
            List<String> spectrumTypes,
            boolean hasSinglePeakReferences,
            List<String> referenceChartIds,
            List<String> explicitSampleRelations,
            List<String> availableEvidenceIds,
            List<String> sampleNames) {

        public SpectrumAnalysisPromptContext {
            spectrumTypes = spectrumTypes == null ? List.of() : List.copyOf(spectrumTypes);
            referenceChartIds = referenceChartIds == null ? List.of() : List.copyOf(referenceChartIds);
            explicitSampleRelations = explicitSampleRelations == null ? List.of() : List.copyOf(explicitSampleRelations);
            availableEvidenceIds = availableEvidenceIds == null ? List.of() : List.copyOf(availableEvidenceIds);
            sampleNames = sampleNames == null ? List.of() : List.copyOf(sampleNames);
        }
    }
}
