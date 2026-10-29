package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.rnd.application.port.*;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.rnd.domain.ResearchTestModels.Status;
import com.jsd.aird.rnd.infrastructure.*;
import com.jsd.aird.shared.error.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/** Full repository SQL, including reads, versions, review events and audit/outbox writes. */
@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class ResearchConcurrencyPostgresTest {
    enum Kind { EXPERIMENT, REPORT, STANDARD }
    private static IsolatedPostgres db;
    private static JdbcExperimentRepository experiments;
    private static JdbcResearchTestRepository research;
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final UUID ORG=UUID.randomUUID(),USER=UUID.randomUUID();
    @BeforeAll static void setup(){
        db=new IsolatedPostgres(Map.ofEntries(Map.entry("rnd.experiment_version","experiment_version"),Map.entry("rnd.experiment_review","experiment_review"),Map.entry("rnd.experiment_audit","experiment_audit"),Map.entry("rnd.experiment_outbox","experiment_outbox"),Map.entry("rnd.experiment_attachment","attachment"),Map.entry("rnd.experiment","experiment"),Map.entry("rnd.research_test_version","research_version"),Map.entry("rnd.research_test_review","research_review"),Map.entry("rnd.research_test_audit","research_audit"),Map.entry("rnd.research_test_record","research_record"),Map.entry("mdm.project","project"),Map.entry("mdm.project_stage","stage"),Map.entry("mdm.project_task","task")));
        for(var name:List.of("project","stage","task"))db.table(name,"id uuid PRIMARY KEY,name text,deleted boolean DEFAULT false");
        db.table("experiment","id uuid PRIMARY KEY,organization_id uuid,experiment_no text,title text,category_id uuid,category_name text,source_type text DEFAULT 'MANUAL',status text,project_id uuid,stage_id uuid,task_id uuid,owner_name text,experiment_date date,revision bigint DEFAULT 0,current_version_id uuid,source_file_id uuid,void_reason text,updated_by uuid,updated_at timestamptz DEFAULT now(),deleted boolean DEFAULT false");
        db.table("research_record","id uuid PRIMARY KEY,organization_id uuid,record_type text,business_no text,name text,category text,applicable_scope text,owner_name text,business_date date,document_format text DEFAULT 'DOCX',source_type text DEFAULT 'MANUAL',status text,visibility text DEFAULT 'ALL',project_id uuid,stage_id uuid,task_id uuid,source_file_id uuid,current_version_id uuid,lock_version bigint DEFAULT 0,updated_by uuid,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),deleted boolean DEFAULT false");
        var version="id uuid PRIMARY KEY,organization_id uuid,version_no integer DEFAULT 1,status text,template_version_id uuid,template_snapshot_hash text,template_snapshot_jsonb jsonb DEFAULT '{}'::jsonb,edit_model_jsonb jsonb DEFAULT '{}'::jsonb,submitted_at timestamptz,published_at timestamptz,created_at timestamptz DEFAULT now(),created_by uuid";
        db.table("experiment_version",version+",experiment_id uuid,revision_reason text");
        db.table("research_version",version+",record_id uuid,member_snapshot_jsonb jsonb DEFAULT '[]'::jsonb,effective_from date DEFAULT '2026-01-01',effective_to date DEFAULT '2026-12-31',change_summary text");
        db.table("experiment_review","id uuid PRIMARY KEY,organization_id uuid,experiment_id uuid,experiment_version_id uuid,action text,comment text,operator_id uuid,operator_name text,created_at timestamptz DEFAULT now()");
        db.table("research_review","id uuid PRIMARY KEY,organization_id uuid,record_id uuid,version_id uuid,action text,comment text,operator_id uuid,operator_name text,created_at timestamptz DEFAULT now()");
        var audit="id uuid PRIMARY KEY,organization_id uuid,action text,before_jsonb jsonb,after_jsonb jsonb,operator_id uuid,operator_name text,created_at timestamptz DEFAULT now()";
        db.table("experiment_audit",audit+",experiment_id uuid,experiment_version_id uuid");db.table("research_audit",audit+",record_id uuid,version_id uuid");
        db.table("experiment_outbox","id uuid PRIMARY KEY,organization_id uuid,aggregate_id uuid,event_type text,payload_jsonb jsonb");
        db.table("attachment","id uuid PRIMARY KEY,experiment_id uuid,file_id uuid,file_version_id uuid,attachment_type text,section_key text,file_name text,description text,created_at timestamptz DEFAULT now()");
        experiments=new JdbcExperimentRepository(db.jdbc,JSON);research=new JdbcResearchTestRepository(db.jdbc,JSON);
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void empty(){db.clear();for(var name:List.of("experiment_audit","research_audit"))db.jdbc.execute("ALTER TABLE "+db.table(name)+" DROP CONSTRAINT IF EXISTS reject_audit");}
    private String parent(Kind kind){return kind==Kind.EXPERIMENT?"experiment":"research_record";}
    private String version(Kind kind){return kind==Kind.EXPERIMENT?"experiment_version":"research_version";}
    private String audit(Kind kind){return kind==Kind.EXPERIMENT?"experiment_audit":"research_audit";}
    private String reviews(Kind kind){return kind==Kind.EXPERIMENT?"experiment_review":"research_review";}
    private String approved(Kind kind){return kind==Kind.EXPERIMENT?"COMPLETED":"PUBLISHED";}
    private UUID seed(Kind kind,String status){
        var id=UUID.randomUUID();var vid=UUID.randomUUID();
        if(kind==Kind.EXPERIMENT)db.jdbc.update("INSERT INTO "+db.table("experiment")+"(id,organization_id,experiment_no,title,status,current_version_id) VALUES(?,?,'EXP','experiment',?,?)",id,ORG,status,vid);
        else db.jdbc.update("INSERT INTO "+db.table("research_record")+"(id,organization_id,record_type,business_no,name,applicable_scope,status,current_version_id) VALUES(?,?,?,?,'record','same-scope',?,?)",id,ORG,kind.name(),"NO-"+id,status,vid);
        db.jdbc.update("INSERT INTO "+db.table(version(kind))+"(id,organization_id,status) VALUES(?,?,?)",vid,ORG,status);return id;
    }
    private void transition(Kind kind,UUID id,UUID org,long revision,String target){
        if(kind==Kind.EXPERIMENT)experiments.transition(org,id,revision,ExperimentStatus.valueOf(target),"reason",USER,"reviewer");
        else research.transition(org,id,revision,Status.valueOf(target),"reason",USER,"reviewer");
    }
    private void save(Kind kind,UUID id){
        var model=JSON.createObjectNode().put("memo","editor change");
        if(kind==Kind.EXPERIMENT)experiments.saveDraft(ORG,id,0,new ExperimentRepository.Draft("EXP","changed",null,null,null,null,null,"editor",LocalDate.now(),null,null,JSON.createObjectNode(),model),USER,"editor");
        else research.save(ORG,id,new ResearchTestRepository.Draft(0,"NO-"+id,"changed",null,"same-scope","editor",LocalDate.now(),"ALL",null,null,null,null,null,JSON.createObjectNode(),model,JSON.createArrayNode(),LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)),USER,"editor");
    }
    private List<String> race(java.util.function.IntConsumer work)throws Exception{
        var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<String>>();for(int i=0;i<2;i++){final int index=i;futures.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{db.tx.executeWithoutResult(status->work.accept(index));return "saved";}catch(ApiException error){assertThat(error.errorCode()).isIn(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,ApiErrorCode.RESOURCE_CONFLICT);return "conflict";}}));}
            return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
        }
    }
    private long count(String table){return db.jdbc.queryForObject("SELECT count(*) FROM "+db.table(table),Long.class);}
    private long revision(Kind kind,UUID id){return db.jdbc.queryForObject("SELECT "+(kind==Kind.EXPERIMENT?"revision":"lock_version")+" FROM "+db.table(parent(kind))+" WHERE id=?",Long.class,id);}
    @ParameterizedTest @EnumSource(Kind.class) void approvalAndRejectionHaveOneWinnerAndOneReviewEvent(Kind kind)throws Exception{
        var id=seed(kind,"PENDING_REVIEW");assertThat(race(i->transition(kind,id,ORG,0,i==0?approved(kind):"RETURNED"))).containsExactlyInAnyOrder("saved","conflict");
        assertThat(revision(kind,id)).isEqualTo(1);assertThat(count(reviews(kind))).isEqualTo(1);assertThat(count(audit(kind))).isEqualTo(1);
        var status=db.jdbc.queryForObject("SELECT status FROM "+db.table(parent(kind))+" WHERE id=?",String.class,id);
        assertThat(db.jdbc.queryForObject("SELECT status FROM "+db.table(version(kind)),String.class)).isEqualTo(status);
    }
    @ParameterizedTest @EnumSource(Kind.class) void saveAndSubmitCannotBothConsumeTheSameDraft(Kind kind)throws Exception{
        var id=seed(kind,"DRAFT");assertThat(race(i->{if(i==0)save(kind,id);else transition(kind,id,ORG,0,"PENDING_REVIEW");})).containsExactlyInAnyOrder("saved","conflict");
        assertThat(revision(kind,id)).isEqualTo(1);assertThat(count(audit(kind))).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(Kind.class) void auditFailureRollsBackParentVersionSnapshotAndReview(Kind kind){
        var id=seed(kind,"PENDING_REVIEW");db.jdbc.execute("ALTER TABLE "+db.table(audit(kind))+" ADD CONSTRAINT reject_audit CHECK(action <> 'STATUS_"+approved(kind)+"')");
        assertThatThrownBy(()->db.tx.execute(status->{transition(kind,id,ORG,0,approved(kind));return null;})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(revision(kind,id)).isZero();assertThat(count(reviews(kind))).isZero();assertThat(count("experiment_outbox")).isZero();
        assertThat(db.jdbc.queryForObject("SELECT status FROM "+db.table(version(kind)),String.class)).isEqualTo("PENDING_REVIEW");
    }
    @ParameterizedTest @EnumSource(Kind.class) void foreignOrganizationCannotApproveOrReadTheRecord(Kind kind){
        var id=seed(kind,"PENDING_REVIEW");var other=UUID.randomUUID();
        assertThatThrownBy(()->db.tx.execute(status->{transition(kind,id,other,0,approved(kind));return null;}))
            .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThat(revision(kind,id)).isZero();assertThat(count(reviews(kind))).isZero();
    }
    @ParameterizedTest @EnumSource(Kind.class) void oldApprovalCycleIsRejectedAfterReturnAndResubmit(Kind kind){
        var id=seed(kind,"PENDING_REVIEW");db.tx.executeWithoutResult(status->transition(kind,id,ORG,0,"RETURNED"));db.tx.executeWithoutResult(status->transition(kind,id,ORG,1,"PENDING_REVIEW"));
        assertThatThrownBy(()->db.tx.execute(status->{transition(kind,id,ORG,0,approved(kind));return null;}))
            .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));
        assertThat(revision(kind,id)).isEqualTo(2);assertThat(count(reviews(kind))).isEqualTo(2);
    }
    @Test void competingStandardsWithOverlappingDatesCannotBothBePublished()throws Exception{
        var first=seed(Kind.STANDARD,"PENDING_REVIEW");var second=seed(Kind.STANDARD,"PENDING_REVIEW");
        db.jdbc.execute("CREATE OR REPLACE FUNCTION "+db.schema+".slow_publish() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_sleep(0.2); RETURN NEW; END'");
        db.jdbc.execute("CREATE TRIGGER slow_publish BEFORE UPDATE ON "+db.table("research_record")+" FOR EACH ROW EXECUTE FUNCTION "+db.schema+".slow_publish()");
        try{assertThat(race(i->transition(Kind.STANDARD,i==0?first:second,ORG,0,"PUBLISHED"))).containsExactlyInAnyOrder("saved","conflict");}
        finally{db.jdbc.execute("DROP TRIGGER slow_publish ON "+db.table("research_record"));}
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM "+db.table("research_record")+" WHERE status='PUBLISHED'",Long.class)).isEqualTo(1);
    }
    @Test void standardsWithDifferentScopesCanBothBePublished()throws Exception{
        var first=seed(Kind.STANDARD,"PENDING_REVIEW");var second=seed(Kind.STANDARD,"PENDING_REVIEW");
        db.jdbc.update("UPDATE "+db.table("research_record")+" SET applicable_scope='other' WHERE id=?",second);
        assertThat(race(i->transition(Kind.STANDARD,i==0?first:second,ORG,0,"PUBLISHED"))).containsExactly("saved","saved");
    }
    @Test void sameScopeStandardsWithDisjointDatesCanBothBePublished()throws Exception{
        var first=seed(Kind.STANDARD,"PENDING_REVIEW");var second=seed(Kind.STANDARD,"PENDING_REVIEW");
        db.jdbc.update("UPDATE "+db.table("research_version")+" SET effective_to='2026-06-30' WHERE id=(SELECT current_version_id FROM "+db.table("research_record")+" WHERE id=?)",first);
        db.jdbc.update("UPDATE "+db.table("research_version")+" SET effective_from='2026-07-01' WHERE id=(SELECT current_version_id FROM "+db.table("research_record")+" WHERE id=?)",second);
        assertThat(race(i->transition(Kind.STANDARD,i==0?first:second,ORG,0,"PUBLISHED"))).containsExactly("saved","saved");
    }
}
