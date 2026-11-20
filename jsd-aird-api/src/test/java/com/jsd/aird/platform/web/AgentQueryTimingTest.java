package com.jsd.aird.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class AgentQueryTimingTest {
    @Test void recordsDatabaseFailuresOnlyInsideAgentRequestWithoutSqlValues() {
        var jdbc = (JdbcTemplate)new AgentQueryTiming().postProcessAfterInitialization(new JdbcTemplate(), "jdbc");
        try {
            assertThatThrownBy(() -> jdbc.queryForObject("private-sql", String.class)).isInstanceOf(IllegalStateException.class);
            assertThat(RequestTimingHolder.snapshot()).isEmpty();
            RequestTimingHolder.put("agent_tool", 1);
            assertThatThrownBy(() -> jdbc.queryForObject("private-sql", String.class)).isInstanceOf(IllegalStateException.class);
            assertThat(RequestTimingHolder.snapshot().get("db_query_count")).isEqualTo(1);
            assertThat(RequestTimingHolder.snapshot().get("db_query_ns")).isPositive();
            assertThat(RequestTimingHolder.snapshot().toString()).doesNotContain("private-sql");
        } finally { RequestTimingHolder.clear(); }
    }
}
