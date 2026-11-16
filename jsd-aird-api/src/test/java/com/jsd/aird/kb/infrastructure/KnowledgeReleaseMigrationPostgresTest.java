package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.jsd.aird.platform.web.IsolatedPostgres;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class KnowledgeReleaseMigrationPostgresTest {
    @Test void oneMigrationInstallsMetadataAndPreservesLegacyVectors() throws Exception {
        try(var db=new IsolatedPostgres(Map.ofEntries(
                Map.entry("iam.organization","organization"),Map.entry("kb.document","document"),
                Map.entry("kb.document_chunk","chunk"),Map.entry("kb.document_bibliography","bibliography"),
                Map.entry("kb.metadata_search_text","metadata_search_text"),Map.entry("kb.metadata_extraction_cache","metadata_cache"),
                Map.entry("kb.document_chunk_embedding","embedding"),Map.entry("ai.operation_manual_chunk","manual"),
                Map.entry("kb.document_processing_step","steps")))) {
            db.table("organization","id uuid PRIMARY KEY");
            db.table("document","id uuid PRIMARY KEY,organization_id uuid");
            db.table("chunk","id uuid PRIMARY KEY,document_id uuid,embedding vector,embedding_model text");
            db.table("bibliography","accepted_jsonb jsonb DEFAULT '{}'::jsonb");
            db.table("manual","embedding vector,embedding_dimension int,CHECK(vector_dims(embedding)=embedding_dimension)");
            db.table("steps","step_key varchar(32)");
            UUID org=UUID.randomUUID(),doc=UUID.randomUUID(),chunk=UUID.randomUUID();
            db.jdbc.update("INSERT INTO iam.organization VALUES (?)",org);
            db.jdbc.update("INSERT INTO kb.document VALUES (?,?)",doc,org);
            db.jdbc.update("INSERT INTO kb.document_chunk VALUES (?,?,?::vector,'text-embedding-v4')",chunk,doc,"[1"+",0".repeat(1023)+"]");
            try(var connection=db.source.getConnection()){KnowledgeMigrationTestSupport.executeWhole(connection);}
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM kb.document_chunk_embedding WHERE embedding_model='text-embedding-v4' AND embedding_dimension=1024",Integer.class)).isEqualTo(1);
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM kb.document_chunk WHERE embedding IS NOT NULL",Integer.class)).isEqualTo(1);
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM kb.metadata_extraction_cache",Integer.class)).isZero();
            db.jdbc.update("INSERT INTO kb.document_bibliography(accepted_jsonb) VALUES (?::jsonb)","{\"extra_fields\":[{\"label\":\"Project\",\"value\":\"AX77\"}]}");
            assertThat(db.jdbc.queryForObject("SELECT search_text FROM kb.document_bibliography",String.class)).contains("AX77");
            db.jdbc.update("INSERT INTO kb.document_processing_step VALUES ('VECTOR_INDEX:cloud:1024')");
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexdef LIKE '%USING hnsw%'",Integer.class,db.schema)).isEqualTo(4);
            assertThat(db.jdbc.queryForObject("SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid=?::regclass AND attname='embedding'",String.class,db.table("manual"))).isEqualTo("halfvec");
        }
    }
}
