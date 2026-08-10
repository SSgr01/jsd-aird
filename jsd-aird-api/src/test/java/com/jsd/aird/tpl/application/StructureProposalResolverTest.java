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
            assertThat(group.path("alternatives").get(0).path("regions")).hasSize(1);
            assertThat(group.path("alternatives").get(1).path("regions")).hasSize(1);
        });
        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("range").asText()).isEqualTo("A4:N100");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT");
            assertThat(region.path("modelAlternatives")).hasSize(1);
            assertThat(region.path("structureAlternativeSets")).hasSize(2);
            assertThat(region.path("resolutionGroupId").asText()).isNotBlank();
        });
        assertThat(resolved.path("semanticTargets")).isEmpty();
    }

    @Test
    void replacesOnePhysicalFalsePositiveWithAnExactModelPartition() {
        var physical = physicalMatrix("A4:J6", "A4:H4", "A5:H6", "I4:J4", "I5:J6");
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("form", "FORM_REGION", "A1:J5"))
                .add(modelRegionProposal("rows", "ROW_TABLE", "A6:J22"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("recognitionStatus").asText()).isEqualTo("COMPLETE");
        assertThat(resolved.path("conflictGroups")).isEmpty();
        assertThat(resolved.path("regions")).hasSize(2).allSatisfy(region -> {
            assertThat(region.path("source").asText()).isEqualTo("MODEL");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("resolutionReason").asText()).isEqualTo("MODEL_PARTITION_EXACT_COVER");
            assertThat(region.path("resolutionGroupId").asText()).isNotBlank();
            assertThat(region.path("resolutionAlternativeId").asText()).isNotBlank();
        });
        assertThat(resolved.path("regions")).extracting(region -> region.path("range").asText())
                .containsExactly("A1:J5", "A6:J22");
        assertThat(resolved.path("suppressedRegions")).singleElement().satisfies(region -> {
            assertThat(region.path("range").asText()).isEqualTo("A4:J6");
            assertThat(region.path("structureStatus").asText()).isEqualTo("SUPERSEDED");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("REJECTED");
        });
        assertThat(resolved.path("resolutionGroups")).singleElement().satisfies(group -> {
            assertThat(group.path("resolutionStatus").asText()).isEqualTo("AUTO_RESOLVED");
            assertThat(group.path("resolutionReason").asText()).isEqualTo("MODEL_PARTITION_EXACT_COVER");
            assertThat(group.path("alternatives")).hasSize(2);
            assertThat(group.path("alternatives").get(1).path("regions")).hasSize(2);
        });
        assertThat(resolved.path("canonicalSemanticTargets")).hasSize(2);
        assertThat(resolved.path("unresolvedStructureTargets")).isEmpty();
    }

    @Test
    void keepsConflictWhenModelPartitionLeavesAnyGap() {
        var physical = physicalMatrix("A1:J10", "A1:D1", "A2:D10", "E1:J1", "E2:J10");
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("left", "FORM_REGION", "A1:E10"))
                .add(modelRegionProposal("right", "ROW_TABLE", "F1:I10"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("resolutionGroups")).isEmpty();
        assertThat(resolved.path("conflictGroups")).singleElement().satisfies(group -> {
            assertThat(group.path("alternatives")).hasSize(2);
            assertThat(group.path("alternatives").get(1).path("regions")).hasSize(2);
        });
        assertThat(resolved.path("regions")).singleElement()
                .satisfies(region -> assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT"));
    }

    @Test
    void keepsConflictWhenModelRegionsOverlapEachOther() {
        var physical = physicalMatrix("A4:J6", "A4:H4", "A5:H6", "I4:J4", "I5:J6");
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("form", "FORM_REGION", "A1:J5"))
                .add(modelRegionProposal("rows", "ROW_TABLE", "A5:J22"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("resolutionGroups")).isEmpty();
        assertThat(resolved.path("conflictGroups")).singleElement().satisfies(group -> {
            assertThat(group.path("alternatives")).hasSize(2);
            assertThat(group.path("alternatives").get(1).path("regions")).hasSize(2);
        });
    }

    @Test
    void doesNotLetHighConfidencePhysicalTableSuppressDisagreeingModelRegion() {
        var physical = objectMapper.createObjectNode()
                .put("candidateId", "physical-column")
                .put("blockType", "COLUMN_TABLE")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("sheetId", "sheet-1")
                .put("range", "A8:I37")
                .put("confidence", 0.90)
                .set("structure", objectMapper.createObjectNode()
                        .put("headerRange", "A8:I8")
                        .put("dataRange", "A9:I37")
                        .put("recordAxis", "COLUMN")
                        .put("recordWidth", 1)
                        .put("recordStride", 1));
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("model-short-row", "ROW_TABLE", "A27:I28"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("recognitionStatus").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(resolved.path("conflictGroups")).hasSize(1);
        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("COLUMN_TABLE");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT");
            assertThat(region.path("physicalStructureOnly").asBoolean()).isTrue();
        });
        assertThat(resolved.path("suppressedRegions")).isEmpty();
        assertThat(resolved.path("resolutionGroups")).isEmpty();
    }

    @Test
    void acceptsAnyStrictlyCompleteModelPartition() {
        var physical = objectMapper.createObjectNode()
                .put("candidateId", "physical-column")
                .put("blockType", "COLUMN_TABLE")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("sheetId", "sheet-1")
                .put("range", "A1:D10")
                .put("confidence", 0.90)
                .set("structure", objectMapper.createObjectNode()
                        .put("headerRange", "A1:D1")
                        .put("dataRange", "A2:D10")
                        .put("recordAxis", "COLUMN")
                        .put("recordWidth", 1)
                        .put("recordStride", 1));
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelProposal("matrix-left", "A1:B10", "A1:A1", "A2:A10", "B1:B1", "B2:B10"))
                .add(modelProposal("matrix-right", "C1:D10", "C1:C1", "C2:C10", "D1:D1", "D2:D10"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("regions")).hasSize(2).allSatisfy(region -> {
            assertThat(region.path("type").asText()).isEqualTo("MATRIX");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("resolutionReason").asText()).isEqualTo("MODEL_PARTITION_EXACT_COVER");
        });
        assertThat(resolved.path("suppressedRegions")).singleElement()
                .satisfies(region -> assertThat(region.path("canonicalStatus").asText()).isEqualTo("REJECTED"));
    }

    @Test
    void acceptsLegacyRepeatAxisButStillRequiresModelAgreement() {
        var physical = objectMapper.createObjectNode()
                .put("candidateId", "physical-row")
                .put("blockType", "ROW_TABLE")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("sheetId", "sheet-1")
                .put("range", "A6:J22")
                .put("confidence", 0.86)
                .set("structure", objectMapper.createObjectNode()
                        .put("headerRange", "A6:J6")
                        .put("dataRange", "A7:J22")
                        .put("repeatAxis", "ROW"));
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("model-short", "ROW_TABLE", "A6:G22"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("ROW_TABLE");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT");
        });
        assertThat(resolved.path("conflictGroups")).hasSize(1);
    }

    @Test
    void keepsLowConfidencePhysicalDisagreementAsManualConflict() {
        var physical = physicalMatrix("A4:N100", "A4:D4", "A5:D100", "E4:N4", "E5:N100")
                .put("confidence", 0.84);
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelProposal("model-low-confidence", "A4:N95", "A4:D4", "A5:D95", "E4:N4", "E5:N95"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("conflictGroups")).hasSize(1);
        assertThat(resolved.path("regions")).singleElement()
                .satisfies(region -> assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT"));
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

    private ObjectNode modelRegionProposal(String id, String type, String range) {
        var proposal = objectMapper.createObjectNode()
                .put("proposalId", id)
                .put("sheetId", "sheet-1")
                .put("type", type)
                .put("range", range)
                .put("recordAxis", "ROW_TABLE".equals(type) ? "ROW" : "UNKNOWN")
                .put("confidence", 0.95);
        if ("ROW_TABLE".equals(type)) {
            var bounds = range.split(":", 2);
            proposal.put("headerRange", bounds[0] + ":" + bounds[0])
                    .put("dataRange", range);
        }
        return proposal;
    }
}
