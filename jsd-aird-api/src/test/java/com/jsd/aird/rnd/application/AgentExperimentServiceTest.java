package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentExperimentServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final ExperimentDraftFacade drafts = mock(ExperimentDraftFacade.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final TemplateDataImportFacade templates = mock(TemplateDataImportFacade.class);
    final ExperimentRepository repository = mock(ExperimentRepository.class);
    final AgentExperimentService service = new AgentExperimentService(repository, drafts,
        mock(AuthorizationService.class), jdbc, json, new JsonCanonicalizer(json), templates);
    final UUID experiment = UUID.randomUUID(), version = UUID.randomUUID();

    @BeforeEach void actor() { ActorContext.set(new Actor(UUID.randomUUID(), UUID.randomUUID(), "test")); }
    @AfterEach void clear() { ActorContext.clear(); }

    ExperimentModels.Version version(ObjectNode model) {
        return new ExperimentModels.Version(version, 1, "DRAFT", null, null, json.createObjectNode(), model,
            "", null, null, Instant.EPOCH, UUID.randomUUID());
    }
    ObjectNode draft() {
        var args = json.createObjectNode().put("operation_key", "a".repeat(64)).put("title", "验收")
            .put("category_id", UUID.randomUUID().toString()).put("planned_date", "2026-11-06")
            .put("template_version_id", UUID.randomUUID().toString());
        args.putObject("edit_model").put("plan", "用户给定方案；未知条件留空");
        return args;
    }

    @Test void readsCurrentDraftByItsExactOwnedVersionWithoutUsingPublishedHistory() {
        var detail = mock(ExperimentModels.Detail.class);
        var summary = mock(ExperimentModels.Summary.class);
        when(detail.summary()).thenReturn(summary);
        when(summary.title()).thenReturn("Synthetic draft");
        when(detail.currentVersionId()).thenReturn(version);
        when(repository.detail(ActorContext.required().organizationId(), experiment)).thenReturn(Optional.of(detail));
        when(repository.version(ActorContext.required().organizationId(), experiment, version))
            .thenReturn(Optional.of(version(json.createObjectNode().put("plan", "No measured results"))));
        var args = json.createObjectNode().put("experiment_id", experiment.toString()).put("version_id", version.toString());
        var result = service.read("get_experiment_record", args);
        assertThat(result.path("candidates").path(0).path("source").path("version_id").asText()).isEqualTo(version.toString());
        verify(repository, never()).versions(any(), any());
        assertThatThrownBy(() -> service.read("get_experiment_record", args.put("version_id", UUID.randomUUID().toString())))
            .isInstanceOf(ApiException.class).hasMessageContaining("版本不属于");
    }

    @Test void projectsBusinessDataWithoutEditorCachesAndPagesWithCompositeSampleIdentity() {
        var model = json.createObjectNode().put("dataNature", "SYNTHETIC");
        model.putObject("documentSnapshot").put("large", "x".repeat(1000000));
        model.putObject("blankDocument").put("large", "x".repeat(1000000));
        model.putObject("dynamicValues").putObject("fields").put("basic.temperature", 23);
        var rows = model.putArray("testResults");
        for (int i=0;i<3;i++) rows.addObject().put("sampleKey", "same-key").put("fieldCode", "viscosity"+i)
            .put("value", 356.9+i).put("unit", "cP").put("testMethod", "method A");
        var projection = new AgentExperimentProjection(json);
        var overview = projection.page(experiment, version(model), json.createObjectNode());
        assertThat(overview.toString()).hasSizeLessThan(2000).doesNotContain("documentSnapshot", "blankDocument");
        assertThat(overview.path("basic_info").get(0).path("rawValue").asInt()).isEqualTo(23);
        var summary = projection.page(experiment, version(model), json.createObjectNode().put("section", "summary"));
        assertThat(summary.toString()).hasSizeLessThan(500).doesNotContain("available_metrics", "basic_info", "documentSnapshot", "sample_columns");
        assertThat(summary.path("experiment_id").asText()).isEqualTo(experiment.toString());
        assertThat(summary.path("version_id").asText()).isEqualTo(version.toString());
        var page = projection.page(experiment, version(model), json.createObjectNode().put("section", "testResults").put("limit", 2));
        assertThat(page.path("next_offset").asInt()).isEqualTo(2);
        assertThat(page.path("version_id").asText()).isEqualTo(version.toString());
        assertThat(page.path("records").get(0).path("values").get(0).path("value").asDouble()).isEqualTo(356.9);
        assertThat(page.path("records").get(0).path("testMethod").asText()).isEqualTo("method A");
        var other = projection.page(UUID.randomUUID(), version(model), json.createObjectNode().put("section", "testResults"));
        assertThat(other.path("experiment_id")).isNotEqualTo(page.path("experiment_id"));
    }

    @Test void invalidOptionalUuidFailsBeforeDraftWritesAndCanBeReconciledWithoutReplay() {
        var args = draft().put("project_id", "");
        assertThatThrownBy(()->service.createDraft(args)).isInstanceOf(ApiException.class).hasMessageContaining("project_id");
        var receipt = service.receipt(args.path("operation_key").asText(), args);
        assertThat(receipt.path("status").asText()).isEqualTo("FAILED");
        assertThat(receipt.path("result").path("error").asText()).isEqualTo("EXPERIMENT_DRAFT_VALIDATION_FAILED");
        verifyNoInteractions(drafts);
    }

    @Test void newTemplateFieldsKeepLabelsConditionsUnitsAndZeroVersusUnmeasured() {
        var model = json.createObjectNode();
        var fields = model.putObject("templateInstance").putObject("definition").putObject("fields");
        var samples = model.putArray("sampleGroups");
        samples.addObject().put("sampleKey", "A").put("sourceIdentity", "样品A");
        samples.addObject().put("sampleKey", "B").put("sourceIdentity", "样品B");
        samples.addObject().put("sampleKey", "C").put("sourceIdentity", "缺失单元格样品");
        fields.putObject("future.shear").put("label", "新增剪切强度").put("scope", "SAMPLE");
        var rows = model.putArray("testResults");
        rows.addObject().put("fieldCode", "future.shear").put("sampleKey", "A").put("value", 0)
            .put("rawValue", 0).put("unit", "MPa").put("valueStatus", "VALID").put("testCondition", "23 °C / 50% RH");
        rows.addObject().put("fieldCode", "future.shear").put("sampleKey", "B").putNull("value")
            .put("unit", "MPa").put("valueStatus", "NOT_MEASURED").put("testCondition", "23 °C / 50% RH");
        var result = new AgentExperimentProjection(json).page(experiment, version(model),
            json.createObjectNode().put("section", "testResults"));
        var row = result.path("records").get(0);
        var text = new AgentExperimentProjection(json).text(result);
        assertThat(text).contains("新增剪切强度", "23 °C / 50% RH", "MPa", "\"value\":0",
            "NOT_MEASURED", "MISSING", "样品A = A", "样品B = B", experiment.toString(), version.toString());
        assertThat(row.path("label").asText()).isEqualTo("新增剪切强度");
        assertThat(row.path("unit").asText()).isEqualTo("MPa");
        assertThat(row.path("testCondition").asText()).isEqualTo("23 °C / 50% RH");
        assertThat(row.path("values").get(0).path("value").isNumber()).isTrue();
        assertThat(row.path("values").get(0).path("value").asInt()).isZero();
        assertThat(row.path("values").get(1).path("value").isNull()).isTrue();
        assertThat(row.path("values").get(1).path("valueStatus").asText()).isEqualTo("NOT_MEASURED");
    }

    @Test void missingReceiptOfAValidWriteRemainsUnknown() {
        assertThat(service.receipt("a".repeat(64), draft()).path("status").asText()).isEqualTo("UNKNOWN");
        verifyNoInteractions(drafts);
    }

    @Test void compatiblePublishedTemplatesDoNotRequireOneOfTheTwoCurrentCodes() throws Exception {
        var args = draft();
        var id = UUID.fromString(args.path("template_version_id").asText());
        var templateId = UUID.randomUUID();
        var parser = new com.jsd.aird.tpl.infrastructure.XlsxStructureParser(json);
        var compiled = new com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog(json).compile(
            com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog.TEST,
            parser.parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx")));
        var contract = json.createObjectNode().put("templateUsage", "EXPERIMENT_DATA");
        when(templates.getPublished(any(), eq(id))).thenReturn(new TemplateDataImportFacade.DataTemplateDefinition(
            templateId, id, "FUTURE_COMPATIBLE_TEMPLATE", "新实验模板", "TEST", 1, "xlsx",
            json.createObjectNode(), json.createObjectNode(), List.of(), 9, 1, "contract", contract));
        when(templates.getExperimentTemplateVersion(any(), eq(id))).thenReturn(new TemplateDataImportFacade.PublishedExperimentTemplate(
            templateId, id, "FUTURE_COMPATIBLE_TEMPLATE", "新实验模板", 1, "snapshot",
            compiled.payload().path("snapshot"), json.createObjectNode(), contract));
        when(drafts.createResearchDraft(any())).thenReturn(new ExperimentDraftFacade.ImportedDraft(
            experiment, version, "TEST-NEW", "验收"));
        assertThat(service.createDraft(args).path("id").asText()).isEqualTo(experiment.toString());
        var command = org.mockito.ArgumentCaptor.forClass(ExperimentDraftFacade.ResearchDraftCommand.class);
        verify(drafts).createResearchDraft(command.capture());
        assertThat(command.getValue().editModel().path("plan").asText()).contains("用户给定方案");
        assertThat(command.getValue().editModel().path("documentSnapshot").isObject()).isTrue();
        var working = command.getValue().editModel().path("documentSnapshot");
        var normalized = com.jsd.aird.shared.lab.LabWorkbook.extract(json, working, json.createObjectNode(), "draft-test");
        assertThat(normalized.path("templateInstance").path("issues")).isEmpty();
        assertThat(normalized.path("dynamicValues").path("fields").path("basic.background").asText())
            .contains("用户给定方案");
        assertThat(normalized.path("sampleGroups")).isEmpty();
    }

    @Test void bothStandardTemplatesExposeBoundedPagesWithoutLosingCellsOrResultStates() throws Exception {
        for (var kind : List.of("test", "synthesis")) {
            var parser = new com.jsd.aird.tpl.infrastructure.XlsxStructureParser(json);
            var catalog = new com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog(json);
            var code = kind.equals("test") ? com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog.TEST
                : com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog.SYNTHESIS;
            var template = catalog.compile(code, parser.parseInstance(getClass().getResourceAsStream("/templates/unified/" + kind + ".xlsx")));
            var workbook = com.jsd.aird.shared.lab.LabWorkbook.prepare(json,
                parser.parse(getClass().getResourceAsStream("/unified-lab/" + kind + "-example.xlsx")).initialEditorSnapshot(),
                template.payload().path("snapshot"), "projection-test");
            var model = com.jsd.aird.shared.lab.LabWorkbook.extract(json, workbook, json.createObjectNode(), "projection-test");
            var projection = new AgentExperimentProjection(json);
            assertThat(projection.page(experiment, version(model), json.createObjectNode()).toString())
                .hasSizeLessThan(15000).doesNotContain("documentSnapshot", "templateInstance", "instanceFields");
            for (var section : List.of("formulaItems", "processSteps", "testResults")) {
                int offset = 0, cells = 0;
                do {
                    var page = projection.page(experiment, version(model), json.createObjectNode().put("section", section).put("offset", offset));
                    assertThat(projection.text(page)).hasSizeLessThan(10000);
                    assertThat(page.toString()).hasSizeLessThan(80000);
                    for (var row : page.path("records")) for (var value : row.path("values")) {
                        cells++;
                        assertThat(value.path("source_refs")).isNotEmpty();
                        assertThat(value.has("valueStatus")).isTrue();
                    }
                    int next = page.path("next_offset").asInt(-1);
                    assertThat(next == -1 || next > offset).isTrue();
                    offset = next;
                } while (offset >= 0);
                assertThat(cells).as(kind + ":" + section).isEqualTo(model.path(section).size());
            }
        }
    }

    @Test void researchDraftWithTemplateOpensThroughTheExistingTemplateReviewFlow() throws Exception {
        var actor = ActorContext.required();
        var category = UUID.randomUUID();
        var templateVersion = UUID.randomUUID();
        var repository = mock(ExperimentRepository.class);
        when(repository.categories(actor.organizationId(), false)).thenReturn(List.of(
            new ExperimentModels.Category(category, "TEST", "测试实验", null, true, 1)));
        var parser = new com.jsd.aird.tpl.infrastructure.XlsxStructureParser(json);
        var template = new com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog(json).compile(
            com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog.TEST,
            parser.parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx")));
        var snapshot = template.payload().path("snapshot");
        var model = json.createObjectNode().put("plan", "三个平行样；方法及结果待填写");
        model.set("documentSnapshot", com.jsd.aird.tpl.api.TemplateWorkbookValues.blankCopy(snapshot, List.of()));
        when(repository.create(any())).thenAnswer(invocation -> {
            var created = invocation.<ExperimentRepository.Create>getArgument(0);
            var summary = new ExperimentModels.Summary(created.id(), "EXP-TEST", created.title(),
                category, "测试实验", created.sourceType(), created.status(), null, null, null, null,
                null, null, created.ownerName(), created.experimentDate(), 1, 1, Instant.now());
            when(repository.detail(actor.organizationId(), created.id())).thenReturn(Optional.of(
                new ExperimentModels.Detail(summary, created.versionId(), null, created.templateVersionId(),
                    created.templateHash(), created.templateSnapshot(), created.editModel(), List.of(), List.of())));
            return summary;
        });
        var facade = new ExperimentDraftApplicationFacade(repository, new ExperimentEditModelNormalizer(json),
            json, mock(AuthorizationService.class));
        var created = facade.createResearchDraft(new ExperimentDraftFacade.ResearchDraftCommand(
            "验收草稿", category, null, null, null, actor.username(), java.time.LocalDate.of(2026, 11, 6),
            templateVersion, "snapshot-hash", snapshot, model));
        var definition = new TemplateDataImportFacade.DataTemplateDefinition(null, templateVersion, "ANY_CODE",
            "实验数据模板", null, 1, "XLSX", json.createObjectNode(), json.createObjectNode(), List.of());
        when(templates.getVersion(actor.organizationId(), templateVersion)).thenReturn(definition);
        when(templates.getBindings(actor.organizationId(), templateVersion)).thenReturn(List.of());
        var reader = new ExperimentService(repository, json, mock(com.jsd.aird.ops.application.port.FileStorageFacade.class),
            new ExperimentEditModelNormalizer(json), null, templates, new JsonCanonicalizer(json));
        var detail = reader.detail(created.experimentId());
        assertThat(detail.summary().sourceType()).isEqualTo("TEMPLATE");
        assertThat(detail.editModel().path("plan").asText()).contains("三个平行样");
        assertThat(reader.templateDataReview(created.experimentId()).templateVersionId()).isEqualTo(templateVersion);
        assertThatThrownBy(() -> reader.transition(created.experimentId(), 1,
            com.jsd.aird.rnd.domain.ExperimentStatus.PENDING_REVIEW, null)).hasMessageContaining("请先确认");
    }
}
