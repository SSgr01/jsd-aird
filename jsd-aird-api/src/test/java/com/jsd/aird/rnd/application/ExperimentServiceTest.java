package com.jsd.aird.rnd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.Detail;
import com.jsd.aird.rnd.domain.ExperimentModels.Summary;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class ExperimentServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ExperimentRepository repository = mock(ExperimentRepository.class);
    private final FileStorageFacade files = mock(FileStorageFacade.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExperimentService service = new ExperimentService(
            repository, mapper, files, new ExperimentEditModelNormalizer(mapper));

    @BeforeEach
    void setActor() {
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "creator"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void keepsCompactResultLayoutForContentOnlyImport() throws Exception {
        when(repository.create(any())).thenAnswer(invocation -> summary(invocation.getArgument(0)));
        var snapshot = mapper.readTree("""
                {"sheets":{"source":{"name":"合成实验","custom":{"labTemplate":{
                  "version":1,"templateCode":"SYNTHESIS","presentation":"RESULT","fields":{}}},
                  "cellData":{"3":{"1":{"v":23.0}}}}}}
                """);
        var model = mapper.createObjectNode().put("importProcessingMode", "CONTENT_ONLY");
        model.set("documentSnapshot", snapshot.deepCopy());
        service.create(new ExperimentService.CreateCommand(
                null, "水性丙烯酸乳液固含量记录", null, null, "EXCEL_IMPORT",
                null, null, null, "creator", LocalDate.of(2026, 11, 18),
                null, null, null, snapshot, model));
        var captor = ArgumentCaptor.forClass(ExperimentRepository.Create.class);
        verify(repository).create(captor.capture());
        assertThat(captor.getValue().editModel().path("documentSnapshot")).isEqualTo(snapshot);
        assertThat(captor.getValue().editModel().path("importProcessingMode").asText()).isEqualTo("CONTENT_ONLY");
        assertThat(captor.getValue().editModel().has("importDataReview")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"EXCEL_IMPORT,false", "EXCEL_IMPORT,true", "OCR_IMPORT,false", "OCR_IMPORT,true"})
    void contentOnlyImportsCanSaveSubmitAndPublishWithoutDataConfirmation(String sourceType, boolean legacyField) {
        var id = UUID.randomUUID();
        var version = UUID.randomUUID();
        java.util.function.BiFunction<ExperimentStatus, Long, Summary> summaryFor = (status, revision) ->
                new Summary(id, "EXP-CONTENT", "原始实验记录", null, null, sourceType, status,
                        null, null, null, null, null, null, "creator", LocalDate.of(2026, 11, 24), 1, revision, Instant.now());
        var model = mapper.createObjectNode().put("importProcessingMode", "CONTENT_ONLY");
        model.withObject("/documentSnapshot/sheets/s/cellData/0/0").put("v", "原始实验内容");
        model.withObject("importDataReview").put("confirmed", false);
        var fields = model.putArray("recognizedFields");
        if (legacyField) fields.addObject().put("name", "旧识别字段").put("confirmed", false);
        model.putArray("recognizedRegions").addObject().put("confirmed", false);
        var state = new java.util.concurrent.atomic.AtomicReference<>(new Detail(summaryFor.apply(ExperimentStatus.DRAFT, 1L),
                version, null, null, null, mapper.createObjectNode(), model, List.of(), List.of()));
        when(repository.detail(ORGANIZATION_ID, id)).thenAnswer(invocation -> Optional.of(state.get()));
        when(repository.saveDraft(any(), any(), any(Long.class), any(), any(), any())).thenAnswer(invocation -> {
            var draft = invocation.<ExperimentRepository.Draft>getArgument(3);
            var next = new Detail(summaryFor.apply(ExperimentStatus.DRAFT, 2L), version, null, null, null,
                    mapper.createObjectNode(), draft.editModel(), List.of(), List.of());
            state.set(next);
            return next;
        });
        when(repository.transition(any(), any(), any(Long.class), any(), any(), any(), any())).thenAnswer(invocation -> {
            var current = state.get();
            var next = new Detail(summaryFor.apply(invocation.getArgument(3), current.summary().revision() + 1),
                    version, null, null, null, mapper.createObjectNode(), current.editModel(), List.of(), List.of());
            state.set(next);
            return next;
        });
        var edits = model.deepCopy();
        edits.remove(List.of("importProcessingMode", "importDataReview"));
        edits.withObject("/documentSnapshot/sheets/s/cellData/0/0").put("v", "修正后的实验内容");
        var saved = service.save(id, 1, new ExperimentService.DraftCommand("EXP-CONTENT", "原始实验记录",
                null, null, null, null, null, "creator", LocalDate.of(2026, 11, 24), null, null,
                mapper.createObjectNode(), edits));
        assertThat(saved.editModel().path("importProcessingMode").asText()).isEqualTo("CONTENT_ONLY");
        assertThat(saved.editModel().has("importDataReview")).isFalse();
        assertThat(saved.editModel().at("/documentSnapshot/sheets/s/cellData/0/0/v").asText()).isEqualTo("修正后的实验内容");
        assertThat(service.transition(id, 2, ExperimentStatus.PENDING_REVIEW, null).summary().status())
                .isEqualTo(ExperimentStatus.PENDING_REVIEW);
        assertThat(service.transition(id, 3, ExperimentStatus.COMPLETED, null).summary().status())
                .isEqualTo(ExperimentStatus.COMPLETED);
    }

    @Test
    void createsACompleteManualDraftContractByDefault() {
        when(repository.create(any())).thenAnswer(invocation -> summary(invocation.getArgument(0)));

        var result = service.create(new ExperimentService.CreateCommand(
                null, "  UV附着力实验  ", null, null, null,
                null, null, null, null, null, null, null, null, null, null
        ));

        var captor = ArgumentCaptor.forClass(ExperimentRepository.Create.class);
        verify(repository).create(captor.capture());
        var command = captor.getValue();
        assertThat(result.title()).isEqualTo("UV附着力实验");
        assertThat(command.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(command.actorId()).isEqualTo(USER_ID);
        assertThat(command.actorName()).isEqualTo("creator");
        assertThat(command.ownerName()).isEqualTo("creator");
        assertThat(command.sourceType()).isEqualTo("MANUAL");
        assertThat(command.status()).isEqualTo(ExperimentStatus.DRAFT);
        assertThat(command.experimentNo()).matches("EXP-\\d{4}-[0-9A-F]{8}");
        assertThat(command.templateSnapshot().isObject()).isTrue();
        assertThat(command.editModel().path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(command.editModel().path("formulaItems").isArray()).isTrue();
        assertThat(command.editModel().path("processSteps").isArray()).isTrue();
        assertThat(command.editModel().path("testResults").isArray()).isTrue();
    }

    @Test
    void normalizesExcelImportWithoutInventingADataImportSource() {
        when(repository.create(any())).thenAnswer(invocation -> summary(invocation.getArgument(0)));

        service.create(new ExperimentService.CreateCommand(
                " EXP-001 ", "导入实验", null, null, " excel_import ",
                null, null, null, " 张三 ", LocalDate.of(2026, 8, 25),
                null, null, null, mapper.createObjectNode(), mapper.createObjectNode()
        ));

        var captor = ArgumentCaptor.forClass(ExperimentRepository.Create.class);
        verify(repository).create(captor.capture());
        assertThat(captor.getValue().sourceType()).isEqualTo("EXCEL_IMPORT");
        assertThat(captor.getValue().experimentNo()).isEqualTo("EXP-001");
        assertThat(captor.getValue().ownerName()).isEqualTo("张三");
    }

    @Test
    void rejectsUnsupportedSourceBeforeCallingTheDatabase() {
        assertThatThrownBy(() -> service.create(new ExperimentService.CreateCommand(
                null, "导入实验", null, null, "DATA_IMPORT",
                null, null, null, null, null, null, null, null, null, null
        )))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("EXCEL_IMPORT");
        verifyNoInteractions(repository);
    }

    @Test
    void normalizesSearchFiltersAndPagination() {
        when(repository.search(any(), any())).thenReturn(List.of());
        when(repository.count(any(), any())).thenReturn(205L);

        var page = service.search(new ExperimentRepository.Search(
                "  UV  ", " completed ", " excel_import ", null, null, null,
                null, null, null, null, 0, 500
        ));

        var captor = ArgumentCaptor.forClass(ExperimentRepository.Search.class);
        verify(repository).search(org.mockito.ArgumentMatchers.eq(ORGANIZATION_ID), captor.capture());
        var query = captor.getValue();
        assertThat(query.keyword()).isEqualTo("UV");
        assertThat(query.status()).isEqualTo("COMPLETED");
        assertThat(query.sourceType()).isEqualTo("EXCEL_IMPORT");
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(100);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void rejectsNonObjectExperimentModels() {
        assertThatThrownBy(() -> service.create(new ExperimentService.CreateCommand(
                null, "非法实验", null, null, "MANUAL",
                null, null, null, null, null, null, null, null,
                mapper.createObjectNode(), mapper.createArrayNode()
        )))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("editModel");
        verifyNoInteractions(repository);
    }

    @Test
    void returnsANotFoundErrorForAnUnknownExperiment() {
        var id = UUID.randomUUID();
        when(repository.detail(ORGANIZATION_ID, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(id))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.NOT_FOUND))
                .hasMessage("实验不存在");
    }

    @Test
    void confirmsImportedWorkbookAndInvalidatesConfirmationWhenCellsChange() {
        var id = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var workbook = mapper.createObjectNode();
        workbook.withObject("sheets").withObject("sheet-1").withObject("cellData")
                .withObject("0").withObject("0").put("v", "result");
        var model = mapper.createObjectNode();
        model.set("documentSnapshot", workbook);
        var summary = new Summary(id, "EXP-IMPORT-1", "导入实验", null, null,
                "EXCEL_IMPORT", ExperimentStatus.DRAFT, null, null, null, null, null, null,
                "creator", LocalDate.of(2026, 8, 25), 1, 1, Instant.now());
        var current = new Detail(summary, versionId, null, null, null,
                mapper.createObjectNode(), model, List.of(), List.of());
        when(repository.detail(ORGANIZATION_ID, id)).thenReturn(Optional.of(current));
        var importService = new ExperimentService(repository, mapper, files,
                new ExperimentEditModelNormalizer(mapper), null, null, new JsonCanonicalizer(mapper));

        assertThatThrownBy(() -> importService.transition(id, 1, ExperimentStatus.PENDING_REVIEW, null))
                .hasMessageContaining("请先确认本次导入的实验数据");

        when(repository.saveDraft(any(), any(), any(Long.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    var draft = invocation.<ExperimentRepository.Draft>getArgument(3);
                    var savedSummary = new Summary(id, "EXP-IMPORT-1", "导入实验", null, null,
                            "EXCEL_IMPORT", ExperimentStatus.DRAFT, null, null, null, null, null, null,
                            "creator", LocalDate.of(2026, 8, 25), 1, 2, Instant.now());
                    return new Detail(savedSummary, versionId, null, null, null,
                            mapper.createObjectNode(), draft.editModel(), List.of(), List.of());
                });
        var confirmed = importService.confirmTemplateData(id, 1);
        assertThat(confirmed.editModel().path("importDataReview").path("confirmed").asBoolean()).isTrue();
        assertThat(confirmed.editModel().path("importDataReview").path("confirmedRevision").asLong()).isEqualTo(2);

        var changed = confirmed.editModel().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("documentSnapshot")
                .path("sheets").path("sheet-1").path("cellData").path("0").path("0"))
                .put("v", "updated");
        when(repository.detail(ORGANIZATION_ID, id)).thenReturn(Optional.of(confirmed));
        importService.save(id, 2, new ExperimentService.DraftCommand(
                "EXP-IMPORT-1", "导入实验", null, null, null, null, null,
                "creator", LocalDate.of(2026, 8, 25), null, null,
                mapper.createObjectNode(), changed));
        var captor = ArgumentCaptor.forClass(ExperimentRepository.Draft.class);
        verify(repository, org.mockito.Mockito.times(2)).saveDraft(any(), any(), any(Long.class),
                captor.capture(), any(), any());
        assertThat(captor.getAllValues().get(1).editModel().path("importDataReview").path("confirmed").asBoolean())
                .isFalse();
    }

    @Test
    void keepsLegacyReviewAfterPublicationAndAdvancesItsEffectiveRevision() {
        var id = UUID.randomUUID();
        var snapshot = mapper.createObjectNode();
        snapshot.withObject("/sheets/s/cellData/0/0").put("v", "750g");
        var model = mapper.createObjectNode();
        model.set("documentSnapshot", snapshot);
        var canonicalizer = new JsonCanonicalizer(mapper);
        model.withObject("importDataReview").put("confirmed", true).put("confirmedRevision", 2)
                .put("dataHash", canonicalizer.hash(snapshot));
        var summary = new Summary(id, "EXP-REVIEW", "硬度记录", null, null,
                "EXCEL_IMPORT", ExperimentStatus.COMPLETED, null, null, null, null, null, null,
                "creator", LocalDate.of(2026, 11, 20), 1, 4, Instant.now());
        when(repository.detail(ORGANIZATION_ID, id)).thenReturn(Optional.of(new Detail(summary,
                UUID.randomUUID(), null, null, null, mapper.createObjectNode(), model, List.of(), List.of())));
        var reviewService = new ExperimentService(repository, mapper, files,
                new ExperimentEditModelNormalizer(mapper), null, null, canonicalizer);
        var detail = reviewService.detail(id);
        assertThat(detail.editModel().at("/importDataReview/confirmed").asBoolean()).isTrue();
        assertThat(detail.editModel().at("/importDataReview/confirmedRevision").asLong()).isEqualTo(4);
        snapshot.withObject("/sheets/s/cellData/0/0").put("v", "800g");
        assertThat(reviewService.detail(id).editModel().at("/importDataReview/confirmed").asBoolean()).isFalse();
    }

    @Test
    void keepsReviewAcrossCanvasSaveSubmissionAndPublicationWithoutASecondConfirmation() {
        var id = UUID.randomUUID(); var version = UUID.randomUUID();
        java.util.function.BiFunction<ExperimentStatus, Long, Summary> summaryFor = (status, revision) ->
                new Summary(id, "EXP-FLOW", "材料性能记录", null, null, "EXCEL_IMPORT", status,
                        null, null, null, null, null, null, "creator", LocalDate.of(2026, 11, 20), 1, revision, Instant.now());
        var model = mapper.createObjectNode();
        model.withObject("/documentSnapshot/sheets/s/cellData/0/0").put("v", "750g");
        var state = new java.util.concurrent.atomic.AtomicReference<>(new Detail(summaryFor.apply(ExperimentStatus.DRAFT, 1L),
                version, null, null, null, mapper.createObjectNode(), model, List.of(), List.of()));
        when(repository.detail(ORGANIZATION_ID, id)).thenAnswer(invocation -> Optional.of(state.get()));
        when(repository.saveDraft(any(), any(), any(Long.class), any(), any(), any())).thenAnswer(invocation -> {
            var draft = invocation.<ExperimentRepository.Draft>getArgument(3);
            var current = state.get();
            var next = new Detail(summaryFor.apply(current.summary().status(), current.summary().revision() + 1),
                    version, null, null, null, mapper.createObjectNode(), draft.editModel(), List.of(), List.of());
            state.set(next); return next;
        });
        when(repository.transition(any(), any(), any(Long.class), any(), any(), any(), any())).thenAnswer(invocation -> {
            var current = state.get();
            var next = new Detail(summaryFor.apply(invocation.getArgument(3), current.summary().revision() + 1),
                    version, null, null, null, mapper.createObjectNode(), current.editModel(), List.of(), List.of());
            state.set(next); return next;
        });
        var reviewService = new ExperimentService(repository, mapper, files,
                new ExperimentEditModelNormalizer(mapper), null, null, new JsonCanonicalizer(mapper));
        var confirmed = reviewService.confirmTemplateData(id, 1);
        var layout = (com.fasterxml.jackson.databind.node.ObjectNode) confirmed.editModel().deepCopy();
        layout.withObject("/documentSnapshot/sheets/s").put("zoomRatio", 1.5);
        layout.withObject("/documentSnapshot/sheets/s/freeze").put("ySplit", 0);
        var saved = reviewService.save(id, 2, new ExperimentService.DraftCommand("EXP-FLOW", "材料性能记录",
                null, null, null, null, null, "creator", LocalDate.of(2026, 11, 20), null, null,
                mapper.createObjectNode(), layout));
        assertThat(saved.editModel().at("/importDataReview/confirmed").asBoolean()).isTrue();
        var submitted = reviewService.transition(id, 3, ExperimentStatus.PENDING_REVIEW, null);
        assertThat(submitted.editModel().at("/importDataReview/confirmedRevision").asLong()).isEqualTo(4);
        var published = reviewService.transition(id, 4, ExperimentStatus.COMPLETED, null);
        assertThat(published.editModel().at("/importDataReview/confirmed").asBoolean()).isTrue();
        assertThat(published.editModel().at("/importDataReview/confirmedRevision").asLong()).isEqualTo(5);
        assertThat(reviewService.detail(id).editModel().at("/importDataReview/confirmed").asBoolean()).isTrue();
    }

    @Test
    void templateImportRequiresItsBoundFieldValueBeforeConfirmation() {
        var id = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var templateVersionId = UUID.randomUUID();
        var workbook = mapper.createObjectNode();
        workbook.withObject("sheets").withObject("sheet-1").withObject("cellData")
                .withObject("1").withObject("1").put("v", "");
        workbook.path("sheets").path("sheet-1").withObject("cellData")
                .withObject("0").withObject("0").put("v", "实验数据");
        var model = mapper.createObjectNode().put("sourceImportJobId", UUID.randomUUID().toString());
        model.set("documentSnapshot", workbook);
        var summary = new Summary(id, "EXP-TEMPLATE-IMPORT", "模板导入实验", null, null,
                "EXCEL_IMPORT", ExperimentStatus.DRAFT, null, null, null, null, null, null,
                "creator", LocalDate.of(2026, 8, 25), 1, 1, Instant.now());
        var current = new Detail(summary, versionId, null, templateVersionId, null,
                mapper.createObjectNode(), model, List.of(), List.of());
        when(repository.detail(ORGANIZATION_ID, id)).thenReturn(Optional.of(current));
        var templates = mock(TemplateDataImportFacade.class);
        var field = new TemplateDataImportFacade.FieldDefinition(
                "VISCOSITY", "粘度", "NUMBER", null, true, false, List.of(), "/viscosity");
        var binding = new TemplateDataImportFacade.ImportBinding(
                "viscosity-binding", "VISCOSITY", "/viscosity", "SCALAR", null, null,
                1, 1, 1, mapper.createObjectNode(), mapper.createObjectNode()
                        .put("sheetId", "sheet-1").put("address", "B2"),
                true, false, true, "SOURCE", "NUMBER", null);
        when(templates.getVersion(ORGANIZATION_ID, templateVersionId)).thenReturn(
                new TemplateDataImportFacade.DataTemplateDefinition(null, templateVersionId, "V9", "实验模板",
                        null, 1, "XLSX", mapper.createObjectNode(), mapper.createObjectNode(), List.of(field)));
        when(templates.getBindings(ORGANIZATION_ID, templateVersionId)).thenReturn(List.of(binding));
        var importService = new ExperimentService(repository, mapper, files,
                new ExperimentEditModelNormalizer(mapper), null, templates, new JsonCanonicalizer(mapper));

        assertThat(importService.templateDataReview(id).fields()).containsExactly(field);
        assertThatThrownBy(() -> importService.confirmTemplateData(id, 1))
                .hasMessageContaining("必填数据尚未填写：粘度");

        ((com.fasterxml.jackson.databind.node.ObjectNode) workbook.path("sheets").path("sheet-1")
                .path("cellData").path("1").path("1")).put("v", "1350");
        when(repository.saveDraft(any(), any(), any(Long.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    var draft = invocation.<ExperimentRepository.Draft>getArgument(3);
                    var savedSummary = new Summary(id, "EXP-TEMPLATE-IMPORT", "模板导入实验", null, null,
                            "EXCEL_IMPORT", ExperimentStatus.DRAFT, null, null, null, null, null, null,
                            "creator", LocalDate.of(2026, 8, 25), 1, 2, Instant.now());
                    return new Detail(savedSummary, versionId, null, templateVersionId, null,
                            mapper.createObjectNode(), draft.editModel(), List.of(), List.of());
                });
        var confirmed = importService.confirmTemplateData(id, 1);
        assertThat(confirmed.editModel().path("importDataReview").path("confirmed").asBoolean()).isTrue();
    }

    @Test
    void confirmsARecordedHistoricalMetadataGapWithoutFillingTheSourceCell() {
        var id=UUID.randomUUID();var version=UUID.randomUUID();var template=UUID.randomUUID();
        var workbook=mapper.createObjectNode();
        workbook.withObject("/sheets/s/cellData/0/0").put("v","原实验数据");
        workbook.withObject("/sheets/s/cellData/1/1").put("v","");
        var model=mapper.createObjectNode().put("sourceImportJobId",UUID.randomUUID().toString());
        model.set("documentSnapshot",workbook);
        var summary=new Summary(id,"EXP-HISTORY","历史实验",null,null,"EXCEL_IMPORT",ExperimentStatus.DRAFT,null,null,null,null,null,null,null,null,1,1,Instant.now());
        var current=new Detail(summary,version,null,template,null,mapper.createObjectNode(),model,List.of(),List.of());
        when(repository.detail(ORGANIZATION_ID,id)).thenReturn(Optional.of(current));
        when(repository.saveDraft(any(),any(),any(Long.class),any(),any(),any())).thenAnswer(invocation->{
            var draft=invocation.<ExperimentRepository.Draft>getArgument(3);
            return new Detail(summary,version,null,template,null,mapper.createObjectNode(),draft.editModel(),List.of(),List.of());
        });
        var templates=mock(TemplateDataImportFacade.class);
        var field=new TemplateDataImportFacade.FieldDefinition("basic.date","日期","DATE",null,true,false,List.of(),"/fields/basic.date");
        var binding=new TemplateDataImportFacade.ImportBinding("date","basic.date","/fields/basic.date","SCALAR",null,null,1,1,1,
                mapper.createObjectNode(),mapper.createObjectNode().put("sheetId","s").put("address","B2"),true,false,true,"SOURCE","DATE",null);
        when(templates.getVersion(ORGANIZATION_ID,template)).thenReturn(new TemplateDataImportFacade.DataTemplateDefinition(null,template,"V9","历史实验",null,1,"XLSX",mapper.createObjectNode(),mapper.createObjectNode(),List.of(field)));
        when(templates.getBindings(ORGANIZATION_ID,template)).thenReturn(List.of(binding));
        var imported=new ExperimentService(repository,mapper,files,new ExperimentEditModelNormalizer(mapper),null,templates,new JsonCanonicalizer(mapper));
        assertThatThrownBy(()->imported.confirmTemplateData(id,1)).hasMessageContaining("必填数据尚未填写");
        model.putArray("sourceMetadataGaps").addObject().put("semantic","EXPERIMENT_DATE").put("status","NOT_RECORDED").put("originalValue","").put("sheetId","s").put("address","B2");
        var confirmed=imported.confirmTemplateData(id,1);
        assertThat(confirmed.editModel().at("/documentSnapshot/sheets/s/cellData/1/1/v").asText()).isEmpty();
        assertThat(confirmed.editModel().path("sourceMetadataGaps")).hasSize(1);
        assertThat(confirmed.editModel().at("/importDataReview/confirmed").asBoolean()).isTrue();
    }

    @Test
    void legacyTemplateDraftWithoutReviewMarkerStillRequiresFieldConfirmation() {
        var id = UUID.randomUUID();
        var templateVersionId = UUID.randomUUID();
        var summary = new Summary(id, "EXP-TEMPLATE-1", "模板实验", null, null,
                "TEMPLATE", ExperimentStatus.DRAFT, null, null, null, null, null, null,
                "creator", LocalDate.of(2026, 8, 25), 1, 1, Instant.now());
        var detail = new Detail(summary, UUID.randomUUID(), null, templateVersionId, null,
                mapper.createObjectNode(), mapper.createObjectNode(), List.of(), List.of());
        when(repository.detail(ORGANIZATION_ID, id)).thenReturn(Optional.of(detail));
        var templates = mock(TemplateDataImportFacade.class);
        when(templates.getVersion(ORGANIZATION_ID, templateVersionId)).thenReturn(
                new TemplateDataImportFacade.DataTemplateDefinition(null, templateVersionId, "V9", "实验模板",
                        null, 1, "XLSX", mapper.createObjectNode(), mapper.createObjectNode(),
                        List.of(new TemplateDataImportFacade.FieldDefinition(
                                "VISCOSITY", "粘度", "NUMBER", null, true, false, List.of(), "/viscosity"))));
        var templateService = new ExperimentService(repository, mapper, files,
                new ExperimentEditModelNormalizer(mapper), null, templates, new JsonCanonicalizer(mapper));

        assertThat(templateService.templateDataReview(id).fields()).hasSize(1);
        assertThatThrownBy(() -> templateService.transition(id, 1, ExperimentStatus.PENDING_REVIEW, null))
                .hasMessageContaining("请先确认本次实验数据");
    }

    @Test
    void savesImportedDraftWithTheExplicitOwnerAndExperimentDate() {
        var experimentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var summary = new Summary(
                experimentId, "EXP-IMPORT-001", "应用测试报告", null, null,
                "EXCEL_IMPORT", ExperimentStatus.DRAFT, null, null, null, null, null, null,
                null, null, 1, 1, Instant.now()
        );
        when(repository.detail(ORGANIZATION_ID, experimentId)).thenReturn(Optional.of(
                new Detail(summary, versionId, null, null, null, mapper.createObjectNode(),
                        mapper.createObjectNode(), List.of(), List.of())));
        when(repository.saveDraft(
                org.mockito.ArgumentMatchers.eq(ORGANIZATION_ID),
                org.mockito.ArgumentMatchers.eq(experimentId),
                org.mockito.ArgumentMatchers.eq(1L),
                any(),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq("creator")
        )).thenAnswer(invocation -> {
            var draft = invocation.<ExperimentRepository.Draft>getArgument(3);
            return new Detail(summary, versionId, null, null, null, mapper.createObjectNode(), draft.editModel(),
                    List.of(), List.of());
        });

        when(repository.detail(ORGANIZATION_ID, experimentId)).thenReturn(Optional.of(
                new Detail(summary, versionId, null, null, null, mapper.createObjectNode(), mapper.createObjectNode(), List.of(), List.of())));
        service.save(experimentId, 1, new ExperimentService.DraftCommand(
                "EXP-IMPORT-001", "应用测试报告", null, null,
                null, null, null, "陈明", java.time.LocalDate.of(2026, 10, 18), null, null,
                mapper.createObjectNode(), mapper.createObjectNode()
        ));

        var captor = ArgumentCaptor.forClass(ExperimentRepository.Draft.class);
        verify(repository).saveDraft(
                org.mockito.ArgumentMatchers.eq(ORGANIZATION_ID),
                org.mockito.ArgumentMatchers.eq(experimentId),
                org.mockito.ArgumentMatchers.eq(1L),
                captor.capture(),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq("creator")
        );
        assertThat(captor.getValue().ownerName()).isEqualTo("陈明");
        assertThat(captor.getValue().experimentDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 18));
    }

    @Test
    void restoresOnlyAuditedUnchangedImportedDrafts() throws Exception {
        var id=UUID.randomUUID();var version=UUID.randomUUID();
        var model=mapper.createObjectNode().put("sourceImportJobId",UUID.randomUUID().toString());
        model.set("documentSnapshot",mapper.readTree("{\"sheets\":{\"s\":{\"cellData\":{\"0\":{\"0\":{\"v\":\"reviewed\"}}}}}}"));
        var summary=new Summary(id,"LEGACY","旧导入",null,null,"EXCEL_IMPORT",ExperimentStatus.DRAFT,null,null,null,null,null,null,null,null,1,4,Instant.now());
        var detail=new Detail(summary,version,null,null,null,mapper.createObjectNode(),model,List.of(),List.of());
        when(repository.detail(ORGANIZATION_ID,id)).thenReturn(Optional.of(detail));
        var evidence=mapper.createObjectNode().put("importJobId",model.path("sourceImportJobId").asText())
                .put("revision","audit-1").put("confirmedBy",USER_ID.toString()).put("confirmedAt",Instant.now().toString());
        evidence.set("initialModel",model.deepCopy());
        when(repository.initialReviewedImport(ORGANIZATION_ID,id)).thenReturn(Optional.of(evidence));
        var reviewed=new ExperimentService(repository,mapper,files,new ExperimentEditModelNormalizer(mapper),null,null,new JsonCanonicalizer(mapper));
        assertThat(reviewed.detail(id).editModel().at("/importDataReview/confirmed").asBoolean()).isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode)model.at("/documentSnapshot/sheets/s/cellData/0/0")).put("v","changed");
        assertThat(reviewed.detail(id).editModel().at("/importDataReview/confirmed").asBoolean()).isFalse();
        when(repository.initialReviewedImport(ORGANIZATION_ID,id)).thenReturn(Optional.empty());
        assertThat(reviewed.detail(id).editModel().at("/importDataReview/confirmed").asBoolean()).isFalse();
        verify(repository,org.mockito.Mockito.never()).saveDraft(any(),any(),org.mockito.ArgumentMatchers.anyLong(),any(),any(),any());
    }

    private Summary summary(ExperimentRepository.Create command) {
        return new Summary(
                command.id(), command.experimentNo(), command.title(), command.categoryId(), command.categoryName(),
                command.sourceType(), command.status(), command.projectId(), null, command.stageId(), null,
                command.taskId(), null,
                command.ownerName(), command.experimentDate(), 1, 0, Instant.now()
        );
    }
}
