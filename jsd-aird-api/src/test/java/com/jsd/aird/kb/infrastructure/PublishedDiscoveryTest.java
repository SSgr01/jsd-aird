package com.jsd.aird.kb.infrastructure;

import com.jsd.aird.platform.web.IsolatedPostgres;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class PublishedDiscoveryTest {
    @Test void publishedTagsAreSeparateFromBibliographyAndRespectVersionGrantAndScope() {
        try (var db=new IsolatedPostgres(Map.of("kb.document","document","kb.document_version","version",
                "kb.publication","publication","ops.file_object","file_object","kb.document_chunk","chunk",
                "kb.document_ai_grant","ai_grant","kb.document_bibliography","bibliography"))) {
            db.table("document","id uuid PRIMARY KEY,organization_id uuid,current_version_no int,current_publication_id uuid,lifecycle_status text,title text,category_id uuid");
            db.table("version","id uuid PRIMARY KEY,document_id uuid,version_no int,original_name text,file_object_id uuid");
            db.table("publication","id uuid PRIMARY KEY,document_version_id uuid,review_revision_id uuid,status text,metadata_snapshot_jsonb jsonb");
            db.table("file_object","id uuid PRIMARY KEY,organization_id uuid,status text");
            db.table("chunk","id uuid PRIMARY KEY,document_id uuid,document_version_id uuid,review_revision_id uuid,chunk_role text,page_no int,section text,content text,chunk_no int");
            db.table("ai_grant","organization_id uuid,document_id uuid,status text");
            db.table("bibliography","organization_id uuid,version_id uuid,accepted_jsonb jsonb");
            UUID org=UUID.randomUUID(),doc=UUID.randomUUID(),version=UUID.randomUUID(),pub=UUID.randomUUID(),review=UUID.randomUUID(),file=UUID.randomUUID(),category=UUID.randomUUID();
            db.jdbc.update("INSERT INTO kb.document VALUES (?,?,2,?,'ACTIVE','Unpublished draft title',?)",doc,org,pub,category);
            db.jdbc.update("INSERT INTO ops.file_object VALUES (?,?,'ACTIVE')",file,org);
            db.jdbc.update("INSERT INTO kb.document_version VALUES (?,?,2,'source.pdf',?)",version,doc,file);
            db.jdbc.update("INSERT INTO kb.publication VALUES (?,?,?,'CURRENT',?::jsonb)",pub,version,review,"{\"title\":\"Published paper\",\"tags\":[\"自愈材料\"]}");
            db.jdbc.update("INSERT INTO kb.publication VALUES (?,?,?,'SUPERSEDED',?::jsonb)",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"{\"tags\":[\"旧版本标签\"]}");
            db.jdbc.update("INSERT INTO kb.document_ai_grant VALUES (?,?,'APPROVED')",org,doc);
            db.jdbc.update("INSERT INTO kb.document_bibliography VALUES (?,?,?::jsonb)",org,version,"{\"title\":\"Accepted paper\",\"authors\":[{\"name\":\"Actual Author\"}],\"doi\":\"10.1234/real.2026\"}");
            for(int i=0;i<30;i++) db.jdbc.update("INSERT INTO kb.document_chunk VALUES (?,?,?,?,'CHILD',1,'Methods','Authorized evidence',?)",UUID.randomUUID(),doc,version,review,i);
            var repo=new JdbcKnowledgeRepository(db.jdbc);
            assertThat(repo.searchPublishedTags(org,List.of("请查自愈材料相关资料"),true,List.of(category),Set.of(doc),20)).hasSize(1);
            assertThat(repo.searchPublishedTags(org,List.of("Actual Author"),true,List.of(),null,20)).isEmpty();
            assertThat(repo.searchPublishedTags(org,List.of("旧版本标签"),true,List.of(),null,20)).isEmpty();
            assertThat(repo.searchPublishedMetadata(org,List.of("10.1234/real.2026"),true,List.of(),null,20)).hasSize(1);
            assertThat(repo.searchPublishedMetadata(org,List.of("Actual Author"),true,List.of(),null,20)).hasSize(1);
            assertThat(repo.searchPublishedMetadata(org,List.of("Unpublished draft title"),true,List.of(),null,20)).isEmpty();
            assertThat(repo.searchPublishedTags(org,List.of("自愈材料"),true,List.of(),Set.of(),20)).isEmpty();
            assertThat(repo.searchPublishedTags(UUID.randomUUID(),List.of("自愈材料"),true,List.of(),null,20)).isEmpty();
            db.jdbc.update("UPDATE kb.document_ai_grant SET status='REVOKED'");
            assertThat(repo.searchPublishedTags(org,List.of("自愈材料"),true,List.of(),null,20)).isEmpty();
            assertThat(repo.searchPublishedMetadata(org,List.of("Actual Author"),true,List.of(),null,20)).isEmpty();
        }
    }
}
