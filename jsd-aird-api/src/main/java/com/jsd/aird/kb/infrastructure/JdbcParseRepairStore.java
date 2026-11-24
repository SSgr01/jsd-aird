package com.jsd.aird.kb.infrastructure;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.jsd.aird.kb.application.port.ParseRepairStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcParseRepairStore implements ParseRepairStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcParseRepairStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public Repair reserve(UUID org, UUID actor, UUID doc, UUID version, String key) {
        jdbc.queryForList("SELECT id FROM kb.document_version WHERE organization_id=? AND document_id=? AND id=? FOR UPDATE", org, doc, version);
        var existing = jdbc.query("""
                SELECT * FROM kb.document_parse_repair WHERE organization_id=? AND document_version_id=?
                AND (request_key=? OR status IN ('QUEUED','RUNNING')) ORDER BY created_at DESC LIMIT 1
                """, this::read, org, version, key);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kb.document_parse_repair
                 (id,organization_id,document_id,document_version_id,requested_by,request_key,
                  base_publication_id,base_review_revision_id,base_review_lock_version)
                SELECT ?,?,?,?,?,?,d.current_publication_id,r.id,r.lock_version FROM kb.document d
                LEFT JOIN LATERAL (SELECT id,lock_version FROM kb.document_review_revision
                   WHERE document_version_id=? ORDER BY revision_no DESC LIMIT 1) r ON true
                WHERE d.organization_id=? AND d.id=?
                """, id, org, doc, version, actor, key, version, org, doc);
        return find(org, id).orElseThrow();
    }
    public Optional<Repair> find(UUID org, UUID id) {
        return jdbc.query("SELECT * FROM kb.document_parse_repair WHERE organization_id=? AND id=?", this::read, org, id).stream().findFirst();
    }
    public void queued(UUID org, UUID id, UUID job) {
        jdbc.update("UPDATE kb.document_parse_repair SET status='QUEUED',job_id=?,error_message=NULL,updated_at=now() WHERE organization_id=? AND id=?", job, org, id);
    }
    public void running(UUID org, UUID id) {
        jdbc.update("UPDATE kb.document_parse_repair SET status='RUNNING',updated_at=now() WHERE organization_id=? AND id=? AND status IN ('QUEUED','RUNNING','FAILED')", org, id);
    }
    public void ready(UUID org, UUID id, UUID run, JsonNode result) {
        jdbc.update("UPDATE kb.document_parse_repair SET status='READY',parse_run_id=?,result_jsonb=?::jsonb,error_message=NULL,updated_at=now() WHERE organization_id=? AND id=? AND status='RUNNING'", run, result.toString(), org, id);
    }
    public void failed(UUID org, UUID id, String error) {
        jdbc.update("UPDATE kb.document_parse_repair SET status='FAILED',error_message=?,updated_at=now() WHERE organization_id=? AND id=? AND status IN ('QUEUED','RUNNING')", error, org, id);
    }
    public void adopted(UUID org, UUID id) {
        jdbc.update("UPDATE kb.document_parse_repair SET status='ADOPTED',updated_at=now() WHERE organization_id=? AND id=? AND status='READY'", org, id);
    }
    public boolean lockUnchanged(Repair r) {
        jdbc.queryForList("SELECT id FROM kb.document_parse_repair WHERE organization_id=? AND id=? FOR UPDATE", r.organizationId(), r.id());
        var docs = jdbc.queryForList("SELECT current_version_id,current_publication_id FROM kb.document WHERE organization_id=? AND id=? FOR UPDATE", r.organizationId(), r.documentId());
        if (docs.isEmpty() || !r.versionId().equals(docs.getFirst().get("current_version_id"))
                || !Objects.equals(r.basePublicationId(), docs.getFirst().get("current_publication_id"))) return false;
        var revisions = jdbc.queryForList("SELECT id,lock_version FROM kb.document_review_revision WHERE document_version_id=? ORDER BY revision_no DESC LIMIT 1 FOR UPDATE", r.versionId());
        if (revisions.isEmpty()) return r.baseRevisionId() == null;
        var latest = revisions.getFirst();
        return Objects.equals(r.baseRevisionId(), latest.get("id")) && Objects.equals(r.baseLockVersion(), latest.get("lock_version"));
    }
    public Optional<JsonNode> previousResult(UUID org, UUID version) {
        return jdbc.query("""
                SELECT result_jsonb FROM kb.document_parse_run WHERE organization_id=? AND document_version_id=?
                AND status='SUCCEEDED' AND NOT candidate_only ORDER BY run_no DESC LIMIT 1
                """, (rs,n) -> parse(rs.getString(1)), org, version).stream().findFirst();
    }
    private Repair read(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Repair(rs.getObject("id",UUID.class), rs.getObject("organization_id",UUID.class),
                rs.getObject("document_id",UUID.class),rs.getObject("document_version_id",UUID.class),rs.getObject("requested_by",UUID.class),
                rs.getString("request_key"),rs.getString("status"),rs.getObject("job_id",UUID.class),rs.getObject("parse_run_id",UUID.class),
                parse(rs.getString("result_jsonb")),rs.getString("error_message"),rs.getObject("base_publication_id",UUID.class),
                rs.getObject("base_review_revision_id",UUID.class),(Integer)rs.getObject("base_review_lock_version"));
    }
    private JsonNode parse(String value) {
        try { return value == null ? json.createObjectNode() : json.readTree(value); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
