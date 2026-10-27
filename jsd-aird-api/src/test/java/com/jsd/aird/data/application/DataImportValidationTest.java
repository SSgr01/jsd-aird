package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataImportValidationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"INTEGER,/", "DATE,/", "DATETIME,未测", "TIME,不适用", "DURATION,/", "BOOLEAN,/"})
    void laboratoryStatusMarkersDoNotBecomeTypeErrors(String type,String raw) {
        givenJob();when(templates.getVersion(ORGANIZATION_ID,templateVersionId)).thenReturn(definition(false));
        var detail=mapper.createObjectNode().put("bindingId","typed").put("dataPath","/typed");detail.withObject("/experimentField").put("domain","TEST").put("field","VALUE");
        when(repository.listMappings(ORGANIZATION_ID,jobId)).thenReturn(List.of(new DataRepository.Mapping(UUID.randomUUID(),"sheet-1","A","验收字段","TYPED","验收字段","MAP",type,null,null,detail,"CONFIRMED")));
        when(repository.listRows(ORGANIZATION_ID,jobId)).thenReturn(List.of(row("A",raw)));
        service.validateInternal(ORGANIZATION_ID,jobId);
        var issues=ArgumentCaptor.forClass(List.class);verify(repository).replaceValidation(eq(ORGANIZATION_ID),eq(jobId),any(),issues.capture(),eq("WAITING_CONFIRM"));assertThat(issues.getValue()).isEmpty();
    }
    @Test void spreadsheetDatesUseFullPrecisionSourceEvidence() {
        givenJob();when(templates.getVersion(ORGANIZATION_ID,templateVersionId)).thenReturn(definition(false));
        var detail=mapper.createObjectNode().put("bindingId","typed").put("dataPath","/typed");
        when(repository.listMappings(ORGANIZATION_ID,jobId)).thenReturn(List.of(new DataRepository.Mapping(UUID.randomUUID(),"sheet-1","A","验收字段","TYPED","验收字段","MAP","DATETIME",null,null,detail,"CONFIRMED")));
        var base=row("A","46321.52426");var meta=base.sourceMetadata().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)meta).withObject("/cells/A").put("rawNumericValue","46321.52425925926").put("numberFormat","yyyy-mm-dd hh:mm:ss");
        when(repository.listRows(ORGANIZATION_ID,jobId)).thenReturn(List.of(new DataRepository.Row(base.id(),base.sheetId(),base.rowNumber(),base.rawValues(),base.normalizedValues(),base.correctedValues(),base.status(),meta)));
        service.validateInternal(ORGANIZATION_ID,jobId);
        var rows=ArgumentCaptor.forClass(List.class);var issues=ArgumentCaptor.forClass(List.class);verify(repository).replaceValidation(eq(ORGANIZATION_ID),eq(jobId),rows.capture(),issues.capture(),eq("WAITING_CONFIRM"));assertThat(issues.getValue()).isEmpty();
        assertThat(((DataRepository.Row)rows.getValue().getFirst()).normalizedValues().path("TYPED").path("normalizedValue").asText()).isEqualTo("2026-10-26 12:34:56");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "DATE,2026/10/26,2026-10-26", "DATETIME,2026-10-26 12:34:56,2026-10-26 12:34:56",
        "TIME,12:34:56,12:34:56", "DURATION,01:30:00,5400", "BOOLEAN,否,false", "INTEGER,80,80"
    })
    void importValidationUsesTheDeclaredFieldType(String type,String raw,String expected) {
        givenJob();when(templates.getVersion(ORGANIZATION_ID,templateVersionId)).thenReturn(definition(false));
        var detail=mapper.createObjectNode().put("bindingId","typed").put("dataPath","/typed");
        when(repository.listMappings(ORGANIZATION_ID,jobId)).thenReturn(List.of(new DataRepository.Mapping(UUID.randomUUID(),"sheet-1","A","验收字段","TYPED","验收字段","MAP",type,null,null,detail,"CONFIRMED")));
        when(repository.listRows(ORGANIZATION_ID,jobId)).thenReturn(List.of(row("A",raw)));
        service.validateInternal(ORGANIZATION_ID,jobId);
        var rows=ArgumentCaptor.forClass(List.class);var issues=ArgumentCaptor.forClass(List.class);
        verify(repository).replaceValidation(eq(ORGANIZATION_ID),eq(jobId),rows.capture(),issues.capture(),eq("WAITING_CONFIRM"));
        assertThat(issues.getValue()).isEmpty();
        var result=(DataRepository.Row)rows.getValue().getFirst();
        assertThat(result.normalizedValues().path("TYPED").path("normalizedValue").asText()).isEqualTo(expected);
    }

    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ObjectMapper mapper = new ObjectMapper();
    private final DataRepository repository = mock(DataRepository.class);
    private final TemplateDataImportFacade templates = mock(TemplateDataImportFacade.class);
    private final FileObjectRepository files = mock(FileObjectRepository.class);
    private final DataImportService service = new DataImportService(repository, files,
            templates, mapper, mock(AuditLogFacade.class), mock(OpsAsyncFacade.class));
    private final UUID jobId = UUID.randomUUID();
    private final UUID templateVersionId = UUID.randomUUID();

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void preservesActionableValidationReasonWhenParsingFails() {
        var reason = "文件中没有可导入的实验数据；请填写本次实验数据后重传，或使用在线新建";
        service.markParseFailed(ORGANIZATION_ID, jobId,
                new ApiException(com.jsd.aird.shared.error.ApiErrorCode.VALIDATION_ERROR, reason));
        verify(repository).updateJobStatus(ORGANIZATION_ID, jobId, "FAILED", 0, "FAILED", reason);
    }

    @Test
    void blocksRowsWhenTemplateRequiredFieldHasNoMapping() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        givenJob();
        when(repository.listMappings(ORGANIZATION_ID, jobId)).thenReturn(List.of());
        when(repository.listRows(ORGANIZATION_ID, jobId)).thenReturn(List.of(row("A", "value")));
        when(templates.getVersion(ORGANIZATION_ID, templateVersionId)).thenReturn(definition(true));

        service.validateInternal(ORGANIZATION_ID, jobId);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceValidation(eq(ORGANIZATION_ID), eq(jobId), any(), captor.capture(), eq("WAITING_MAPPING"));
        assertThat(captor.getValue()).anyMatch(item -> ((DataRepository.Issue) item).issueType().equals("REQUIRED_MAPPING_MISSING"));
    }

    @Test
    void explainsThatExperimentImportsRequireTheV9ImportContractAndExperimentUsage() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        when(templates.getPublished(ORGANIZATION_ID, templateVersionId))
                .thenReturn(importTemplate(7, "GENERAL_DATA"));

        assertThatThrownBy(() -> service.createExperiment(experimentDraftCommand()))
                .isInstanceOf(ApiException.class)
                .hasMessage("生成实验草稿需选择已发布且导入契约版本为 V9、用途为实验数据（EXPERIMENT_DATA）的模板；V9 指导入契约版本，不是模板版本号");
        verify(repository, never()).insertJob(any());
    }

    @Test
    void createsExperimentImportJobFromPublishedV9ExperimentTemplate() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        var sourceFileId = UUID.randomUUID();
        var experimentCategoryId = UUID.randomUUID();
        when(templates.getPublished(ORGANIZATION_ID, templateVersionId))
                .thenReturn(importTemplate(9, "EXPERIMENT_DATA"));
        when(files.find(ORGANIZATION_ID, sourceFileId)).thenReturn(Optional.of(new FileObjectRepository.FileObject(
                sourceFileId, ORGANIZATION_ID, "staged/data.xlsx", "data.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 100, "sha-256", "STAGED")));
        when(repository.findCompletedDuplicate(ORGANIZATION_ID, "sha-256", templateVersionId))
                .thenReturn(Optional.empty());
        when(repository.findJob(eq(ORGANIZATION_ID), any(UUID.class))).thenReturn(Optional.of(new DataRepository.Job(
                jobId, sourceFileId, "sha-256", "data.xlsx", "XLSX", templateVersionId, null,
                "QUEUED", 0, "PREPARING", null, null, Instant.now(), Instant.now(),
                9, "contract-hash", "READY", "EXPERIMENT_DRAFT", experimentCategoryId)));

        var created = service.createExperiment(new DataImportService.CreateCommand(sourceFileId, templateVersionId,
                null, false, List.of(), "EXPERIMENT_DRAFT", experimentCategoryId));

        assertThat(created.importPurpose()).isEqualTo("EXPERIMENT_DRAFT");
        var captor = org.mockito.ArgumentCaptor.forClass(DataRepository.NewJob.class);
        verify(repository).insertJob(captor.capture());
        assertThat(captor.getValue().importContractVersion()).isEqualTo(9);
        assertThat(captor.getValue().importPurpose()).isEqualTo("EXPERIMENT_DRAFT");
        assertThat(captor.getValue().targetExperimentCategoryId()).isEqualTo(experimentCategoryId);
    }

    @Test
    void createsTemplateGuidedDataImportJobForWordSourceAndWordTemplate() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        var sourceFileId = UUID.randomUUID();
        when(templates.getPublished(ORGANIZATION_ID, templateVersionId))
                .thenReturn(importTemplate("DOCX", 0, "GENERAL_DATA"));
        when(files.find(ORGANIZATION_ID, sourceFileId)).thenReturn(Optional.of(new FileObjectRepository.FileObject(
                sourceFileId, ORGANIZATION_ID, "staged/report.docx", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 100, "docx-sha", "STAGED")));
        when(repository.findCompletedDuplicate(ORGANIZATION_ID, "docx-sha", templateVersionId))
                .thenReturn(Optional.empty());
        when(repository.findJob(eq(ORGANIZATION_ID), any(UUID.class))).thenReturn(Optional.of(new DataRepository.Job(
                jobId, sourceFileId, "docx-sha", "report.docx", "DOCX", templateVersionId, null,
                "QUEUED", 0, "PREPARING", null, null, Instant.now(), Instant.now(),
                0, "contract-hash", "READY", "DATA_ONLY", null)));

        var created = service.create(new DataImportService.CreateCommand(sourceFileId, templateVersionId,
                null, false, List.of(), "DATA_ONLY", null));

        assertThat(created.sourceFormat()).isEqualTo("DOCX");
        var captor = ArgumentCaptor.forClass(DataRepository.NewJob.class);
        verify(repository).insertJob(captor.capture());
        assertThat(captor.getValue().sourceFormat()).isEqualTo("DOCX");
        assertThat(captor.getValue().templateVersionId()).isEqualTo(templateVersionId);
    }

    @Test
    void rejectsWordSourceWhenSpreadsheetTemplateIsSelected() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        var sourceFileId = UUID.randomUUID();
        when(templates.getPublished(ORGANIZATION_ID, templateVersionId))
                .thenReturn(importTemplate("XLSX", 0, "GENERAL_DATA"));
        when(files.find(ORGANIZATION_ID, sourceFileId)).thenReturn(Optional.of(new FileObjectRepository.FileObject(
                sourceFileId, ORGANIZATION_ID, "staged/report.docx", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 100, "docx-sha", "STAGED")));

        assertThatThrownBy(() -> service.create(new DataImportService.CreateCommand(sourceFileId, templateVersionId,
                null, false, List.of(), "DATA_ONLY", null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("所选模板格式与文件类型不匹配：Excel / CSV 请选择 Excel 模板，DOC / DOCX 请选择 Word 模板；PDF 不支持数据中心导入");
        verify(repository, never()).insertJob(any());
    }

    @Test
    void blocksDuplicateSourceColumnsMappedToSameField() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        givenJob();
        var detail = mapper.createObjectNode().put("required", false).put("dataPath", "/name");
        when(repository.listMappings(ORGANIZATION_ID, jobId)).thenReturn(List.of(
                mapping("A", detail), mapping("B", detail)));
        when(repository.listRows(ORGANIZATION_ID, jobId)).thenReturn(List.of(row("A", "one", "B", "two")));
        when(templates.getVersion(ORGANIZATION_ID, templateVersionId)).thenReturn(definition(false));

        service.validateInternal(ORGANIZATION_ID, jobId);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceValidation(eq(ORGANIZATION_ID), eq(jobId), any(), captor.capture(), eq("WAITING_MAPPING"));
        assertThat(captor.getValue()).anyMatch(item -> ((DataRepository.Issue) item).issueType().equals("DUPLICATE_FIELD_MAPPING"));
    }

    @Test
    void correctsLegacyNormalizedValueWithoutExplicitBindingMetadata() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        givenJob();
        var recordId = UUID.randomUUID();
        var value = mapper.createObjectNode()
                .put("fieldCode", "MATERIAL.NAME")
                .put("normalizedValue", "旧名称")
                .put("valueSource", "INPUT");
        var normalized = mapper.createObjectNode().set("MATERIAL.NAME", value);
        var row = new DataRepository.Row(recordId, "sheet-1", 2, mapper.createObjectNode(), normalized,
                mapper.createObjectNode(), "VALID");
        when(repository.listRows(ORGANIZATION_ID, jobId)).thenReturn(List.of(row));
        when(repository.listMappings(ORGANIZATION_ID, jobId)).thenReturn(List.of());
        when(templates.getVersion(ORGANIZATION_ID, templateVersionId)).thenReturn(definition(false));

        service.correctValue(jobId, recordId, "MATERIAL.NAME", "/MATERIAL.NAME",
                mapper.getNodeFactory().textNode("新名称"), "导入确认页直接修正");

        verify(repository).correctValue(ORGANIZATION_ID, jobId, recordId, "MATERIAL.NAME",
                "/MATERIAL.NAME", mapper.getNodeFactory().textNode("新名称"), USER_ID, "导入确认页直接修正");
    }

    @Test
    void selectsMappingsBySheetComponentAndSourceColumn() {
        givenJob();
        var componentA = mapper.createObjectNode().put("componentId", "basic")
                .put("bindingId", "name").put("dataPath", "/product/name");
        var componentB = mapper.createObjectNode().put("componentId", "detail")
                .put("bindingId", "material").put("dataPath", "/formula/material");
        when(repository.listMappings(ORGANIZATION_ID, jobId)).thenReturn(List.of(
                mapping("A", "PRODUCT.NAME", "产品名称", componentA),
                mapping("A", "FORMULA.MATERIAL", "原料", componentB)));
        when(repository.listRows(ORGANIZATION_ID, jobId)).thenReturn(List.of(
                componentRow("basic", "A", "UV 树脂"),
                componentRow("detail", "A", "光引发剂")));
        when(templates.getVersion(ORGANIZATION_ID, templateVersionId)).thenReturn(new TemplateDataImportFacade.DataTemplateDefinition(
                UUID.randomUUID(), templateVersionId, "material", "物料", "", 1, "XLSX",
                mapper.createObjectNode(), mapper.createArrayNode(), List.of(
                new TemplateDataImportFacade.FieldDefinition("PRODUCT.NAME", "产品名称", "TEXT", "",
                        false, false, List.of(), "/product/name"),
                new TemplateDataImportFacade.FieldDefinition("FORMULA.MATERIAL", "原料", "TEXT", "",
                        false, false, List.of(), "/formula/material"))));

        service.validateInternal(ORGANIZATION_ID, jobId);

        @SuppressWarnings("unchecked")
        var rows = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceValidation(eq(ORGANIZATION_ID), eq(jobId), rows.capture(), any(), eq("WAITING_CONFIRM"));
        var normalized = (List<DataRepository.Row>) rows.getValue();
        assertThat(normalized.get(0).normalizedValues().path("PRODUCT.NAME").path("normalizedValue").asText())
                .isEqualTo("UV 树脂");
        assertThat(normalized.get(0).normalizedValues().has("FORMULA.MATERIAL")).isFalse();
        assertThat(normalized.get(1).normalizedValues().path("FORMULA.MATERIAL").path("normalizedValue").asText())
                .isEqualTo("光引发剂");
    }

    private void givenJob() {
        when(repository.findJob(ORGANIZATION_ID, jobId)).thenReturn(Optional.of(new DataRepository.Job(
                 jobId, UUID.randomUUID(), "sha", "data.xlsx", "XLSX", templateVersionId, null,
                "WAITING_MAPPING", 45, "WAITING_MAPPING", "parser", null, Instant.now(), Instant.now())));
    }

    @Test
    void rereadingTheSameSourceKeepsCorrectionsButReanchoringDoesNotMoveThem() {
        var raw=mapper.createObjectNode().put("A","100").put("B","树脂");
        var oldLocation=mapper.createObjectNode().put("address","A2").put("sheet","one");
        var correction=mapper.createObjectNode().put("correctedValue","200");
        var previous=new DataRepository.Row(UUID.randomUUID(),"sheet-1",2,raw,raw,correction,"VALID",oldLocation);
        var unchanged=new DataRepository.Row(UUID.randomUUID(),"sheet-1",2,mapper.createObjectNode().put("B","树脂").put("A","100"),raw,mapper.createObjectNode(),"STAGED",mapper.createObjectNode().put("sheet","one").put("address","A2"));
        var relocated=new DataRepository.Row(UUID.randomUUID(),"sheet-1",2,raw,raw,mapper.createObjectNode(),"STAGED",mapper.createObjectNode().put("address","B2"));
        var restored=service.restoreCorrections(List.of(unchanged,relocated),List.of(previous));
        assertThat(restored.get(0).id()).isEqualTo(previous.id());
        assertThat(restored.get(0).correctedValues()).isEqualTo(correction);
        assertThat(restored.get(1).correctedValues().isEmpty()).isTrue();
    }

    @Test
    void selectedSecondSheetWithoutMappingCannotSilentlyDisappear() {
        givenJob();
        when(templates.getVersion(ORGANIZATION_ID, templateVersionId)).thenReturn(definition(false));
        when(repository.listSheets(ORGANIZATION_ID, jobId)).thenReturn(List.of(new DataRepository.Sheet(
                UUID.randomUUID(), "sheet-2", "耐候性能", 1, true, List.of(1), 2, 4, mapper.createObjectNode(), "CONFIRMED")));
        service.validateInternal(ORGANIZATION_ID, jobId);
        var issues = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceValidation(eq(ORGANIZATION_ID),eq(jobId),any(),issues.capture(),eq("WAITING_MAPPING"));
        assertThat(issues.getValue()).anyMatch(value -> ((DataRepository.Issue)value).issueType().equals("SHEET_MAPPING_MISSING"));
    }

    @Test
    void correctedNumericValueIsValidatedAndPublishedWithoutLosingOriginalValue() {
        givenJob();
        when(templates.getVersion(ORGANIZATION_ID, templateVersionId)).thenReturn(definition(false));
        var mapping = new DataRepository.Mapping(UUID.randomUUID(),"sheet-1","A","黏度","VISCOSITY","黏度",
                "MAP","NUMBER",null,null,mapper.createObjectNode().put("bindingId","viscosity").put("dataPath","/viscosity"),"CONFIRMED");
        when(repository.listMappings(ORGANIZATION_ID,jobId)).thenReturn(List.of(mapping));
        var raw = mapper.createObjectNode().put("A","读数不清");
        var correction = mapper.createObjectNode().put("fieldCode","VISCOSITY").put("bindingId","viscosity").put("valuePath","/viscosity").put("normalizedValue","读数不清").put("correctedValue","1250.50");
        when(repository.listRows(ORGANIZATION_ID,jobId)).thenReturn(List.of(new DataRepository.Row(UUID.randomUUID(),"sheet-1",2,raw,mapper.createObjectNode(),mapper.createObjectNode().set("VISCOSITY",correction),"BLOCKED")));
        service.validateInternal(ORGANIZATION_ID,jobId);
        var rows = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceValidation(eq(ORGANIZATION_ID),eq(jobId),rows.capture(),any(),eq("WAITING_CONFIRM"));
        var row = (DataRepository.Row)rows.getValue().getFirst();
        assertThat(row.normalizedValues().path("VISCOSITY").path("rawValue").asText()).isEqualTo("读数不清");
        assertThat(row.normalizedValues().path("VISCOSITY").path("normalizedValue").asText()).isEqualTo("1250.5");
        assertThat(row.correctedValues().path("VISCOSITY").path("normalizedValue").asText()).isEqualTo("1250.5");
    }

    @Test
    void invalidCorrectionCannotBypassNumericValidation() {
        givenJob();
        when(templates.getVersion(ORGANIZATION_ID,templateVersionId)).thenReturn(definition(false));
        var detail=mapper.createObjectNode().put("bindingId","number").put("dataPath","/number");
        when(repository.listMappings(ORGANIZATION_ID,jobId)).thenReturn(List.of(new DataRepository.Mapping(UUID.randomUUID(),"sheet-1","A","数值","N","数值","MAP","NUMBER",null,null,detail,"CONFIRMED")));
        var correction=mapper.createObjectNode().put("bindingId","number").put("valuePath","/number").put("correctedValue","未知");
        when(repository.listRows(ORGANIZATION_ID,jobId)).thenReturn(List.of(new DataRepository.Row(UUID.randomUUID(),"sheet-1",2,mapper.createObjectNode().put("A","100"),mapper.createObjectNode(),mapper.createObjectNode().set("N",correction),"VALID")));
        service.validateInternal(ORGANIZATION_ID,jobId);
        var issues=ArgumentCaptor.forClass(List.class);
        verify(repository).replaceValidation(eq(ORGANIZATION_ID),eq(jobId),any(),issues.capture(),eq("WAITING_MAPPING"));
        assertThat(issues.getValue()).anyMatch(value -> ((DataRepository.Issue)value).issueType().equals("TYPE_ERROR"));
    }

    private DataImportService.CreateCommand experimentDraftCommand() {
        return new DataImportService.CreateCommand(UUID.randomUUID(), templateVersionId,
                null, false, List.of(), "EXPERIMENT_DRAFT", UUID.randomUUID());
    }

    private TemplateDataImportFacade.DataTemplateDefinition importTemplate(int contractVersion, String usage) {
        return importTemplate("XLSX", contractVersion, usage);
    }

    private TemplateDataImportFacade.DataTemplateDefinition importTemplate(String format, int contractVersion, String usage) {
        var contract = mapper.createObjectNode().put("templateUsage", usage);
        return new TemplateDataImportFacade.DataTemplateDefinition(UUID.randomUUID(), templateVersionId,
                "experiment-template", "实验模板", "实验", 1, format, mapper.createObjectNode(),
                mapper.createArrayNode(), List.of(), contractVersion, 1, "contract-hash", contract);
    }

    private DataRepository.Row row(String... values) {
        var raw = mapper.createObjectNode();
        for (int index = 0; index < values.length; index += 2) raw.put(values[index], values[index + 1]);
        return new DataRepository.Row(UUID.randomUUID(), "sheet-1", 2, raw, raw.deepCopy(), raw.deepCopy(), "STAGED");
    }

    private DataRepository.Row componentRow(String componentId, String column, String value) {
        var raw = mapper.createObjectNode().put(column, value);
        var metadata = mapper.createObjectNode().put("componentId", componentId);
        metadata.putObject("cells").putObject(column).put("bindingId", column)
                .put("valuePath", "/" + column).put("valueSource", "INPUT");
        return new DataRepository.Row(UUID.randomUUID(), "sheet-1", 2, raw, raw.deepCopy(), raw.deepCopy(),
                "STAGED", metadata);
    }

    private DataRepository.Mapping mapping(String sourceColumn, com.fasterxml.jackson.databind.JsonNode detail) {
        return new DataRepository.Mapping(UUID.randomUUID(), "sheet-1", sourceColumn, sourceColumn,
                "MATERIAL.NAME", "名称", "MAP", "TEXT", null, null, detail, "CONFIRMED");
    }

    private DataRepository.Mapping mapping(String sourceColumn, String fieldCode, String fieldName,
                                             com.fasterxml.jackson.databind.JsonNode detail) {
        return new DataRepository.Mapping(UUID.randomUUID(), "sheet-1", sourceColumn, sourceColumn,
                fieldCode, fieldName, "MAP", "TEXT", null, null, detail, "CONFIRMED");
    }

    private TemplateDataImportFacade.DataTemplateDefinition definition(boolean required) {
        return new TemplateDataImportFacade.DataTemplateDefinition(UUID.randomUUID(), templateVersionId,
                "material", "物料", "", 1, "XLSX", mapper.createObjectNode(),
                mapper.createArrayNode(), List.of(new TemplateDataImportFacade.FieldDefinition(
                        "MATERIAL.NAME", "名称", "TEXT", "", required, true, List.of("物料名称"), "/material/name")));
    }
}
