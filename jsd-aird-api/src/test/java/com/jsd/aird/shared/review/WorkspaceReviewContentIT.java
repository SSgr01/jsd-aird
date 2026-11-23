package com.jsd.aird.shared.review;

import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL checks, isolated from the application's database. */
class WorkspaceReviewContentIT {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private final UUID org=UUID.randomUUID(), user=UUID.randomUUID(), id=UUID.randomUUID();
    private Connection connection;
    private JdbcTemplate jdbc;
    private WorkspaceReviewService reviews;

    @BeforeAll static void start() {
        var external=System.getProperty("jsd.review.jdbcUrl");
        if(external==null) POSTGRES.start();
        else assertThat(external).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/jsd_review_content_[a-z0-9_]+");
    }
    @AfterAll static void stop() { if(POSTGRES.isRunning()) POSTGRES.stop(); }

    @BeforeEach void setup() throws Exception {
        var external=System.getProperty("jsd.review.jdbcUrl");
        connection=external==null
                ? DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                : DriverManager.getConnection(external,System.getProperty("jsd.review.username"),System.getProperty("jsd.review.password",""));
        connection.setAutoCommit(false);
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("""
            CREATE SCHEMA ops; CREATE SCHEMA mdm; CREATE SCHEMA quality; CREATE SCHEMA mfg; CREATE SCHEMA kb; CREATE SCHEMA iam;
            CREATE TABLE iam.app_user(id uuid,display_name text,username text);
            CREATE TABLE mdm.project(id uuid,organization_id uuid);
            CREATE TABLE mdm.project_document(id uuid,project_id uuid,title text,format text,file_object_id uuid,
                content_snapshot jsonb,content_schema jsonb,content_mapping jsonb,content_data jsonb,
                deleted boolean DEFAULT false,status text DEFAULT 'DRAFT',updated_at timestamptz DEFAULT now());
            CREATE TABLE quality.data_record(id uuid,organization_id uuid,business_type text,business_no text,display_name text,
                data_jsonb jsonb,workbook_snapshot_jsonb jsonb,source_file_id uuid,project_id uuid,stage_id uuid,task_id uuid,
                deleted boolean DEFAULT false,lock_version bigint DEFAULT 0,updated_at timestamptz DEFAULT now());
            CREATE TABLE quality.data_record_version(organization_id uuid,record_id uuid,change_type text,lock_version bigint);
            CREATE TABLE mfg.production_order(id uuid,organization_id uuid,order_no text,template_version_id uuid,product_id uuid,
                quantity numeric,unit_code text,planned_date date,owner_id uuid,instance_schema_jsonb jsonb,
                instance_mapping_jsonb jsonb,draft_data_jsonb jsonb,draft_editor_snapshot_hash text,
                status text DEFAULT 'DRAFT',updated_at timestamptz DEFAULT now());
            CREATE TABLE mfg.production_upload(id uuid,organization_id uuid,file_id uuid,production_name text,order_no text,
                product_name text,category text,manufacture_date date,project_id uuid,stage_id uuid,task_id uuid,
                workbook_snapshot_jsonb jsonb,recognition_progress int,status text DEFAULT 'SAVED',updated_at timestamptz DEFAULT now());
            CREATE TABLE kb.document(id uuid,organization_id uuid,title text,library_scope text,category_id uuid);
            CREATE TABLE kb.document_version(id uuid,document_id uuid,review_status text DEFAULT 'PENDING_REVIEW',updated_at timestamptz DEFAULT now());
            CREATE TABLE kb.document_review_revision(id uuid,document_version_id uuid,revision_no int,parse_run_id uuid,
                confirmed_document_jsonb jsonb,excluded_review_node_ids jsonb,status text DEFAULT 'DRAFT',
                lock_version int DEFAULT 0,updated_at timestamptz DEFAULT now());
            CREATE TABLE kb.document_tag(document_id uuid,tag_id uuid);
            CREATE TABLE kb.tag(id uuid,display_name text);
            CREATE TABLE kb.document_review_table_cell_patch(review_revision_id uuid,source_table_id uuid,row_no int,column_no int,confirmed_value text);
            CREATE TABLE kb.document_review_table_row_state(review_revision_id uuid,source_table_id uuid,row_no int,excluded boolean,header boolean);
            CREATE TABLE kb.document_review_issue_state(review_revision_id uuid,parse_issue_id uuid,status text,resolution text);
            """);
        try(var in=new ClassPathResource("db/migration/V106__workspace_review.sql").getInputStream()) {
            jdbc.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
        migrate();
        jdbc.update("INSERT INTO iam.app_user VALUES (?,?,?)",user,"研发负责人","editor");
        ActorContext.set(new Actor(org,user,"ADMIN"));
        reviews=new WorkspaceReviewService(jdbc);
    }
    @AfterEach void cleanup() throws Exception {
        ActorContext.clear();
        if(connection!=null) { connection.rollback(); connection.close(); }
    }
    private void migrate() throws Exception {
        try(var in=new ClassPathResource("db/migration/V122__workspace_review_content_fingerprint.sql").getInputStream()) {
            jdbc.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private void seed(String type) {
        switch(type) {
            case "PROJECT_DOCUMENT" -> {
                jdbc.update("INSERT INTO mdm.project VALUES (?,?)",id,org);
                jdbc.update("INSERT INTO mdm.project_document(id,project_id,title,content_data) VALUES (?,?,'研发周报','{\"value\":750}')",id,id);
            }
            case "QUALITY_RECORD" -> jdbc.update("INSERT INTO quality.data_record(id,organization_id,business_type,data_jsonb) VALUES (?,?,'coa','{\"value\":750}')",id,org);
            case "PRODUCTION_ORDER" -> jdbc.update("INSERT INTO mfg.production_order(id,organization_id,draft_data_jsonb) VALUES (?,?,'{\"value\":750}')",id,org);
            case "PRODUCTION_UPLOAD" -> jdbc.update("INSERT INTO mfg.production_upload(id,organization_id,workbook_snapshot_jsonb) VALUES (?,?,'{\"value\":750}')",id,org);
            case "KNOWLEDGE_VERSION" -> {
                jdbc.update("INSERT INTO kb.document VALUES (?,?,'材料研发记录','INTERNAL',null)",id,org);
                jdbc.update("INSERT INTO kb.document_version(id,document_id) VALUES (?,?)",id,id);
                jdbc.update("INSERT INTO kb.document_review_revision(id,document_version_id,revision_no,confirmed_document_jsonb) VALUES (?,?,1,'{\"value\":750}')",id,id);
            }
            default -> throw new IllegalArgumentException(type);
        }
    }
    private String table(String type) {
        return switch(type) {
            case "PROJECT_DOCUMENT" -> "mdm.project_document";
            case "QUALITY_RECORD" -> "quality.data_record";
            case "PRODUCTION_ORDER" -> "mfg.production_order";
            case "PRODUCTION_UPLOAD" -> "mfg.production_upload";
            case "KNOWLEDGE_VERSION" -> "kb.document_review_revision";
            default -> throw new IllegalArgumentException(type);
        };
    }
    private void changeContent(String type) {
        // Deliberately leave updated_at unchanged: content, not time, must invalidate.
        jdbc.update("UPDATE "+table(type)+" SET "+contentColumn(type)+"='{\"value\":760}' WHERE id=?",id);
    }

    @ParameterizedTest @ValueSource(strings={"PROJECT_DOCUMENT","QUALITY_RECORD","PRODUCTION_ORDER","PRODUCTION_UPLOAD","KNOWLEDGE_VERSION"})
    void backgroundUpdatesKeepSubmissionAndApproval(String type) {
        seed(type);
        var submitted=reviews.submit(type,id);
        jdbc.update("UPDATE "+table(type)+" SET updated_at=updated_at+interval '1 hour' WHERE id=?",id);
        assertThat(reviews.get(type,id)).isEqualTo(submitted);
        var approved=reviews.approve(type,id);
        jdbc.update("UPDATE "+table(type)+" SET updated_at=updated_at+interval '1 hour' WHERE id=?",id);
        assertThat(reviews.get(type,id)).isEqualTo(approved);
        reviews.requireApproved(type,id);
        assertThat(reviews.history(type,id)).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings={"PROJECT_DOCUMENT","QUALITY_RECORD","PRODUCTION_ORDER","PRODUCTION_UPLOAD","KNOWLEDGE_VERSION"})
    void changedContentInvalidatesEvenWhenTimestampIsUnchanged(String type) {
        seed(type); reviews.submit(type,id); changeContent(type);
        assertThat(reviews.get(type,id).status()).isEqualTo("NOT_SUBMITTED");
        assertThatThrownBy(()->reviews.approve(type,id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reviews.requireApproved(type,id)).isInstanceOf(ApiException.class);
        assertThat(reviews.submit(type,id).status()).isEqualTo("SUBMITTED");
        reviews.approve(type,id);
        jdbc.update("UPDATE "+table(type)+" SET "+contentColumn(type)+"='{\"value\":770}' WHERE id=?",id);
        assertThat(reviews.get(type,id).status()).isEqualTo("NOT_SUBMITTED");
    }
    private String contentColumn(String type) {
        return switch(type) {
            case "PROJECT_DOCUMENT" -> "content_data";
            case "QUALITY_RECORD" -> "data_jsonb";
            case "PRODUCTION_ORDER" -> "draft_data_jsonb";
            case "PRODUCTION_UPLOAD" -> "workbook_snapshot_jsonb";
            default -> "confirmed_document_jsonb";
        };
    }

    @Test void processingAndPublicationStatesDoNotInvalidate() {
        seed("PRODUCTION_UPLOAD"); reviews.submit("PRODUCTION_UPLOAD",id); reviews.approve("PRODUCTION_UPLOAD",id);
        jdbc.update("UPDATE mfg.production_upload SET status='PUBLISHED',recognition_progress=100,updated_at=updated_at+interval '1 hour' WHERE id=?",id);
        assertThat(reviews.get("PRODUCTION_UPLOAD",id).status()).isEqualTo("APPROVED");
        seed("PRODUCTION_ORDER"); reviews.submit("PRODUCTION_ORDER",id); reviews.approve("PRODUCTION_ORDER",id);
        jdbc.update("UPDATE mfg.production_order SET status='SUBMITTED' WHERE id=?",id);
        assertThat(reviews.get("PRODUCTION_ORDER",id).status()).isEqualTo("APPROVED");
    }
    @Test void rejectedStateAndReasonSurviveBackgroundUpdates() {
        seed("QUALITY_RECORD"); reviews.submit("QUALITY_RECORD",id);
        var rejected=reviews.reject("QUALITY_RECORD",id,"请补充测定条件");
        jdbc.update("UPDATE quality.data_record SET updated_at=updated_at+interval '1 hour' WHERE id=?",id);
        assertThat(reviews.get("QUALITY_RECORD",id)).isEqualTo(rejected);
    }
    @ParameterizedTest @ValueSource(strings={"QUALITY_RECORD","PRODUCTION_UPLOAD"})
    void realListQueriesAgreeWithDetailAfterBackgroundAndContentUpdates(String type) throws Exception {
        seed(type); reviews.submit(type,id); reviews.approve(type,id);
        String expression,from;
        if(type.equals("QUALITY_RECORD")) {
            var field=Class.forName("com.jsd.aird.quality.infrastructure.QualityDataRepository").getDeclaredField("WORKFLOW_STATUS_SQL");
            field.setAccessible(true); expression=(String)field.get(null); from="quality.data_record r";
        } else {
            var repository=new com.jsd.aird.mfg.upload.infrastructure.JdbcProductionUploadRepository(jdbc,new com.fasterxml.jackson.databind.ObjectMapper());
            var method=repository.getClass().getDeclaredMethod("selectSql"); method.setAccessible(true);
            var sql=(String)method.invoke(repository);
            expression=sql.substring(sql.indexOf("CASE WHEN"),sql.indexOf("END workflow_status")+"END workflow_status".length());
            from="mfg.production_upload pu";
        }
        jdbc.update("UPDATE "+table(type)+" SET updated_at=updated_at+interval '1 hour' WHERE id=?",id);
        assertThat(jdbc.queryForObject("SELECT "+expression+" FROM "+from,String.class)).isEqualTo("APPROVED");
        changeContent(type);
        assertThat(jdbc.queryForObject("SELECT "+expression+" FROM "+from,String.class)).isEqualTo(reviews.get(type,id).status()).isEqualTo("NOT_SUBMITTED");
    }
    @Test void knowledgeTablesAndNewRevisionArePartOfReviewContent() {
        seed("KNOWLEDGE_VERSION"); reviews.submit("KNOWLEDGE_VERSION",id);
        assertThatThrownBy(()->reviews.requireEditable("KNOWLEDGE_VERSION",id)).isInstanceOf(ApiException.class);
        jdbc.update("INSERT INTO kb.document_review_table_cell_patch VALUES (?,?,0,0,'750')",id,id);
        assertThat(reviews.get("KNOWLEDGE_VERSION",id).status()).isEqualTo("NOT_SUBMITTED");
        reviews.submit("KNOWLEDGE_VERSION",id);
        jdbc.update("INSERT INTO kb.document_review_table_row_state VALUES (?,?,0,true,false)",id,id);
        assertThat(reviews.get("KNOWLEDGE_VERSION",id).status()).isEqualTo("NOT_SUBMITTED");
        reviews.submit("KNOWLEDGE_VERSION",id);
        jdbc.update("INSERT INTO kb.document_review_revision(id,document_version_id,revision_no,confirmed_document_jsonb) SELECT ?,document_version_id,2,confirmed_document_jsonb FROM kb.document_review_revision WHERE id=?",UUID.randomUUID(),id);
        assertThat(reviews.get("KNOWLEDGE_VERSION",id).status()).isEqualTo("NOT_SUBMITTED");
    }
    @Test void explicitRevisionInvalidationDoesNotReuseApproval() {
        seed("QUALITY_RECORD"); reviews.submit("QUALITY_RECORD",id); reviews.approve("QUALITY_RECORD",id);
        jdbc.update("UPDATE ops.workspace_review SET resource_content_hash=null WHERE resource_id=?",id);
        assertThat(reviews.get("QUALITY_RECORD",id).status()).isEqualTo("NOT_SUBMITTED");
        assertThat(reviews.submit("QUALITY_RECORD",id).status()).isEqualTo("SUBMITTED");
    }
    @Test void migrationPreservesValidLegacyApprovalWithoutResurrectingStaleApproval() throws Exception {
        seed("QUALITY_RECORD");
        jdbc.execute("ALTER TABLE ops.workspace_review DROP COLUMN resource_content_hash; DROP FUNCTION ops.workspace_review_content_hash(text,uuid,uuid)");
        jdbc.update("INSERT INTO ops.workspace_review(organization_id,resource_type,resource_id,status,resource_updated_at) SELECT organization_id,'QUALITY_RECORD',id,'APPROVED',updated_at FROM quality.data_record");
        migrate(); assertThat(reviews.get("QUALITY_RECORD",id).status()).isEqualTo("APPROVED");
        jdbc.execute("ALTER TABLE ops.workspace_review DROP COLUMN resource_content_hash; DROP FUNCTION ops.workspace_review_content_hash(text,uuid,uuid)");
        jdbc.update("UPDATE quality.data_record SET updated_at=updated_at+interval '1 hour'");
        migrate(); assertThat(reviews.get("QUALITY_RECORD",id).status()).isEqualTo("NOT_SUBMITTED");
    }
}
