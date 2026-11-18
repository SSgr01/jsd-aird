package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import com.jsd.aird.kb.application.port.MineruTaskStore.Task;
import com.jsd.aird.platform.web.IsolatedPostgres;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL", matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class MineruTaskPersistenceTest {
    @Test
    void migrationKeepsCheckpointsAcrossInstancesAndDeletesThemWithVersion() throws Exception {
        try (var db = new IsolatedPostgres(Map.of("iam.organization", "organization", "kb.document_version", "version",
                "kb.mineru_task_checkpoint", "checkpoint", "kb.document_parse_run", "parse_run"))) {
            db.table("organization", "id uuid PRIMARY KEY");
            db.table("version", "id uuid PRIMARY KEY");
            db.table("parse_run", "organization_id uuid, document_version_id uuid, provider text, provider_task_id text, error_message text, result_jsonb jsonb DEFAULT '{\"requestedOcrMode\":\"AUTO\"}', created_at timestamptz DEFAULT now()");
            try (var connection = db.source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V120__mineru_task_checkpoints.sql"));
            }
            var org = UUID.randomUUID(); var version = UUID.randomUUID();
            db.jdbc.update("INSERT INTO iam.organization VALUES (?)", org);
            db.jdbc.update("INSERT INTO kb.document_version VALUES (?)", version);
            var store = new JdbcMineruTaskStore(db.jdbc);
            store.save(org, version, "key", new Task("batch", "https://upload.invalid", false, "waiting-file"));
            var restarted = new JdbcMineruTaskStore(db.jdbc);
            assertThat(restarted.find(org, version, "key")).contains(new Task("batch", "https://upload.invalid", false, "waiting-file"));
            restarted.save(org, version, "key", new Task("batch", null, true, "running"));
            assertThat(store.latest(org, version)).contains(new Task("batch", null, true, "running"));
            assertThat(store.find(UUID.randomUUID(), version, "key")).isEmpty();
            db.jdbc.update("INSERT INTO kb.document_parse_run (organization_id,document_version_id,provider,provider_task_id,error_message) VALUES (?,?,'MineruDocumentParser','old-batch','MinerU 精准任务等待超时')", org, version);
            assertThat(store.previousTimeout(org, version, "AUTO")).contains(new Task("old-batch", null, true, "pending"));
            assertThat(store.previousTimeout(org, version, "ON")).isEmpty();
            db.jdbc.update("DELETE FROM kb.document_version WHERE id=?", version);
            assertThat(store.find(org, version, "key")).isEmpty();
        }
    }
}
