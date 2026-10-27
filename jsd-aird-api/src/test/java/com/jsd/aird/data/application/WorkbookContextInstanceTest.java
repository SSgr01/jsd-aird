package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.excel.WorkbookInstanceParser;
import com.jsd.aird.shared.excel.WorkbookStructure;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class WorkbookContextInstanceTest {
    @Test void laboratoryImportsUseTheBoundTemplateGroupsBeforeAnyDraftIsSaved()throws Exception {
        var json=new ObjectMapper();var parser=new com.jsd.aird.tpl.infrastructure.XlsxStructureParser(json);
        var pack=new com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog(json).compile("EXP_TEST_UNIFIED",parser.parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"))).payload();
        var source=parser.parseInstance(getClass().getResourceAsStream("/unified-lab/test-example.xlsx"));
        var org=UUID.randomUUID();var jobId=UUID.randomUUID();var version=UUID.randomUUID();var fileId=UUID.randomUUID();
        var repository=mock(DataRepository.class);var files=mock(FileObjectRepository.class);var storage=mock(ObjectStorage.class);var templates=mock(TemplateDataImportFacade.class);var workbookParser=mock(WorkbookInstanceParser.class);
        var definition=new TemplateDataImportFacade.DataTemplateDefinition(UUID.randomUUID(),version,"EXP_TEST_UNIFIED","测试实验",null,1,"XLSX",pack.path("schema"),pack.path("mapping"),List.of(),9,7,"hash",pack.path("contract"));
        var job=new DataRepository.Job(jobId,fileId,"hash","test.xlsx","XLSX",version,null,"WAITING_CONFIRM",100,"WAITING_CONFIRM","tester",null,Instant.now(),Instant.now());
        when(repository.findJob(org,jobId)).thenReturn(Optional.of(job));when(templates.getVersion(org,version)).thenReturn(definition);
        when(templates.getExperimentTemplateVersion(org,version)).thenReturn(new TemplateDataImportFacade.PublishedExperimentTemplate(definition.templateId(),version,"EXP_TEST_UNIFIED","测试实验",1,"hash",pack.path("snapshot"),pack.path("mapping"),pack.path("contract")));
        when(files.find(org,fileId)).thenReturn(Optional.of(new FileObjectRepository.FileObject(fileId,org,"qa","test.xlsx","application/octet-stream",1,"hash","ACTIVE")));
        when(storage.get("qa")).thenAnswer(call->new ObjectStorage.StoredObject(new ByteArrayInputStream(new byte[0]),0,"application/octet-stream"));
        when(workbookParser.parseInstance(any())).thenReturn(source);
        when(templates.prepareWorkbookImport(eq(org),eq(version),any(),any())).thenAnswer(call->{
            var instance=(com.fasterxml.jackson.databind.JsonNode)call.getArgument(3);
            assertThat(instance.path("model").path("groups")).hasSize(9);
            assertThat(instance.path("records")).hasSize(6);
            return new TemplateDataImportFacade.ImportExecution(definition,List.of(),null);
        });
        ActorContext.set(new Actor(org,UUID.randomUUID(),"tester"));
        try {
            var context=new DataWorkbookService(repository,files,storage,workbookParser,templates,json).importJob(jobId);
            assertThat(context.templateInstance().path("model").path("groups")).isEqualTo(pack.at("/schema/x-jsd-field-model/groups"));
            verify(templates).getVersion(org,version);
            verify(templates,never()).getPublishedExperimentTemplate(any(),any());
        }finally{ActorContext.clear();}
    }
    @Test void sourceWorkbookInstanceIsUsedBeforeTheFirstDraftSave()throws Exception {
        var json=new ObjectMapper();var org=UUID.randomUUID();var jobId=UUID.randomUUID();var version=UUID.randomUUID();var fileId=UUID.randomUUID();
        var repository=mock(DataRepository.class);var files=mock(FileObjectRepository.class);var storage=mock(ObjectStorage.class);var parser=mock(WorkbookInstanceParser.class);var templates=mock(TemplateDataImportFacade.class);
        var snapshot=json.readTree("{\"sheets\":{\"s\":{\"id\":\"s\",\"name\":\"普通明细\"}}}");
        var instance=json.readTree("{\"version\":2,\"model\":{\"fields\":[]},\"mapping\":[],\"records\":[],\"items\":[],\"issues\":[]}");WorkbookStructure.attach(snapshot,instance);
        var original=new TemplateDataImportFacade.DataTemplateDefinition(UUID.randomUUID(),version,"QA","QA",null,1,"XLSX",json.createObjectNode(),json.createArrayNode(),List.of(),8,7,"hash",json.createObjectNode());
        var job=new DataRepository.Job(jobId,fileId,"hash","qa.xlsx","XLSX",version,null,"WAITING_CONFIRM",100,"WAITING_CONFIRM","tester",null,Instant.now(),Instant.now());
        when(repository.findJob(org,jobId)).thenReturn(Optional.of(job));when(templates.getVersion(org,version)).thenReturn(original);
        when(files.find(org,fileId)).thenReturn(Optional.of(new FileObjectRepository.FileObject(fileId,org,"qa","qa.xlsx","application/octet-stream",1,"hash","ACTIVE")));
        when(storage.get("qa")).thenAnswer(call->new ObjectStorage.StoredObject(new ByteArrayInputStream(new byte[0]),0,"application/octet-stream"));
        when(parser.parseInstance(any())).thenReturn(new WorkbookInstanceParser.ParsedWorkbook(json.createObjectNode(),snapshot,List.of()));
        when(templates.prepareWorkbookImport(eq(org),eq(version),any(),eq(instance))).thenReturn(new TemplateDataImportFacade.ImportExecution(original,List.of(),null));
        ActorContext.set(new Actor(org,UUID.randomUUID(),"tester"));
        try {
            var context=new DataWorkbookService(repository,files,storage,parser,templates,json).importJob(jobId);
            assertThat(context.templateInstance()).isEqualTo(instance);
            verify(templates).prepareWorkbookImport(eq(org),eq(version),any(),eq(instance));
        } finally {ActorContext.clear();}
    }
}
