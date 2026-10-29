package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.*;
import com.jsd.aird.data.application.*;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class DataPublicationConcurrencyPostgresTest {
    private static IsolatedPostgres db;
    private static final UUID ORG=UUID.randomUUID(),USER=UUID.randomUUID();
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private DataPublicationService service;
    private DataRepository.Job job;
    private DataRepository repository;
    private UUID id,snapshot;
    @BeforeAll static void setup(){
        var tables=new HashMap<>(Map.of("data.import_job","job","data.publication_snapshot","snapshot","data.publication_event","event","data.publication","publication","core.project_resource_link","link","mdm.project","project","mdm.project_stage","stage","mdm.project_task","task","iam.app_user","app_user"));
        tables.put("data.data_record","record");tables.put("data.record_governance","governance");tables.put("data.source_anchor","anchor");db=new IsolatedPostgres(tables);
        db.table("job","id uuid PRIMARY KEY,organization_id uuid,source_file_name text DEFAULT 'fixture.xlsx',source_owner text DEFAULT 'DATA_CENTER',recognition_mode text DEFAULT 'TEMPLATE_GUIDED',status text DEFAULT 'SAVED',updated_at timestamptz DEFAULT now()");
        db.table("publication","organization_id uuid,import_job_id uuid,status text,confirmed_snapshot_id uuid,published_snapshot_id uuid,reviewer_id uuid,legacy_published boolean DEFAULT false,published_enabled boolean DEFAULT true,updated_at timestamptz DEFAULT now()");
        db.table("snapshot","id uuid PRIMARY KEY,organization_id uuid,import_job_id uuid,version_no integer,content_hash text,confirmed_submission_id uuid,confirmed_by uuid,confirmed_at timestamptz DEFAULT now()");
        db.table("event","id uuid PRIMARY KEY,organization_id uuid,import_job_id uuid,snapshot_id uuid,action text,actor_id uuid,actor_name text,comment text,created_at timestamptz DEFAULT now()");
        db.table("link","id uuid PRIMARY KEY,organization_id uuid,resource_type text,resource_id uuid,project_id uuid,stage_id uuid,task_id uuid");
        for(var name:List.of("project","stage","task"))db.table(name,"id uuid PRIMARY KEY,name text");
        db.table("app_user","id uuid PRIMARY KEY,organization_id uuid,status text,username text,display_name text");
        db.table("record","id uuid PRIMARY KEY,organization_id uuid,import_job_id uuid,record_key text,synthetic_key boolean,record_index integer,sheet_name text,source_row_number integer,effective_data_jsonb jsonb");
        db.table("governance","organization_id uuid,record_id uuid,display_name text,status text,reason text");
        db.table("anchor","id uuid PRIMARY KEY,record_id uuid,binding_id text,value_path text,sheet_name text,cell_address text");
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void seed()throws Exception{
        db.clear();id=UUID.randomUUID();snapshot=UUID.randomUUID();ActorContext.set(new Actor(ORG,USER,"test"));
        db.jdbc.update("INSERT INTO "+db.table("job")+"(id,organization_id) VALUES(?,?)",id,ORG);
        db.jdbc.update("INSERT INTO "+db.table("publication")+"(organization_id,import_job_id,status,confirmed_snapshot_id,reviewer_id) VALUES(?,?,'PENDING_REVIEW',?,?)",ORG,id,snapshot,USER);
        db.jdbc.update("INSERT INTO "+db.table("app_user")+"(id,organization_id,status,username) VALUES(?,?,'ACTIVE','reviewer')",USER,ORG);
        repository=mock(DataRepository.class);job=mock(DataRepository.Job.class);
        when(job.sourceFileId()).thenReturn(UUID.randomUUID());when(job.sourceFileName()).thenReturn("fixture.xlsx");when(job.sourceSha256()).thenReturn("source-hash");
        when(repository.findJob(ORG,id)).thenReturn(Optional.of(job));when(repository.findSourceOwner(ORG,id)).thenReturn(Optional.of("DATA_CENTER"));
        when(repository.findJobForUpdate(ORG,id)).thenAnswer(call->{db.jdbc.queryForObject("SELECT id FROM "+db.table("job")+" WHERE id=? AND organization_id=? FOR UPDATE",UUID.class,id,ORG);return Optional.of(job);});
        service=new DataPublicationService(db.jdbc,JSON,repository,mock(DataImportService.class),check->new PermissionDecision(true,check.permissionCode(),"ALLOW","ALL","TEST"),mock(AuditLogFacade.class));
        // Build a genuine confirmation digest with production code; subsequent
        // transitions and all locking/state/event writes use real PostgreSQL.
        var payload=DataPublicationService.class.getDeclaredMethod("payload",UUID.class);payload.setAccessible(true);
        var hash=DataPublicationService.class.getDeclaredMethod("hash",JsonNode.class);hash.setAccessible(true);
        var digest=(String)hash.invoke(service,payload.invoke(service,id));
        db.jdbc.update("INSERT INTO "+db.table("snapshot")+"(id,organization_id,import_job_id,version_no,content_hash,confirmed_by) VALUES(?,?,?,1,?,?)",snapshot,ORG,id,digest,USER);
    }
    @AfterEach void clearActor(){ActorContext.clear();}
    private List<Boolean> race(java.util.function.IntConsumer action)throws Exception{
        var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++){final int index=i;futures.add(pool.submit(()->{ActorContext.set(new Actor(ORG,USER,"test"));try{barrier.await(5,TimeUnit.SECONDS);db.tx.executeWithoutResult(status->action.accept(index));return true;}catch(ApiException error){assertThat(error.errorCode()).isIn(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,ApiErrorCode.RESOURCE_CONFLICT);return false;}finally{ActorContext.clear();}}));}
            return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
        }
    }
    @Test void approveAndWithdrawCannotBothConsumeTheSameReviewCycle()throws Exception{
        var revision=service.get(id).revision();
        assertThat(race(i->{if(i==0)service.review(id,snapshot,true,null,revision);else service.withdraw(id,revision);})).containsExactlyInAnyOrder(true,false);
        assertThat(service.get(id).events()).hasSize(1);assertThat(service.get(id).status()).isIn("APPROVED","CONFIRMED");
    }
    @Test void twoApprovalsCreateOnlyOneDecision()throws Exception{
        var revision=service.get(id).revision();assertThat(race(i->service.review(id,snapshot,true,null,revision))).containsExactlyInAnyOrder(true,false);
        assertThat(service.get(id).events()).hasSize(1);assertThat(service.get(id).status()).isEqualTo("APPROVED");
    }
    @Test void changedSourceDigestCannotBeApprovedEvenWithCurrentWorkspaceRevision(){
        var revision=service.get(id).revision();when(job.sourceSha256()).thenReturn("another-file");
        assertThatThrownBy(()->db.tx.execute(status->service.review(id,snapshot,true,null,revision))).isInstanceOf(ApiException.class);
        assertThat(service.get(id).events()).isEmpty();assertThat(service.get(id).status()).isEqualTo("PENDING_REVIEW");
    }
    @Test void failureWritingDecisionEventRollsBackTheApprovedState(){
        var revision=service.get(id).revision();db.jdbc.execute("ALTER TABLE "+db.table("event")+" ADD CONSTRAINT injected_event_failure CHECK(action <> 'APPROVED')");
        try{
            assertThatThrownBy(()->db.tx.execute(status->service.review(id,snapshot,true,null,revision))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(service.get(id).status()).isEqualTo("PENDING_REVIEW");assertThat(service.get(id).revision()).isEqualTo(revision);
        }finally{db.jdbc.execute("ALTER TABLE "+db.table("event")+" DROP CONSTRAINT injected_event_failure");}
    }
    @Test void preparingDigestDoesNotHoldTheWriteLockAndConcurrentWithdrawalRejectsCommit()throws Exception{
        service.configureCapacity(db.tx.getTransactionManager());
        var revision=service.get(id).revision();var preparing=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{preparing.countDown();assertThat(release.await(4,TimeUnit.SECONDS)).isTrue();return null;}).when(repository).forEachRow(eq(ORG),eq(id),any());
        try(var pool=Executors.newSingleThreadExecutor()){
            var approval=pool.submit(()->{ActorContext.set(new Actor(ORG,USER,"test"));try{return service.review(id,snapshot,true,null,revision);}finally{ActorContext.clear();}});
            try{
                assertThat(preparing.await(2,TimeUnit.SECONDS)).isTrue();
                db.tx.executeWithoutResult(status->service.withdraw(id,revision));
            }finally{release.countDown();}
            assertThatThrownBy(()->approval.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        }
        assertThat(service.get(id).status()).isEqualTo("CONFIRMED");assertThat(service.get(id).events()).hasSize(1);
    }
    @Test void preparedCsvStreamsRealDatabaseRowsAndReleasesItsDiskReservation()throws Exception{
        var record=UUID.randomUUID();db.jdbc.update("INSERT INTO data.data_record VALUES(?,?,?,'source-1',false,1,'隔离表',2,?::jsonb)",record,ORG,id,"{\"VISCOSITY\":{\"normalizedValue\":\"=unsafe\",\"bindingId\":\"viscosity\",\"valuePath\":\"/viscosity\"}}");
        db.jdbc.update("INSERT INTO data.source_anchor VALUES(?,?, 'viscosity','/viscosity','隔离表','A2')",UUID.randomUUID(),record);
        var root=java.nio.file.Files.createTempDirectory("capacity-csv-test-");var space=new com.jsd.aird.shared.resource.TemporarySpace(8L*1024*1024*1024,root);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"temporarySpace",space);
        try{
            var prepared=db.tx.execute(status->service.prepareExport(id));
            try(prepared){var csv=java.nio.file.Files.readString(prepared.path());assertThat(csv).contains("记录ID","'=unsafe","A2","fixture.xlsx");assertThat(prepared.size()).isEqualTo(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);}
            assertThat(space.reserved()).isZero();
        }finally{java.nio.file.Files.deleteIfExists(root);}
    }
}
