package com.jsd.aird.kb.infrastructure;

import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Focused fixtures may execute one section; the dual-model integration test executes the full migration. */
final class KnowledgeMigrationTestSupport {
    static final String RESOURCE="db/migration/V118__knowledge_metadata_and_dual_model_vectors.sql";
    private KnowledgeMigrationTestSupport() { }
    static void executeWhole(Connection connection) throws Exception {
        ScriptUtils.executeSqlScript(connection,new ClassPathResource(RESOURCE));
    }
    static void executeSection(Connection connection,String section) throws Exception {
        String sql=new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        int start=sql.indexOf("-- section: "+section+":begin");
        int end=sql.indexOf("-- section: "+section+":end");
        if(start<0||end<=start)throw new IllegalArgumentException("Missing migration section: "+section);
        ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.substring(start,end).getBytes(StandardCharsets.UTF_8)));
    }
}
