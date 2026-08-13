package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataImportValidationTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ObjectMapper mapper = new ObjectMapper();
    private final DataRepository repository = mock(DataRepository.class);
    private final TemplateDataImportFacade templates = mock(TemplateDataImportFacade.class);
    private final DataImportService service = new DataImportService(repository, mock(FileObjectRepository.class),
            templates, mapper, mock(AuditLogFacade.class), mock(OpsAsyncFacade.class));
    private final UUID jobId = UUID.randomUUID();
    private final UUID templateVersionId = UUID.randomUUID();

    @AfterEach
    void clearActor() {
        ActorContext.clear();
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
                jobId, UUID.randomUUID(), "sha", "data.xlsx", "XLSX", templateVersionId, "MATERIAL",
                "WAITING_MAPPING", 45, "WAITING_MAPPING", "parser", null, Instant.now(), Instant.now())));
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
