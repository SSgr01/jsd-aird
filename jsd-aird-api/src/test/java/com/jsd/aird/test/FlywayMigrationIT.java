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
            "spc", "mfg", "kb", "ai", "ops", "data"
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

                try (var statement = connection.prepareStatement(
                        "select count(*) from information_schema.columns where table_schema = 'tpl' and table_name = 'template_version' and column_name = 'template_scope'"
                ); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.tables
                        where table_schema = 'kb' and table_name in (
                          'document_parse_run', 'document_parse_block', 'document_parse_issue',
                          'publication', 'document_ai_grant'
                        )
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(5);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.tables
                        where (table_schema = 'kb' and table_name in (
                          'document_extract_field', 'document_relation', 'ai_usage_grant',
                          'knowledge_page', 'knowledge_page_version', 'knowledge_page_source'
                        )) or (table_schema = 'core' and table_name = 'business_object_ref')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.columns
                        where (table_schema = 'kb' and table_name = 'document'
                               and column_name in ('document_type', 'ai_status'))
                           or (table_schema = 'kb' and table_name = 'document_version'
                               and column_name in ('media_processing_consent', 'media_consent_by', 'media_consent_at'))
                           or (table_schema = 'kb' and table_name = 'document_parse_issue'
                               and column_name = 'extract_field_id')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.columns
                        where table_schema = 'kb' and table_name = 'document_parse_issue'
                          and column_name in (
                            'id', 'parse_run_id', 'parse_block_id',
                            'issue_code', 'severity', 'message', 'status', 'resolution', 'updated_at'
                          )
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(9);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.views
                        where (table_schema = 'kb' and table_name = 'current_file_search_projection')
                           or (table_schema = 'data' and table_name = 'completed_source_file_projection')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(2);
                }

                try (var statement = connection.prepareStatement(
                        "select count(*) from information_schema.columns where table_schema = 'tpl' and table_name = 'template' and column_name = 'purpose'"
                ); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement(
                        "select count(*) from information_schema.columns where table_schema = 'tpl' and table_name = 'template_version' and column_name = 'target_data_type'"
                ); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.columns
                        where table_schema = 'data'
                          and column_name = 'target_data_type'
                          and table_name in ('import_job', 'data_category', 'data_asset')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement(
                        "select count(*) from information_schema.tables where table_schema = 'data'"
                ); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(12);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.tables
                        where (table_schema = 'data' and table_name in ('data_asset', 'data_asset_revision'))
                           or (table_schema = 'ai' and table_name = 'data_asset_index_entry')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement("select count(*) from information_schema.tables where table_schema = 'tpl' and table_name = 'template_import_contract'");
                     var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(1);
                }

                try (var statement = connection.prepareStatement("select count(*) from information_schema.columns where table_schema = 'data' and table_name = 'data_value' and column_name in ('binding_id','value_path','label_path','rag_eligible','calculation_status','calculation_trust_status')");
                     var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(6);
                }

                try (var statement = connection.prepareStatement("select count(*) from information_schema.tables where table_schema = 'data' and table_name = 'import_component_override'");
                     var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(1);
                }

                try (var statement = connection.prepareStatement("""
                        select table_name, column_name, character_maximum_length
                        from information_schema.columns
                        where table_schema = 'tpl'
                          and table_name in ('recognition_suggestion', 'recognition_run',
                                             'recognition_call', 'template_quality_issue')
                          and column_name in ('region_id', 'relation_id', 'block_id', 'root_block_id')
                        """ ); var resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        assertThat(resultSet.getInt("character_maximum_length"))
                                .isEqualTo(256);
                    }
                }
            }
        }
    }
}
