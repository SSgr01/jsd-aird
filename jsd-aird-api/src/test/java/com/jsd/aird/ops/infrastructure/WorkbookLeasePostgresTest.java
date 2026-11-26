package com.jsd.aird.ops.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.platform.web.IsolatedPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class WorkbookLeasePostgresTest {
    @Test void heartbeatsRunDuringBusinessWritesAndLostFinalFenceRollsBack() throws Exception {
        try(var db=new IsolatedPostgres(Map.of("ops.async_job","job","rnd.business","business"))) {
            db.table("job","id uuid PRIMARY KEY, organization_id uuid,status text,lease_owner text,lease_token uuid,lease_generation bigint,lease_expires_at timestamptz,updated_at timestamptz");
            db.table("business","id int PRIMARY KEY,value int");db.jdbc.update("INSERT INTO rnd.business VALUES(1,0)");
            var id=UUID.randomUUID();var org=UUID.randomUUID();var token=UUID.randomUUID();
            db.jdbc.update("INSERT INTO ops.async_job VALUES(?,?,'RUNNING','worker',?,1,now()+interval '5 minutes',now())",id,org,token);
            var claim=new WorkRepository.AsyncJob(id,"DATA_TO_EXPERIMENT_SYNC",new ObjectMapper().createObjectNode(),1,5,"WRITE",org,token,1);
            var context=new AsyncJobHandler.ExecutionContext(id,org,1,token,1,()->false);
            var facade=new JdbcOpsAsyncFacade(db.jdbc);var heartbeat=heartbeat(db);
            warmConnections(db);assertThat(heartbeat.heartbeatClaimedJob(claim,"worker",Duration.ofMinutes(5))).isTrue();
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                db.tx.executeWithoutResult(tx->{
                    facade.checkExecution(context,false);
                    db.jdbc.update("UPDATE rnd.business SET value=1 WHERE id=1");
                    try { assertThat(executor.submit(()->heartbeat.heartbeatClaimedJob(claim,"worker",Duration.ofMinutes(5))).get(10,TimeUnit.SECONDS)).isTrue(); }
                    catch(Exception e){throw new RuntimeException(e);}
                    facade.requireExecution(context,false);
                });
                assertThat(db.jdbc.queryForObject("SELECT value FROM rnd.business",Integer.class)).isEqualTo(1);
                assertThatThrownBy(()->db.tx.executeWithoutResult(tx->{
                    facade.checkExecution(context,false);
                    db.jdbc.update("UPDATE rnd.business SET value=2 WHERE id=1");
                    try { executor.submit(()->db.jdbc.update("UPDATE ops.async_job SET lease_generation=2,lease_token=? WHERE id=?",UUID.randomUUID(),id)).get(10,TimeUnit.SECONDS); }
                    catch(Exception e){throw new RuntimeException(e);}
                    facade.requireExecution(context,false);
                })).isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
                assertThat(db.jdbc.queryForObject("SELECT value FROM rnd.business",Integer.class)).isEqualTo(1);
            }
        }
    }
    @Test void heartbeatLockWaitIsBoundedAndContentionDoesNotRevokeLease() throws Exception {
        try(var db=new IsolatedPostgres(Map.of("ops.async_job","job"))) {
            db.table("job","id uuid PRIMARY KEY,organization_id uuid,status text,lease_owner text,lease_token uuid,lease_generation bigint,lease_expires_at timestamptz,updated_at timestamptz");
            var id=UUID.randomUUID();var org=UUID.randomUUID();var token=UUID.randomUUID();
            db.jdbc.update("INSERT INTO ops.async_job VALUES(?,?,'RUNNING','worker',?,1,now()+interval '5 minutes',now())",id,org,token);
            var claim=new WorkRepository.AsyncJob(id,"DATA_TO_EXPERIMENT_SYNC",new ObjectMapper().createObjectNode(),1,5,"WRITE",org,token,1);
            var heartbeat=heartbeat(db);
            warmConnections(db);assertThat(heartbeat.heartbeatClaimedJob(claim,"worker",Duration.ofMinutes(5))).isTrue();
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                db.tx.executeWithoutResult(tx->{
                    db.jdbc.queryForObject("SELECT id FROM ops.async_job WHERE id=? FOR UPDATE",UUID.class,id);
                    try { var future=executor.submit(()->heartbeat.heartbeatClaimedJob(claim,"worker",Duration.ofMinutes(5)));
                        assertThatThrownBy(()->future.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                    } catch(Exception e){throw new RuntimeException(e);}
                });
                assertThat(heartbeat.heartbeatClaimedJob(claim,"worker",Duration.ofMinutes(5))).isTrue();
            }
        }
    }
    private void warmConnections(IsolatedPostgres db) throws Exception {
        // Connection/class initialization is not part of the row-lock wait limit.
        try(var first=db.source.getConnection();var second=db.source.getConnection()) { first.isValid(2);second.isValid(2); }
    }
    private WorkRepository heartbeat(IsolatedPostgres db) {
        var proxy=new ProxyFactory(new PostgresWorkRepository(db.jdbc,new ObjectMapper()));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.source),new AnnotationTransactionAttributeSource()));
        return (WorkRepository)proxy.getProxy();
    }
}
