package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class StructureProposalResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructureProposalResolver resolver = new StructureProposalResolver(objectMapper);

    @Test
    void promotesOnlyAnEquivalentModelProposalToConfirmedCanonical() {
        var physical = physicalMatrix("A4:N100", "A4:D4", "A5:D100", "E4:N4", "E5:N100");
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelProposal("model-1", "A4:N100", "A4:D4", "A5:D100", "E4:N4", "E5:N100"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("recognitionStatus").asText()).isEqualTo("COMPLETE");
        assertThat(resolved.path("conflictGroups")).isEmpty();
        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("modelAssessmentVerdict").asText()).isEqualTo("MODEL_AGREES");
            assertThat(region.has("resolutionGroupId")).isFalse();
            assertThat(region.path("candidateOnly").asBoolean()).isFalse();
        });
    }

    @Test
    void preservesBothGeometriesAsAlternativesWhenModelDisagrees() {
        var physical = physicalMatrix("A4:N100", "A4:D4", "A5:D100", "E4:N4", "E5:N100");
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelProposal("model-1", "A4:N95", "A4:D4", "A5:D95", "E4:N4", "E5:N95"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("recognitionStatus").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(resolved.path("conflictGroups")).singleElement().satisfies(group -> {
            assertThat(group.path("type").asText()).isEqualTo("STRUCTURE_CONFLICT");
            assertThat(group.path("alternatives")).hasSize(2);
        });
        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("range").asText()).isEqualTo("A4:N100");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT");
            assertThat(region.path("modelAlternatives")).hasSize(1);
            assertThat(region.path("resolutionGroupId").asText()).isNotBlank();
        });
    }

    @Test
    void reportsOverlappingBackendRegionsAsSetConflictInsteadOfSilentlySelectingOne() {
        var matrix = physicalMatrix("A4:N20", "A4:D4", "A5:D20", "E4:N4", "E5:N20");
        var form = objectMapper.createObjectNode()
                .put("candidateId", "physical-form")
                .put("blockType", "FORM_REGION")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("sheetId", "sheet-1")
                .put("range", "N20:N22")
                .put("confidence", 0.5)
                .set("structure", objectMapper.createObjectNode()
                        .put("labelRange", "N20")
                        .put("valueRange", "N21:N22"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(matrix, form), null);

        assertThat(resolved.path("recognitionStatus").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(resolved.path("diagnostics"))
                .anySatisfy(diagnostic -> assertThat(diagnostic.path("code").asText())
                        .isEqualTo("STRUCTURE_CANDIDATE_CONFLICT"));
        assertThat(resolved.path("conflictGroups"))
                .anySatisfy(group -> assertThat(group.path("type").asText()).isEqualTo("STRUCTURE_CONFLICT"));
    }

    private ObjectNode physicalMatrix(String range, String corner, String rowHeader,
                                       String columnHeader, String crossData) {
        return objectMapper.createObjectNode()
                .put("candidateId", "physical-matrix")
                .put("blockType", "MATRIX")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("sheetId", "sheet-1")
                .put("range", range)
                .put("confidence", 0.86)
                .set("structure", objectMapper.createObjectNode()
                        .put("cornerRange", corner)
                        .put("rowHeaderRange", rowHeader)
                        .put("columnHeaderRange", columnHeader)
                        .put("crossDataRange", crossData)
                        .put("recordAxis", "COLUMN"));
    }

    private ObjectNode modelProposal(String id, String range, String corner, String rowHeader,
                                     String columnHeader, String crossData) {
        return objectMapper.createObjectNode()
                .put("proposalId", id)
                .put("sheetId", "sheet-1")
                .put("type", "MATRIX")
                .put("range", range)
                .put("cornerRange", corner)
                .put("rowHeaderRange", rowHeader)
                .put("columnHeaderRange", columnHeader)
                .put("crossDataRange", crossData)
                .put("recordAxis", "COLUMN")
                .put("confidence", 0.72);
    }
}
