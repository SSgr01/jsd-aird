package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TemplateImportServiceStructurePolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completePhysicalCoverageIsNotDowngradedByRejectedModelDiagnostics() {
        assertThat(TemplateImportService.shouldForceStructureReview("COMPLETE", true)).isFalse();
        assertThat(TemplateImportService.shouldForceStructureReview("REVIEW_REQUIRED", true)).isTrue();
    }

    @Test
    void aDirectionConflictCannotBeRewrittenAsDeterminatePhysicalStructure() {
        var conflict = objectMapper.createObjectNode()
                .put("physicalConfirmed", true)
                .put("structureConflict", true);
        var determinate = objectMapper.createObjectNode()
                .put("physicalConfirmed", true)
                .put("structureConflict", false);

        assertThat(TemplateImportService.shouldReplaceDeterminatePhysicalComponent(conflict)).isFalse();
        assertThat(TemplateImportService.shouldReplaceDeterminatePhysicalComponent(determinate)).isTrue();
    }
}
