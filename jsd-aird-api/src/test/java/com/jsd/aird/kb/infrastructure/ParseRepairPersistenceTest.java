package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.platform.web.IsolatedPostgres;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class ParseRepairPersistenceTest {
    @Test void migrationAndCandidateLifecycleAreIsolatedFromHumanContentAndPublication() throws Exception {
        try(var db=new IsolatedPostgres(Map.of("iam.organization","organization","iam.app_user","app_user",
                "kb.document","document","kb.document_version","version","kb.document_parse_run","parse_run",
                "kb.mineru_task_checkpoint","checkpoint","kb.document_parse_repair","repair","kb.document_review_revision","revision"))) {
            db.table("organization","id uuid PRIMARY KEY"); db.table("app_user","id uuid PRIMARY KEY");
            db.table("document","id uuid PRIMARY KEY,organization_id uuid,current_version_id uuid,current_publication_id uuid");
            db.table("version","id uuid PRIMARY KEY,organization_id uuid,document_id uuid");
            db.table("parse_run","id uuid PRIMARY KEY,organization_id uuid,document_version_id uuid,status text,run_no integer,result_jsonb jsonb");
            db.table("checkpoint","id uuid PRIMARY KEY");
            db.table("revision","id uuid PRIMARY KEY,document_version_id uuid,revision_no integer,lock_version integer");
            try(var c=db.source.getConnection()) { ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V123__mineru_self_hosted_and_parse_repair.sql")); }
            UUID org=UUID.randomUUID(),actor=UUID.randomUUID(),doc=UUID.randomUUID(),version=UUID.randomUUID(),publication=UUID.randomUUID(),revision=UUID.randomUUID();
            db.jdbc.update("INSERT INTO iam.organization VALUES (?)",org); db.jdbc.update("INSERT INTO iam.app_user VALUES (?)",actor);
            db.jdbc.update("INSERT INTO kb.document VALUES (?,?,?,?)",doc,org,version,publication);
            db.jdbc.update("INSERT INTO kb.document_version VALUES (?,?,?)",version,org,doc);
            db.jdbc.update("INSERT INTO kb.document_review_revision VALUES (?,?,1,7)",revision,version);
            var store=new JdbcParseRepairStore(db.jdbc,new ObjectMapper());
            var repair=db.tx.execute(s -> store.reserve(org,actor,doc,version,"request-1"));
            assertThat(repair.basePublicationId()).isEqualTo(publication); assertThat(repair.baseLockVersion()).isEqualTo(7);
            var duplicate=db.tx.execute(s -> store.reserve(org,actor,doc,version,"request-1")); assertThat(duplicate.id()).isEqualTo(repair.id());
            var active=db.tx.execute(s -> store.reserve(org,actor,doc,version,"request-2")); assertThat(active.id()).isEqualTo(repair.id());
            store.queued(org,repair.id(),UUID.randomUUID()); store.running(org,repair.id());
            store.ready(org,repair.id(),UUID.randomUUID(),new ObjectMapper().createObjectNode().put("tier","standard"));
            assertThat(store.find(org,repair.id()).orElseThrow().status()).isEqualTo("READY");
            assertThat(Boolean.TRUE.equals(db.tx.execute(s -> store.lockUnchanged(repair)))).isTrue();
            db.jdbc.update("UPDATE kb.document_review_revision SET lock_version=8 WHERE id=?",revision);
            assertThat(Boolean.TRUE.equals(db.tx.execute(s -> store.lockUnchanged(repair)))).isFalse();
            assertThat(db.jdbc.queryForObject("SELECT current_publication_id FROM kb.document WHERE id=?",UUID.class,doc)).isEqualTo(publication);
            assertThat(store.find(UUID.randomUUID(),repair.id())).isEmpty();
        }
    }
}
