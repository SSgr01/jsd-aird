package com.jsd.aird.test;

import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly enabled against disposable local databases, never the application's database. */
@EnabledIfEnvironmentVariable(named = "JSD_AIRD_V76_MIGRATION_TEST", matches = "true")
class FlywayV76LocalIT {
    private Flyway flyway(String url, String target) {
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/jsd_v76_(fresh|existing)");
        return Flyway.configure().dataSource(url, System.getenv("JSD_AIRD_V76_TEST_USER"),
                        System.getenv("JSD_AIRD_V76_TEST_PASSWORD"))
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion(target)).load();
    }

    @Test
    void canonicalV75UpgradesWithExactlyOneMigrationAndCanBeReappliedWithoutDeletingData() throws Exception {
        var url = System.getenv("JSD_AIRD_V76_FRESH_TEST_URL");
        flyway(url, "52").migrate();
        try (var connection = connect(url)) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/foundation/mdm_rnd_foundation.sql"));
        }
        flyway(url, "75").migrate();
        try (var connection = connect(url); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT script FROM flyway_schema_history WHERE version='75'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("V75__structured_retrieval_experiment_context.sql");
            }
            try (var rows = statement.executeQuery("SELECT to_regclass('ai.modeling_workbench_run')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isNull();
            }
        }
        var latest = flyway(url, "76");
        assertThat(latest.info().pending()).hasSize(1);
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertV76Schema(url);
        try (var connection = connect(url); var input = new ClassPathResource(
                "db/migration/V76__ai_workbench_and_experiment_updates.sql").getInputStream()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            connection.commit();
        }
        assertV76Schema(url);
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    @Test
    void previouslyAppliedLocalChangesRemainUsableAfterHistoryAlignment() throws Exception {
        var url = System.getenv("JSD_AIRD_V76_EXISTING_TEST_URL");
        var latest = flyway(url, "76");
        assertThat(latest.info().pending()).hasSize(1);
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertV76Schema(url);
        try (var connection = connect(url); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM ai.model_version WHERE workbench_run_id IS NOT NULL")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isGreaterThan(0);
        }
    }

    private java.sql.Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, System.getenv("JSD_AIRD_V76_TEST_USER"),
                System.getenv("JSD_AIRD_V76_TEST_PASSWORD"));
    }

    private void assertV76Schema(String url) throws Exception {
        try (var connection = connect(url); var statement = connection.createStatement()) {
            for (var table : List.of("operation_manual_index", "operation_manual_chunk",
                    "research_candidate_observation_v2", "research_candidate_history_observation_v2",
                    "modeling_workbench_run")) {
                try (var rows = statement.executeQuery("SELECT to_regclass('ai." + table + "')")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).as(table).isNotNull();
                }
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE "
                    + "(table_schema='data' AND table_name='import_job' AND column_name='hidden_at') OR "
                    + "(table_schema='ai' AND table_name IN ('model_version','artifact') AND column_name='workbench_run_id')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='ai' "
                    + "AND table_name='model_version' AND column_name IN ('training_snapshot_id','training_job_id') AND is_nullable='YES'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE version::numeric > 76 OR version='69.1'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }
}
