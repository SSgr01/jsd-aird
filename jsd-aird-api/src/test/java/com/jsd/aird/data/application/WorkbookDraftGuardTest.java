package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkbookDraftGuardTest {
    final ObjectMapper json=new ObjectMapper();
    final DataRepository repository=mock(DataRepository.class);
    final TemplateDataImportFacade templates=mock(TemplateDataImportFacade.class);
    final DataImportService service=new DataImportService(repository,mock(FileObjectRepository.class),templates,json,mock(AuditLogFacade.class),mock(OpsAsyncFacade.class));
    final UUID org=UUID.randomUUID(),id=UUID.randomUUID();
    DataRepository.Job job(){return new DataRepository.Job(id,UUID.randomUUID(),"hash","fixture.xlsx","XLSX",UUID.randomUUID(),null,"WAITING_CONFIRM",100,"WAITING_CONFIRM","test",null,Instant.parse("2026-10-25T00:00:00Z"),Instant.parse("2026-10-25T00:00:00Z"));}
    void actor(){ActorContext.set(new Actor(org,UUID.randomUUID(),"tester"));}
    @AfterEach void clear(){ActorContext.clear();}
    @Test void staleRevisionNeverWritesOrReparsesTheWorkbook(){
        actor();when(repository.findJob(org,id)).thenReturn(Optional.of(job()));
        assertThatThrownBy(()->service.saveWorkbookDraft(id,"stale",json.createObjectNode(),json.createObjectNode(),json.createArrayNode())).isInstanceOf(ApiException.class);
        verify(repository,never()).saveWorkbookDraft(any(),any(),any(),any(),any(),any());
        verify(repository,never()).clearParsed(any(),any());
    }
    @Test void reviewLockPreventsSavingTheStructureAndValuesTogether(){
        actor();transactions();var job=job();when(repository.findJob(org,id)).thenReturn(Optional.of(job));
        when(repository.findJobForUpdate(org,id)).thenReturn(Optional.of(job));
        var prepared=emptyPrepared(job);
        when(templates.prepareWorkbookImport(any(),any(),any(),any())).thenReturn(prepared);
        var guard=mock(DataReviewGuard.class);service.setReviewGuard(guard);
        doThrow(new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"审核期间不能修改")).when(guard).beforeEdit(org,id);
        assertThatThrownBy(()->service.saveWorkbookDraft(id,job.getRevision(),workbook(),instance(),json.createArrayNode())).isInstanceOf(ApiException.class).hasMessageContaining("审核期间");
        verify(repository,never()).saveWorkbookDraft(any(),any(),any(),any(),any(),any());
    }
    @Test void experimentRouteCannotEditADataCenterOwnedJob(){
        actor();when(repository.findSourceOwner(org,id)).thenReturn(Optional.of("DATA_CENTER"));
        assertThatThrownBy(()->service.saveExperimentWorkbookDraft(id,"revision",json.createObjectNode(),json.createObjectNode(),json.createArrayNode())).isInstanceOf(ApiException.class);
        verify(repository,never()).saveWorkbookDraft(any(),any(),any(),any(),any(),any());
    }

    @Test void lostSaveResponseCanBeAcknowledgedWithoutOverwritingOrReparsing() {
        actor();var current=job();var source=workbook();
        var normalized=com.jsd.aird.shared.excel.WorkbookStructure.validate(json,source,instance());
        when(repository.findJob(org,id)).thenReturn(Optional.of(current));
        when(repository.findWorkbookDraft(org,id)).thenReturn(Optional.of(new DataRepository.WorkbookDraft(source,normalized,json.createArrayNode())));
        assertThat(service.saveWorkbookDraft(id,"old-revision",source,normalized,json.createArrayNode())).isEqualTo(current);
        verifyNoInteractions(templates);
        verify(repository,never()).saveWorkbookDraft(any(),any(),any(),any(),any(),any());
    }

    @Test void lostResponseRecoveryNeverAcceptsDifferentPersistedContent() {
        actor();var current=job();var source=workbook();
        var normalized=com.jsd.aird.shared.excel.WorkbookStructure.validate(json,source,instance());
        var other=source.deepCopy();other.withObject("/sheets/s/cellData/0/0").put("v","8h");
        when(repository.findJob(org,id)).thenReturn(Optional.of(current));
        when(repository.findWorkbookDraft(org,id)).thenReturn(Optional.of(new DataRepository.WorkbookDraft(other,normalized,json.createArrayNode())));
        assertThatThrownBy(()->service.saveWorkbookDraft(id,"old-revision",source,normalized,json.createArrayNode()))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(templates);
        verify(repository,never()).saveWorkbookDraft(any(),any(),any(),any(),any(),any());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode workbook() {
        var source=json.createObjectNode();
        source.withObject("/sheets/s").put("id","s").withObject("/cellData/0/0").put("v","5h");
        return source;
    }
    private com.fasterxml.jackson.databind.node.ObjectNode instance() {
        var instance=json.createObjectNode().put("version",2);
        instance.withObject("/model").putArray("fields");instance.putArray("mapping");
        return instance;
    }
    private void transactions() {
        service.setWorkbookTransactions(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition def){}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){}
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){}
        });
    }
    @Test void parsesOutsideTheTransactionAndSavesExtractedDataOnlyOnceInsideIt() {
        actor();transactions();var job=job();var source=workbook();var instance=instance();
        when(repository.findJob(org,id)).thenReturn(Optional.of(job));
        when(repository.findJobForUpdate(org,id)).thenReturn(Optional.of(job));
        var guard=mock(DataReviewGuard.class);service.setReviewGuard(guard);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(guard).beforeEdit(org,id);
        when(repository.findWorkbookDraft(org,id)).thenReturn(Optional.of(new DataRepository.WorkbookDraft(source,instance,json.createArrayNode())));
        var definition=new TemplateDataImportFacade.DataTemplateDefinition(null,job.templateVersionId(),"V9","test",null,1,"XLSX",json.createObjectNode(),json.createArrayNode(),List.of(),9,2,"hash",json.createObjectNode());
        when(templates.getVersion(org,job.templateVersionId())).thenReturn(definition);
        when(templates.prepareWorkbookImport(eq(org),eq(job.templateVersionId()),any(),any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((com.fasterxml.jackson.databind.JsonNode)call.getArgument(2)).at("/sheets/s/cellData/0/0/v").asText()).isEqualTo("5h");
            return new TemplateDataImportFacade.ImportExecution(definition,List.of(),new TemplateDataImportFacade.ParsedTabularFile("XLSX","test",List.of()));
        });
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return null;})
                .when(repository).saveWorkbookDraft(eq(org),eq(id),any(),any(),any(),any());
        assertThat(service.saveWorkbookDraft(id,job.getRevision(),source,instance,json.createArrayNode())).isEqualTo(job);
        verify(templates,times(1)).prepareWorkbookImport(any(),any(),any(),any());
        verify(templates,never()).prepareWorkbookReview(any(),any(),any(),any());
        verify(repository).saveParsed(eq(id),eq("test"),anyList(),anyList(),anyList(),eq(id));
        verify(repository).replaceValidation(eq(org),eq(id),anyList(),anyList(),eq("WAITING_CONFIRM"));
    }
    @Test void aRevisionChangedDuringParsingCannotOverwriteTheNewerDraft() {
        actor();transactions();var job=job();
        when(repository.findJob(org,id)).thenReturn(Optional.of(job));
        var newer=mock(DataRepository.Job.class);when(newer.getRevision()).thenReturn("newer-revision");
        when(repository.findJobForUpdate(org,id)).thenReturn(Optional.of(newer));
        var prepared=emptyPrepared(job);
        when(templates.prepareWorkbookImport(any(),any(),any(),any())).thenReturn(prepared);
        assertThatThrownBy(()->service.saveWorkbookDraft(id,job.getRevision(),workbook(),instance(),json.createArrayNode())).isInstanceOf(ApiException.class);
        verify(repository,never()).saveWorkbookDraft(any(),any(),any(),any(),any(),any());
        verify(repository,never()).clearParsed(any(),any());
    }

    @Test void reExtractionPreparesOutsideTheTransactionAndReplacesDataInsideTheLock() {
        actor();transactions();var job=job();
        when(repository.findJob(org,id)).thenReturn(Optional.of(job));
        when(repository.findJobForUpdate(org,id)).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(job);
        });
        var sheetReads=new java.util.concurrent.atomic.AtomicInteger();
        when(repository.listSheets(org,id)).thenAnswer(call->{
            if(sheetReads.getAndIncrement()==0) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                verify(repository,never()).findJobForUpdate(any(),any());
            }
            return List.of();
        });
        var source=workbook();var instance=instance();
        when(repository.findWorkbookDraft(org,id)).thenReturn(Optional.of(new DataRepository.WorkbookDraft(source,instance,json.createArrayNode())));
        var definition=new TemplateDataImportFacade.DataTemplateDefinition(null,job.templateVersionId(),"V9","test",null,1,"XLSX",json.createObjectNode(),json.createArrayNode(),List.of(),9,2,"hash",json.createObjectNode());
        when(templates.getVersion(org,job.templateVersionId())).thenReturn(definition);
        when(templates.prepareWorkbookImport(any(),any(),any(),any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            verify(repository,never()).findJobForUpdate(any(),any());
            return new TemplateDataImportFacade.ImportExecution(definition,List.of(),new TemplateDataImportFacade.ParsedTabularFile("XLSX","test",List.of()));
        });
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return null;})
                .when(repository).saveParsed(any(),any(),anyList(),anyList(),anyList(),any());
        assertThat(service.parseAtRevision(id,job.getRevision(),true)).isEqualTo(job);
        verify(templates,times(1)).prepareWorkbookImport(any(),any(),any(),any());
        verify(repository).clearParsed(org,id);
        verify(repository).saveParsed(eq(id),eq("test"),anyList(),anyList(),anyList(),eq(id));
    }

    @Test void aConcurrentEditWhileReExtractingDoesNotClearDataOrInvalidateReview() {
        actor();transactions();var job=job();
        when(repository.findJob(org,id)).thenReturn(Optional.of(job));
        var newer=mock(DataRepository.Job.class);when(newer.getRevision()).thenReturn("newer-revision");
        when(repository.findJobForUpdate(org,id)).thenReturn(Optional.of(newer));
        var prepared=emptyPrepared(job);
        when(templates.prepareImport(any(),any(),any())).thenReturn(prepared);
        var guard=mock(DataReviewGuard.class);service.setReviewGuard(guard);
        assertThatThrownBy(()->service.parseAtRevision(id,job.getRevision(),true)).isInstanceOf(ApiException.class);
        verifyNoInteractions(guard);
        verify(repository,never()).clearParsed(any(),any());
        verify(repository,never()).updateJobStatus(any(),any(),any(),anyInt(),any(),any());
    }

    @Test void aSourceParserFailureLeavesTheExistingDataAndReviewUntouched() {
        actor();transactions();var job=job();
        when(repository.findJob(org,id)).thenReturn(Optional.of(job));
        when(templates.prepareImport(any(),any(),any())).thenThrow(new ApiException(ApiErrorCode.VALIDATION_ERROR,"source failure"));
        var guard=mock(DataReviewGuard.class);service.setReviewGuard(guard);
        assertThatThrownBy(()->service.parseAtRevision(id,job.getRevision(),true)).isInstanceOf(ApiException.class).hasMessageContaining("source failure");
        verifyNoInteractions(guard);
        verify(repository,never()).findJobForUpdate(any(),any());
        verify(repository,never()).clearParsed(any(),any());
        verify(repository,never()).updateJobStatus(any(),any(),any(),anyInt(),any(),any());
    }

    @Test void atomicWriteFailureIsNotMaskedByAnotherStatusWriteOnTheAbortedTransaction() {
        actor();transactions();var job=job();
        when(repository.findJob(org,id)).thenReturn(Optional.of(job));
        when(repository.findJobForUpdate(org,id)).thenReturn(Optional.of(job));
        when(repository.findWorkbookDraft(org,id)).thenReturn(Optional.of(new DataRepository.WorkbookDraft(workbook(),instance(),json.createArrayNode())));
        var definition=new TemplateDataImportFacade.DataTemplateDefinition(null,job.templateVersionId(),"V9","test",null,1,"XLSX",json.createObjectNode(),json.createArrayNode(),List.of(),9,2,"hash",json.createObjectNode());
        when(templates.getVersion(org,job.templateVersionId())).thenReturn(definition);
        when(templates.prepareWorkbookImport(any(),any(),any(),any())).thenReturn(new TemplateDataImportFacade.ImportExecution(definition,List.of(),new TemplateDataImportFacade.ParsedTabularFile("XLSX","test",List.of())));
        var original=new IllegalStateException("original write failure");
        doThrow(original).when(repository).saveParsed(any(),any(),anyList(),anyList(),anyList(),any());
        assertThatThrownBy(()->service.parseAtRevision(id,job.getRevision(),true)).isSameAs(original);
        verify(repository,never()).updateJobStatus(any(),any(),eq("FAILED"),anyInt(),any(),any());
    }

    private TemplateDataImportFacade.ImportExecution emptyPrepared(DataRepository.Job job) {
        var definition=new TemplateDataImportFacade.DataTemplateDefinition(null,job.templateVersionId(),"V8","test",null,1,"XLSX",json.createObjectNode(),json.createArrayNode(),List.of(),8,2,"hash",json.createObjectNode());
        when(templates.getVersion(org,job.templateVersionId())).thenReturn(definition);
        return new TemplateDataImportFacade.ImportExecution(definition,List.of(),new TemplateDataImportFacade.ParsedTabularFile("XLSX","test",List.of()));
    }
}
