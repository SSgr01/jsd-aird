package com.jsd.aird.spc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.port.SpectrumPromptPort.SpectrumAnalysisPromptContext;
import org.junit.jupiter.api.Test;

class SpectrumResultValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpectrumResultValidator validator = new SpectrumResultValidator(objectMapper);

    @Test
    void clearsMappingsWhenNoSinglePeakReferenceWasVerified() {
        var result = objectMapper.createObjectNode();
        result.putArray("peakMappings").addObject()
                .put("referenceChartId", "reference-a")
                .putArray("evidenceIds").add("reference-a/page-1");

        var validated = validator.validate(result, context(false, List.of()));

        assertThat(validated.result().path("peakMappings").size()).isZero();
        assertThat(validated.result().path("evidenceSufficiency").asText())
                .isEqualTo("INSUFFICIENT_FOR_MAPPING");
        assertThat(validated.result().path("referenceAvailability").path("hasSinglePeakReferences").asBoolean())
                .isFalse();
    }

    @Test
    void keepsOnlyMappingsWithVerifiedReferenceAndEvidence() {
        var result = objectMapper.createObjectNode();
        result.putArray("peakMappings").addObject()
                .put("referenceChartId", "reference-a")
                .putArray("evidenceIds").add("reference-a/page-1");

        var validated = validator.validate(result, context(true, List.of("reference-a")));

        assertThat(validated.result().path("peakMappings").size()).isEqualTo(1);
        assertThat(validated.warnings()).isEmpty();
    }

    @Test
    void removesUnauthorizedCrossSampleRelation() {
        var result = objectMapper.createObjectNode();
        result.putArray("candidateInterpretations").add("UA-338可能是USP-480的原料");

        var validated = validator.validate(result, context(false, List.of()));

        assertThat(validated.result().path("candidateInterpretations").size()).isZero();
        assertThat(validated.warnings()).anyMatch(item -> item.contains("跨样品推断"));
    }

    private SpectrumAnalysisPromptContext context(boolean hasReference, List<String> referenceIds) {
        return new SpectrumAnalysisPromptContext(
                "哪些峰可能对应参考图谱？", "", "", "COMPETITOR_DECOMPOSITION",
                List.of("IR"), hasReference, referenceIds, List.of(),
                List.of("reference-a/page-1"), List.of("UA-338", "USP-480"));
    }
}
