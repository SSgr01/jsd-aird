package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.modeling.*;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static com.jsd.aird.ai.rnd.modeling.ModelRegistryContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real model release CAS, target locks, receipts and rollback; no model server calls. */
@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class ModelRegistryConcurrencyPostgresTest {
    @Test void artifactMetadataChangingAfterPreparationCannotActivateTheModel(){
        doAnswer(call->{db.jdbc.update("UPDATE ai.artifact SET sha256=? WHERE id=(SELECT model_artifact_id FROM ai.model_version WHERE id=?)","b".repeat(64),first);return null;}).when(artifacts).verify(any(),any(),any());
        assertThatThrownBy(()->db.tx.execute(status->service.rollback(first,new ModelActionCommand(0,active,"隔离复核"),"changed-artifact"))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.MODEL_VERSION_CHANGED));
        assertThat(db.jdbc.queryForObject("SELECT status FROM ai.model_version WHERE id=?",String.class,active)).isEqualTo("ACTIVE");assertThat(db.jdbc.queryForObject("SELECT count(*) FROM ai.model_release",Integer.class)).isZero();
    }
    @Test void revokedPermissionAfterFileVerificationRejectsTheCommit(){
        com.jsd.aird.iam.api.AuthorizationService deny=check->new com.jsd.aird.iam.api.PermissionDecision(false,check.permissionCode(),"DENY","ALL","TEST");
        doAnswer(call->{org.springframework.test.util.ReflectionTestUtils.setField(service,"authorization",deny);return null;}).when(artifacts).verify(any(),any(),any());
        assertThatThrownBy(()->db.tx.execute(status->service.rollback(first,new ModelActionCommand(0,active,"隔离权限复核"),"revoked-permission"))).isInstanceOf(ApiException.class);
        assertThat(db.jdbc.queryForObject("SELECT status FROM ai.model_version WHERE id=?",String.class,active)).isEqualTo("ACTIVE");assertThat(db.jdbc.queryForObject("SELECT count(*) FROM ai.model_release",Integer.class)).isZero();
    }
    private static IsolatedPostgres db;
    private static final UUID ORG=UUID.randomUUID(),USER=UUID.randomUUID();
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private ModelRegistryService service;
    private FormulaModelArtifactStore artifacts;
    private AuditLogFacade audit;
    private UUID target,active,first,second;

    @BeforeAll static void setup(){
        db=new IsolatedPostgres(Map.of("ai.prediction_target","target","ai.target_version","target_version","ai.model_version","model","ai.artifact","artifact","ai.model_release","release","ai.configuration_command_receipt","receipt"));
        db.table("target","id uuid PRIMARY KEY,organization_id uuid,name text,performance_project text,current_version_id uuid,current_input_scheme_id uuid");
        db.table("target_version","id uuid PRIMARY KEY,organization_id uuid,value_type text");
        db.table("model","id uuid PRIMARY KEY,organization_id uuid,target_id uuid,target_version_id uuid,input_scheme_id uuid,workbench_run_id uuid,model_artifact_id uuid,contract_version text DEFAULT 'modeling-workbench.v1',model_type text DEFAULT 'LINEAR',data_nature text DEFAULT 'REAL',production_eligible boolean DEFAULT true,comparison_status text,metrics_jsonb jsonb DEFAULT '{}',model_card_jsonb jsonb DEFAULT '{\"rowCount\":8}',version_no integer,status text,revision bigint DEFAULT 0,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now()");
        db.table("artifact","id uuid PRIMARY KEY,organization_id uuid,object_key text,sha256 text,media_type text");
        db.table("release","id uuid PRIMARY KEY,organization_id uuid,target_id uuid,model_version_id uuid,previous_model_version_id uuid,action text,expected_previous_revision bigint,reason text,actor_id uuid,request_id text,idempotency_key text");
        db.table("receipt","id uuid PRIMARY KEY,organization_id uuid,operation text,idempotency_key text,request_hash text,resource_type text,resource_id uuid,response_jsonb jsonb,created_by uuid,UNIQUE(organization_id,operation,idempotency_key)");
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void seed(){
        db.clear();ActorContext.set(new Actor(ORG,USER,"test"));target=UUID.randomUUID();active=UUID.randomUUID();first=UUID.randomUUID();second=UUID.randomUUID();
        db.jdbc.update("INSERT INTO "+db.table("target")+"(id,organization_id,name,performance_project) VALUES(?,?,'Test target','Test')",target,ORG);
        int version=0;for(var id:List.of(first,second,active)){
            var artifact=UUID.randomUUID();var config=UUID.randomUUID();
            db.jdbc.update("INSERT INTO "+db.table("artifact")+" VALUES(?,?,'test/model.zip',?,'application/zip')",artifact,ORG,"a".repeat(64));
            db.jdbc.update("INSERT INTO "+db.table("target_version")+" VALUES(?,?,'NUMERIC')",config,ORG);
            db.jdbc.update("INSERT INTO "+db.table("model")+"(id,organization_id,target_id,target_version_id,input_scheme_id,workbench_run_id,model_artifact_id,version_no,status) VALUES(?,?,?,?,?,?,?,?,?)",id,ORG,target,config,UUID.randomUUID(),UUID.randomUUID(),artifact,++version,id.equals(active)?"ACTIVE":"RETIRED");
        }
        db.jdbc.update("UPDATE "+db.table("target")+" SET current_version_id=(SELECT target_version_id FROM "+db.table("model")+" WHERE id=?) WHERE id=?",active,target);
        artifacts=mock(FormulaModelArtifactStore.class);audit=mock(AuditLogFacade.class);
        when(artifacts.verify(anyString(),anyString(),anyString())).thenAnswer(call->new FormulaModelArtifactStore.StoredArtifact(call.getArgument(0),call.getArgument(1),100,call.getArgument(2)));
        service=new ModelRegistryService(new ModelRegistryRepository(db.jdbc,JSON),new ConfigurationHashing(JSON),JSON,audit,artifacts);
    }
    @AfterEach void clearActor(){ActorContext.clear();}
    private ModelActionCommand command(String reason){return new ModelActionCommand(0,active,reason);}
    private List<Boolean> race(java.util.function.IntConsumer action)throws Exception{
        var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++){final int index=i;futures.add(pool.submit(()->{ActorContext.set(new Actor(ORG,USER,"test"));try{barrier.await(5,TimeUnit.SECONDS);db.tx.executeWithoutResult(status->action.accept(index));return true;}catch(ApiException error){assertThat(error.errorCode()).isIn(ApiErrorCode.VERSION_CONFLICT,ApiErrorCode.MODEL_VERSION_CHANGED,ApiErrorCode.RESOURCE_CONFLICT);return false;}finally{ActorContext.clear();}}));}
            return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
        }
    }
    private long count(String table){return db.jdbc.queryForObject("SELECT count(*) FROM "+db.table(table),Long.class);}
    private String status(UUID id){return db.jdbc.queryForObject("SELECT status FROM "+db.table("model")+" WHERE id=?",String.class,id);}
    @Test void twoPausesWithDifferentKeysConsumeTheRevisionOnlyOnce()throws Exception{
        assertThat(race(i->service.pause(active,command("pause"),"pause-"+i))).containsExactlyInAnyOrder(true,false);
        assertThat(status(active)).isEqualTo("PAUSED");assertThat(count("release")).isEqualTo(1);assertThat(count("receipt")).isEqualTo(1);
    }
    @Test void twoHistoricalModelsCannotBothReplaceTheSameActiveVersion()throws Exception{
        assertThat(race(i->service.rollback(i==0?first:second,command("rollback"),"rollback-"+i))).containsExactlyInAnyOrder(true,false);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM "+db.table("model")+" WHERE status='ACTIVE'",Long.class)).isEqualTo(1);
        assertThat(status(active)).isEqualTo("RETIRED");assertThat(count("release")).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT t.current_version_id=m.target_version_id FROM "+db.table("target")+" t JOIN "+db.table("model")+" m ON m.target_id=t.id AND m.status='ACTIVE'",Boolean.class)).isTrue();
    }
    @Test void simultaneousReplayOfTheSameKeyReturnsTheReceiptWithoutAnotherPause()throws Exception{
        assertThat(race(i->service.pause(active,command("pause"),"same-key"))).containsExactly(true,true);
        assertThat(count("release")).isEqualTo(1);assertThat(count("receipt")).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT revision FROM "+db.table("model")+" WHERE id=?",Long.class,active)).isEqualTo(1);
    }
    @Test void reusingAnIdempotencyKeyWithDifferentContentIsRejected(){
        db.tx.executeWithoutResult(tx->service.pause(active,command("first"),"same-key"));
        assertThatThrownBy(()->db.tx.execute(tx->service.pause(active,command("changed"),"same-key"))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(count("release")).isEqualTo(1);
    }
    @Test void staleRevisionCannotPauseOrCreateAReceipt(){
        assertThatThrownBy(()->db.tx.execute(tx->service.pause(active,new ModelActionCommand(3,active,"stale"),"stale"))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
        assertThat(status(active)).isEqualTo("ACTIVE");assertThat(count("release")).isZero();assertThat(count("receipt")).isZero();
    }
    @Test void unavailableArtifactKeepsTheCurrentModelAndTargetUnchanged(){
        when(artifacts.verify(anyString(),anyString(),anyString())).thenThrow(new ApiException(ApiErrorCode.MODEL_ARTIFACT_UNAVAILABLE));
        assertThatThrownBy(()->db.tx.execute(tx->service.rollback(first,command("rollback"),"broken-file"))).isInstanceOf(ApiException.class);
        assertThat(status(active)).isEqualTo("ACTIVE");assertThat(status(first)).isEqualTo("RETIRED");assertThat(count("release")).isZero();assertThat(count("receipt")).isZero();
    }
    @Test void auditFailureRollsBackBothVersionsTheTargetPointerAndReceipt(){
        doThrow(new IllegalStateException("injected audit failure")).when(audit).append(any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->db.tx.execute(tx->service.rollback(first,command("rollback"),"audit-failure"))).isInstanceOf(IllegalStateException.class);
        assertThat(status(active)).isEqualTo("ACTIVE");assertThat(status(first)).isEqualTo("RETIRED");assertThat(count("release")).isZero();assertThat(count("receipt")).isZero();
        assertThat(db.jdbc.queryForObject("SELECT t.current_version_id=m.target_version_id FROM "+db.table("target")+" t JOIN "+db.table("model")+" m ON m.id=?",Boolean.class,active)).isTrue();
    }
    @Test void anotherOrganizationCannotPauseAKnownModelId(){
        ActorContext.set(new Actor(UUID.randomUUID(),USER,"foreign"));
        assertThatThrownBy(()->db.tx.execute(tx->service.pause(active,command("foreign"),"foreign"))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThat(status(active)).isEqualTo("ACTIVE");assertThat(count("receipt")).isZero();
    }
    @Test void changedActiveModelRejectsAnOtherwiseCurrentRevision(){
        assertThatThrownBy(()->db.tx.execute(tx->service.rollback(first,new ModelActionCommand(0,UUID.randomUUID(),"stale head"),"stale-head"))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.MODEL_VERSION_CHANGED));
        assertThat(status(active)).isEqualTo("ACTIVE");assertThat(count("release")).isZero();
    }
}
