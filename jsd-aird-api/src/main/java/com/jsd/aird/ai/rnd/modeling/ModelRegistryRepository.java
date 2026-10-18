package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.api.PageResponse;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.modeling.ModelRegistryContracts.*;

@Repository
public class ModelRegistryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ModelRegistryRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public JdbcTemplate jdbc() { return jdbc; }

    public Optional<ModelSummary> model(UUID org,UUID id){return jdbc.query(modelSelect()+" WHERE m.contract_version='modeling-workbench.v1' AND m.workbench_run_id IS NOT NULL AND m.organization_id=? AND m.id=?",this::modelSummary,org,id).stream().findFirst();}

    public PageResponse<ModelSummary> models(UUID org,String status,String category,String keyword,int page,int size){var where=new StringBuilder(" WHERE m.contract_version='modeling-workbench.v1' AND m.workbench_run_id IS NOT NULL AND m.organization_id=?");var args=new ArrayList<Object>();args.add(org);if(status!=null&&!status.isBlank()){where.append(" AND m.status=?");args.add(status);}if(category!=null&&!category.isBlank()){where.append(" AND t.performance_project=?");args.add(category);}if(keyword!=null&&!keyword.isBlank()){where.append(" AND (t.name ILIKE ? OR t.target_code ILIKE ?)");args.add("%"+keyword+"%");args.add("%"+keyword+"%");}Long total=jdbc.queryForObject("SELECT count(*) FROM ai.model_version m JOIN ai.prediction_target t ON t.organization_id=m.organization_id AND t.id=m.target_id"+where,Long.class,args.toArray());var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);var items=jdbc.query(modelSelect()+where+" ORDER BY t.performance_project,t.name,m.version_no DESC,m.id LIMIT ? OFFSET ?",this::modelSummary,pageArgs.toArray());long count=total==null?0:total;return new PageResponse<>(items,page,size,count,(count+size-1)/size);}

    public JsonNode node(ResultSet rs,String column){try{var raw=rs.getString(column);return raw==null?json.nullNode():json.readTree(raw);}catch(Exception e){throw new IllegalStateException("无法解析"+column,e);}}

    public PGobject pg(JsonNode n){try{var p=new PGobject();p.setType("jsonb");p.setValue((n==null?json.nullNode():n).toString());return p;}catch(Exception e){throw new IllegalArgumentException(e);}}

    public ObjectMapper json(){return json;}

    private ModelSummary modelSummary(ResultSet rs,int n)throws java.sql.SQLException{return new ModelSummary(rs.getObject("id",UUID.class),rs.getObject("target_id",UUID.class),rs.getString("target_name"),rs.getString("category"),rs.getString("value_type"),rs.getInt("version_no"),rs.getString("status"),rs.getString("model_type"),rs.getString("data_nature"),rs.getBoolean("production_eligible"),rs.getString("comparison_status"),node(rs,"metrics_jsonb"),rs.getInt("sample_count"),rs.getObject("workbench_run_id",UUID.class),instant(rs,"created_at"),instant(rs,"updated_at"),rs.getLong("revision"),node(rs,"model_source"));}

    private Instant instant(ResultSet rs,String column)throws java.sql.SQLException{var t=rs.getTimestamp(column);return t==null?null:t.toInstant();}
    public ModelDetail modelDetail(UUID org,UUID id){
        var model=model(org,id).orElseThrow(()->new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND,"模型版本不存在"));
        var payload=jdbc.query("SELECT m.applicability_domain_jsonb,m.model_card_jsonb,w.request_jsonb FROM ai.model_version m JOIN ai.modeling_workbench_run w ON w.organization_id=m.organization_id AND w.id=m.workbench_run_id WHERE m.organization_id=? AND m.id=?",
            (rs,n)->new JsonNode[]{node(rs,"applicability_domain_jsonb"),node(rs,"model_card_jsonb"),node(rs,"request_jsonb")},org,id).getFirst();
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload[1]).set("trainingRows",payload[2].path("rows"));
        var releases=jdbc.query("SELECT * FROM ai.model_release WHERE organization_id=? AND target_id=? ORDER BY created_at DESC",
            (rs,n)->new ReleaseView(rs.getObject("id",UUID.class),rs.getString("action"),rs.getObject("model_version_id",UUID.class),rs.getObject("previous_model_version_id",UUID.class),rs.getString("reason"),instant(rs,"created_at")),org,model.targetId());
        return new ModelDetail(model,payload[0],payload[1],releases);
    }
    private String modelSelect(){return "SELECT m.*,t.name target_name,t.performance_project category,tv.value_type,jsonb_build_object('origin',m.model_card_jsonb->'origin','datasetName',m.model_card_jsonb->'datasetName','xColumns',m.model_card_jsonb->'xColumns','yColumn',m.model_card_jsonb->'yColumn') model_source,(m.model_card_jsonb->>'rowCount')::integer sample_count FROM ai.model_version m JOIN ai.prediction_target t ON t.organization_id=m.organization_id AND t.id=m.target_id JOIN ai.target_version tv ON tv.organization_id=m.organization_id AND tv.id=m.target_version_id";}
}
