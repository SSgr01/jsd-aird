package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.*;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.iam.application.*;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.infrastructure.JdbcIamStore;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.mfg.infrastructure.JdbcProductionOrderRepository;
import com.jsd.aird.mfg.upload.application.ProductionRecognitionScope;
import com.jsd.aird.mfg.upload.infrastructure.JdbcProductionUploadRepository;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.quality.application.QualityDataService;
import com.jsd.aird.quality.infrastructure.QualityDataRepository;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real SQL/transactions against disposable tables only. Never touches application schemas. */
@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class BusinessConsistencyPostgresTest {
    private static final String SCHEMA="business_consistency_"+UUID.randomUUID().toString().replace("-","");
    private static final UUID ORG=UUID.randomUUID(), USER=UUID.randomUUID();
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final AuthorizationService ALLOW=check->new PermissionDecision(true,check.permissionCode(),"ALLOW","ALL","TEST");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static JdbcProductionOrderRepository production;
    private static QualityDataRepository quality;
    private static DataSource dataSource;
    private static final Map<String,String> TABLES=Map.ofEntries(
        Map.entry("mfg.production_order","production_order"),Map.entry("tpl.record_revision","record_revision"),
        Map.entry("quality.data_record_version","quality_record_version"),Map.entry("quality.data_record","quality_record"),
        Map.entry("quality.data_category","quality_category"),Map.entry("quality.upload_file","quality_upload_file"),
        Map.entry("ops.file_object","file_object"),Map.entry("iam.app_user","app_user"),Map.entry("iam.role","role"),Map.entry("iam.login_session","login_session"),
        Map.entry("mfg.production_upload","production_upload"),Map.entry("ops.async_job","async_job"),
        Map.entry("data.import_job","import_job"),Map.entry("data.publication_snapshot","publication_snapshot"),
        Map.entry("data.publication_event","publication_event"),Map.entry("data.publication","publication"),Map.entry("core.project_resource_link","resource_link"));

    @BeforeAll static void setup(){
        var env=System.getenv();var url=env.getOrDefault("JSD_AIRD_DATASOURCE_URL","jdbc:postgresql://localhost:5432/jsd_aird");
        if(!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*"))throw new IllegalStateException("Only a loopback test database is allowed");
        dataSource=new RebasedDataSource(new DriverManagerDataSource(url,env.getOrDefault("JSD_AIRD_DATASOURCE_USERNAME","jsd_aird"),env.getOrDefault("JSD_AIRD_DATASOURCE_PASSWORD","jsd_aird_dev")));
        jdbc=new JdbcTemplate(dataSource);tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE SCHEMA "+SCHEMA);
        table("production_order","id uuid PRIMARY KEY,organization_id uuid,status text,lock_version bigint DEFAULT 0,updated_by uuid,updated_at timestamptz DEFAULT now()");
        table("record_revision","id uuid PRIMARY KEY,production_order_id uuid,revision_no integer,status text,core_snapshot_jsonb jsonb,schema_snapshot_jsonb jsonb,mapping_snapshot_jsonb jsonb,data_jsonb jsonb,editor_snapshot_file_id uuid,editor_snapshot_hash text,schema_hash text,mapping_hash text,data_hash text,workspace_hash text NOT NULL,created_by uuid,UNIQUE(production_order_id,revision_no)");
        table("quality_record","id uuid PRIMARY KEY,organization_id uuid,business_type text,category_id uuid,business_no text,display_name text,data_jsonb jsonb,workbook_snapshot_jsonb jsonb,source_file_id uuid,project_id uuid,project_name text,stage_id uuid,stage_name text,task_id uuid,task_name text,visibility text DEFAULT 'ALL',lock_version bigint DEFAULT 0,created_by uuid,updated_by uuid,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),deleted boolean DEFAULT false");
        table("quality_category","id uuid PRIMARY KEY,name text");
        table("quality_upload_file","id uuid,generated_record_id uuid,original_name text,deleted boolean,created_at timestamptz");
        table("quality_record_version","id uuid PRIMARY KEY,organization_id uuid,record_id uuid,version_no integer,business_no text,data_jsonb jsonb,workbook_snapshot_jsonb jsonb,lock_version bigint,change_type text,created_by uuid,created_at timestamptz DEFAULT now(),UNIQUE(record_id,version_no)");
        table("file_object","id uuid PRIMARY KEY,original_name text,status text");
        table("app_user","id uuid PRIMARY KEY,organization_id uuid,role_id uuid,status text,auth_version bigint DEFAULT 0,display_name text,username text,failed_login_count integer DEFAULT 0,locked_until timestamptz");
        table("role","id uuid PRIMARY KEY,organization_id uuid,code text,enabled boolean DEFAULT true");
        table("login_session","id uuid,organization_id uuid,user_id uuid,revoked_at timestamptz");
        table("production_upload","id uuid PRIMARY KEY,organization_id uuid,async_job_id uuid,status text,lock_version bigint DEFAULT 0,workbook_snapshot_jsonb jsonb,failure_message text,current_stage text,updated_at timestamptz DEFAULT now()");
        table("async_job","id uuid PRIMARY KEY,organization_id uuid,status text,lease_token uuid,lease_generation bigint,lease_expires_at timestamptz");
        table("import_job","id uuid PRIMARY KEY,organization_id uuid,source_owner text DEFAULT 'DATA_CENTER',recognition_mode text DEFAULT 'TEMPLATE_GUIDED',status text DEFAULT 'SAVED',updated_at timestamptz DEFAULT now()");
        table("publication","organization_id uuid,import_job_id uuid,status text,confirmed_snapshot_id uuid,published_snapshot_id uuid,reviewer_id uuid,legacy_published boolean DEFAULT false,published_enabled boolean DEFAULT true,updated_at timestamptz DEFAULT now()");
        table("publication_snapshot","id uuid PRIMARY KEY,organization_id uuid,import_job_id uuid,version_no integer,content_hash text,confirmed_submission_id uuid,confirmed_by uuid,confirmed_at timestamptz DEFAULT now()");
        table("publication_event","id uuid PRIMARY KEY,organization_id uuid,import_job_id uuid,snapshot_id uuid,action text,actor_name text,comment text,created_at timestamptz DEFAULT now()");
        table("resource_link","id uuid,organization_id uuid,resource_type text,resource_id uuid");
        production=new JdbcProductionOrderRepository(jdbc,JSON);quality=new QualityDataRepository(jdbc,JSON);
    }
    private static void table(String name,String columns){jdbc.execute("CREATE TABLE "+SCHEMA+"."+name+" ("+columns+")");}
    @AfterAll static void cleanup(){if(jdbc!=null&&SCHEMA.matches("business_consistency_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA IF EXISTS "+SCHEMA+" CASCADE");}
    @BeforeEach void empty(){jdbc.execute("TRUNCATE "+TABLES.values().stream().distinct().map(name->SCHEMA+"."+name).collect(java.util.stream.Collectors.joining(",")));ActorContext.set(new Actor(ORG,USER,"qa"));}
    @AfterEach void clearActor(){ActorContext.clear();}

    @Test void competingProductionSubmissionsCreateExactlyOneSnapshot() throws Exception {
        var id=UUID.randomUUID();jdbc.update("INSERT INTO "+SCHEMA+".production_order(id,organization_id,status) VALUES(?,?,'DRAFT')",id,ORG);
        assertOneWinner(()->tx.execute(status->{production.submit(submit(id,"hash"));return "saved";}));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".record_revision",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lock_version FROM "+SCHEMA+".production_order WHERE id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void failedSnapshotInsertRollsBackTheClaimedProductionStatus(){
        var id=UUID.randomUUID();jdbc.update("INSERT INTO "+SCHEMA+".production_order(id,organization_id,status) VALUES(?,?,'DRAFT')",id,ORG);
        assertThatThrownBy(()->tx.execute(status->production.submit(submit(id,null)))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM "+SCHEMA+".production_order WHERE id=?",String.class,id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT lock_version FROM "+SCHEMA+".production_order WHERE id=?",Long.class,id)).isZero();
    }
    @Test void changedProductionCannotBeCancelledOrDeletedByAnOldView(){
        var id=UUID.randomUUID();jdbc.update("INSERT INTO "+SCHEMA+".production_order(id,organization_id,status,lock_version) VALUES(?,?,'DRAFT',1)",id,ORG);
        assertThat(production.cancel(ORG,id,0,USER)).isZero();assertThat(production.delete(ORG,id,0)).isZero();
        assertThat(production.cancel(ORG,id,1,USER)).isEqualTo(1);assertThat(production.delete(ORG,id,1)).isZero();assertThat(production.delete(ORG,id,2)).isEqualTo(1);
    }
    @Test void concurrentQualityPublicationAllocatesOnlyOneVersion() throws Exception {
        var id=qualityRecord("COA-1","{}");
        assertOneWinner(()->tx.execute(status->{quality.publish(ORG,USER,id,0);return "saved";}));
        assertThat(quality.versions(ORG,id)).hasSize(1);assertThat(quality.record(ORG,id).lockVersion()).isEqualTo(1);
        assertThat(quality.record(ORG,id).data().path("status").asText()).isEqualTo("已签发");
    }
    @Test void aLateFinalizedDeletionRollsBackEarlierUpdatesInTheSameBatch(){
        var first=qualityRecord("COA-1","{}");var second=qualityRecord("COA-2","{\"status\":\"已签发\"}");
        var service=new QualityDataService(quality,JSON,null,null,null,null,mock(AuditLogFacade.class),ALLOW);
        var changed=new QualityDataService.RecordInput(first,"COA-1",JSON.createObjectNode().put("productName","changed"),null,null,null,null,null,null,null,0);
        assertThatThrownBy(()->tx.execute(status->{service.saveBatch("coa",UUID.randomUUID(),List.of(changed),List.of(new QualityDataService.RecordVersion(second,0L)));return null;})).isInstanceOf(ApiException.class);
        assertThat(quality.record(ORG,first).lockVersion()).isZero();assertThat(quality.record(ORG,first).data().has("productName")).isFalse();
        assertThat(quality.record(ORG,second)).isNotNull();
    }
    @Test void oldQualityDeleteAndCrossOrganizationWritesDoNotTouchTheRow(){
        var id=qualityRecord("COA-1","{}");jdbc.update("UPDATE "+SCHEMA+".quality_record SET lock_version=1 WHERE id=?",id);
        assertConflict(()->quality.softDeleteOne(ORG,id,0));assertConflict(()->quality.softDeleteOne(UUID.randomUUID(),id,1));
        assertThat(quality.record(ORG,id).lockVersion()).isEqualTo(1);
    }
    @Test void aCancelledReplacedOrExpiredWorkerCannotReplaceAnEditedWorkbook(){
        var id=UUID.randomUUID();var job=UUID.randomUUID();var lease=UUID.randomUUID();var uploads=new JdbcProductionUploadRepository(jdbc,JSON);
        jdbc.update("INSERT INTO "+SCHEMA+".production_upload(id,organization_id,async_job_id,status,workbook_snapshot_jsonb) VALUES(?,?,?,'SAVED','{\"edited\":true}')",id,ORG,job);
        jdbc.update("INSERT INTO "+SCHEMA+".async_job VALUES(?,?,'RUNNING',?,2,now()+interval '5 minutes')",job,ORG,lease);
        for(long generation:new long[]{1,3}){
            var context=new AsyncJobHandler.ExecutionContext(job,ORG,1,lease,generation,()->false);
            assertConflict(()->tx.execute(status->ProductionRecognitionScope.run(context,false,()->{uploads.saveGeneratedWorkbook(id,JSON.createObjectNode());return null;})));
        }
        jdbc.update("UPDATE "+SCHEMA+".async_job SET status='CANCELLED' WHERE id=?",job);
        var context=new AsyncJobHandler.ExecutionContext(job,ORG,1,lease,2,()->true);
        assertConflict(()->tx.execute(status->ProductionRecognitionScope.run(context,false,()->{uploads.saveGeneratedWorkbook(id,JSON.createObjectNode());return null;})));
        assertThat(jdbc.queryForObject("SELECT workbook_snapshot_jsonb->>'edited' FROM "+SCHEMA+".production_upload WHERE id=?",String.class,id)).isEqualTo("true");
    }
    @Test void oldPublicationCycleCannotApproveTheSameSnapshotAfterResubmission(){
        var id=dataSource("PENDING_REVIEW");var service=publicationService(id);var old=service.get(id).revision();
        jdbc.update("INSERT INTO "+SCHEMA+".publication_event(id,organization_id,import_job_id,action) VALUES(?,?,?,'WITHDRAWN'),(?,?,?,'SUBMITTED')",UUID.randomUUID(),ORG,id,UUID.randomUUID(),ORG,id);
        assertThat(service.get(id).revision()).isNotEqualTo(old);
        assertConflict(()->tx.execute(status->service.review(id,UUID.randomUUID(),true,"old approval",old)));
        assertThat(service.get(id).status()).isEqualTo("PENDING_REVIEW");
    }
    @Test void editingAnAlreadyDraftDataSourceChangesItsPublicationRevision(){
        var id=dataSource("DRAFT");var service=publicationService(id);var old=service.get(id).revision();
        tx.executeWithoutResult(status->new DataReviewGuard(jdbc).beforeEdit(ORG,id));
        assertThat(service.get(id).revision()).isNotEqualTo(old);
        assertConflict(()->tx.execute(status->service.confirm(id,old)));
    }
    @Test void concurrentAdminDisablesLeaveOneActiveAdministrator() throws Exception {
        var role=UUID.randomUUID();var first=UUID.randomUUID();var second=UUID.randomUUID();
        jdbc.update("INSERT INTO "+SCHEMA+".role VALUES(?,?,'SYSTEM_ADMIN',true)",role,ORG);
        for(var id:List.of(first,second))jdbc.update("INSERT INTO "+SCHEMA+".app_user(id,organization_id,role_id,status) VALUES(?,?,?,'ACTIVE')",id,ORG,role);
        var actual=new JdbcIamStore(jdbc,JSON);var store=mock(IamStore.class);
        doAnswer(call->{actual.lockAdministration(ORG);return null;}).when(store).lockAdministration(ORG);
        doAnswer(call->{actual.lockUser(ORG,call.getArgument(1));return null;}).when(store).lockUser(eq(ORG),any());
        when(store.user(any())).thenAnswer(call->{UUID id=call.getArgument(0);return jdbc.query("SELECT status,auth_version FROM "+SCHEMA+".app_user WHERE id=?",(rs,n)->new IamStore.User(id,ORG,"admin","admin",null,null,null,role,"SYSTEM_ADMIN","SYSTEM_ADMIN",rs.getString(1),false,"hash",rs.getLong(2),null,0,null),id).stream().findFirst();});
        when(store.activeSystemAdmins(ORG)).thenAnswer(call->actual.activeSystemAdmins(ORG));
        doAnswer(call->{actual.setStatus(ORG,call.getArgument(1),call.getArgument(2));return null;}).when(store).setStatus(eq(ORG),any(),anyString());
        var service=new IamAdminService(store,ALLOW,mock(PasswordEncoder.class),mock(AuditLogFacade.class),mock(AuditLogPresentationService.class));
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->disable(service,first,barrier));var b=pool.submit(()->disable(service,second,barrier));
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved","blocked");
        }
        assertThat(actual.activeSystemAdmins(ORG)).isEqualTo(1);
    }
    private String disable(IamAdminService service,UUID id,CyclicBarrier barrier)throws Exception {barrier.await(5,TimeUnit.SECONDS);try{return tx.execute(status->{service.setStatus(new Actor(ORG,USER,"qa"),id,false,0);return "saved";});}catch(ApiException error){assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPERATION_FORBIDDEN);return "blocked";}}
    private UUID qualityRecord(String number,String data){var id=UUID.randomUUID();jdbc.update("INSERT INTO "+SCHEMA+".quality_record(id,organization_id,business_type,business_no,data_jsonb) VALUES(?,?,'coa',?,?::jsonb)",id,ORG,number,data);return id;}
    private UUID dataSource(String status){var id=UUID.randomUUID();jdbc.update("INSERT INTO "+SCHEMA+".import_job(id,organization_id) VALUES(?,?)",id,ORG);jdbc.update("INSERT INTO "+SCHEMA+".publication(organization_id,import_job_id,status) VALUES(?,?,?)",ORG,id,status);return id;}
    private DataPublicationService publicationService(UUID id){var repository=mock(DataRepository.class);var job=mock(DataRepository.Job.class);when(repository.findJob(ORG,id)).thenReturn(Optional.of(job));when(repository.findJobForUpdate(ORG,id)).thenAnswer(call->{jdbc.queryForObject("SELECT id FROM "+SCHEMA+".import_job WHERE id=? FOR UPDATE",UUID.class,id);return Optional.of(job);});when(repository.findSourceOwner(ORG,id)).thenReturn(Optional.of("DATA_CENTER"));return new DataPublicationService(jdbc,JSON,repository,mock(DataImportService.class),ALLOW,mock(AuditLogFacade.class));}
    private ProductionOrderRepository.SubmitRevision submit(UUID id,String hash){var empty=JSON.createObjectNode();return new ProductionOrderRepository.SubmitRevision(0,UUID.randomUUID(),ORG,id,empty,empty,empty,empty,null,null,"s","m","d",hash,USER);}
    private void assertOneWinner(Callable<String> work)throws Exception {var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){Callable<String> contender=()->{barrier.await(5,TimeUnit.SECONDS);try{return work.call();}catch(ApiException error){assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);return "conflict";}};var a=pool.submit(contender);var b=pool.submit(contender);assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved","conflict");}}
    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));}
    private static class RebasedDataSource extends AbstractDataSource {
        private final DataSource original;RebasedDataSource(DataSource original){this.original=original;}
        public Connection getConnection()throws java.sql.SQLException{return wrap(original.getConnection());}
        public Connection getConnection(String user,String password)throws java.sql.SQLException{return wrap(original.getConnection(user,password));}
        private Connection wrap(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(args!=null&&args.length>0&&args[0] instanceof String sql&&method.getName().startsWith("prepare")){
                for(var entry:TABLES.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String,String> item)->item.getKey().length()).reversed()).toList())sql=sql.replace(entry.getKey(),SCHEMA+"."+entry.getValue());
                args=args.clone();args[0]=sql;
            }
            try{return method.invoke(connection,args);}catch(InvocationTargetException error){throw error.getCause();}
        });}
    }
}
