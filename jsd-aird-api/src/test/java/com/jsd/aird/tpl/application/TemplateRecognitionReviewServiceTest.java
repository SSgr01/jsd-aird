package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TemplateRecognitionReviewServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsWordFieldsIndependentlyReviewableInsideTheSameRegion() throws Exception {
        var service = new TemplateRecognitionReviewService(
                mock(TemplateImportRepository.class), mock(TemplateRepository.class),
                mock(FileObjectRepository.class), mock(ObjectStorage.class), objectMapper,
                mock(RecognitionModelClient.class));
        var importJobId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var first = wordFieldSuggestion(UUID.randomUUID(), importJobId, runId,
                "word-project", "docx-cell-project", "SCALAR");
        var second = wordFieldSuggestion(UUID.randomUUID(), importJobId, runId,
                "word-applicant", "docx-cell-applicant", "SCALAR");
        var repeat = wordFieldSuggestion(UUID.randomUUID(), importJobId, runId,
                "word-material", "docx-cell-material", "REPEAT_FIELD");

        Method isStructural = TemplateRecognitionReviewService.class.getDeclaredMethod(
                "isStructuralCandidate", TemplateImportRepository.RecognitionSuggestionView.class);
        isStructural.setAccessible(true);
        Method reviewKey = TemplateRecognitionReviewService.class.getDeclaredMethod(
                "reviewKey", TemplateImportRepository.RecognitionSuggestionView.class);
        reviewKey.setAccessible(true);
        Method isChildField = TemplateRecognitionReviewService.class.getDeclaredMethod(
                "isChildField", com.fasterxml.jackson.databind.JsonNode.class);
        isChildField.setAccessible(true);

        assertThat(isStructural.invoke(service, first)).isEqualTo(false);
        assertThat(isStructural.invoke(service, repeat)).isEqualTo(false);
        assertThat(reviewKey.invoke(service, first)).isNotEqualTo(reviewKey.invoke(service, second));
        assertThat(reviewKey.invoke(service, second)).isNotEqualTo(reviewKey.invoke(service, repeat));
        assertThat(isChildField.invoke(service, first.payload())).isEqualTo(false);
        assertThat(isChildField.invoke(service, repeat.payload())).isEqualTo(true);

    }

    @Test
    void rendersOneRegionCardForSinglePhysicalAndModelAlternatives() throws Exception {
        var service = new TemplateRecognitionReviewService(
                mock(TemplateImportRepository.class), mock(TemplateRepository.class),
                mock(FileObjectRepository.class), mock(ObjectStorage.class), objectMapper,
                mock(RecognitionModelClient.class));
        var importJobId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var physical = structuralSuggestion(UUID.randomUUID(), importJobId, runId,
                "PHYSICAL", "FORM_REGION", "A23:J28", "footer-physical", "physical-option");
        var model = structuralSuggestion(UUID.randomUUID(), importJobId, runId,
                "MODEL", "FORM_REGION", "A23:J27", "footer-model", "model-option");

        Method buildRegionTree = TemplateRecognitionReviewService.class.getDeclaredMethod(
                "buildRegionTree", List.class, List.class, com.fasterxml.jackson.databind.JsonNode.class);
        buildRegionTree.setAccessible(true);
        var regions = (com.fasterxml.jackson.databind.node.ArrayNode) buildRegionTree.invoke(
                service, List.of(physical, model), List.of(), objectMapper.createObjectNode());

        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).path("range").asText()).isEqualTo("A23:J28");
        assertThat(regions.get(0).path("alternatives")).hasSize(2);
    }

    @Test
    void doesNotShowAutoConfirmedGeometryAsUserConfirmed() throws Exception {
        var service = new TemplateRecognitionReviewService(
                mock(TemplateImportRepository.class), mock(TemplateRepository.class),
                mock(FileObjectRepository.class), mock(ObjectStorage.class), objectMapper,
                mock(RecognitionModelClient.class));
        var importJobId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var id = UUID.randomUUID();
        var payload = objectMapper.createObjectNode()
                .put("kind", "FORM_REGION")
                .put("blockType", "FORM_REGION")
                .put("fieldName", "基本信息区域")
                .put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED")
                .put("candidateOnly", false)
                .put("reviewRequired", false)
                .put("regionId", "form-1")
                .put("blockId", "form-1");
        payload.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("range", "A1:H3").put("address", "A1:H3"));
        var suggestion = new TemplateImportRepository.RecognitionSuggestionView(
                id, importJobId, runId, "PHYSICAL", "FORM_REGION", payload, 0.9,
                objectMapper.createArrayNode(), "PENDING", "test", "test", "test", "", "", Instant.now());

        Method buildRegionTree = TemplateRecognitionReviewService.class.getDeclaredMethod(
                "buildRegionTree", List.class, List.class, com.fasterxml.jackson.databind.JsonNode.class);
        buildRegionTree.setAccessible(true);
        var regions = (com.fasterxml.jackson.databind.node.ArrayNode) buildRegionTree.invoke(
                service, List.of(suggestion), List.of(), objectMapper.createObjectNode());

        assertThat(regions).singleElement().satisfies(region -> {
            assertThat(region.path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("structureStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(region.path("status").asText()).isEqualTo("PENDING");
        });
    }

    @Test
    void hidesUnlocatedSemanticChildWhenItsCandidateRefMatchesPhysicalField() throws Exception {
        var service = new TemplateRecognitionReviewService(
                mock(TemplateImportRepository.class), mock(TemplateRepository.class),
                mock(FileObjectRepository.class), mock(ObjectStorage.class), objectMapper,
                mock(RecognitionModelClient.class));
        var importJobId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var physicalId = UUID.randomUUID();
        var orphanId = UUID.randomUUID();

        var physicalPayload = objectMapper.createObjectNode()
                .put("kind", "SCALAR").put("mappingKind", "REPEAT_FIELD")
                .put("suggestionLevel", "CHILD").put("regionId", "column-region")
                .put("candidateRef", "|physical-child|粘度|C5:H5");
        physicalPayload.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("valueRange", "C5:H5"));
        var orphanPayload = physicalPayload.deepCopy()
                .put("candidateRef", "|physical-child|粘度|C5:H5")
                .put("relationId", "model-child")
                .put("fieldName", "粘度");
        orphanPayload.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("address", "")
                .put("valueMode", "ARRAY_ROW"));

        var physical = new TemplateImportRepository.RecognitionSuggestionView(
                physicalId, importJobId, runId, "PHYSICAL", "SCALAR_FIELD", physicalPayload, 0.95,
                objectMapper.createArrayNode(), "PENDING", "test", "test", "test", "", "", Instant.now());
        var orphan = new TemplateImportRepository.RecognitionSuggestionView(
                orphanId, importJobId, runId, "MODEL", "TABLE_CHILD_FIELD", orphanPayload, 0.9,
                objectMapper.createArrayNode(), "PENDING", "test", "test", "test", "", "", Instant.now());

        Method duplicate = TemplateRecognitionReviewService.class.getDeclaredMethod(
                "isUnboundDuplicateOfPhysicalField",
                TemplateImportRepository.RecognitionSuggestionView.class, List.class);
        duplicate.setAccessible(true);

        assertThat(duplicate.invoke(service, orphan, List.of(physical, orphan))).isEqualTo(true);
        assertThat(duplicate.invoke(service, physical, List.of(physical, orphan))).isEqualTo(false);
    }

    @Test
    void confirmsACompositeAlternativeOnlyAfterOneBatchCoversEveryRegion() {
        var imports = mock(TemplateImportRepository.class);
        var templates = mock(TemplateRepository.class);
        var model = mock(RecognitionModelClient.class);
        var service = new TemplateRecognitionReviewService(
                imports, templates, mock(FileObjectRepository.class), mock(ObjectStorage.class), objectMapper, model);
        var organizationId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var importJobId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var physicalId = UUID.randomUUID();
        var formId = UUID.randomUUID();
        var rowsId = UUID.randomUUID();

        var workspace = mock(TemplateRepository.TemplateWorkspace.class);
        when(workspace.versionId()).thenReturn(versionId);
        when(workspace.format()).thenReturn(TemplateFormat.XLSX);
        when(workspace.schema()).thenReturn(objectMapper.createObjectNode());
        when(templates.findWorkspace(organizationId, versionId)).thenReturn(Optional.of(workspace));

        var run = mock(TemplateImportRepository.ImportJobView.class);
        when(run.id()).thenReturn(importJobId);
        when(run.recognitionRunId()).thenReturn(runId);
        when(run.recognitionRunStatus()).thenReturn("COMPLETED");
        when(run.status()).thenReturn("PARSED");
        when(run.sourceFileName()).thenReturn("测试.xlsx");
        when(run.structureSummary()).thenReturn(workbookFacts());
        when(run.result()).thenReturn(objectMapper.createObjectNode().put("recognitionStatus", "REVIEW_REQUIRED"));
        when(imports.findLatestForVersion(organizationId, versionId)).thenReturn(Optional.of(run));

        var physical = structuralSuggestion(physicalId, importJobId, runId, "PHYSICAL", "UNKNOWN", "A4:J6",
                "physical", "physical-option");
        var form = structuralSuggestion(formId, importJobId, runId, "MODEL", "FORM_REGION", "A1:J5",
                "form", "model-partition");
        var rows = structuralSuggestion(rowsId, importJobId, runId, "MODEL", "ROW_TABLE", "A6:J22",
                "rows", "model-partition");
        when(imports.listSuggestions(organizationId, importJobId)).thenReturn(List.of(physical, form, rows));
        when(imports.listQualityIssues(organizationId, importJobId)).thenReturn(List.of());
        when(imports.decideSuggestion(eq(organizationId), eq(runId), any(), anyString(), eq(actorId)))
                .thenAnswer(invocation -> Optional.ofNullable(
                        List.of(physical, form, rows).stream()
                                .filter(item -> item.id().equals(invocation.getArgument(2)))
                                .findFirst().orElse(null)));
        when(model.isConfigured()).thenReturn(true);
        when(model.recognize(any())).thenReturn(new RecognitionModelClient.RecognitionBatch(
                List.of(
                        semanticSuggestion("SCALAR_FIELD", "form"),
                        semanticSuggestion("TABLE_CHILD_FIELD", "rows")
                ), List.of(), "test", "test", "test", "request", "response"));

        service.applyActions(organizationId, actorId, versionId, List.of(
                new TemplateRecognitionReviewService.RecognitionAction(
                        physicalId, "CONFIRM", null, "model-partition")));

        var request = ArgumentCaptor.forClass(RecognitionModelClient.RecognitionRequest.class);
        verify(model).recognize(request.capture());
        assertThat(request.getValue().callPhase()).isEqualTo("REGION_FIELDS");
        assertThat(request.getValue().structureSummary().path("semanticRegions")).hasSize(2);
        assertThat(request.getValue().structureSummary().path("semanticRegions"))
                .extracting(region -> region.path("regionId").asText())
                .containsExactly("form", "rows");

        verify(imports).decideSuggestion(organizationId, runId, physicalId, "REJECTED_BY_RESOLUTION", actorId);
        verify(imports).decideSuggestion(organizationId, runId, formId, "ACCEPTED", actorId);
        verify(imports).decideSuggestion(organizationId, runId, rowsId, "ACCEPTED", actorId);
        verify(imports).markStructureResolved(organizationId, runId, formId);
        verify(imports).markStructureResolved(organizationId, runId, rowsId);
        verify(imports, never()).markStructureResolved(organizationId, runId, physicalId);
        verify(imports).supersedeStructureGeneration(
                eq(organizationId), eq(runId), eq(List.of(formId, rowsId)), any(), anyString(), eq(actorId));
        verify(imports).appendModelSuggestions(eq(importJobId), eq(runId), any());
    }

    @Test
    void leavesCompositeAlternativeUntouchedWhenAnyRegionHasNoSemanticResult() {
        var imports = mock(TemplateImportRepository.class);
        var templates = mock(TemplateRepository.class);
        var model = mock(RecognitionModelClient.class);
        var service = new TemplateRecognitionReviewService(
                imports, templates, mock(FileObjectRepository.class), mock(ObjectStorage.class), objectMapper, model);
        var organizationId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var importJobId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var physicalId = UUID.randomUUID();
        var formId = UUID.randomUUID();
        var rowsId = UUID.randomUUID();

        var workspace = mock(TemplateRepository.TemplateWorkspace.class);
        when(workspace.versionId()).thenReturn(versionId);
        when(workspace.format()).thenReturn(TemplateFormat.XLSX);
        when(workspace.schema()).thenReturn(objectMapper.createObjectNode());
        when(templates.findWorkspace(organizationId, versionId)).thenReturn(Optional.of(workspace));

        var run = mock(TemplateImportRepository.ImportJobView.class);
        when(run.id()).thenReturn(importJobId);
        when(run.recognitionRunId()).thenReturn(runId);
        when(run.recognitionRunStatus()).thenReturn("COMPLETED");
        when(run.status()).thenReturn("PARSED");
        when(run.sourceFileName()).thenReturn("测试.xlsx");
        when(run.structureSummary()).thenReturn(workbookFacts());
        when(run.result()).thenReturn(objectMapper.createObjectNode().put("recognitionStatus", "REVIEW_REQUIRED"));
        when(imports.findLatestForVersion(organizationId, versionId)).thenReturn(Optional.of(run));

        var physical = structuralSuggestion(physicalId, importJobId, runId, "PHYSICAL", "UNKNOWN", "A4:J6",
                "physical", "physical-option");
        var form = structuralSuggestion(formId, importJobId, runId, "MODEL", "FORM_REGION", "A1:J5",
                "form", "model-partition");
        var rows = structuralSuggestion(rowsId, importJobId, runId, "MODEL", "ROW_TABLE", "A6:J22",
                "rows", "model-partition");
        when(imports.listSuggestions(organizationId, importJobId)).thenReturn(List.of(physical, form, rows));
        when(imports.listQualityIssues(organizationId, importJobId)).thenReturn(List.of());
        when(model.isConfigured()).thenReturn(true);
        when(model.recognize(any())).thenReturn(new RecognitionModelClient.RecognitionBatch(
                List.of(semanticSuggestion("SCALAR_FIELD", "form")),
                List.of(), "test", "test", "test", "request", "response"));

        assertThatThrownBy(() -> service.applyActions(organizationId, actorId, versionId, List.of(
                new TemplateRecognitionReviewService.RecognitionAction(
                        physicalId, "CONFIRM", null, "model-partition"))))
                .hasMessageContaining("rows");

        verify(imports, never()).appendModelSuggestions(any(), any(), any());
        verify(imports, never()).supersedeStructureGeneration(any(), any(), any(), any(), anyString(), any());
        verify(imports, never()).decideSuggestion(any(), any(), any(), anyString(), any());
        verify(imports, never()).markStructureResolved(any(), any(), any());
    }

    private TemplateImportRepository.RecognitionSuggestionView structuralSuggestion(
            UUID id, UUID importJobId, UUID runId, String source, String kind, String range,
            String candidateRef, String alternativeId
    ) {
        var payload = objectMapper.createObjectNode()
                .put("kind", kind)
                .put("blockType", kind)
                .put("fieldName", kind)
                .put("candidateRef", candidateRef)
                .put("resolutionGroupId", "structure-conflict-1")
                .put("resolutionAlternativeId", alternativeId)
                .put("alternativeRole", source)
                .put("candidateOnly", true)
                .put("reviewRequired", true)
                .put("structureConflict", true)
                .put("canonicalStatus", "PROVISIONAL")
                .put("structureStatus", "CONFLICT");
        payload.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("range", range).put("address", range));
        if ("ROW_TABLE".equals(kind)) {
            payload.put("headerRange", "A6:J6").put("dataRange", "A7:J22").put("recordAxis", "ROW");
        }
        return new TemplateImportRepository.RecognitionSuggestionView(
                id, importJobId, runId, source, kind, payload, 0.9,
                objectMapper.createArrayNode(), "PENDING", "test", "test", "test", "", "", Instant.now());
    }

    private TemplateImportRepository.RecognitionSuggestionView wordFieldSuggestion(
            UUID id, UUID importJobId, UUID runId, String relationId, String nodeId, String mappingKind
    ) {
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR")
                .put("blockType", "REPEAT_FIELD".equals(mappingKind) ? "ROW_TABLE" : "FORM_REGION")
                .put("role", "FIELD")
                .put("mappingKind", mappingKind)
                .put("relationId", relationId)
                .put("fieldId", UUID.randomUUID().toString())
                .put("fieldName", relationId)
                .put("regionId", "word-region")
                .put("suggestionLevel", "REPEAT_FIELD".equals(mappingKind) ? "CHILD" : "SCALAR");
        payload.set("locator", objectMapper.createObjectNode()
                .put("locatorType", "DOCX_TABLE_CELL")
                .put("nodeId", nodeId)
                .put("valueAnchor", nodeId));
        return new TemplateImportRepository.RecognitionSuggestionView(
                id, importJobId, runId, "RULE", "SCALAR_FIELD", payload, 0.95,
                objectMapper.createArrayNode(), "PENDING", "test", "test", "test", "", "", Instant.now());
    }

    private RecognitionModelClient.ModelSuggestion semanticSuggestion(String type, String regionId) {
        // The semantic compiler replaces regionId with a stable block id while
        // retaining the selected proposal identity in candidateRef.
        ObjectNode payload = objectMapper.createObjectNode()
                .put("regionId", "stable-block-" + regionId)
                .put("blockId", "stable-block-" + regionId)
                .put("candidateRef", regionId);
        if ("SCALAR_FIELD".equals(type)) {
            payload.put("kind", "SCALAR").put("role", "FIELD").put("fieldName", "测试字段")
                    .put("formExpectedFieldCount", 1);
            payload.set("locator", objectMapper.createObjectNode()
                    .put("sheetId", "sheet-1").put("labelRange", "A1").put("valueRange", "B1"));
        }
        if ("TABLE_CHILD_FIELD".equals(type)) {
            payload.put("kind", "SCALAR").put("role", "FIELD").put("fieldName", "明细字段");
            payload.set("locator", objectMapper.createObjectNode()
                    .put("sheetId", "sheet-1").put("labelRange", "A6").put("valueRange", "A7:A22"));
        }
        return new RecognitionModelClient.ModelSuggestion(type, payload, 0.9, objectMapper.createArrayNode());
    }

    private ObjectNode workbookFacts() {
        var facts = objectMapper.createObjectNode().put("structureVersion", 3).put("parserVersion", "test");
        var sheet = facts.putArray("sheets").addObject()
                .put("id", "sheet-1").put("name", "Sheet1").put("usedRange", "A1:J22");
        sheet.putArray("semanticCells");
        sheet.putArray("mergedRanges");
        sheet.putArray("borderSegments");
        sheet.putArray("layoutSpans");
        sheet.putArray("rowProfiles");
        sheet.putArray("columnProfiles");
        sheet.putArray("dataValidationRules");
        sheet.putArray("nativeTables");
        return facts;
    }
}
