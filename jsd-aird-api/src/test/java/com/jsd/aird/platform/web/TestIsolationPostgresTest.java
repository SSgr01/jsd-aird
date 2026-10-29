package com.jsd.aird.platform.web;

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class TestIsolationPostgresTest {
    @Test void preparedAndDirectSqlRejectUnmappedApplicationTablesBeforeExecution() throws Exception {
        try(var db=new IsolatedPostgres(Map.of("mdm.project","project"));var connection=db.source.getConnection()) {
            assertThatThrownBy(()->connection.prepareStatement("SELECT * FROM data.import_job"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Unmapped application table");
            try(var statement=connection.createStatement()) {
                assertThatThrownBy(()->statement.execute("DELETE FROM iam.app_user WHERE false"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("Unmapped application table");
            }
            db.table("project","id integer");
            db.jdbc.update("INSERT INTO mdm.project(id) VALUES(?)",1);
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM mdm.project",Integer.class)).isEqualTo(1);
        }
    }
}
