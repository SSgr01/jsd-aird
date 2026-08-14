package com.jsd.aird.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Optional local-Postgres migration test used when Docker/Testcontainers is unavailable. */
class FlywayV31LocalIT {

    @Test
    void upgradesHistoricalV30RevisionWithoutLosingPublishedContent() throws Exception {
        var url = System.getenv("JSD_AIRD_TEST_JDBC_URL");
        var username = System.getenv().getOrDefault("JSD_AIRD_TEST_DB_USER", "postgres");
        var password = System.getenv().getOrDefault("JSD_AIRD_TEST_DB_PASSWORD", "");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "local migration database is not configured");

        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("30")).load().migrate();
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO ops.file_object (
                        id, organization_id, bucket, object_key, original_name, content_type,
                        size_bytes, sha256, status, created_by
                    ) VALUES (
                        '10000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000001', 'test', 'v31/source',
                        '历史报告.txt', 'text/plain', 12,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'ACTIVE', '00000000-0000-0000-0000-000000000002'
                    );
                    INSERT INTO kb.document (
                        id, organization_id, title, status, scan_status, current_version_no,
                        created_by, library_scope, category_id, lifecycle_status
                    ) VALUES (
                        '10000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000001', '历史报告', 'READY', 'SAFE', 1,
                        '00000000-0000-0000-0000-000000000002', 'INTERNAL',
                        (SELECT id FROM kb.document_category WHERE scope='INTERNAL' ORDER BY created_at LIMIT 1),
                        'ACTIVE'
                    );
                    INSERT INTO kb.document_version (
                        id, document_id, version_no, file_object_id, original_name, content_type,
                        size_bytes, sha256, parser_version, status, review_status, review_revision
                    ) VALUES (
                        '10000000-0000-0000-0000-000000000003',
                        '10000000-0000-0000-0000-000000000002', 1,
                        '10000000-0000-0000-0000-000000000001', '历史报告.txt', 'text/plain', 12,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'text-v1', 'READY', 'PUBLISHED', 2
                    );
                    INSERT INTO kb.document_parse_run (
                        id, organization_id, document_id, document_version_id, run_no, status,
                        parser_version, result_jsonb, finished_at
                    ) VALUES
                    ('10000000-0000-0000-0000-000000000004',
                     '00000000-0000-0000-0000-000000000001',
                     '10000000-0000-0000-0000-000000000002',
                     '10000000-0000-0000-0000-000000000003', 1, 'PENDING_REVIEW', 'text-v1', '{}', now()),
                    ('10000000-0000-0000-0000-000000000005',
                     '00000000-0000-0000-0000-000000000001',
                     '10000000-0000-0000-0000-000000000002',
                     '10000000-0000-0000-0000-000000000003', 2, 'PUBLISHED', 'review-v1',
                     '{"revision":true,"sourceParseRunId":"10000000-0000-0000-0000-000000000004"}', now());
                    INSERT INTO kb.document_parse_block (
                        id, parse_run_id, block_no, section, raw_text, normalized_text,
                        confirmed_text, confidence, review_status
                    ) VALUES
                    ('10000000-0000-0000-0000-000000000006',
                     '10000000-0000-0000-0000-000000000004', 0, 'paragraph',
                     '原始识别', '原始识别', '原始识别', 0.9, 'CONFIRMED'),
                    ('10000000-0000-0000-0000-000000000007',
                     '10000000-0000-0000-0000-000000000005', 0, 'paragraph',
                     '原始识别', '原始识别', '人工确认内容', 0.9, 'CONFIRMED');
                    INSERT INTO kb.publication (
                        id, organization_id, document_id, document_version_id, parse_run_id,
                        publication_no, status, metadata_snapshot_jsonb, published_by
                    ) VALUES (
                        '10000000-0000-0000-0000-000000000008',
                        '00000000-0000-0000-0000-000000000001',
                        '10000000-0000-0000-0000-000000000002',
                        '10000000-0000-0000-0000-000000000003',
                        '10000000-0000-0000-0000-000000000005', 1, 'CURRENT', '{}',
                        '00000000-0000-0000-0000-000000000002'
                    );
                    UPDATE kb.document SET current_publication_id =
                        '10000000-0000-0000-0000-000000000008'
                    WHERE id = '10000000-0000-0000-0000-000000000002';
                    INSERT INTO kb.document_chunk (
                        id, document_id, document_version_id, chunk_no, content, parse_run_id
                    ) VALUES (
                        '10000000-0000-0000-0000-000000000009',
                        '10000000-0000-0000-0000-000000000002',
                        '10000000-0000-0000-0000-000000000003', 0, '人工确认内容',
                        '10000000-0000-0000-0000-000000000005'
                    );
                    """);
        }

        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(url, username, password)) {
            assertThat(queryInt(connection, "SELECT count(*) FROM kb.document_parse_run WHERE document_id='10000000-0000-0000-0000-000000000002'"))
                    .isEqualTo(1);
            assertThat(queryInt(connection, "SELECT count(*) FROM kb.document_review_revision WHERE document_id='10000000-0000-0000-0000-000000000002'"))
                    .isEqualTo(1);
            assertThat(queryText(connection, "SELECT status FROM kb.document_review_revision WHERE document_id='10000000-0000-0000-0000-000000000002'"))
                    .isEqualTo("PUBLISHED");
            assertThat(queryText(connection, "SELECT confirmed_text FROM kb.document_review_revision WHERE document_id='10000000-0000-0000-0000-000000000002'"))
                    .isEqualTo("人工确认内容");
            assertThat(queryText(connection, "SELECT parse_run_id::text FROM kb.publication WHERE id='10000000-0000-0000-0000-000000000008'"))
                    .isEqualTo("10000000-0000-0000-0000-000000000004");
            assertThat(queryText(connection, "SELECT review_revision_id::text FROM kb.document_chunk WHERE id='10000000-0000-0000-0000-000000000009'"))
                    .isNotBlank();
        }
    }

    private int queryInt(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next(); return result.getInt(1);
        }
    }

    private String queryText(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next(); return result.getString(1);
        }
    }
}
