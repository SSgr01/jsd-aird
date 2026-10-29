package com.jsd.aird.platform.web;

import com.jsd.aird.bootstrap.configuration.CapacityConfiguration.*;
import com.jsd.aird.shared.resource.*;
import com.jsd.aird.shared.error.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class CapacityPostgresTest {
    private static IsolatedPostgres db;
    @BeforeAll static void setup(){db=new IsolatedPostgres(Map.of("mdm.project","project"));db.table("project","id integer PRIMARY KEY,value integer");}
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void seed(){db.clear();db.jdbc.update("INSERT INTO mdm.project VALUES(1,0)");}
    private CapacityProperties limits(Map<String,Object> custom){return new Binder(new MapConfigurationPropertySource(custom)).bindOrCreate("app.capacity",Bindable.of(CapacityProperties.class));}
    @Test void lockWaitHasABoundAndRollbackDoesNotPoisonReusedConnections()throws Exception{
        var p=limits(Map.of("app.capacity.lock-millis",120));var source=new BudgetDataSource(db.source,p);var jdbc=new JdbcTemplate(source);var tx=new TransactionTemplate(new BudgetTransactionManager(source,p));
        try(var held=db.source.getConnection()){
            held.setAutoCommit(false);try(var lock=held.prepareStatement("SELECT id FROM mdm.project WHERE id=1 FOR UPDATE")){lock.executeQuery().close();}
            long start=System.nanoTime();
            assertThatThrownBy(()->tx.execute(status->jdbc.update("UPDATE mdm.project SET value=1 WHERE id=1"))).satisfies(error->assertThat(ResourceFailures.code(error)).isEqualTo(ApiErrorCode.DATABASE_BUSY));
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(1500);
            held.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT current_setting('lock_timeout')",String.class)).isEqualTo("0");
        tx.executeWithoutResult(status->jdbc.update("UPDATE mdm.project SET value=2 WHERE id=1"));
        assertThat(db.jdbc.queryForObject("SELECT value FROM mdm.project WHERE id=1",Integer.class)).isEqualTo(2);
    }
    @Test void slowSqlTimesOutAndEarlierWritesAreRolledBack(){
        var p=limits(Map.of("app.capacity.write-sql-millis",100));var source=new BudgetDataSource(db.source,p);var jdbc=new JdbcTemplate(source);var tx=new TransactionTemplate(new BudgetTransactionManager(source,p));
        assertThatThrownBy(()->tx.execute(status->{jdbc.update("UPDATE mdm.project SET value=1 WHERE id=1");return jdbc.queryForObject("SELECT pg_sleep(2)",Object.class);})).satisfies(error->assertThat(ResourceFailures.code(error)).isEqualTo(ApiErrorCode.OPERATION_TIMEOUT));
        assertThat(db.jdbc.queryForObject("SELECT value FROM mdm.project WHERE id=1",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
    }
    @Test void localTimeoutSettingsDoNotLeakAfterCommit(){
        var p=CapacityProperties.defaults();var source=new BudgetDataSource(db.source,p);var jdbc=new JdbcTemplate(source);var tx=new TransactionTemplate(new BudgetTransactionManager(source,p));
        String before=jdbc.queryForObject("SELECT current_setting('statement_timeout')",String.class);
        try(var budget=OperationBudget.open(OperationBudget.Kind.HEAVY,120000)){
            String local=tx.execute(status->jdbc.queryForObject("SELECT current_setting('statement_timeout')",String.class));
            assertThat(local).isEqualTo("20s");
        }
        assertThat(jdbc.queryForObject("SELECT current_setting('statement_timeout')",String.class)).isEqualTo(before);
    }
    @Test void connectionAcquisitionTimesOutAndNetworkTimeoutResetsOnReuse()throws Exception{
        var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(System.getenv("JSD_AIRD_DATASOURCE_URL"));config.setUsername(System.getenv("JSD_AIRD_DATASOURCE_USERNAME"));config.setPassword(System.getenv("JSD_AIRD_DATASOURCE_PASSWORD"));config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setConnectionTimeout(1000);
        try(var pool=new com.zaxxer.hikari.HikariDataSource(config)){
            var source=new BudgetDataSource(pool,CapacityProperties.defaults());
            try(var held=pool.getConnection()){
                long start=System.nanoTime();assertThatThrownBy(source::getConnection).satisfies(error->assertThat(ResourceFailures.code(error)).isEqualTo(ApiErrorCode.DATABASE_BUSY));
                assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isBetween(850L,2000L);
            }
            new JdbcTemplate(source).queryForObject("SELECT 1",Integer.class);
            try(var reused=pool.getConnection()){assertThat(reused.getNetworkTimeout()).isZero();}
        }
    }
    @Test void annotationStyleReadOnlyDefinitionGetsReadBudgetAndRestoresThreadState(){
        var p=CapacityProperties.defaults();var source=new BudgetDataSource(db.source,p);var jdbc=new JdbcTemplate(source);
        var tx=new TransactionTemplate(new BudgetTransactionManager(source,p));tx.setReadOnly(true);
        tx.executeWithoutResult(status->{assertThat(OperationBudget.kind()).isEqualTo(OperationBudget.Kind.READ);assertThat(jdbc.queryForObject("SELECT current_setting('statement_timeout')",String.class)).isEqualTo("5s");});
        assertThat(OperationBudget.active()).isFalse();
    }
    @Test void realDeadlockRollsBackOneWholeTransactionAndReleasesBothLocks()throws Exception{
        db.jdbc.update("INSERT INTO mdm.project VALUES(2,0)");
        var p=limits(Map.of("app.capacity.lock-millis",3000));var source=new BudgetDataSource(db.source,p);var jdbc=new JdbcTemplate(source);var tx=new TransactionTemplate(new BudgetTransactionManager(source,p));var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var results=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int n=0;n<2;n++){final int first=n+1,second=2-n;results.add(pool.submit(()->{
                try{tx.executeWithoutResult(status->{jdbc.update("UPDATE mdm.project SET value=value+1 WHERE id=?",first);try{barrier.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception error){throw new IllegalStateException(error);}jdbc.update("UPDATE mdm.project SET value=value+1 WHERE id=?",second);});return true;}
                catch(RuntimeException error){assertThat(ResourceFailures.code(error)).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);return false;}
            }));}
            assertThat(java.util.List.of(results.get(0).get(8,java.util.concurrent.TimeUnit.SECONDS),results.get(1).get(8,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(db.jdbc.queryForList("SELECT value FROM mdm.project ORDER BY id",Integer.class)).containsExactly(1,1);
    }
}
