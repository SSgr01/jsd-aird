package com.jsd.aird.shared.resource;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/** Low-cardinality, local management data. No SQL text, identities or business payloads. */
public final class CapacityDiagnostics implements AutoCloseable {
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("aird-capacity-metrics-",0).factory());
    private final AtomicLong lockWaiters=new AtomicLong(),transactionAge=new AtomicLong(),pending=new AtomicLong(),jobAge=new AtomicLong(),available=new AtomicLong();
    public CapacityDiagnostics(JdbcTemplate jdbc,HikariDataSource pool,MeterRegistry metrics){
        metrics.gauge("aird.database.connections.active",pool,p->p.getHikariPoolMXBean()==null?0:p.getHikariPoolMXBean().getActiveConnections());
        metrics.gauge("aird.database.connections.waiting",pool,p->p.getHikariPoolMXBean()==null?0:p.getHikariPoolMXBean().getThreadsAwaitingConnection());
        metrics.gauge("aird.database.connections.total",pool,p->p.getHikariPoolMXBean()==null?0:p.getHikariPoolMXBean().getTotalConnections());
        metrics.gauge("aird.database.lock.waiters",lockWaiters);metrics.gauge("aird.database.transaction.oldest.seconds",transactionAge);
        metrics.gauge("aird.capacity.jobs.pending",pending);metrics.gauge("aird.capacity.jobs.oldest.seconds",jobAge);metrics.gauge("aird.capacity.diagnostics.available",available);
        clock.scheduleWithFixedDelay(()->{
            try(var budget=OperationBudget.open(OperationBudget.Kind.READ,3000)){
                jdbc.query("""
                    SELECT count(*) FILTER(WHERE wait_event_type='Lock') locks,
                        coalesce(max(extract(epoch FROM clock_timestamp()-xact_start)) FILTER(WHERE state<>'idle'),0)::bigint age
                    FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid()
                    """,(org.springframework.jdbc.core.RowCallbackHandler)rs->{lockWaiters.set(rs.getLong("locks"));transactionAge.set(rs.getLong("age"));});
                jdbc.query("SELECT count(*) pending,coalesce(max(extract(epoch FROM now()-created_at)),0)::bigint age FROM ops.async_job WHERE status IN ('READY','RUNNING')",
                    (org.springframework.jdbc.core.RowCallbackHandler)rs->{pending.set(rs.getLong("pending"));jobAge.set(rs.getLong("age"));});
                available.set(1);
            }catch(RuntimeException error){available.set(0);}
        },10,5,TimeUnit.SECONDS);
    }
    @Override public void close(){clock.shutdownNow();}
}
