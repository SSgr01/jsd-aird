package com.jsd.aird.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.platform.web.IsolatedPostgres;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL", matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class ExhaustedJobRecoveryPostgresTest {
    @Test void expiresOnlyExhaustedLeasesAndFencesTheirOldOwners() throws Exception {
        try (var db = new IsolatedPostgres(Map.of("ops.async_job", "job"))) {
            db.table("job", "id uuid PRIMARY KEY, status text, current_stage text, attempt_count int, max_attempts int, "
                    + "lease_expires_at timestamptz, lease_owner text, lease_token uuid, lease_generation bigint, "
                    + "last_error text, finished_at timestamptz, updated_at timestamptz");
            var expired = UUID.randomUUID(); var active = UUID.randomUUID(); var retryable = UUID.randomUUID();
            for (var id : java.util.List.of(expired, active, retryable)) {
                db.jdbc.update("INSERT INTO ops.async_job VALUES (?, 'RUNNING', 'PREPARING', ?, 5, "
                        + "now() + make_interval(secs => ?), 'old', ?, 7, 'original failure', NULL, now())",
                        id, id.equals(retryable) ? 4 : 5, id.equals(active) ? 600 : -600, UUID.randomUUID());
            }
            var repository = new PostgresWorkRepository(db.jdbc, new ObjectMapper());
            assertThat(repository.expireExhaustedJobs()).isEqualTo(1);
            var row = db.jdbc.queryForMap("SELECT * FROM ops.async_job WHERE id=?", expired);
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("lease_generation")).isEqualTo(8L);
            assertThat(row.get("lease_token")).isNull();
            assertThat(row.get("finished_at")).isNotNull();
            assertThat(row.get("last_error").toString()).contains("original failure");
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM ops.async_job WHERE status='RUNNING'", Integer.class))
                    .isEqualTo(2);
            assertThat(repository.expireExhaustedJobs()).isZero();
        }
    }
}
