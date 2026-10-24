package com.jsd.aird.platform.web;

import com.jsd.aird.shared.resource.ResourceFailures;
import com.jsd.aird.shared.error.ApiErrorCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class QueueCapacityPostgresTest {
    private static IsolatedPostgres db;
    private final UUID org=UUID.randomUUID();
    @BeforeAll static void setup()throws Exception{
        db=new IsolatedPostgres(Map.of("ops.async_job","job"));
        db.table("job","id uuid PRIMARY KEY,organization_id uuid,status text,idempotency_key text,lease_token uuid,lease_generation bigint DEFAULT 0,lease_expires_at timestamptz,lease_owner text,attempt_count integer DEFAULT 1,next_attempt_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),UNIQUE(organization_id,idempotency_key)");
        String sql=new ClassPathResource("db/migration/V81__bounded_async_admission.sql").getContentAsString(StandardCharsets.UTF_8)
            .replace("ops.enforce_async_capacity",db.schema+".enforce_async_capacity");
        db.jdbc.execute(sql);
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void clear(){db.clear();}
    private UUID enqueue(UUID organization,String key){
        return db.tx.execute(status->{
            db.jdbc.execute("SET LOCAL app.capacity_pending_jobs='3'");db.jdbc.execute("SET LOCAL app.capacity_org_pending_jobs='2'");
            var id=UUID.randomUUID();db.jdbc.update("INSERT INTO ops.async_job(id,organization_id,status,idempotency_key) VALUES(?,?,'READY',?) ON CONFLICT(organization_id,idempotency_key) DO NOTHING",id,organization,key);return id;
        });
    }
    @Test void concurrentEnqueueCannotExceedOrganizationCapacity()throws Exception{
        var barrier=new CyclicBarrier(5);try(var pool=Executors.newFixedThreadPool(5)){
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<5;i++){final int n=i;futures.add(pool.submit(()->{barrier.await(3,TimeUnit.SECONDS);try{enqueue(org,"job-"+n);return true;}catch(RuntimeException error){assertThat(ResourceFailures.code(error)).isEqualTo(ApiErrorCode.SERVER_BUSY);return false;}}));}
            int accepted=0;for(var future:futures)if(future.get(8,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(2);assertThat(db.jdbc.queryForObject("SELECT count(*) FROM ops.async_job",Integer.class)).isEqualTo(2);
        }
    }
    @Test void globalCapacityIdempotentRetryAndRunningLeaseRetainTheirReservations(){
        var first=enqueue(org,"first");enqueue(org,"second");enqueue(UUID.randomUUID(),"third");
        enqueue(org,"first");
        assertThatThrownBy(()->enqueue(UUID.randomUUID(),"overflow")).satisfies(error->assertThat(ResourceFailures.code(error)).isEqualTo(ApiErrorCode.SERVER_BUSY));
        db.jdbc.update("UPDATE ops.async_job SET status='RUNNING' WHERE id=?",first);
        assertThatThrownBy(()->enqueue(org,"overflow")).isInstanceOf(RuntimeException.class);
        db.jdbc.update("UPDATE ops.async_job SET status='SUCCEEDED' WHERE id=?",first);
        enqueue(org,"replacement");
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM ops.async_job WHERE status IN ('READY','RUNNING')",Integer.class)).isEqualTo(3);
    }
    @Test void failedBusinessTransactionDoesNotLeaveAQueueReservation(){
        assertThatThrownBy(()->db.tx.execute(status->{enqueue(org,"rolled-back");throw new IllegalStateException("injected business failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM ops.async_job",Integer.class)).isZero();enqueue(org,"next");
    }
    @Test void expiredAndReplacedLeasesCannotCommitOrMarkANewerJobFailed(){
        var id=enqueue(org,"leased");var token=UUID.randomUUID();
        db.jdbc.update("UPDATE ops.async_job SET status='RUNNING',lease_token=?,lease_generation=2,lease_expires_at=now()+interval '1 minute' WHERE id=?",token,id);
        var facade=new com.jsd.aird.ops.infrastructure.JdbcOpsAsyncFacade(db.jdbc);
        var current=new com.jsd.aird.ops.application.port.AsyncJobHandler.ExecutionContext(id,org,1,token,2,()->false);
        db.tx.executeWithoutResult(status->facade.requireExecution(current,false));
        var stale=new com.jsd.aird.ops.application.port.AsyncJobHandler.ExecutionContext(id,org,1,token,1,()->false);
        assertThatThrownBy(()->db.tx.executeWithoutResult(status->facade.requireExecution(stale,false))).isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
        db.jdbc.update("UPDATE ops.async_job SET status='FAILED' WHERE id=?",id);
        assertThatThrownBy(()->db.tx.executeWithoutResult(status->facade.requireExecution(stale,true))).isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
        db.tx.executeWithoutResult(status->facade.requireExecution(current,true));
    }
    @Test void capacityDeferralDoesNotConsumeAttemptsOrOverwriteANewerLease(){
        var id=enqueue(org,"deferral");var token=UUID.randomUUID();db.jdbc.update("UPDATE ops.async_job SET status='RUNNING',lease_token=?,lease_generation=2 WHERE id=?",token,id);
        var repository=new com.jsd.aird.ops.infrastructure.PostgresWorkRepository(db.jdbc,new com.fasterxml.jackson.databind.ObjectMapper());
        var payload=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        repository.deferClaimedJob(new com.jsd.aird.ops.application.port.WorkRepository.AsyncJob(id,"TEST",payload,1,5,"",org,token,1));
        assertThat(db.jdbc.queryForObject("SELECT status FROM ops.async_job WHERE id=?",String.class,id)).isEqualTo("RUNNING");
        repository.deferClaimedJob(new com.jsd.aird.ops.application.port.WorkRepository.AsyncJob(id,"TEST",payload,1,5,"",org,token,2));
        assertThat(db.jdbc.queryForObject("SELECT status FROM ops.async_job WHERE id=?",String.class,id)).isEqualTo("READY");assertThat(db.jdbc.queryForObject("SELECT attempt_count FROM ops.async_job WHERE id=?",Integer.class,id)).isZero();
    }
}
