package com.jsd.aird.data.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.ExperimentAssemblyRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class JdbcExperimentAssemblyRepository implements ExperimentAssemblyRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcExperimentAssemblyRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public SourceImport load(UUID organizationId, UUID importJobId) {
        var jobs = jdbc.query("""
                SELECT id, source_file_id, source_file_name, source_sha256, template_version_id,
                       coalesce(import_contract_version, 0) import_contract_version, contract_hash,
                       status, import_purpose, target_experiment_category_id, created_by, created_at
                  FROM data.import_job WHERE organization_id=? AND id=?
                """, (rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getObject("source_file_id", UUID.class),
                rs.getString("source_file_name"), rs.getString("source_sha256"),
                rs.getObject("template_version_id", UUID.class), rs.getInt("import_contract_version"),
                rs.getString("contract_hash"), rs.getString("status"), rs.getString("import_purpose"),
                rs.getObject("target_experiment_category_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant()}, organizationId, importJobId);
        if (jobs.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND, "数据导入任务不存在");
        var anchors = jdbc.query("""
                SELECT record_id, field_code, binding_id, value_path, label_path, sheet_id, sheet_name,
                       row_number, column_number, column_name, cell_address, raw_value_jsonb
                  FROM data.source_anchor WHERE import_job_id=? ORDER BY row_number, column_number
                """, (rs, n) -> new AnchorRow(rs.getObject("record_id", UUID.class), anchor(rs)), importJobId).stream()
                .collect(java.util.stream.Collectors.groupingBy(AnchorRow::recordId,
                        LinkedHashMap::new, java.util.stream.Collectors.mapping(AnchorRow::anchor,
                                java.util.stream.Collectors.toList())));
        var records = jdbc.query("""
                SELECT id, record_key, sheet_id, sheet_name, source_row_number, effective_data_jsonb
                  FROM data.data_record WHERE organization_id=? AND import_job_id=? ORDER BY record_index
                """, (rs, n) -> new SourceRecord(rs.getObject("id", UUID.class), rs.getString("record_key"),
                rs.getString("sheet_id"), rs.getString("sheet_name"), (Integer) rs.getObject("source_row_number"),
                parse(rs.getString("effective_data_jsonb")),
                List.copyOf(anchors.getOrDefault(rs.getObject("id", UUID.class), List.of()))),
                organizationId, importJobId);
        var j = jobs.getFirst();
        return new SourceImport((UUID) j[0], (UUID) j[1], (String) j[2], (String) j[3], (UUID) j[4],
                (Integer) j[5], (String) j[6], (String) j[7], (String) j[8], (UUID) j[9], (UUID) j[10],
                (java.time.Instant) j[11], List.copyOf(records));
    }

    @Override public List<Link> links(UUID org, UUID job) {
        return jdbc.query("""
                SELECT assembly_key,parent_assembly_key,status,resolution_action,resolution_reason,
                       experiment_id,experiment_version_id,experiment_no,plan_hash,content_hash,error_message
                  FROM data.import_experiment_link WHERE organization_id=? AND import_job_id=? ORDER BY created_at
                """, this::link, org, job);
    }
    @Override public Optional<Link> find(UUID org, UUID job, String key) {
        return links(org, job).stream().filter(x -> x.assemblyKey().equals(key)).findFirst();
    }
    @Override public void upsertPlan(UUID org, UUID job, PlanRow p, UUID actor) {
        jdbc.update("""
            INSERT INTO data.import_experiment_link(id,organization_id,import_job_id,assembly_key,parent_assembly_key,
              source_identity,source_identity_type,source_record_keys_jsonb,shared_context_record_keys_jsonb,
              plan_hash,content_hash,status,conflicts_jsonb,warnings_jsonb,created_by)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (organization_id,import_job_id,assembly_key) DO UPDATE SET
              parent_assembly_key=excluded.parent_assembly_key,source_identity=excluded.source_identity,
              source_identity_type=excluded.source_identity_type,source_record_keys_jsonb=excluded.source_record_keys_jsonb,
              shared_context_record_keys_jsonb=excluded.shared_context_record_keys_jsonb,plan_hash=excluded.plan_hash,
              content_hash=excluded.content_hash,conflicts_jsonb=excluded.conflicts_jsonb,warnings_jsonb=excluded.warnings_jsonb,
              status=CASE WHEN data.import_experiment_link.status IN ('SYNCED','ALREADY_CREATED','RUNNING','SKIPPED')
                          THEN data.import_experiment_link.status ELSE excluded.status END,updated_at=now()
            """, UUID.randomUUID(),org,job,p.assemblyKey(),p.parentAssemblyKey(),p.sourceIdentity(),p.sourceIdentityType(),
                pg(p.sourceRecordKeys()),pg(p.sharedContextRecordKeys()),p.planHash(),p.contentHash(),p.status(),
                pg(p.conflicts()),pg(p.warnings()),actor);
    }
    @Override public void supersedeOtherPlans(UUID org, UUID job, String activeKey) {
        jdbc.update("""
             UPDATE data.import_experiment_link
                SET status='SKIPPED',
                    resolution_reason=coalesce(resolution_reason,'实验聚合边界已修正为整份文件'),
                    updated_at=now()
              WHERE organization_id=? AND import_job_id=? AND assembly_key<>?
                AND experiment_id IS NULL AND status NOT IN ('SYNCED','ALREADY_CREATED')
            """,org,job,activeKey);
    }
    @Override public void lockAssembly(UUID org, UUID job, String assemblyKey) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,
                org + ":" + job + ":" + assemblyKey);
    }
    @Override public boolean markRunning(UUID org,UUID job,String key){return jdbc.update("""
            UPDATE data.import_experiment_link SET status='RUNNING',error_message=NULL,updated_at=now()
             WHERE organization_id=? AND import_job_id=? AND assembly_key=? AND status='READY'
            """,org,job,key)==1;}
    @Override public void markSynced(UUID org,UUID job,String key,UUID exp,UUID ver,String no){jdbc.update("""
            UPDATE data.import_experiment_link SET status='SYNCED',experiment_id=?,experiment_version_id=?,
                   experiment_no=?,error_message=NULL,updated_at=now() WHERE organization_id=? AND import_job_id=? AND assembly_key=?
            """,exp,ver,no,org,job,key);}
    @Override public void markFailed(UUID org,UUID job,String key,String error){jdbc.update("""
            UPDATE data.import_experiment_link SET status='FAILED',error_message=?,updated_at=now()
             WHERE organization_id=? AND import_job_id=? AND assembly_key=? AND status<>'SYNCED'
            """,error,org,job,key);}

    private Link link(ResultSet rs,int n)throws SQLException{return new Link(rs.getString(1),rs.getString(2),rs.getString(3),
            rs.getString(4),rs.getString(5),rs.getObject(6,UUID.class),rs.getObject(7,UUID.class),rs.getString(8),
            rs.getString(9),rs.getString(10),rs.getString(11));}
    private SourceAnchor anchor(ResultSet rs)throws SQLException{return new SourceAnchor(rs.getString("field_code"),
            rs.getString("binding_id"),rs.getString("value_path"),rs.getString("label_path"),rs.getString("sheet_id"),
            rs.getString("sheet_name"),(Integer)rs.getObject("row_number"),(Integer)rs.getObject("column_number"),
            rs.getString("column_name"),rs.getString("cell_address"),parse(rs.getString("raw_value_jsonb")));}
    private JsonNode parse(String value){try{return value==null?json.createObjectNode():json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private PGobject pg(JsonNode value){try{var p=new PGobject();p.setType("jsonb");p.setValue((value==null?json.createObjectNode():value).toString());return p;}catch(SQLException e){throw new IllegalStateException(e);}}
    private record AnchorRow(UUID recordId,SourceAnchor anchor){}
}
