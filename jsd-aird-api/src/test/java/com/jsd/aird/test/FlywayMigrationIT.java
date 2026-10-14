package com.jsd.aird.test;

import java.sql.SQLException;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.facts.UnifiedFactService;
import com.jsd.aird.data.infrastructure.JdbcSourceRecognitionRepository;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.recognition.application.port.SourceRecognitionRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FlywayMigrationIT {

    private static final Set<String> EXPECTED_SCHEMAS = Set.of(
            "core", "iam", "mdm", "tpl", "rnd", "quality",
            "spc", "mfg", "kb", "ai", "ops", "data"
    );

    @Test
    void createsSearchExtensionsAndPlatformSchemas() throws SQLException {
        var externalUrl = System.getProperty("jsd.it.jdbcUrl");
        if (externalUrl != null && !externalUrl.isBlank()) {
            verifyDatabase(externalUrl, System.getProperty("jsd.it.username", "postgres"),
                    System.getProperty("jsd.it.password", ""));
            return;
        }
        var image = DockerImageName.parse("pgvector/pgvector:0.8.6-pg18")
                .asCompatibleSubstituteFor("postgres");

        try (var postgres = new PostgreSQLContainer<>(image)
                .withDatabaseName("jsd_aird")
                .withUsername("jsd_aird")
                .withPassword("jsd_aird_dev")) {
            postgres.start();
            verifyDatabase(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    private void verifyDatabase(String jdbcUrl, String username, String password) throws SQLException {
            Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("52"))
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(jdbcUrl, username, password)) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/foundation/mdm_rnd_foundation.sql"));
            }

            Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(jdbcUrl, username, password)) {
                try (var statement = connection.prepareStatement(
                        "select count(*) from pg_extension where extname in ('vector', 'pg_trgm')"
                )) {
                    try (var resultSet = statement.executeQuery()) {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getInt(1)).isEqualTo(2);
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
                          'document_parse_run', 'document_source_node', 'document_review_revision',
                          'document_review_issue_state', 'document_parse_issue',
                          'document_parse_issue_source_node', 'publication', 'document_ai_grant',
                          'document_source_table', 'document_source_table_cell',
                          'document_review_table_cell_patch', 'document_review_table_row_state'
                        )
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getInt(1)).isEqualTo(12);
                }

                try (var statement = connection.prepareStatement(
                        "select max(version::integer), count(*) filter (where version='60') from flyway_schema_history where success"
                ); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getInt(1)).isEqualTo(63);
                        assertThat(resultSet.getInt(2)).isEqualTo(1);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.tables
                        where table_schema='ai' and table_name in (
                          'training_scheduler_setting','training_schedule_intent'
                        )
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(2);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.columns
                        where table_schema='ops' and table_name='async_job'
                          and column_name in ('lease_token','lease_generation')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(2);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from pg_indexes where schemaname='ai' and indexname in (
                          'uq_training_snapshot_business_fingerprint','uq_training_job_business_key',
                          'uq_training_job_active_target','uq_training_job_running_organization',
                          'uq_model_version_number','uq_model_version_active_target'
                        )
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(6);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.triggers
                        where event_object_schema='ai' and event_object_table='training_job_attempt'
                          and trigger_name='protect_terminal_training_attempt'
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(2);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.tables
                        where (table_schema='mdm' and table_name in (
                          'business_partner','partner_contact','partner_contact_project','communication_record',
                          'customer_requirement','material','project','project_stage','project_task','project_material',
                          'meeting_minutes','project_document','project_document_version','project_document_review','project_document_audit'
                        )) or (table_schema='rnd' and table_name in (
                          'experiment','experiment_version','experiment_review','experiment_audit','experiment_attachment',
                          'experiment_outbox','experiment_category','experiment_import_job','research_test_record',
                          'research_test_version','research_test_review','research_test_audit','research_test_upload'
                        ))
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(28);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.tables
                        where table_schema='ai' and table_name in (
                          'prediction_target','target_version','input_field','input_field_version','input_scheme',
                          'input_scheme_field','source_mapping_version','modeling_policy_version','material_dictionary_version',
                          'configuration_command_receipt',
                          'training_sample','sample_source','sample_revision','training_eligibility','sample_identity_issue',
                          'data_review','data_review_decision','training_snapshot','training_snapshot_item','training_job',
                          'training_job_attempt','artifact','model_version','model_release','prediction_record',
                          'research_run_v2','research_candidate_v2','research_experiment_link_v2','model_feedback'
                        )
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(29);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.tables
                        where table_schema='ai' and table_name in (
                          'training_dataset','training_dataset_record','research_run','research_candidate',
                          'research_experiment_link','formula_model_activation','formula_model_target',
                          'formula_model_version','formula_model_snapshot','formulation_task_profile'
                        )
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.columns
                        where table_schema='data' and table_name='import_job'
                          and column_name='latest_training_dataset_id'
                        """ ); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.columns
                        where (table_schema='ai' and table_name='prediction_target' and column_name='current_input_scheme_id')
                           or (table_schema='ai' and table_name='target_version' and column_name='value_type')
                           or (table_schema='ai' and table_name='input_scheme' and column_name='target_version_id')
                           or (table_schema='ai' and table_name='source_mapping_version' and column_name='target_version_id')
                           or (table_schema='ai' and table_name='material_dictionary_version' and column_name='revision')
                           or (table_schema='mdm' and table_name='material_alias' and column_name='normalized_alias')
                           or (table_schema='ai' and table_name='modeling_policy_version' and column_name='kind')
                           or (table_schema='ai' and table_name='prediction_record' and column_name='execution_status')
                           or (table_schema='ai' and table_name='prediction_target' and column_name='current_quality_policy_version_id')
                           or (table_schema='ai' and table_name='prediction_target' and column_name='current_domain_policy_version_id')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(10);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from iam.permission_definition where code in (
                          'ai.modeling.read','ai.model.read','ai.performance.predict','ai.formula.predict',
                          'ai.experiment.optimize','ai.modeling.manage','ai.data.review','ai.model.publish',
                          'ai.training.operate','ai.config.manage'
                        )
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(10);
                }

                try (var statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO iam.organization(id,name)
                            VALUES('20000000-0000-0000-0000-000000000001','R01 other organization');
                            INSERT INTO iam.app_user(id,organization_id,username,display_name)
                            VALUES('20000000-0000-0000-0000-000000000002',
                                   '20000000-0000-0000-0000-000000000001','r01-other','R01 other user');
                            INSERT INTO ai.prediction_target(
                              id,organization_id,target_code,name,performance_project,value_type,created_by,updated_by
                            ) VALUES(
                              '20000000-0000-0000-0000-000000000010',
                              '20000000-0000-0000-0000-000000000001','gloss60','60 degree gloss','UV',
                              'CONTINUOUS','20000000-0000-0000-0000-000000000002',
                              '20000000-0000-0000-0000-000000000002'
                            )
                            """);
                    assertThatThrownBy(() -> statement.execute("""
                            INSERT INTO ai.target_version(
                              id,organization_id,target_id,version_no,status,value_type,definition_jsonb,config_hash,created_by
                            ) VALUES(
                              '20000000-0000-0000-0000-000000000011',
                              '00000000-0000-0000-0000-000000000001',
                              '20000000-0000-0000-0000-000000000010',1,'PUBLISHED','CONTINUOUS','{}',
                              repeat('a',64),'00000000-0000-0000-0000-000000000002'
                            )
                            """)).isInstanceOf(SQLException.class);
                    statement.execute("""
                            INSERT INTO ai.target_version(
                              id,organization_id,target_id,version_no,status,value_type,definition_jsonb,config_hash,created_by
                            ) VALUES(
                              '20000000-0000-0000-0000-000000000012',
                              '20000000-0000-0000-0000-000000000001',
                              '20000000-0000-0000-0000-000000000010',1,'PUBLISHED','CONTINUOUS','{}',
                              repeat('b',64),'20000000-0000-0000-0000-000000000002'
                            )
                            """);
                    assertThatThrownBy(() -> statement.execute("""
                            UPDATE ai.target_version SET definition_jsonb='{"changed":true}'
                            WHERE id='20000000-0000-0000-0000-000000000012'
                            """)).isInstanceOf(SQLException.class);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.tables
                        where (table_schema = 'kb' and table_name in (
                          'document_extract_field', 'document_parse_block', 'document_relation', 'ai_usage_grant',
                          'knowledge_page', 'knowledge_page_version', 'knowledge_page_source'
                        )) or (table_schema = 'core' and table_name = 'business_object_ref')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isZero();
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.columns
                        where (table_schema = 'kb' and table_name = 'publication'
                               and column_name = 'review_revision_id')
                           or (table_schema = 'kb' and table_name = 'document_processing_step'
                               and column_name = 'review_revision_id')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(2);
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
                            'id', 'parse_run_id',
                            'issue_code', 'severity', 'message', 'status', 'resolution', 'updated_at'
                          )
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(8);
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
                    assertThat(resultSet.getInt(1)).isEqualTo(17);
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

                try (var statement = connection.prepareStatement("""
                        select count(*) from information_schema.columns
                        where (table_schema = 'kb' and table_name = 'document_version'
                               and column_name in ('ocr_mode', 'allow_agent_fallback', 'effective_ocr',
                                                   'parser_mode', 'parser_metadata_jsonb'))
                           or (table_schema = 'kb' and table_name = 'document_chunk'
                               and column_name in ('chunk_role', 'heading_path_jsonb', 'source_anchors_jsonb',
                                                   'relations_jsonb', 'model_token_length'))
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(10);
                }

                try (var statement = connection.prepareStatement("""
                        select count(*) from pg_indexes
                        where schemaname = 'kb'
                          and indexname in ('idx_kb_chunk_child_document', 'idx_kb_chunk_content_trgm')
                        """); var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(2);
                }
            }

            var dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
            var mapper = new ObjectMapper().findAndRegisterModules();
            verifyRecognitionBoundaryPersistence(dataSource, mapper);
    }

    private void verifyRecognitionBoundaryPersistence(DriverManagerDataSource dataSource, ObjectMapper mapper) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var repository = new JdbcSourceRecognitionRepository(jdbc, mapper);
        var organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var actorId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var fileId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        var jobId = UUID.fromString("30000000-0000-0000-0000-000000000002");
        jdbc.update("""
                INSERT INTO ops.file_object(id,organization_id,bucket,object_key,original_name,content_type,
                    size_bytes,sha256,status,created_by,activated_at)
                VALUES(?,?, 'r03-test','boundary.xlsx','boundary.xlsx','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                    10,?,'ACTIVE',?,now())
                """, fileId, organizationId, "c".repeat(64), actorId);
        repository.create(new SourceRecognitionRepository.NewJob(jobId, organizationId, fileId,
                "boundary.xlsx", "c".repeat(64), "XLSX", "DATA_CENTER", "FREEFORM",
                null, null, "EXPERIMENT_DRAFT", null, "ALL", mapper.createObjectNode(),
                mapper.createArrayNode(), actorId));

        var experiment1 = UUID.fromString("30000000-0000-0000-0000-000000000010");
        var version1 = UUID.fromString("30000000-0000-0000-0000-000000000011");
        var experiment2 = UUID.fromString("30000000-0000-0000-0000-000000000020");
        var version2 = UUID.fromString("30000000-0000-0000-0000-000000000021");
        createExperiment(jdbc, organizationId, actorId, experiment1, version1, "R03-1");
        createExperiment(jdbc, organizationId, actorId, experiment2, version2, "R03-2");

        var empty = mapper.createObjectNode();
        var sampleAFact = mapper.createObjectNode();
        var observations = sampleAFact.putArray("observations");
        for (var index = 1; index <= 3; index++) observations.addObject()
                .put("observationId", "gloss-observation-" + index)
                .put("replicateGroupKey", "gloss60:film-1")
                .put("measurementIndex", index)
                .put("fieldCode", "gloss60")
                .put("effectiveValue", 79 + index);
        var sampleA = new SourceRecognitionRepository.RecognizedSample("experiment-a", "sample-a", "R03:SAMPLE-A",
                List.of("sheet1:formula", "sheet1:gloss"), "Sample A", empty, empty, sampleAFact, empty, "d".repeat(64));
        var sampleB = new SourceRecognitionRepository.RecognizedSample("experiment-a", "sample-b", "R03:SAMPLE-B",
                List.of("sheet1:sample-b"), "Sample B", empty, empty, empty, empty, "e".repeat(64));
        var sampleC = new SourceRecognitionRepository.RecognizedSample("experiment-b", "sample-c", "R03:SAMPLE-C",
                List.of("page:3"), "Sample C", empty, empty, empty, empty, "f".repeat(64));
        jdbc.update("UPDATE data.import_job SET status='WAITING_MAPPING' WHERE id=?", jobId);
        assertThat(repository.claimFinalization(organizationId, jobId, "DATA_CENTER", 0)).isEqualTo(1);
        var submission = repository.finalizeData(organizationId, jobId, actorId,
                List.of(sampleA, sampleB, sampleC), empty, "4".repeat(64));
        var facts = new UnifiedFactService(jdbc, mapper, new ConfigurationHashing(mapper),
                mock(AuthorizationService.class));
        assertThat(facts.projectSubmission(organizationId, submission.submissionId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.training_sample WHERE logical_sample_key LIKE 'R03:SAMPLE-%'",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(r.observations_jsonb->'items')
                FROM ai.training_sample s JOIN ai.sample_revision r ON r.id=s.current_sample_revision_id
                WHERE s.logical_sample_key='R03:SAMPLE-A'
                """, Integer.class)).isEqualTo(3);

        repository.linkExperiment(organizationId, jobId, submission.submissionId(), "experiment-a", experiment1, version1,
                "R03-1", empty, empty, List.of(sampleA, sampleB), "1".repeat(64), actorId);
        repository.linkExperiment(organizationId, jobId, submission.submissionId(), "experiment-b", experiment2, version2,
                "R03-2", empty, empty, List.of(sampleC), "2".repeat(64), actorId);
        repository.linkExperiment(organizationId, jobId, submission.submissionId(), "experiment-a", experiment1, version1,
                "R03-1", empty, empty, List.of(sampleA, sampleB), "1".repeat(64), actorId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM data.import_experiment_link WHERE import_job_id=?",
                Integer.class, jobId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rnd.experiment_source_reference WHERE recognition_job_id=?",
                Integer.class, jobId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(source_group_keys_jsonb) FROM rnd.experiment_source_reference
                WHERE recognition_job_id=? AND sample_boundary_id='sample-a'
                """, Integer.class, jobId)).isEqualTo(2);
        assertThatThrownBy(() -> repository.linkExperiment(organizationId, jobId, submission.submissionId(), "experiment-a",
                experiment2, version2, "R03-2", empty, empty, List.of(sampleA), "3".repeat(64), actorId))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ApiErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE rnd.experiment_source_reference SET logical_sample_key='MUTATED'
                WHERE recognition_job_id=? AND sample_boundary_id='sample-a'
                """, jobId)).isInstanceOf(RuntimeException.class);
    }

    private void createExperiment(org.springframework.jdbc.core.JdbcTemplate jdbc, UUID organizationId,
                                  UUID actorId, UUID experimentId, UUID versionId, String number) {
        jdbc.update("""
                INSERT INTO rnd.experiment(id,organization_id,experiment_no,title,source_type,status,
                    created_by,updated_by) VALUES(?,?,?,?,'EXCEL_IMPORT','DRAFT',?,?)
                """, experimentId, organizationId, number, number, actorId, actorId);
        jdbc.update("""
                INSERT INTO rnd.experiment_version(id,organization_id,experiment_id,version_no,status,created_by)
                VALUES(?,?,?,1,'DRAFT',?)
                """, versionId, organizationId, experimentId, actorId);
        jdbc.update("UPDATE rnd.experiment SET current_version_id=? WHERE id=?", versionId, experimentId);
    }
}
