package com.jsd.aird.test;

import java.sql.SQLException;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIT {

    private static final Set<String> EXPECTED_SCHEMAS = Set.of(
            "core", "iam", "mdm", "tpl", "rnd", "quality",
            "spc", "mfg", "kb", "ai", "ops"
    );

    @Test
    void createsPgvectorExtensionAndPlatformSchemas() throws SQLException {
        var image = DockerImageName.parse("pgvector/pgvector:0.8.6-pg18")
                .asCompatibleSubstituteFor("postgres");

        try (var postgres = new PostgreSQLContainer<>(image)
                .withDatabaseName("jsd_aird")
                .withUsername("jsd_aird")
                .withPassword("jsd_aird_dev")) {
            postgres.start();

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var connection = postgres.createConnection("")) {
                try (var statement = connection.prepareStatement(
                        "select extname from pg_extension where extname = 'vector'"
                )) {
                    try (var resultSet = statement.executeQuery()) {
                        assertThat(resultSet.next()).isTrue();
                    }
                }

                try (var statement = connection.prepareStatement(
                        "select schema_name from information_schema.schemata"
                )) {
                    try (var resultSet = statement.executeQuery()) {
                        var schemas = new java.util.HashSet<String>();
                        while (resultSet.next()) {
                            schemas.add(resultSet.getString(1));
                        }
                        assertThat(schemas).containsAll(EXPECTED_SCHEMAS);
                        assertThat(schemas).doesNotContain("export");
                    }
                }
            }
        }
    }
}
