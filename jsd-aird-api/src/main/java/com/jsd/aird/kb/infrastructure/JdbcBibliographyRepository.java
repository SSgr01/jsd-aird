package com.jsd.aird.kb.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.BibliographyRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBibliographyRepository implements BibliographyRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcBibliographyRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override public Optional<Record> find(UUID org, UUID version, Integer revision) {
        if (revision != null) return jdbc.query("""
            SELECT snapshot_jsonb FROM kb.document_bibliography_revision
            WHERE organization_id=? AND version_id=? AND revision=?
            """, (rs,n) -> decode(rs.getString(1), Record.class), org, version, revision).stream().findFirst();
        return jdbc.query("SELECT * FROM kb.document_bibliography WHERE organization_id=? AND version_id=?",
            (rs,n) -> new Record(rs.getObject("document_id",UUID.class), version, rs.getInt("revision"),
                rs.getString("status"), rs.getString("document_type"), read(rs.getString("accepted_jsonb")),
                read(rs.getString("provenance_jsonb")), read(rs.getString("candidates_jsonb")),
                read(rs.getString("manual_fields_jsonb")), rs.getObject("parse_run_id",UUID.class),
                rs.getString("source_fingerprint"), rs.getString("message")), org, version).stream().findFirst();
    }
    @Override @Transactional public boolean save(UUID org, UUID actor, Record v, int expected) {
        jdbc.update("""
            INSERT INTO kb.document_bibliography(organization_id,document_id,version_id)
            VALUES (?,?,?) ON CONFLICT DO NOTHING
            """, org, v.documentId(), v.versionId());
        int changed = jdbc.update("""
            UPDATE kb.document_bibliography SET revision=?,status=?,document_type=?,accepted_jsonb=?::jsonb,
              provenance_jsonb=?::jsonb,candidates_jsonb=?::jsonb,manual_fields_jsonb=?::jsonb,
              parse_run_id=?,source_fingerprint=?,message=?,updated_at=now()
            WHERE organization_id=? AND version_id=? AND revision=?
            """, v.revision(), v.status(), v.documentType(), encode(v.accepted()), encode(v.provenance()),
            encode(v.candidates()), encode(v.manualFields()), v.parseRunId(), v.sourceFingerprint(),
            v.message(), org, v.versionId(), expected);
        if (changed == 0) return false;
        jdbc.update("""
            INSERT INTO kb.document_bibliography_revision(organization_id,version_id,revision,snapshot_jsonb,actor_id)
            VALUES (?,?,?,?::jsonb,?)
            """, org, v.versionId(), v.revision(), encode(v), actor);
        return true;
    }
    @Override public List<Target> targets(UUID org, UUID category, List<UUID> docs, UUID after, Instant before, int limit) {
        var args = new ArrayList<Object>(List.of(org, Timestamp.from(before)));
        String filter = "";
        if (category != null) { filter += " AND d.category_id=?"; args.add(category); }
        if (after != null) { filter += " AND d.id>?"; args.add(after); }
        if (docs != null && !docs.isEmpty()) {
            filter += " AND d.id IN (" + String.join(",", Collections.nCopies(docs.size(),"?")) + ")";
            args.addAll(docs);
        }
        args.add(limit);
        return jdbc.query("""
            SELECT d.id,v.id FROM kb.document d
            JOIN kb.document_version v ON v.document_id=d.id AND v.version_no=d.current_version_no
            JOIN ops.file_object f ON f.id=v.file_object_id AND f.status<>'DELETED'
            WHERE d.organization_id=? AND d.created_at<=? AND d.lifecycle_status='ACTIVE'
            """ + filter + " ORDER BY d.id LIMIT ?", (rs,n) -> new Target(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),args.toArray());
    }
    @Override public Source source(UUID org, UUID doc, UUID version) {
        var runs = jdbc.query("""
            SELECT r.id,v.extracted_text_sha256 FROM kb.document_parse_run r
            JOIN kb.document_version v ON v.id=r.document_version_id
            JOIN kb.document d ON d.id=v.document_id
            WHERE d.organization_id=? AND d.id=? AND v.id=? AND r.status='SUCCEEDED'
            ORDER BY r.run_no DESC LIMIT 1
            """, (rs,n) -> new Source(rs.getObject(1,UUID.class),rs.getString(2),List.of()),org,doc,version);
        if (runs.isEmpty()) return new Source(null,null,List.of());
        var run = runs.getFirst();
        var pages = jdbc.query("""
            SELECT raw_text,source_anchor_jsonb,source_node_key,node_type FROM kb.document_source_node
            WHERE parse_run_id=? ORDER BY node_no LIMIT 1000
            """, (rs,n) -> new Page(rs.getString(1),read(rs.getString(2)),rs.getString(3),rs.getString(4)),run.parseRunId());
        return new Source(run.parseRunId(),run.fingerprint(),pages);
    }
    @Override public void lockVersion(UUID org,UUID doc,UUID version) {
        var rows=jdbc.query("""
            SELECT v.id FROM kb.document_version v JOIN kb.document d ON d.id=v.document_id
            WHERE d.organization_id=? AND d.id=? AND v.id=? FOR UPDATE OF d,v
            """,(rs,n)->rs.getObject(1,UUID.class),org,doc,version);
        if(rows.isEmpty())throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND);
    }
    @Override public JsonNode vectorStatus(UUID org, UUID doc, UUID version) {
        var result = json.createObjectNode();
        var counts = jdbc.queryForMap("""
            SELECT count(c.id) AS total,count(c.embedding) AS embedded FROM kb.document d
            LEFT JOIN kb.publication p ON p.id=d.current_publication_id AND p.document_version_id=?
            LEFT JOIN kb.document_chunk c ON c.document_version_id=p.document_version_id
              AND c.review_revision_id=p.review_revision_id AND c.chunk_role='CHILD'
            WHERE d.organization_id=? AND d.id=?
            """,version,org,doc);
        long total=((Number)counts.get("total")).longValue(), embedded=((Number)counts.get("embedded")).longValue();
        result.put("total",total).put("embedded",embedded);
        result.put("status", total == 0 ? "NOT_INDEXED" : embedded == total ? "PRESENT" : embedded == 0 ? "MISSING" : "PARTIAL");
        result.put("modelCompatibilityChecked",false);
        return result;
    }
    @Override public Task createTask(UUID org, UUID owner, String key, String fingerprint) {
        return jdbc.query("""
            INSERT INTO kb.bibliography_task(id,organization_id,owner_id,request_key,scope_fingerprint) VALUES (?,?,?,?,?)
            ON CONFLICT(organization_id,owner_id,request_key) DO UPDATE SET request_key=excluded.request_key
            WHERE kb.bibliography_task.scope_fingerprint=excluded.scope_fingerprint
            RETURNING *
            """,(rs,n) -> taskRow(rs), UUID.randomUUID(),org,owner,key,fingerprint).stream().findFirst()
            .orElseThrow(()->new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.RESOURCE_CONFLICT,"此请求标识已用于不同的资料范围或文件版本"));
    }
    @Override public List<Task> tasks(UUID org,UUID owner,int limit) {
        return jdbc.query("SELECT * FROM kb.bibliography_task WHERE organization_id=? AND owner_id=? ORDER BY created_at DESC LIMIT ?",
            (rs,n)->taskRow(rs),org,owner,Math.clamp(limit,1,50));
    }
    @Override public Optional<Task> task(UUID org, UUID owner, UUID id) {
        return jdbc.query("SELECT * FROM kb.bibliography_task WHERE id=? AND organization_id=? AND owner_id=?",
            (rs,n) -> taskRow(rs),id,org,owner).stream().findFirst();
    }
    private Task taskRow(ResultSet rs) throws SQLException {
        return new Task(rs.getObject("id",UUID.class),rs.getObject("organization_id",UUID.class),rs.getObject("owner_id",UUID.class),rs.getTimestamp("created_at").toInstant());
    }
    @Override public Item addItem(UUID task, Target target) {
        return jdbc.queryForObject("""
            INSERT INTO kb.bibliography_task_item(id,task_id,document_id,version_id) VALUES (?,?,?,?)
            ON CONFLICT(task_id,version_id) DO UPDATE SET document_id=excluded.document_id RETURNING *
            """,(rs,n) -> itemRow(rs),UUID.randomUUID(),task,target.documentId(),target.versionId());
    }
    @Override public Optional<Item> item(UUID task, UUID item) {
        return jdbc.query("SELECT * FROM kb.bibliography_task_item WHERE id=? AND task_id=?",
            (rs,n) -> itemRow(rs),item,task).stream().findFirst();
    }
    @Override public List<Item> items(UUID task, UUID after, int limit) {
        return jdbc.query("SELECT * FROM kb.bibliography_task_item WHERE task_id=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
            (rs,n) -> itemRow(rs),task,after,after,limit);
    }
    private Item itemRow(ResultSet rs) throws SQLException {
        var at=rs.getTimestamp("retry_at");
        return new Item(rs.getObject("id",UUID.class),rs.getObject("task_id",UUID.class),rs.getObject("document_id",UUID.class),
            rs.getObject("version_id",UUID.class),rs.getString("status"),rs.getInt("generation"),rs.getInt("failure_count"),
            at == null ? null : at.toInstant(),rs.getString("error_code"));
    }
    @Override public boolean updateItem(UUID item,int generation,String status,String code,Instant retryAt,boolean failure) {
        return jdbc.update("""
            UPDATE kb.bibliography_task_item SET status=?,error_code=?,retry_at=?,
              failure_count=failure_count+?,updated_at=now() WHERE id=? AND generation=?
            """,status,code,retryAt == null ? null : Timestamp.from(retryAt),failure?1:0,item,generation)>0;
    }
    @Override public Item retryItem(UUID item,int generation) {
        return jdbc.queryForObject("""
            UPDATE kb.bibliography_task_item SET generation=generation+1,
              status=CASE WHEN status='WAITING_PROVIDER' THEN status ELSE 'PENDING' END,
              retry_at=CASE WHEN status='WAITING_PROVIDER' THEN retry_at ELSE NULL END,
              error_code=CASE WHEN status='WAITING_PROVIDER' THEN error_code ELSE NULL END,
              updated_at=now() WHERE id=? AND generation=? RETURNING *
            """,(rs,n) -> itemRow(rs),item,generation);
    }
    private JsonNode read(String value) { try { return value == null ? json.createObjectNode() : json.readTree(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    private <T> T decode(String value,Class<T> type) { try { return json.readValue(value,type); } catch(Exception e) { throw new IllegalStateException(e); } }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
}
