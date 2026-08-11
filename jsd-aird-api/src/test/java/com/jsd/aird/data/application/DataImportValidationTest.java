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
        when(templates.getPublished(ORGANIZATION_ID, templateVersionId)).thenReturn(definition(true));

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
        when(templates.getPublished(ORGANIZATION_ID, templateVersionId)).thenReturn(definition(false));

        service.validateInternal(ORGANIZATION_ID, jobId);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceValidation(eq(ORGANIZATION_ID), eq(jobId), any(), captor.capture(), eq("WAITING_MAPPING"));
        assertThat(captor.getValue()).anyMatch(item -> ((DataRepository.Issue) item).issueType().equals("DUPLICATE_FIELD_MAPPING"));
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

    private DataRepository.Mapping mapping(String sourceColumn, com.fasterxml.jackson.databind.JsonNode detail) {
        return new DataRepository.Mapping(UUID.randomUUID(), "sheet-1", sourceColumn, sourceColumn,
                "MATERIAL.NAME", "名称", "MAP", "TEXT", null, null, detail, "CONFIRMED");
    }

    private TemplateDataImportFacade.DataTemplateDefinition definition(boolean required) {
        return new TemplateDataImportFacade.DataTemplateDefinition(UUID.randomUUID(), templateVersionId,
                "material", "物料", "", "MATERIAL", 1, "XLSX", mapper.createObjectNode(),
                mapper.createArrayNode(), List.of(new TemplateDataImportFacade.FieldDefinition(
                        "MATERIAL.NAME", "名称", "TEXT", "", required, true, List.of("物料名称"), "/material/name")));
    }
}
