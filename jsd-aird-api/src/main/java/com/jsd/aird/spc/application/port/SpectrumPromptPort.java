package com.jsd.aird.spc.application.port;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface SpectrumPromptPort {

    static String currentVersion() {
        return "spectrum-chat-v4-natural-language";
    }

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
            List<String> sampleNames,
            List<SpectrumPromptPort.ConversationMessage> conversationHistory) {

        public SpectrumAnalysisPromptContext(
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
            this(question, chartContext, pageContext, requestedIntent, resolvedIntent, routingResolution,
                    routingReasonCode, spectrumProtocols, categoryHints, evidenceSourceTypes,
                    referenceChartIds, explicitSampleRelations, sampleNames, List.of());
        }

        public SpectrumAnalysisPromptContext {
            spectrumProtocols = spectrumProtocols == null ? List.of() : List.copyOf(spectrumProtocols);
            categoryHints = categoryHints == null ? List.of() : List.copyOf(categoryHints);
            evidenceSourceTypes = evidenceSourceTypes == null ? Map.of() : Map.copyOf(evidenceSourceTypes);
            referenceChartIds = referenceChartIds == null ? List.of() : List.copyOf(referenceChartIds);
            explicitSampleRelations = explicitSampleRelations == null ? List.of() : List.copyOf(explicitSampleRelations);
            sampleNames = sampleNames == null ? List.of() : List.copyOf(sampleNames);
            conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        }
    }

    record ConversationMessage(String role, String content) { }
}
