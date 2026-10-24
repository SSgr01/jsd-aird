package com.jsd.aird.bootstrap.configuration;

import com.jsd.aird.shared.resource.*;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.DelegatingTransactionDefinition;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({CapacityProperties.class,DataSourceProperties.class})
public class CapacityConfiguration {
    @Bean ResourceAdmission resourceAdmission(CapacityProperties limits){return new ResourceAdmission(limits);}
    @Bean com.jsd.aird.platform.web.CapacityFilter capacityFilter(CapacityProperties limits,TemporarySpace space,com.fasterxml.jackson.databind.ObjectMapper json,io.micrometer.core.instrument.MeterRegistry metrics,ResourceAdmission admission,@Qualifier("corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors){var filter=new com.jsd.aird.platform.web.CapacityFilter(limits,space,json,metrics,admission);filter.setCorsSource(cors);return filter;}
    @Bean(destroyMethod="close") @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource capacityPool(DataSourceProperties properties){return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();}

    @Bean @Primary
    DataSource dataSource(@Qualifier("capacityPool") HikariDataSource pool,CapacityProperties limits,io.micrometer.core.instrument.MeterRegistry metrics){
        pool.addDataSourceProperty("connectTimeout","1");pool.addDataSourceProperty("cancelSignalTimeout","1");
        pool.setConnectionInitSql("SELECT set_config('lock_timeout','"+limits.lockMillis()+"ms',false),set_config('statement_timeout','"+limits.writeSqlMillis()+"ms',false),set_config('idle_in_transaction_session_timeout','10000ms',false)");
        return new BudgetDataSource(pool,limits,metrics);
    }
    @Bean
    PlatformTransactionManager transactionManager(DataSource source,CapacityProperties limits){return new BudgetTransactionManager(source,limits);}

    @Bean TemporarySpace temporarySpace(CapacityProperties limits,@Value("${app.capacity.temporary-root:${java.io.tmpdir}/jsd-aird-bounded}") String root){return new TemporarySpace(limits.temporaryBytes(),Path.of(root));}
    @Bean(destroyMethod="close") CapacityDiagnostics capacityDiagnostics(org.springframework.jdbc.core.JdbcTemplate jdbc,@Qualifier("capacityPool") HikariDataSource pool,io.micrometer.core.instrument.MeterRegistry metrics){return new CapacityDiagnostics(jdbc,pool,metrics);}

    public static final class BudgetDataSource extends AbstractDataSource {
        private final DataSource delegate;private final CapacityProperties limits;
        private final io.micrometer.core.instrument.MeterRegistry metrics;
        public BudgetDataSource(DataSource delegate,CapacityProperties limits){this(delegate,limits,null);}
        public BudgetDataSource(DataSource delegate,CapacityProperties limits,io.micrometer.core.instrument.MeterRegistry metrics){this.delegate=delegate;this.limits=limits;this.metrics=metrics;}
        @Override public Connection getConnection()throws SQLException{OperationBudget.check();return wrap(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{OperationBudget.check();return wrap(delegate.getConnection(user,password));}
        private Connection wrap(Connection connection){
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try{
                    if(method.getName().startsWith("prepare") || method.getName().equals("createStatement")){
                        OperationBudget.check();
                        connection.setNetworkTimeout(Runnable::run,Math.max(1,OperationBudget.sqlMillis(limits)+1000));
                    }
                    Object result=method.invoke(connection,args);
                    if(result instanceof Statement statement){
                        int seconds=Math.max(1,(OperationBudget.sqlMillis(limits)+999)/1000);statement.setQueryTimeout(seconds);
                        if(metrics!=null){
                            Class<?> type=statement instanceof CallableStatement?CallableStatement.class:statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                                boolean executing=m.getName().startsWith("execute");long started=System.nanoTime();boolean successful=false;
                                try{if(executing)OperationBudget.check();var value=m.invoke(statement,a);successful=true;return value;}
                                catch(InvocationTargetException error){throw error.getCause();}
                                finally{if(executing)metrics.timer("aird.database.statement","kind",OperationBudget.kind().name(),"outcome",successful?"success":"failure").record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);}
                            });
                        }
                    }
                    return result;
                }catch(InvocationTargetException error){throw error.getCause();}
            });
        }
    }

    public static final class BudgetTransactionManager extends DataSourceTransactionManager {
        private final CapacityProperties limits;
        private final ThreadLocal<java.util.IdentityHashMap<Object,OperationBudget.Scope>> automatic=ThreadLocal.withInitial(java.util.IdentityHashMap::new);
        public BudgetTransactionManager(DataSource source,CapacityProperties limits){super(source);this.limits=limits;setRollbackOnCommitFailure(true);}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){
            if(!OperationBudget.active())automatic.get().put(transaction,OperationBudget.open(definition.isReadOnly()?OperationBudget.Kind.READ:OperationBudget.Kind.WRITE,definition.isReadOnly()?limits.readMillis():limits.writeMillis()));
            try{begin(transaction,definition);}catch(RuntimeException failure){closeAutomatic(transaction);throw failure;}
        }
        private void begin(Object transaction,TransactionDefinition definition){
            int millis=OperationBudget.transactionMillis(limits);
            int seconds=Math.max(1,(millis+999)/1000);
            var bounded=new DelegatingTransactionDefinition(definition){@Override public int getTimeout(){int requested=super.getTimeout();return requested==TIMEOUT_DEFAULT?seconds:Math.min(requested,seconds);}};
            super.doBegin(transaction,bounded);
            try {
                var connection=DataSourceUtils.getConnection(obtainDataSource());
                try(var statement=connection.prepareStatement("SELECT set_config('lock_timeout',?,true),set_config('statement_timeout',?,true),set_config('idle_in_transaction_session_timeout',?,true)")){
                    statement.setString(1,limits.lockMillis()+"ms");statement.setString(2,OperationBudget.sqlMillis(limits)+"ms");statement.setString(3,Math.min(millis,10000)+"ms");statement.execute();
                }
                if(connection.getMetaData().getDatabaseMajorVersion()>=17){
                    try(var statement=connection.prepareStatement("SELECT set_config('transaction_timeout',?,true)")){statement.setString(1,millis+"ms");statement.execute();}
                }
                try(var statement=connection.prepareStatement("SELECT set_config('app.capacity_pending_jobs',?,true),set_config('app.capacity_org_pending_jobs',?,true)")){
                    statement.setString(1,Integer.toString(limits.pendingJobs()));statement.setString(2,Integer.toString(limits.organizationPendingJobs()));statement.execute();
                }
            }catch(SQLException|RuntimeException error){
                try{super.doRollback(new org.springframework.transaction.support.DefaultTransactionStatus("capacity-begin",transaction,true,false,false,false,false,null));}catch(RuntimeException ignored){ }
                super.doCleanupAfterCompletion(transaction);
                throw new CannotCreateTransactionException("Cannot apply transaction budget",error);
            }
        }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){OperationBudget.check();super.doCommit(status);}
        @Override protected void doCleanupAfterCompletion(Object transaction){try{super.doCleanupAfterCompletion(transaction);}finally{closeAutomatic(transaction);}}
        private void closeAutomatic(Object transaction){var states=automatic.get();var scope=states.remove(transaction);if(scope!=null)scope.close();if(states.isEmpty())automatic.remove();}
    }
}
