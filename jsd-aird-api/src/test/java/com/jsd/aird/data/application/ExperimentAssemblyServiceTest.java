package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.data.application.port.ExperimentAssemblyRepository;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.ops.application.port.AsyncJobHandler.ExecutionContext;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.recognition.application.port.SourceRecognitionRepository;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.*;
import org.springframework.transaction.TransactionDefinition;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExperimentAssemblyServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final UUID org=UUID.randomUUID(),user=UUID.randomUUID(),job=UUID.randomUUID(),category=UUID.randomUUID(),version=UUID.randomUUID();
    final String key="FILE:83e3f3819ed819ae1649586a",hash="27f6dd601e8b99d548e32799a41516a24a2336125e750dfb27c4ac04f3a09769";
    final ExperimentAssemblyRepository repository=mock(ExperimentAssemblyRepository.class);
    final ExperimentImportAssembler assembler=mock(ExperimentImportAssembler.class);
    final TemplateDataImportFacade templates=mock(TemplateDataImportFacade.class);
    final ExperimentDraftFacade experiments=mock(ExperimentDraftFacade.class);
    final DataWorkbookService workbooks=mock(DataWorkbookService.class);
    final OpsAsyncFacade jobs=mock(OpsAsyncFacade.class);
    final SourceRecognitionRepository recognition=mock(SourceRecognitionRepository.class);
    final ExperimentAssemblyService service=new ExperimentAssemblyService(repository,assembler,templates,experiments,workbooks,
            mock(ProjectResourceFacade.class),jobs,mock(AuthorizationService.class),json,recognition,new Transactions());
    final ExperimentAssemblyRepository.SourceImport source=new ExperimentAssemblyRepository.SourceImport(job,UUID.randomUUID(),
            "透明硬化液 性能记录.xlsx","source",version,9,"contract","COMPLETED","EXPERIMENT_DRAFT",category,user,Instant.EPOCH,List.of());
    final ExecutionContext context=new ExecutionContext(UUID.randomUUID(),org,1,UUID.randomUUID(),1,()->false);

    @BeforeEach void setup(){
        ActorContext.set(new Actor(org,user,"陈霖"));
        when(repository.load(org,job)).thenReturn(source);
        when(repository.links(org,job)).thenReturn(List.of(link("READY",null)));
        when(repository.find(org,job,key)).thenReturn(Optional.of(link("READY",null)));
        when(repository.markRunning(org,job,key)).thenReturn(true);
        when(repository.prepareRetry(org,job,key,hash)).thenReturn(true);
        var contract=json.createObjectNode().put("templateUsage","EXPERIMENT_DATA");contract.putObject("experimentImport");
        when(templates.getVersion(org,version)).thenReturn(new TemplateDataImportFacade.DataTemplateDefinition(version,version,"EXP_TEST_UNIFIED",
                "性能记录",null,1,"XLSX",json.createObjectNode(),json.createArrayNode(),List.of(),9,1,"contract",contract));
        var model=json.createObjectNode().put("title","透明硬化液 性能记录").put("sourceOwnerName","周妍").put("sourceExperimentDate","2026-11-02");
        var candidate=new ExperimentImportAssembler.Candidate(key,null,null,"FILE","READY",List.of("row"),List.of(),
                json.createArrayNode(),json.createArrayNode(),model,hash,"content");
        when(assembler.assemble(any(),any())).thenReturn(new ExperimentImportAssembler.AssemblyPlan(job,List.of(candidate),1,0,0,1,0,0,1,0,0));
        when(workbooks.sourceSnapshotForExperiment(org,job)).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return json.createObjectNode().put("id","source-workbook");
        });
        when(templates.getExperimentTemplateVersion(org,version)).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new TemplateDataImportFacade.PublishedExperimentTemplate(version,version,"EXP_TEST_UNIFIED","性能记录",1,"template",json.createObjectNode().put("id","blank"),json.createArrayNode(),contract);
        });
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var command=call.<ExperimentDraftFacade.ImportedDraftCommand>getArgument(0);
            return new ExperimentDraftFacade.PreparedImportedDraft(UUID.randomUUID(),UUID.randomUUID(),command,command.editModel());
        }).when(experiments).prepareImportedDraft(any());
        when(experiments.createPreparedImportedDraft(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return new ExperimentDraftFacade.ImportedDraft(UUID.randomUUID(),UUID.randomUUID(),"RD261101","透明硬化液 性能记录");
        });
    }
    @AfterEach void cleanup(){ActorContext.clear();}
    @Test void reportsCurrentExperimentStatusAndHidesInaccessibleLinks(){
        var experiment=UUID.randomUUID();var statuses=mock(com.jsd.aird.rnd.api.ExperimentStatusFacade.class);
        service.setExperimentStatuses(statuses);
        when(repository.links(org,job)).thenReturn(List.of(link("SYNCED",experiment)));
        var current=new com.jsd.aird.rnd.api.ExperimentStatusFacade.Summary(experiment,UUID.randomUUID(),"RD261101","已发布实验","COMPLETED");
        when(statuses.current(List.of(experiment))).thenReturn(List.of(current));
        assertThat(service.status(job).experiments()).containsExactly(current);
        when(statuses.current(List.of(experiment))).thenReturn(List.of());
        var hidden=service.status(job);
        assertThat(hidden.experiments()).isEmpty();
        assertThat(hidden.links().getFirst().experimentId()).isNull();
        assertThat(hidden.links().getFirst().experimentVersionId()).isNull();
        assertThat(hidden.links().getFirst().experimentNo()).isNull();
    }

    ExperimentDraftFacade.ImportedDraft execute(){return service.execute(org,user,"陈霖",job,key,hash,category,null,null,null,context);}
    ExperimentAssemblyRepository.Link link(String status,UUID experiment){return new ExperimentAssemblyRepository.Link(key,null,status,null,null,experiment,
            experiment==null?null:UUID.randomUUID(),experiment==null?null:"RD261101",hash,"content",status.equals("FAILED")?"文件读取失败":null);}
    OpsAsyncFacade.AsyncJobView async(UUID id,String status){return new OpsAsyncFacade.AsyncJobView(id,"DATA_TO_EXPERIMENT_SYNC",status,5,"PREPARING",1,5,Instant.EPOCH,"file unavailable",Instant.EPOCH);}

    @Test void preparesFilesOutsideTransactionAndCommitsDraftAndSourceLinkTogether(){
        var created=execute();
        verify(jobs).requireExecution(context,false);
        verify(repository).markSynced(org,job,key,created.experimentId(),created.experimentVersionId(),created.experimentNo());
        verify(recognition).linkExperiment(eq(org),eq(job),any(),eq(key),eq(created.experimentId()),eq(created.experimentVersionId()),eq(created.experimentNo()),any(),any(),anyList(),eq("content"),eq(user));
    }
    @Test void uncategorizedImportCanQueueAndCreateDraftThroughAsyncHandler(){
        when(repository.load(org,job)).thenReturn(new ExperimentAssemblyRepository.SourceImport(job,source.sourceFileId(),
                source.sourceFileName(),source.sourceSha256(),version,9,"contract","COMPLETED","EXPERIMENT_DRAFT",
                null,user,Instant.EPOCH,List.of()));
        when(jobs.enqueue(eq(org),eq("DATA_TO_EXPERIMENT_SYNC"),any(),anyString(),eq(40))).thenReturn(UUID.randomUUID());
        assertThat(service.sync(job,null,List.of(key)).acceptedCount()).isEqualTo(1);
        var payload=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(jobs).enqueue(eq(org),eq("DATA_TO_EXPERIMENT_SYNC"),payload.capture(),anyString(),eq(40));
        assertThat(payload.getValue().hasNonNull("categoryId")).isFalse();
        new DataToExperimentJobHandler(service,json).handle(payload.getValue(),context);
        verify(experiments).prepareImportedDraft(argThat(command->command.categoryId()==null));
        verify(experiments).createPreparedImportedDraft(argThat(prepared->prepared.command().categoryId()==null));
        verify(experiments,never()).requireActiveCategory(any(),any());
    }
    @Test void fileFailureKeepsOriginalErrorAndDoesNotStartWriting(){
        var error=new ApiException(ApiErrorCode.FILE_NOT_READY,"原文件暂不可读取");
        when(workbooks.sourceSnapshotForExperiment(org,job)).thenThrow(error);
        assertThatThrownBy(this::execute).isSameAs(error);
        verify(repository,never()).markRunning(any(),any(),any());
        verify(experiments,never()).createImportedDraft(any());
    }
    @Test void sourceChangeDuringFilePreparationStopsCreation(){
        var latest=new AtomicReference<>(source);
        when(repository.load(org,job)).thenAnswer(call->latest.get());
        when(workbooks.sourceSnapshotForExperiment(org,job)).thenAnswer(call->{
            latest.set(new ExperimentAssemblyRepository.SourceImport(job,source.sourceFileId(),source.sourceFileName(),"changed",version,9,
                    "contract","COMPLETED","EXPERIMENT_DRAFT",category,user,Instant.EPOCH,List.of()));
            return json.createObjectNode().put("id","source");
        });
        assertThatThrownBy(this::execute).hasMessageContaining("读取文件期间导入资料已变化");
        verify(experiments,never()).createImportedDraft(any());
        verify(experiments,never()).createPreparedImportedDraft(any());
    }
    @Test void aNewSourceRevisionCannotReusePreparedWorkbookEvenWhenValuesMatch(){
        var latest=new AtomicReference<>(source);
        when(repository.load(org,job)).thenAnswer(call->latest.get());
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            latest.set(new ExperimentAssemblyRepository.SourceImport(job,source.sourceFileId(),source.sourceFileName(),source.sourceSha256(),
                    version,9,"contract","COMPLETED","EXPERIMENT_DRAFT",category,user,Instant.EPOCH,List.of(),"new-revision"));
            var command=call.<ExperimentDraftFacade.ImportedDraftCommand>getArgument(0);
            return new ExperimentDraftFacade.PreparedImportedDraft(UUID.randomUUID(),UUID.randomUUID(),command,command.editModel());
        }).when(experiments).prepareImportedDraft(any());
        assertThatThrownBy(this::execute).hasMessageContaining("读取文件期间导入资料已变化");
        verify(experiments,never()).createPreparedImportedDraft(any());
    }
    @Test void completedDraftReturnsItsIdentityWithoutReadingOrCreatingAgain(){
        var existing=link("SYNCED",UUID.randomUUID());when(repository.find(org,job,key)).thenReturn(Optional.of(existing));
        assertThat(execute().experimentId()).isEqualTo(existing.experimentId());
        verifyNoInteractions(workbooks);
        verify(experiments,never()).createImportedDraft(any());
    }
    @Test void expiredExecutionCannotWriteAfterFilePreparation(){
        doThrow(new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT)).when(jobs).requireExecution(context,false);
        assertThatThrownBy(this::execute).isInstanceOf(ApiException.class);
        verify(experiments,never()).createImportedDraft(any());
    }
    @Test void failedJobIsVisibleEvenWhenDomainCallbackWasMissed(){
        when(jobs.findLatestJob(eq(org),anyString())).thenReturn(Optional.of(async(UUID.randomUUID(),"FAILED")));
        var state=service.status(job);
        verify(repository,never()).upsertPlan(any(),any(),any(),any());
        verify(repository,never()).supersedeOtherPlans(any(),any(),any());
        assertThat(state.plan().candidates().getFirst().status()).isEqualTo("FAILED");
        assertThat(state.links().getFirst().status()).isEqualTo("FAILED");
        assertThat(state.links().getFirst().errorMessage()).contains("实验草稿生成失败");
        verify(repository,never()).markFailed(any(),any(),any(),any(),any());
    }
    @Test void refreshingDoesNotRetryFailedJob(){
        when(jobs.findLatestJob(eq(org),anyString())).thenReturn(Optional.of(async(UUID.randomUUID(),"FAILED")));
        service.status(job);service.preview(job);
        assertThatThrownBy(()->service.sync(job,category,List.of())).hasMessageContaining("没有可创建");
        verify(jobs,never()).enqueue(any(),anyString(),any(),anyString(),anyInt());
    }
    @Test void explicitRetryUsesStableNewAttemptKey(){
        var failed=UUID.randomUUID();var fresh=UUID.randomUUID();
        when(repository.links(org,job)).thenReturn(List.of(link("FAILED",null)));
        when(repository.find(org,job,key)).thenReturn(Optional.of(link("FAILED",null)));
        when(jobs.findLatestJob(eq(org),anyString())).thenReturn(Optional.of(async(failed,"FAILED")));
        when(jobs.enqueue(eq(org),eq("DATA_TO_EXPERIMENT_SYNC"),any(),anyString(),eq(40))).thenReturn(fresh);
        assertThat(service.sync(job,category,List.of(key)).jobIds()).containsExactly(fresh);
        verify(jobs).enqueue(eq(org),eq("DATA_TO_EXPERIMENT_SYNC"),any(),argThat(value->value.length()<=160
                &&value.startsWith("data-experiment:")&&value.endsWith(":retry:"+failed)),eq(40));
    }
    @Test void existingLegacyJobIsStillFoundBeforeStartingAnyNewAttempt(){
        var active=UUID.randomUUID();
        when(jobs.findLatestJob(org,"data-experiment:"+job+":"+key+":"+hash)).thenReturn(Optional.of(async(active,"RUNNING")));
        assertThat(service.sync(job,category,List.of(key)).jobIds()).containsExactly(active);
        verify(jobs,never()).enqueue(any(),anyString(),any(),anyString(),anyInt());
    }
    @Test void duplicateRequestReusesActiveJob(){
        var active=UUID.randomUUID();when(jobs.findLatestJob(eq(org),anyString())).thenReturn(Optional.of(async(active,"RUNNING")));
        assertThat(service.sync(job,category,List.of(key)).jobIds()).containsExactly(active);
        verify(jobs,never()).enqueue(any(),anyString(),any(),anyString(),anyInt());
    }
    @Test void concurrentCompletionReturnsExistingDraftWithoutEnqueuing(){
        when(repository.find(org,job,key)).thenReturn(Optional.of(link("SYNCED",UUID.randomUUID())));
        assertThat(service.sync(job,category,List.of(key)).acceptedCount()).isZero();
        verify(jobs,never()).enqueue(any(),anyString(),any(),anyString(),anyInt());
    }
    @Test void changedPlanWhileWaitingForLockCannotEnqueueOldPayload(){
        when(repository.find(org,job,key)).thenReturn(Optional.of(new ExperimentAssemblyRepository.Link(key,null,"READY",null,null,
                null,null,null,"new-plan","content",null)));
        assertThatThrownBy(()->service.sync(job,category,List.of(key))).hasMessageContaining("组装计划已变化");
        verify(jobs,never()).enqueue(any(),anyString(),any(),anyString(),anyInt());
    }
    @Test void lateFailureCannotOverwriteNewerAttempt(){
        when(jobs.findLatestJob(eq(org),anyString())).thenReturn(Optional.of(async(UUID.randomUUID(),"RUNNING")));
        service.fail(org,job,key,hash,new IllegalStateException("late"),context);
        verify(repository,never()).markFailed(any(),any(),any(),any(),any());
    }
    @Test void terminalFailureIsScopedToItsOriginalPlan(){
        service.fail(org,job,key,hash,new ApiException(ApiErrorCode.FILE_NOT_READY,"原文件暂不可读取"),context);
        verify(repository).markFailed(org,job,key,hash,"原文件暂不可读取");
    }
    static class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object tx,TransactionDefinition definition){}
        @Override protected void doCommit(DefaultTransactionStatus status){}
        @Override protected void doRollback(DefaultTransactionStatus status){}
    }
}
