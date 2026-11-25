package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.*;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.excel.*;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportedDraftWorkflowTest {
    final ObjectMapper json=new ObjectMapper();
    final UUID organization=UUID.randomUUID(),actor=UUID.randomUUID(),template=UUID.randomUUID();
    final ExperimentRepository repository=mock(ExperimentRepository.class);
    final TemplateDataImportFacade templates=mock(TemplateDataImportFacade.class);
    Detail stored;
    @AfterEach void clear(){ActorContext.clear();}

    Detail detail(ExperimentStatus status,long revision,JsonNode model) {
        return new Detail(new Summary(stored.summary().id(),"EXP-TEST","导入回归",null,null,"EXCEL_IMPORT",status,
                null,null,null,null,null,null,"tester",LocalDate.now(),1,revision,Instant.now()),stored.currentVersionId(),
                null,template,"hash",stored.templateSnapshot(),model,List.of(),List.of());
    }
    ExperimentService.DraftCommand command(JsonNode model) {
        return new ExperimentService.DraftCommand("EXP-TEST","导入回归",null,null,null,null,null,"tester",LocalDate.now(),
                template,"hash",stored.templateSnapshot(),model);
    }
    void confirmed(Detail value) {
        assertThat(value.editModel().at("/importDataReview/confirmed").asBoolean()).isTrue();
        assertThat(value.editModel().at("/importDataReview/confirmedRevision").asLong()).isEqualTo(value.summary().revision());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void preservesReviewedLaboratoryContentThroughSaveReentrySubmitApproveAndPublish(boolean legacy) throws Exception {
        ActorContext.set(new Actor(organization,actor,"tester"));
        var parser=new XlsxStructureParser(json);
        var parsed=parser.parseInstance(getClass().getResourceAsStream("/templates/unified/synthesis.xlsx"));
        var pack=new UnifiedLabTemplateCatalog(json).compile(UnifiedLabTemplateCatalog.SYNTHESIS,parsed).payload();
        var baseline=pack.path("snapshot");
        when(templates.getVersion(organization,template)).thenReturn(new TemplateDataImportFacade.DataTemplateDefinition(
                template,template,"EXP_SYNTHESIS_UNIFIED","合成",null,1,"XLSX",pack.path("schema"),pack.path("mapping"),List.of()));
        when(templates.getBindings(organization,template)).thenReturn(List.of());
        when(repository.create(any())).thenAnswer(call->{
            var c=call.<ExperimentRepository.Create>getArgument(0);
            stored=new Detail(new Summary(c.id(),"EXP-TEST",c.title(),null,null,"EXCEL_IMPORT",ExperimentStatus.DRAFT,
                    null,null,null,null,null,null,"tester",LocalDate.now(),1,0,Instant.now()),c.versionId(),null,template,"hash",baseline,c.editModel(),List.of(),List.of());
            return stored.summary();
        });
        when(repository.detail(eq(organization),any())).thenAnswer(call->Optional.of(stored));
        when(repository.saveDraft(eq(organization),any(),anyLong(),any(),eq(actor),any())).thenAnswer(call->{
            var draft=call.<ExperimentRepository.Draft>getArgument(3);
            return stored=detail(stored.summary().status(),stored.summary().revision()+1,draft.editModel());
        });
        when(repository.transition(eq(organization),any(),anyLong(),any(),any(),eq(actor),any())).thenAnswer(call->
                stored=detail(call.getArgument(3),stored.summary().revision()+1,stored.editModel()));
        when(repository.publish(eq(organization),any(),anyLong(),eq(actor),any())).thenAnswer(call->
                stored=detail(ExperimentStatus.COMPLETED,stored.summary().revision()+1,stored.editModel()));
        var path=System.getProperty("lab.pu62.workbook");
        var original=parsed.snapshot();
        if(path!=null)try(var stream=java.nio.file.Files.newInputStream(java.nio.file.Path.of(path))){original=parser.parseInstance(stream).snapshot();}
        var source=LabWorkbook.prepare(json,original,baseline,"source");
        var model=json.createObjectNode().put("sourceImportJobId",UUID.randomUUID().toString());model.set("documentSnapshot",source);
        var proof=json.createObjectNode().put("importJobId",model.path("sourceImportJobId").asText()).put("revision","audit-id")
                .put("confirmedBy",actor.toString()).put("confirmedAt",Instant.now().toString())
                .put("dataHash",new JsonCanonicalizer(json).hash(WorkbookReviewContent.workbook(json,source)));
        var drafts=new ExperimentDraftApplicationFacade(repository,new ExperimentEditModelNormalizer(json),json,null);drafts.setTemplates(templates);
        var created=drafts.createImportedDraft(new ExperimentDraftFacade.ImportedDraftCommand(organization,actor,"tester","导入回归",null,
                "EXCEL_IMPORT",null,null,null,"tester",LocalDate.now(),template,"hash",baseline,model,proof));
        var service=new ExperimentService(repository,json,mock(com.jsd.aird.ops.application.port.FileStorageFacade.class),
                new ExperimentEditModelNormalizer(json),null,templates,new JsonCanonicalizer(json));
        if(legacy) {
            var old=(ObjectNode)stored.editModel().deepCopy();old.remove(List.of("importDataReview","templateInstance"));
            for(var sheet:old.path("documentSnapshot").path("sheets"))if(sheet.path("custom") instanceof ObjectNode custom)custom.remove("templateInstance");
            stored=detail(ExperimentStatus.DRAFT,0,old);
            var evidence=proof.deepCopy();evidence.set("initialModel",old.deepCopy());
            when(repository.initialReviewedImport(organization,created.experimentId())).thenReturn(Optional.of(evidence));
        }
        var opened=service.detail(created.experimentId());confirmed(opened);
        if(!legacy)assertThat(WorkbookStructure.instance(opened.editModel().path("documentSnapshot")).isObject()).isTrue();
        var saved=service.save(created.experimentId(),opened.summary().revision(),command(opened.editModel()));
        confirmed(saved);
        // The compatibility path must still reject edits in every physical sheet.
        if(legacy)for(var sheet:saved.editModel().path("documentSnapshot").path("sheets")) {
            if(sheet.path("name").asText().startsWith("_JSD"))continue;
            var changed=saved.editModel().path("documentSnapshot").deepCopy();
            var target=(ObjectNode)changed.path("sheets").path(sheet.path("id").asText());
            target.withObject("/cellData/10/2").put("v",9999);
            assertThat(WorkbookReviewContent.equivalent(json,opened.editModel().path("documentSnapshot"),changed)).isFalse();
        }
        if(legacy)for(var key:List.of("unit","valueType","testCondition")) {
            var changed=saved.editModel().path("documentSnapshot").deepCopy();
            var instance=WorkbookStructure.instance(changed);
            ((ObjectNode)instance.path("model").path("fields").get(0)).put(key,"changed");
            assertThat(WorkbookReviewContent.equivalent(json,opened.editModel().path("documentSnapshot"),changed)).isFalse();
        }
        confirmed(service.detail(created.experimentId()));
        confirmed(service.transition(created.experimentId(),saved.summary().revision(),ExperimentStatus.PENDING_REVIEW,null));
        confirmed(service.detail(created.experimentId()));
        confirmed(service.transition(created.experimentId(),stored.summary().revision(),ExperimentStatus.APPROVED,null));
        confirmed(service.publish(created.experimentId(),stored.summary().revision()));
        confirmed(service.detail(created.experimentId()));
    }
}
