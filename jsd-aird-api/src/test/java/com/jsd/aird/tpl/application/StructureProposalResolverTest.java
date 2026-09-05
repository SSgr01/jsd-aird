package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StructureProposalResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructureProposalResolver resolver = new StructureProposalResolver(objectMapper);

    @Test
    void confirmsMatchingRowTableProposals() throws Exception {
        var physical = objectMapper.readTree("""
                {"id":"p1","candidateId":"p1","blockType":"ROW_TABLE","sheetId":"s1","range":"A1:C8",
                 "validationStatus":"VALID","geometryStatus":"VALID_GEOMETRY","confidence":0.9,
                 "structure":{"headerRange":"A1:C1","dataRange":"A2:C8","recordAxis":"ROW","repeatAxis":"ROW"}}
                """);
        var model = objectMapper.readTree("""
                {"structureProposals":[
                  {"proposalId":"m1","sheetId":"s1","type":"ROW_TABLE","range":"A1:C8",
                   "headerRange":"A1:C1","dataRange":"A2:C8","recordAxis":"ROW","confidence":0.9}
                ]}
                """);

        var result = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(result.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("ROW_TABLE");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
        });
    }

    @Test
    void exactOuterRangeAgreementKeepsDeterminatePhysicalHeaderAndDataRanges() throws Exception {
        var physical = objectMapper.readTree("""
                {"id":"p1","candidateId":"p1","blockType":"COLUMN_TABLE","sheetId":"s1","range":"A4:K41",
                 "validationStatus":"VALID","geometryStatus":"VALID_GEOMETRY","confidence":0.94,"physicalConfirmed":true,
                 "structure":{"headerRange":"A4:K5","dataRange":"A6:K41","recordAxis":"COLUMN","repeatAxis":"COLUMN",
                              "recordHeight":36,"recordWidth":1,"recordStride":1,"physicalConfirmed":true}}
                """);
        var model = objectMapper.readTree("""
                {"structureProposals":[
                  {"proposalId":"m1","sheetId":"s1","type":"COLUMN_TABLE","range":"A4:K41",
                   "headerRange":"A4:K4","dataRange":"A5:K41","recordAxis":"COLUMN","confidence":0.91}
                ]}
                """);

        var result = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(result.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("range").asText()).isEqualTo("A4:K41");
            assertThat(region.path("structure").path("headerRange").asText()).isEqualTo("A4:K5");
            assertThat(region.path("structure").path("dataRange").asText()).isEqualTo("A6:K41");
            assertThat(region.path("structure").path("recordHeight").asInt()).isEqualTo(36);
            assertThat(region.path("resolutionStatus").asText()).isEqualTo("AUTO_RESOLVED");
            assertThat(region.path("structureConflict").asBoolean()).isFalse();
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
        });
        assertThat(result.path("conflictGroups")).isEmpty();
        assertThat(result.path("modelDiagnostics")).anyMatch(item ->
                "MODEL_GEOMETRY_IGNORED".equals(item.path("code").asText()));
    }

    @Test
    void keepsUnknownDirectionAsBlockingStructureCandidate() throws Exception {
        var physical = objectMapper.readTree("""
                {"id":"p1","candidateId":"p1","blockType":"UNKNOWN","sheetId":"s1","range":"A1:D8",
                 "validationStatus":"VALID","geometryStatus":"VALID_GEOMETRY","confidence":0.55,
                 "structure":{"recordAxis":"UNKNOWN","reviewRequired":true}}
                """);

        var result = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), null);

        assertThat(result.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("UNKNOWN");
            assertThat(region.path("pendingReason").asText()).isEqualTo("STRUCTURE_DIRECTION_UNCLEAR");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
        });
        assertThat(result.path("recognitionStatus").asText()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void confirmsACompletePhysicalRowTableWhenTheModelOmitsIt() throws Exception {
        var physical = objectMapper.readTree("""
                {"id":"p1","candidateId":"p1","blockType":"ROW_TABLE","sheetId":"s1","range":"A6:G22",
                 "validationStatus":"VALID","geometryStatus":"VALID_GEOMETRY","confidence":0.86,
                 "structure":{"headerRange":"A6:G6","dataRange":"A7:G21","recordAxis":"ROW",
                              "repeatAxis":"ROW","recordHeight":1,"recordWidth":7,"recordStride":1,
                              "physicalConfirmed":true}}
                """);

        var result = resolver.resolve(objectMapper.createObjectNode(), List.of(physical),
                objectMapper.createObjectNode().putArray("structureProposals"));

        assertThat(result.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("resolutionReason").asText())
                    .isEqualTo("DETERMINATE_COMPONENT_GEOMETRY");
        });
        assertThat(result.path("recognitionStatus").asText()).isEqualTo("COMPLETE");
    }

    @Test
    void keepsOppositeRecordDirectionsAsAConflictEvenWhenPhysicalCandidateIsMarkedConfirmed() throws Exception {
        var physical = objectMapper.readTree("""
                {"id":"p1","candidateId":"p1","blockType":"ROW_TABLE","sheetId":"s1","range":"A1:H20",
                 "validationStatus":"VALID","geometryStatus":"VALID_GEOMETRY","confidence":0.9,"physicalConfirmed":true,
                 "structure":{"headerRange":"A1:H1","dataRange":"A2:H20","recordAxis":"ROW","repeatAxis":"ROW",
                              "columns":[{"name":"A"},{"name":"B"}]}}
                """);
        var model = objectMapper.readTree("""
                {"structureProposals":[
                  {"proposalId":"m1","sheetId":"s1","type":"COLUMN_TABLE","range":"C1:H20",
                   "headerRange":"C1:H1","dataRange":"C2:H20","recordAxis":"COLUMN","confidence":0.93}
                ]}
                """);

        var result = resolver.resolve(objectMapper.createObjectNode(), List.of(physical), model);

        assertThat(result.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("PROVISIONAL");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFLICT");
            assertThat(region.path("pendingReason").asText()).isEqualTo("STRUCTURE_CONFLICT");
        });
        assertThat(result.path("suppressedRegions")).noneMatch(item ->
                "MODEL_STRUCTURE_REJECTED_BY_DETERMINATE_COMPONENT".equals(item.path("code").asText()));
        assertThat(result.path("recognitionStatus").asText()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void keepsModelPartitionsAsConflictWithoutSplittingPhysicalColumnComponent() throws Exception {
        var physical = objectMapper.readTree("""
                {"id":"p1","candidateId":"p1","blockType":"COLUMN_TABLE","sheetId":"s1","range":"A8:I38",
                 "validationStatus":"VALID","geometryStatus":"VALID_GEOMETRY","confidence":0.94,"physicalConfirmed":true,
                 "structure":{"headerRange":"A8:I8","dataRange":"A9:I38","recordAxis":"COLUMN","repeatAxis":"COLUMN"}}
                """);
        var model = objectMapper.readTree("""
                {"structureProposals":[
                  {"proposalId":"m1","sheetId":"s1","type":"COLUMN_TABLE","range":"C8:I15",
                   "headerRange":"C8:I8","dataRange":"C9:I15","recordAxis":"COLUMN","confidence":0.93},
                  {"proposalId":"m2","sheetId":"s1","type":"COLUMN_TABLE","range":"C16:I26",
                   "headerRange":"C16:I16","dataRange":"C17:I26","recordAxis":"COLUMN","confidence":0.93},
                  {"proposalId":"m3","sheetId":"s1","type":"COLUMN_TABLE","range":"C28:I38",
                   "headerRange":"C28:I28","dataRange":"C29:I38","recordAxis":"COLUMN","confidence":0.92}
                ]}
                """);
        var structure = objectMapper.readTree("""
                {"sheets":[{"sheetId":"s1","semanticCells":[
                  {"address":"C8","value":"树脂编号"},
                  {"address":"C16","value":"实验编号"},
                  {"address":"C28","value":"漆膜外观"}
                ]}]}
                """);

        var result = resolver.resolve(structure, List.of(physical), model);

        assertThat(result.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("COLUMN_TABLE");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("source").asText()).isEqualTo("PHYSICAL_HEURISTIC");
            assertThat(region.path("range").asText()).isEqualTo("A8:I38");
            assertThat(region.path("structureConflict").asBoolean()).isFalse();
        });
        assertThat(result.path("conflictGroups")).isEmpty();
        assertThat(result.path("modelDiagnostics")).hasSize(3);
        assertThat(result.path("suppressedRegions")).noneMatch(item ->
                "MODEL_STRUCTURE_REJECTED_BY_DETERMINATE_COMPONENT".equals(item.path("code").asText()));
        assertThat(result.path("recognitionStatus").asText()).isEqualTo("COMPLETE");
    }

    @Test
    void keepsContinuousMeasureGroupsInOneColumnRegion() throws Exception {
        var physical = objectMapper.readTree("""
                {"id":"p1","candidateId":"p1","blockType":"COLUMN_TABLE","sheetId":"s1","range":"A9:I35",
                 "validationStatus":"VALID","geometryStatus":"VALID_GEOMETRY","confidence":0.94,"physicalConfirmed":true,
                 "structure":{"headerRange":"A9:I9","dataRange":"A9:I35","recordAxis":"COLUMN","repeatAxis":"COLUMN"}}
                """);
        var model = objectMapper.readTree("""
                {"structureProposals":[
                  {"proposalId":"m1","sheetId":"s1","type":"COLUMN_TABLE","range":"A9:I16",
                   "headerRange":"A9:I9","dataRange":"A10:I16","recordAxis":"COLUMN","confidence":0.93},
                  {"proposalId":"m2","sheetId":"s1","type":"COLUMN_TABLE","range":"A17:I35",
                   "headerRange":"A17:I17","dataRange":"A18:I35","recordAxis":"COLUMN","confidence":0.93}
                ]}
                """);
        var structure = objectMapper.readTree("""
                {"sheets":[{"sheetId":"s1","semanticCells":[
                  {"address":"C9","value":"实验编号"},
                  {"address":"C17","value":"涂料固含"}
                ]}]}
                """);

        var result = resolver.resolve(structure, List.of(physical), model);

        assertThat(result.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("range").asText()).isEqualTo("A9:I35");
            assertThat(region.path("type").asText()).isEqualTo("COLUMN_TABLE");
            assertThat(region.path("resolutionStatus").asText()).isEqualTo("AUTO_RESOLVED");
        });
        assertThat(result.path("conflictGroups")).isEmpty();
    }

    @Test
    void ignoresModelFormThatOnlyIncludesTheRepeatTableHeaderBoundary() throws Exception {
        var physicalTable = objectMapper.readTree("""
                {"id":"table","candidateId":"table","blockType":"COLUMN_TABLE","sheetId":"s1","range":"A4:H19",
                 "validationStatus":"VALID","geometryStatus":"VALID_GEOMETRY","confidence":0.94,"physicalConfirmed":true,
                 "structure":{"headerRange":"A4:H4","dataRange":"A5:H19","recordAxis":"COLUMN","repeatAxis":"COLUMN",
                              "recordHeight":15,"recordWidth":1,"recordStride":1,"physicalConfirmed":true}}
                """);
        var model = objectMapper.readTree("""
                {"structureProposals":[
                  {"proposalId":"form-model","sheetId":"s1","type":"FORM_REGION","range":"A1:H4","confidence":0.95},
                  {"proposalId":"table-model","sheetId":"s1","type":"COLUMN_TABLE","range":"A5:H19",
                   "headerRange":"A5:H5","dataRange":"A6:H19","recordAxis":"COLUMN","confidence":0.85}
                ]}
                """);

        var result = resolver.resolve(objectMapper.createObjectNode(), List.of(physicalTable), model);

        assertThat(result.path("conflictGroups")).isEmpty();
        assertThat(result.path("regions")).hasSize(1);
        assertThat(result.path("regions")).anyMatch(region ->
                "COLUMN_TABLE".equals(region.path("type").asText())
                        && "CONFIRMED".equals(region.path("structureStatus").asText())
                        && "A4:H19".equals(region.path("range").asText()));
        assertThat(result.path("suppressedRegions")).anyMatch(item ->
                "MODEL_FORM_HEADER_BOUNDARY_IGNORED".equals(item.path("code").asText()));
    }

    @Test
    void ignoresModelFormSubdivisionInsideDeterminateRepeatTable() throws Exception {
        var physicalTable = objectMapper.readTree("""
                {"id":"table","candidateId":"table","blockType":"COLUMN_TABLE","sheetId":"s1","range":"A4:K41",
                 "validationStatus":"VALID","geometryStatus":"VALID_GEOMETRY","confidence":0.94,"physicalConfirmed":true,
                 "structure":{"headerRange":"A4:K5","dataRange":"A6:K41","recordAxis":"COLUMN","repeatAxis":"COLUMN",
                              "recordHeight":36,"recordWidth":1,"recordStride":1,"physicalConfirmed":true}}
                """);
        var model = objectMapper.readTree("""
                {"structureProposals":[
                  {"proposalId":"form-subdivision","sheetId":"s1","type":"FORM_REGION","range":"A34:K41","confidence":0.95}
                ]}
                """);

        var result = resolver.resolve(objectMapper.createObjectNode(), List.of(physicalTable), model);

        assertThat(result.path("conflictGroups")).isEmpty();
        assertThat(result.path("regions")).singleElement().satisfies(region -> {
            assertThat(region.path("type").asText()).isEqualTo("COLUMN_TABLE");
            assertThat(region.path("range").asText()).isEqualTo("A4:K41");
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("structureConflict").asBoolean()).isFalse();
        });
        assertThat(result.path("modelDiagnostics")).anyMatch(item ->
                "MODEL_FORM_SUBDIVISION_IGNORED".equals(item.path("code").asText()));
    }
}
