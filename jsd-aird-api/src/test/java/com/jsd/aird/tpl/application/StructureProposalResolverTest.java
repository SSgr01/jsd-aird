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
    void keepsDifferentStructureTypesAsOnePendingChoiceEvenWhenRangeMatches() {
        var physical = physicalMatrix("A2:E6", "A2", "A3:A6", "B2:E2", "B3:E6");
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("model-row", "ROW_TABLE", "A2:E6"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("recognitionStatus").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(resolved.path("conflictGroups")).singleElement().satisfies(group -> {
            assertThat(group.path("type").asText()).isEqualTo("STRUCTURE_CONFLICT");
            assertThat(group.path("alternatives")).hasSize(2);
        });
        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("MATRIX");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT");
        });
        assertThat(resolved.path("resolutionGroups")).isEmpty();
    }

    @Test
    void demotesModelFormSubsectionsToSemanticsInsteadOfCompetingStructures() {
        var physical = objectMapper.createObjectNode()
                .put("candidateId", "physical-form")
                .put("blockType", "FORM_REGION")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("sheetId", "sheet-1")
                .put("range", "A1:F21")
                .put("confidence", 0.86);
        var structure = physical.putObject("structure").put("recordAxis", "UNKNOWN");
        var surfaces = structure.putArray("fieldSurfaces");
        surfaces.addObject().put("range", "A4:C4");
        surfaces.addObject().put("range", "D4:F4");
        surfaces.addObject().put("range", "A6:F8");
        surfaces.addObject().put("range", "A9:F13");
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("header", "FORM_REGION", "A1:F3"))
                .add(modelRegionProposal("quality", "FORM_REGION", "A6:F8"))
                .add(modelRegionProposal("analysis", "FORM_REGION", "A9:F13"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("conflictGroups")).isEmpty();
        assertThat(resolved.path("suppressedRegions"))
                .extracting(item -> item.path("code").asText())
                .containsExactlyInAnyOrder("MODEL_FORM_DEMOTED_TO_SEMANTIC_GROUP",
                        "MODEL_FORM_DEMOTED_TO_SEMANTIC_GROUP",
                        "MODEL_FORM_DEMOTED_TO_SEMANTIC_GROUP");
        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("FORM_REGION");
            assertThat(region.path("range").asText()).isEqualTo("A1:F21");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFIRMED");
        });
    }

    @Test
    void rejectsOneRowModelTableThatOnlyConsumesKnownFormFields() {
        var physical = objectMapper.createObjectNode()
                .put("candidateId", "physical-form")
                .put("blockType", "FORM_REGION")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("sheetId", "sheet-1")
                .put("range", "A1:F21")
                .put("confidence", 0.86);
        var structure = physical.putObject("structure").put("recordAxis", "UNKNOWN");
        var surfaces = structure.putArray("fieldSurfaces");
        surfaces.addObject().put("range", "A4:C4");
        surfaces.addObject().put("range", "D4:F4");
        surfaces.addObject().put("range", "A5:C5");
        surfaces.addObject().put("range", "D5:F5");
        var model = objectMapper.createObjectNode();
        var proposal = modelRegionProposal("false-table", "COLUMN_TABLE", "A4:F5");
        proposal.put("headerRange", "A4:F4").put("dataRange", "A5:F5").put("recordAxis", "COLUMN");
        model.putArray("structureProposals").add(proposal);

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("conflictGroups")).isEmpty();
        assertThat(resolved.path("suppressedRegions")).singleElement().satisfies(item ->
                assertThat(item.path("code").asText()).isEqualTo("MODEL_TABLE_WITHOUT_REPEAT_EVIDENCE"));
        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("FORM_REGION");
            assertThat(region.path("structureStatus").asText()).isEqualTo("UNRESOLVED");
            assertThat(region.path("pendingReason").asText()).isEqualTo("PHYSICAL_FORM_FIELDS_READY");
        });
    }

    @Test
    void keepsAnExactModelPartitionAsOnePendingChoiceInsteadOfAutoSelectingIt() {
        var physical = physicalMatrix("A4:J6", "A4:H4", "A5:H6", "I4:J4", "I5:J6");
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("form", "FORM_REGION", "A1:J5"))
                .add(modelRegionProposal("rows", "ROW_TABLE", "A6:J22"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("recognitionStatus").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(resolved.path("conflictGroups")).singleElement().satisfies(group -> {
            assertThat(group.path("type").asText()).isEqualTo("STRUCTURE_CONFLICT");
            assertThat(group.path("alternatives")).hasSize(2);
        });
        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("source").asText()).isEqualTo("PHYSICAL_HEURISTIC");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT");
            assertThat(region.path("resolutionReason").asText()).isBlank();
            assertThat(region.path("resolutionGroupId").asText()).isNotBlank();
            assertThat(region.path("modelAlternatives")).hasSize(2);
        });
        assertThat(resolved.path("resolutionGroups")).isEmpty();
        assertThat(resolved.path("canonicalSemanticTargets")).isEmpty();
        assertThat(resolved.path("unresolvedStructureTargets")).hasSize(1);
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
            assertThat(group.path("alternatives")).hasSize(3);
            assertThat(group.path("alternatives").get(1).path("regions")).hasSize(1);
            assertThat(group.path("alternatives").get(2).path("regions")).hasSize(1);
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
    void componentLevelPhysicalConfirmationRejectsACompetingInnerModelRegion() {
        var structure = objectMapper.createObjectNode()
                .put("headerRange", "A8:I8")
                .put("dataRange", "A8:I37")
                .put("crossDataRange", "D8:I37")
                .put("recordAxis", "COLUMN")
                .put("physicalConfirmed", true);
        structure.putArray("fieldRows")
                .addObject().put("labelPath", "分组 > 字段一").put("valueRange", "D8:I8");
        structure.withArray("fieldRows")
                .addObject().put("labelPath", "分组 > 字段二").put("valueRange", "D9:I9");
        structure.putObject("recordProjection").put("mode", "COLUMN_RECORDS")
                .put("recordAxis", "COLUMN").putArray("recordColumns")
                .add("D").add("E").add("F").add("G").add("H").add("I");
        var physical = objectMapper.createObjectNode()
                .put("candidateId", "physical-column")
                .put("blockType", "COLUMN_TABLE")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("physicalConfirmed", true)
                .put("sheetId", "sheet-1")
                .put("range", "A8:I37")
                .put("confidence", 0.94)
                .set("structure", structure);
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("model-inner-form", "FORM_REGION", "A27:I28"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("conflictGroups")).isEmpty();
        assertThat(resolved.path("suppressedRegions")).singleElement().satisfies(item ->
                assertThat(item.path("code").asText())
                        .isEqualTo("MODEL_STRUCTURE_REJECTED_BY_DETERMINATE_COMPONENT"));
        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("resolutionReason").asText())
                    .isEqualTo("DETERMINATE_COMPONENT_GEOMETRY");
        });
    }

    @Test
    void highConfidenceWithoutExplicitPhysicalConfirmationStillRequiresReview() {
        var structure = objectMapper.createObjectNode()
                .put("headerRange", "A8:I8").put("dataRange", "A8:I37")
                .put("crossDataRange", "D8:I37").put("recordAxis", "COLUMN");
        structure.putArray("fieldRows")
                .addObject().put("labelPath", "字段一").put("valueRange", "D8:I8");
        structure.withArray("fieldRows")
                .addObject().put("labelPath", "字段二").put("valueRange", "D9:I9");
        structure.putObject("recordProjection").putArray("recordColumns").add("D").add("E");
        var physical = objectMapper.createObjectNode().put("candidateId", "unconfirmed")
                .put("blockType", "COLUMN_TABLE").put("geometryStatus", "VALID_GEOMETRY")
                .put("sheetId", "sheet-1").put("range", "A8:I37").put("confidence", 0.99)
                .set("structure", structure);
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("model-inner", "FORM_REGION", "A27:I28"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("conflictGroups")).hasSize(1);
        assertThat(resolved.path("regions")).singleElement().satisfies(region ->
                assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT"));
    }

    @Test
    void doesNotAutoSelectACompleteModelPartition() {
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

        assertThat(resolved.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("COLUMN_TABLE");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT");
            assertThat(region.path("modelAlternatives")).hasSize(2);
        });
        assertThat(resolved.path("recognitionStatus").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(resolved.path("suppressedRegions")).isEmpty();
    }

    @Test
    void keepsModelOnlyRegionsAsCandidatesWhenThereIsNoPhysicalEvidence() {
        var model = objectMapper.createObjectNode();
        model.putArray("structureProposals")
                .add(modelRegionProposal("form", "FORM_REGION", "A1:D8"))
                .add(modelRegionProposal("rows", "ROW_TABLE", "A9:D20"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(), model);

        assertThat(resolved.path("regions")).hasSize(2).allSatisfy(region -> {
            assertThat(region.path("source").asText()).isEqualTo("MODEL");
            assertThat(region.path("candidateOnly").asBoolean()).isTrue();
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
            assertThat(region.path("resolutionStatus").asText()).isEqualTo("PENDING");
        });
        assertThat(resolved.path("recognitionStatus").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(resolved.path("regions").get(0).path("resolutionGroupId").asText())
                .isNotEqualTo(resolved.path("regions").get(1).path("resolutionGroupId").asText());
    }

    @Test
    void groupsOnlyExplicitComplementaryModelPartitionsTogether() {
        var model = objectMapper.createObjectNode();
        var proposals = model.putArray("structureProposals");
        proposals.add(modelRegionProposal("form", "FORM_REGION", "A1:D8")
                .put("componentId", "component-main").put("hypothesisId", "partition-a"));
        proposals.add(modelRegionProposal("rows", "ROW_TABLE", "A9:D20")
                .put("componentId", "component-main").put("hypothesisId", "partition-a"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(), model);

        assertThat(resolved.path("regions")).hasSize(2);
        assertThat(resolved.path("regions").get(0).path("resolutionGroupId").asText())
                .isEqualTo(resolved.path("regions").get(1).path("resolutionGroupId").asText());
        assertThat(resolved.path("regions").get(0).path("resolutionAlternativeId").asText())
                .isEqualTo(resolved.path("regions").get(1).path("resolutionAlternativeId").asText());
        assertThat(resolved.path("resolutionGroups")).singleElement().satisfies(group ->
                assertThat(group.path("alternatives").get(0).path("regions")).hasSize(2));
    }

    @Test
    void keepsOverlappingExplicitModelHypothesesAsSeparateAlternatives() {
        var model = objectMapper.createObjectNode();
        var proposals = model.putArray("structureProposals");
        proposals.add(modelRegionProposal("wide", "ROW_TABLE", "A1:D20")
                .put("componentId", "component-main").put("hypothesisId", "competing"));
        proposals.add(modelRegionProposal("inner", "FORM_REGION", "A1:D8")
                .put("componentId", "component-main").put("hypothesisId", "competing"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(), model);

        assertThat(resolved.path("conflictGroups")).singleElement().satisfies(group -> {
            assertThat(group.path("alternatives")).hasSize(2);
            assertThat(group.path("alternatives").get(0).path("regions")).hasSize(1);
            assertThat(group.path("alternatives").get(1).path("regions")).hasSize(1);
        });
    }

    @Test
    void confirmedPhysicalOnlySuppressesModelProposalInTheSameComponent() {
        var physical = physicalMatrix("A1:D8", "A1", "A2:A8", "B1:D1", "B2:D8");
        var model = objectMapper.createObjectNode();
        var proposals = model.putArray("structureProposals");
        proposals.add(modelProposal("exact", "A1:D8", "A1", "A2:A8", "B1:D1", "B2:D8"));
        proposals.add(modelRegionProposal("independent", "FORM_REGION", "H1:J5"));

        var resolved = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(resolved.path("regions")).hasSize(2);
        assertThat(resolved.path("regions")).anySatisfy(region ->
                assertThat(region.path("range").asText()).isEqualTo("H1:J5"));
        assertThat(resolved.path("modelDiagnostics")).noneSatisfy(diagnostic ->
                assertThat(diagnostic.path("proposalId").asText()).isEqualTo("independent"));
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
