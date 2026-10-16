package com.jsd.aird.ai.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.operation.OperationManual.Chunk;
import com.jsd.aird.ai.application.port.OperationManualIndexRepository.IndexKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exercises real pgvector SQL inside a disposable schema, without touching application tables. */
@EnabledIfEnvironmentVariable(named = "JSD_AIRD_OPERATION_MANUAL_PG_TEST", matches = "true")
class OperationManualIndexPostgresTest {
    private static final String SCHEMA = "operation_manual_test_" + UUID.randomUUID().toString().replace("-", "");
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcOperationManualIndexRepository repository;
    private final IndexKey key = new IndexKey("manual-1", "model-a", 3);
    private final List<Chunk> chunks = List.of(
            new Chunk("import-0", "import", "导入", "导入数据", "导入数据", "a".repeat(64)),
            new Chunk("private-0", "private", "权限", "配置权限", "配置权限", "b".repeat(64)));

    @BeforeAll
    static void setup() throws Exception {
        var environment = System.getenv();
        dataSource = new DriverManagerDataSource(
                environment.getOrDefault("JSD_AIRD_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/jsd_aird"),
                environment.getOrDefault("JSD_AIRD_DATASOURCE_USERNAME", "jsd_aird"),
                environment.getOrDefault("JSD_AIRD_DATASOURCE_PASSWORD", "jsd_aird_dev"));
        jdbc = new RebasedJdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        try (var input = new ClassPathResource("db/migration/V76__ai_workbench_and_experiment_updates.sql").getInputStream()) {
            var migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var start = migration.indexOf("-- section: operation-manual:begin");
            var end = migration.indexOf("-- section: operation-manual:end");
            assertThat(start).isGreaterThanOrEqualTo(0);
            assertThat(end).isGreaterThan(start);
            jdbc.execute(migration.substring(start, end).replace("ai.", SCHEMA + "."));
        }
        repository = new JdbcOperationManualIndexRepository(jdbc, new DataSourceTransactionManager(dataSource), new ObjectMapper());
    }

    @AfterAll
    static void cleanup() {
        if (jdbc != null && SCHEMA.matches("operation_manual_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @BeforeEach
    void empty() { jdbc.execute("TRUNCATE TABLE " + SCHEMA + ".operation_manual_chunk, " + SCHEMA + ".operation_manual_index"); }

    @Test
    void realVectorsAreRankedAndPermissionsAreAppliedInsideTheQuery() {
        assertThat(repository.synchronize(key, chunks, texts -> List.of(Optional.of("[1,0,0]"), Optional.of("[0,1,0]")))).isTrue();
        assertThat(repository.ready(key)).isTrue();
        var found = repository.search(key, "[0,1,0]", Set.of("import"), 20);
        assertThat(found).extracting(hit -> hit.chunkId()).containsExactly("import-0");
        assertThat(repository.search(new IndexKey("manual-old", "model-a", 3), "[1,0,0]", Set.of("import"), 20)).isEmpty();
        assertThat(repository.synchronize(key, chunks, texts -> { throw new AssertionError("Ready index should not re-embed"); })).isTrue();
        repository.failed(key, "late failure");
        assertThat(repository.ready(key)).isTrue();
    }

    @Test
    void unchangedContentReusesVectorsButModelAndDimensionChangesRebuild() {
        repository.synchronize(key, chunks, texts -> List.of(Optional.of("[1,0,0]"), Optional.of("[0,1,0]")));
        var versionTwo = new IndexKey("manual-2", "model-a", 3);
        assertThat(repository.synchronize(versionTwo, chunks, texts -> { throw new AssertionError("Unchanged chunks must be reused"); })).isTrue();
        var called = new AtomicInteger();
        var changedModel = new IndexKey("manual-2", "model-b", 4);
        repository.synchronize(changedModel, chunks, texts -> {
            called.addAndGet(texts.size());
            return List.of(Optional.of("[1,0,0,0]"), Optional.of("[0,1,0,0]"));
        });
        assertThat(called.get()).isEqualTo(2);
        assertThat(repository.search(changedModel, "[0,1,0,0]", Set.of("private"), 20).getFirst().score()).isCloseTo(1, within(.0001));
        assertThatThrownBy(() -> repository.search(changedModel, "[1,0,0]", Set.of("import"), 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incompleteEmbeddingRollsBackNewVersionAndOldVectorsCannotServeIt() {
        repository.synchronize(key, chunks, texts -> List.of(Optional.of("[1,0,0]"), Optional.of("[0,1,0]")));
        var newKey = new IndexKey("manual-2", "model-a", 3);
        var changed = List.of(new Chunk("import-0", "import", "导入", "新步骤", "新步骤", "c".repeat(64)));
        assertThatThrownBy(() -> repository.synchronize(newKey, changed, texts -> List.of(Optional.empty())))
                .isInstanceOf(IllegalStateException.class);
        repository.failed(newKey, "embedding failed");
        assertThat(repository.ready(newKey)).isFalse();
        assertThat(repository.search(newKey, "[1,0,0]", Set.of("import"), 20)).isEmpty();
        assertThat(repository.ready(key)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.operation_manual_chunk WHERE manual_version = 'manual-2'", Integer.class)).isZero();
    }

    @Test
    void competingInstanceCannotBuildTheSameVersionWhileLockIsHeld() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "operation-manual:" + key.manualVersion() + ":" + key.model() + ":" + key.dimension());
                statement.execute();
            }
            assertThat(repository.synchronize(key, chunks, texts -> { throw new AssertionError("Lock owner alone can embed"); })).isFalse();
            connection.rollback();
        }
    }

    /** Rebase only the table qualifier so production SQL can run unchanged in an isolated schema. */
    private static class RebasedJdbcTemplate extends JdbcTemplate {
        RebasedJdbcTemplate(DataSource source) { super(source); }
        private String sql(String text) { return text.replace("ai.operation_manual_", SCHEMA + ".operation_manual_"); }
        @Override public <T> T queryForObject(String text, Class<T> type) { return super.queryForObject(sql(text), type); }
        @Override public <T> T queryForObject(String text, Class<T> type, Object... args) { return super.queryForObject(sql(text), type, args); }
        @Override public void query(String text, RowCallbackHandler handler, Object... args) { super.query(sql(text), handler, args); }
        @Override public <T> List<T> query(String text, RowMapper<T> mapper, Object... args) { return super.query(sql(text), mapper, args); }
        @Override public int update(String text, Object... args) { return super.update(sql(text), args); }
        @Override public <T> int[][] batchUpdate(String text, Collection<T> batch, int size, ParameterizedPreparedStatementSetter<T> setter) {
            return super.batchUpdate(sql(text), batch, size, setter);
        }
    }
}
