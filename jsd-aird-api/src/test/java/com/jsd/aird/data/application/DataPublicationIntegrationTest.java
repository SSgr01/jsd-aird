package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.data.infrastructure.JdbcDataRepository;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionDecision;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs against a disposable PostgreSQL database with the application migrations applied. */
@EnabledIfSystemProperty(named="jsd.it.jdbcUrl", matches=".+")
class DataPublicationIntegrationTest {
    @Test
    void confirmationReviewReturnRevisionAndRecordGovernancePreservePublishedFacts() {
        var source = new DriverManagerDataSource(System.getProperty("jsd.it.jdbcUrl"),
                System.getProperty("jsd.it.username"), System.getProperty("jsd.it.password"));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            ActorContext.set(ActorContext.developmentDefault());
            try {
                var org=ActorContext.required().organizationId(); var user=ActorContext.required().userId();
                var jdbc=new JdbcTemplate(source); var json=new ObjectMapper().findAndRegisterModules();
                var repo=new JdbcDataRepository(jdbc,json); var templates=mock(TemplateDataImportFacade.class);
                var file=UUID.randomUUID(); var job=UUID.randomUUID(); var version=UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO ops.file_object(id,organization_id,bucket,object_key,original_name,content_type,size_bytes,sha256,status,created_by,activated_at)
                        VALUES(?,?,'data','resin.xlsx','树脂黏度记录.xlsx','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',10,?,'ACTIVE',?,now())
                        """,file,org,"a".repeat(64),user);
                repo.insertJob(new DataRepository.NewJob(job,org,file,"a".repeat(64),"树脂黏度记录.xlsx","XLSX",version,false,user));
                var sheet=new DataRepository.Sheet(UUID.randomUUID(),"resin","树脂黏度",0,true,List.of(1),2,2,json.createObjectNode(),"CONFIRMED");
                var mapping=new DataRepository.Mapping(UUID.randomUUID(),"resin","A","黏度","VISCOSITY","黏度","MAP","NUMBER",null,null,
                        json.createObjectNode().put("bindingId","viscosity").put("dataPath","/viscosity"),"CONFIRMED");
                var rowId=UUID.randomUUID();
                var row=new DataRepository.Row(rowId,"resin",2,json.createObjectNode().put("A","100"),json.createObjectNode(),json.createObjectNode(),"STAGED");
                repo.saveParsed(job,"workbook",List.of(sheet),List.of(mapping),List.of(row),null);
                when(templates.getVersion(org,version)).thenReturn(new TemplateDataImportFacade.DataTemplateDefinition(UUID.randomUUID(),version,"RESIN","树脂黏度","物料",1,"XLSX",
                        json.createObjectNode(),json.createArrayNode(),List.of(new TemplateDataImportFacade.FieldDefinition("VISCOSITY","黏度","NUMBER",null,true,false,List.of(),"/viscosity"))));
                var audit=mock(AuditLogFacade.class);
                var imports=new DataImportService(repo,mock(FileObjectRepository.class),templates,json,audit,mock(OpsAsyncFacade.class));
                var guard=new DataReviewGuard(jdbc); imports.setReviewGuard(guard);
                AuthorizationService auth=check -> new PermissionDecision(true,check.permissionCode(),"ALLOW","ALL","ROLE");
                var service=new DataPublicationService(jdbc,json,repo,imports,auth,audit);
                var confirmed=service.confirm(job);
                assertThat(confirmed.status()).isEqualTo("CONFIRMED");
                assertThat(service.records(job)).isEmpty();
                service.submit(job,user);
                assertThatThrownBy(() -> service.submit(job,user)).isInstanceOf(ApiException.class);
                jdbc.update("UPDATE data.staging_row SET raw_values_jsonb='{\"A\":\"101\"}'::jsonb WHERE id=?",rowId);
                assertThatThrownBy(() -> service.review(job,confirmed.confirmedSnapshot().id(),true,""))
                        .isInstanceOf(ApiException.class).hasMessageContaining("已变化");
                jdbc.update("UPDATE data.staging_row SET raw_values_jsonb='{\"A\":\"100\"}'::jsonb WHERE id=?",rowId);
                assertThatThrownBy(() -> imports.correctValue(job,rowId,"viscosity","/viscosity",json.getNodeFactory().textNode("200"),"原始记录核对"))
                        .isInstanceOf(ApiException.class).hasMessageContaining("审核期间不能修改");
                assertThatThrownBy(() -> service.review(job,UUID.randomUUID(),true,""))
                        .isInstanceOf(ApiException.class).hasMessageContaining("快照已变化");
                service.review(job,confirmed.confirmedSnapshot().id(),true,"原始记录一致");
                assertThat(service.get(job).status()).isEqualTo("APPROVED");
                assertThat(service.records(job)).isEmpty();
                assertThatThrownBy(() -> imports.correctValue(job,rowId,"viscosity","/viscosity",json.getNodeFactory().textNode("200"),"读数复核")).isInstanceOf(ApiException.class);
                service.publish(job);
                assertThatThrownBy(() -> service.review(job,confirmed.confirmedSnapshot().id(),true,"重复点击"))
                        .isInstanceOf(ApiException.class).hasMessageContaining("不在审核中");
                var record=service.records(job).getFirst(); var recordId=record.id();
                assertThat(record.values().path("VISCOSITY").path("normalizedValue").asText()).isEqualTo("100");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.structured_retrieval_projection WHERE import_job_id=?",Integer.class,job)).isEqualTo(1);
                service.govern(job,recordId,"树脂黏度", "DISABLED","仪器校准期间暂停使用");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.structured_retrieval_projection WHERE import_job_id=?",Integer.class,job)).isZero();
                service.govern(job,recordId,"树脂黏度", "ACTIVE","校准已完成");
                service.revise(job);
                imports.correctValue(job,rowId,"viscosity","/viscosity",json.getNodeFactory().textNode("200"),"读数复核");
                assertThat(service.records(job).getFirst().values().path("VISCOSITY").path("normalizedValue").asText()).isEqualTo("100");
                var revision=service.confirm(job); service.submit(job,user);
                assertThatThrownBy(() -> service.review(job,revision.confirmedSnapshot().id(),false," ")).isInstanceOf(ApiException.class);
                service.review(job,revision.confirmedSnapshot().id(),false,"请核对仪器读数");
                imports.correctValue(job,rowId,"viscosity","/viscosity",json.getNodeFactory().textNode("250"),"按仪器原始读数更正");
                var finalVersion=service.submit(job,user);
                assertThat(finalVersion.status()).isEqualTo("PENDING_REVIEW");
                assertThat(finalVersion.events()).filteredOn(event -> event.action().equals("CONFIRMED")).hasSize(3);
                service.review(job,finalVersion.confirmedSnapshot().id(),true,"已核对");
                assertThat(service.records(job).getFirst().values().path("VISCOSITY").path("normalizedValue").asText()).isEqualTo("100");
                service.publish(job);
                assertThat(service.records(job).getFirst().id()).isEqualTo(recordId);
                assertThat(service.records(job).getFirst().name()).isEqualTo("树脂黏度");
                assertThat(service.records(job).getFirst().values().path("VISCOSITY").path("normalizedValue").asText()).isEqualTo("250");
                assertThat(jdbc.queryForObject("SELECT value_text FROM data.data_value WHERE record_id=?",String.class,recordId)).isEqualTo("250");
                assertThat(new String(service.export(job),java.nio.charset.StandardCharsets.UTF_8)).contains("250","树脂黏度记录.xlsx","A2");
                assertThat(service.snapshot(job,confirmed.confirmedSnapshot().id()).path("rows").get(0).path("normalizedValues").path("VISCOSITY").path("normalizedValue").asText()).isEqualTo("100");
                var savepoint=transaction.createSavepoint();
                assertThatThrownBy(() -> jdbc.update("UPDATE data.publication_snapshot SET payload_jsonb='{}'::jsonb WHERE id=?",confirmed.confirmedSnapshot().id())).isInstanceOf(RuntimeException.class);
                transaction.rollbackToSavepoint(savepoint); transaction.releaseSavepoint(savepoint);
                service.revise(job);
                var reparsed=new DataRepository.Row(UUID.randomUUID(),"resin",2,json.createObjectNode().put("A","300"),json.createObjectNode(),json.createObjectNode(),"STAGED");
                repo.saveParsed(job,"workbook",List.of(sheet),List.of(mapping),List.of(reparsed),null);
                var reparsedConfirmation=service.submit(job,user);
                service.review(job,reparsedConfirmation.confirmedSnapshot().id(),true,"工作表重新读取后核对一致");
                service.publish(job);
                assertThat(service.records(job).getFirst().id()).isEqualTo(recordId);
                service.govern(job,recordId,"树脂黏度","DISABLED","该批次记录暂停使用");
                service.govern(job,recordId,"树脂黏度","ACTIVE","恢复使用");
                service.setPublishedEnabled(job,false);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.structured_retrieval_projection WHERE import_job_id=?",Integer.class,job)).isZero();
                service.revise(job);
                assertThat(service.get(job).publishedEnabled()).isFalse();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.structured_retrieval_projection WHERE import_job_id=?",Integer.class,job)).isZero();
                var restarted=service.submit(job,user);
                assertThatThrownBy(() -> service.publish(job)).isInstanceOf(ApiException.class).hasMessageContaining("先完成审核");
                service.review(job,restarted.confirmedSnapshot().id(),true,"修订内容一致");
                service.publish(job);
                assertThat(service.get(job).publishedEnabled()).isTrue();
                service.setPublishedEnabled(job,false); service.setPublishedEnabled(job,true);
                assertThat(service.records(job).getFirst().status()).isEqualTo("ACTIVE");
                assertThatThrownBy(() -> service.govern(job,recordId,"树脂黏度","VOIDED",""))
                        .isInstanceOf(ApiException.class).hasMessageContaining("仅支持有效、停用");
                assertThat(service.get(job).events()).anyMatch(event -> event.action().equals("RETURNED") && "请核对仪器读数".equals(event.comment()));
            } finally { ActorContext.clear(); }
        });
    }

    @Test
    void matchedWorkbookValidatesBothSheetsImmediatelyAndRereadingPreservesCorrections() {
        var source=new DriverManagerDataSource(System.getProperty("jsd.it.jdbcUrl"),System.getProperty("jsd.it.username"),System.getProperty("jsd.it.password"));
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
            tx.setRollbackOnly(); ActorContext.set(ActorContext.developmentDefault());
            try {
                var org=ActorContext.required().organizationId();var user=ActorContext.required().userId();
                var jdbc=new JdbcTemplate(source);var json=new ObjectMapper().findAndRegisterModules();
                var repo=new JdbcDataRepository(jdbc,json);var templates=mock(TemplateDataImportFacade.class);
                var job=UUID.randomUUID();var file=UUID.randomUUID();var version=UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO ops.file_object(id,organization_id,bucket,object_key,original_name,content_type,size_bytes,sha256,status,created_by,activated_at)
                        VALUES(?,?,'data','resin.xlsx','树脂黏度记录.xlsx','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',10,?,'ACTIVE',?,now())
                        """,file,org,"e".repeat(64),user);
                repo.insertJob(new DataRepository.NewJob(job,org,file,"e".repeat(64),"树脂黏度记录.xlsx","XLSX",version,false,user));
                when(templates.getVersion(org,version)).thenReturn(new TemplateDataImportFacade.DataTemplateDefinition(UUID.randomUUID(),version,"RESIN","树脂黏度","物料",1,"XLSX",
                        json.createObjectNode(),json.createArrayNode(),List.of(new TemplateDataImportFacade.FieldDefinition("VISCOSITY","黏度","NUMBER",null,true,false,List.of(),"/viscosity"))));
                var sheets=List.of(
                        new TemplateDataImportFacade.ParsedSheet("resin-a","树脂甲",0,1,2,1,1,List.of(1),1,2,List.of(List.of("黏度"),List.of("100"))),
                        new TemplateDataImportFacade.ParsedSheet("resin-b","树脂乙",1,1,2,1,1,List.of(1),1,2,List.of(List.of("黏度"),List.of("200"))));
                when(templates.parse(org,version,file)).thenReturn(new TemplateDataImportFacade.ParsedTabularFile("XLSX","workbook",sheets));
                var imports=new DataImportService(repo,mock(FileObjectRepository.class),templates,json,mock(AuditLogFacade.class),mock(OpsAsyncFacade.class));
                imports.parseInternal(org,job);
                assertThat(repo.findJob(org,job).orElseThrow().status()).isEqualTo("WAITING_CONFIRM");
                assertThat(repo.listRows(org,job)).hasSize(2).allSatisfy(row -> assertThat(row.status()).isEqualTo("VALID"));
                var first=repo.listRows(org,job).stream().filter(row -> "resin-a".equals(row.sheetId())).findFirst().orElseThrow();
                imports.correctValue(job,first.id(),"VISCOSITY","/viscosity",json.getNodeFactory().textNode("150"),"按原始读数补充");
                imports.reExtract(job);
                var reread=repo.listRows(org,job).stream().filter(row -> "resin-a".equals(row.sheetId())).findFirst().orElseThrow();
                assertThat(reread.id()).isEqualTo(first.id());
                assertThat(reread.correctedValues().path("VISCOSITY").path("correctedValue").asText()).isEqualTo("150");
                assertThat(repo.findJob(org,job).orElseThrow().status()).isEqualTo("WAITING_CONFIRM");
                assertThat(repo.listIssues(org,job)).isEmpty();
            } finally { ActorContext.clear(); }
        });
    }

    @Test
    void freeRecognitionConfirmationKeepsThePublishedHeadUntilApproval() {
        var source=new DriverManagerDataSource(System.getProperty("jsd.it.jdbcUrl"),System.getProperty("jsd.it.username"),System.getProperty("jsd.it.password"));
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
            tx.setRollbackOnly(); ActorContext.set(ActorContext.developmentDefault());
            try {
                var org=ActorContext.required().organizationId();var user=ActorContext.required().userId();
                var jdbc=new JdbcTemplate(source);var json=new ObjectMapper().findAndRegisterModules();
                var repo=new JdbcDataRepository(jdbc,json);var recognition=new com.jsd.aird.data.infrastructure.JdbcSourceRecognitionRepository(jdbc,json);
                var job=UUID.randomUUID();var file=UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO ops.file_object(id,organization_id,bucket,object_key,original_name,content_type,size_bytes,sha256,status,created_by,activated_at)
                        VALUES(?,?,'data','resin.pdf','树脂理化性能.pdf','application/pdf',10,?,'ACTIVE',?,now())
                        """,file,org,"b".repeat(64),user);
                recognition.create(new com.jsd.aird.recognition.application.port.SourceRecognitionRepository.NewJob(job,org,file,"树脂理化性能.pdf","b".repeat(64),"PDF","DATA_CENTER","FREEFORM",null,null,"DATA_ONLY",null,"ALL",json.createObjectNode(),json.createArrayNode(),user));
                var imports=mock(DataImportService.class);
                AuthorizationService auth=check -> new PermissionDecision(true,check.permissionCode(),"ALLOW","ALL","ROLE");
                var service=new DataPublicationService(jdbc,json,repo,imports,auth,mock(AuditLogFacade.class));
                UUID firstSubmission=null;UUID stableRecord=null;
                for(var value:List.of("100","200")) {
                    if(firstSubmission!=null) service.revise(job);
                    var workspace=json.createObjectNode().put("value",value);
                    jdbc.update("UPDATE data.import_job SET recognition_workspace_jsonb=?::jsonb,status='WAITING_MAPPING' WHERE id=?",workspace.toString(),job);
                    var current=recognition.find(org,job,"DATA_CENTER").orElseThrow();
                    assertThat(recognition.claimFinalization(org,job,"DATA_CENTER",current.recognitionRevision())).isEqualTo(1);
                    var fact=json.createObjectNode();fact.putArray("recognizedItems").addObject().put("fieldCode","VISCOSITY").put("labelPath","黏度").put("effectiveValue",value).putObject("sourceCoordinate").put("pageNo",1);
                    var sample=new com.jsd.aird.recognition.application.port.SourceRecognitionRepository.RecognizedSample("experiment-1","sample-1","resin",List.of("page:1"),"树脂样品",json.createObjectNode(),json.createObjectNode(),fact,json.createObjectNode().put("pageNo",1),"c".repeat(64));
                    var finalized=recognition.finalizeData(org,job,user,List.of(sample),json.createObjectNode(),"d".repeat(64));
                    recognition.markFinalized(org,job);service.confirmRecognition(job,finalized.submissionId());
                    if(firstSubmission==null) assertThat(service.records(job)).isEmpty();
                    else {
                        assertThat(jdbc.queryForObject("SELECT confirmed_submission_id FROM data.confirmed_submission_head WHERE import_job_id=?",UUID.class,job)).isEqualTo(firstSubmission);
                        assertThat(service.records(job).getFirst().values().path("recognizedItems").get(0).path("effectiveValue").asText()).isEqualTo("100");
                    }
                    var confirmed=service.get(job);service.submit(job,user);service.review(job,confirmed.confirmedSnapshot().id(),true,"已核对原文");
                    service.publish(job);
                    if(stableRecord==null) stableRecord=service.records(job).getFirst().id();
                    assertThat(service.records(job).getFirst().id()).isEqualTo(stableRecord);
                    firstSubmission=finalized.submissionId();
                }
                assertThat(new String(service.export(job),java.nio.charset.StandardCharsets.UTF_8)).contains("200","黏度","pageNo");
                verifyNoInteractions(imports);
            } finally {ActorContext.clear();}
        });
    }
}
