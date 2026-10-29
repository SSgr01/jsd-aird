package com.jsd.aird.platform.web;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only SQL routing. Unmapped application schema access is rejected. */
public final class IsolatedPostgres implements AutoCloseable {
    public final String schema = "module_test_" + UUID.randomUUID().toString().replace("-", "");
    public final JdbcTemplate jdbc;
    public final TransactionTemplate tx;
    public final DataSource source;
    private final HikariDataSource pool;
    private final Map<String,String> tables;

    public IsolatedPostgres(Map<String,String> tables) {
        this.tables = Map.copyOf(tables);
        var env = System.getenv(); var url = env.get("JSD_AIRD_DATASOURCE_URL");
        if(url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*"))
            throw new IllegalStateException("Only a loopback disposable schema is allowed");
        var config = new HikariConfig(); config.setJdbcUrl(url);
        config.setUsername(env.get("JSD_AIRD_DATASOURCE_USERNAME")); config.setPassword(env.get("JSD_AIRD_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(6); config.setMinimumIdle(0); config.setConnectionTimeout(10_000);
        config.setPoolName(schema); pool = new HikariDataSource(config);
        source = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return wrap(pool.getConnection()); }
            @Override public Connection getConnection(String user,String password) throws SQLException { return getConnection(); }
        };
        jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        try { jdbc.execute("CREATE SCHEMA " + schema); } catch(RuntimeException error) { pool.close(); throw error; }
    }
    public void table(String name,String fields) {
        if(!tables.containsValue(name)) throw new IllegalArgumentException("Unknown test table");
        jdbc.execute("CREATE TABLE " + schema + "." + name + " (" + fields + ")");
    }
    public void clear() { jdbc.execute("TRUNCATE " + tables.values().stream().distinct().map(name -> schema+"."+name).collect(java.util.stream.Collectors.joining(","))); }
    public String table(String name) { if(!tables.containsValue(name))throw new IllegalArgumentException(); return schema+"."+name; }
    private String route(String sql) throws SQLException {
        for(var entry: tables.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String,String> e)->e.getKey().length()).reversed()).toList())
            sql = sql.replace(entry.getKey(),schema+"."+entry.getValue());
        if(java.util.regex.Pattern.compile("\\b(?:mdm|mfg|rnd|tpl|kb|data|quality|iam|ops|ai|spc|core)\\.").matcher(sql).find())
            throw new SQLException("Unmapped application table access refused by isolated test");
        return sql;
    }
    private Connection wrap(Connection original) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(args != null && args.length > 0 && args[0] instanceof String sql && method.getName().startsWith("prepare")) {
                args = args.clone(); args[0] = route(sql);
            }
            try {
                var result=method.invoke(original,args);
                if(method.getName().equals("createStatement") && result instanceof Statement statement)
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                        if(a!=null && a.length>0 && a[0] instanceof String sql){a=a.clone();a[0]=route(sql);}
                        try{return m.invoke(statement,a);}catch(InvocationTargetException error){throw error.getCause();}
                    });
                return result;
            } catch(InvocationTargetException error) { throw error.getCause(); }
        });
    }
    @Override public void close() {
        try { if(schema.matches("module_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
        finally { pool.close(); }
    }
}
