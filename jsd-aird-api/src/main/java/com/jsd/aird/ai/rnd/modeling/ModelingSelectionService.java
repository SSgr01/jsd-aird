package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.shared.error.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Lightweight selection queries never fetch workbook JSON. Batches are daily creation cohorts. */
@Service
public class ModelingSelectionService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final ObjectMapper json;
    public ModelingSelectionService(JdbcTemplate jdbc, AuthorizationService authorization, ObjectMapper json) {
        this.jdbc=jdbc;this.authorization=authorization;this.json=json;
    }
    private static final String BATCH="to_char(e.created_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD') || ':' || e.source_type";
    private static final String FROM=" FROM rnd.experiment e JOIN rnd.experiment_version v ON v.organization_id=e.organization_id AND v.id=e.current_version_id JOIN tpl.template_version tv ON tv.id=v.template_version_id JOIN tpl.template t ON t.organization_id=e.organization_id AND t.id=tv.template_id";
    private Query query(UUID dataset, String batch, String keyword) {
        var actor=ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(),actor.userId(),"ai.modeling.read","AI_MODELING",dataset,"READ"));
        var scope=authorization.resolveScope(new PermissionCheck(actor.organizationId(),actor.userId(),"experiment.view","EXPERIMENT",null,"READ"));
        if(!scope.allowed())throw new ApiException(ApiErrorCode.PERMISSION_DENIED);
        var sql=new StringBuilder(" WHERE e.organization_id=? AND e.deleted=false AND e.status='COMPLETED' AND v.status='COMPLETED' AND t.template_code IN ('EXP_SYNTHESIS_UNIFIED','EXP_TEST_UNIFIED') AND v.template_version_id=?");
        var args=new ArrayList<Object>(List.of(actor.organizationId(),dataset));
        switch(scope.scopeType()) {
            case "ALL","ORG" -> {}
            case "SELF","CREATOR" -> {sql.append(" AND e.created_by=?");args.add(actor.userId());}
            case "DEPARTMENT" -> {sql.append(" AND e.department_id IS NOT NULL AND e.department_id=(SELECT department_id FROM iam.app_user WHERE organization_id=? AND id=?)");args.add(actor.organizationId());args.add(actor.userId());}
            case "SELECTED" -> {
                sql.append(" AND (e.created_by=?");args.add(actor.userId());
                if(!scope.targetIds().isEmpty()){sql.append(" OR e.project_id IN ("+String.join(",",Collections.nCopies(scope.targetIds().size(),"?"))+")");args.addAll(scope.targetIds());}
                sql.append(")");
            }
            default -> sql.append(" AND false");
        }
        if(batch!=null&&!batch.isBlank()){sql.append(" AND ("+BATCH+")=?");args.add(batch);}
        if(keyword!=null&&!keyword.isBlank()){
            sql.append(" AND (e.experiment_no ILIKE ? OR e.title ILIKE ? OR e.owner_name ILIKE ?)");
            var like="%"+keyword.strip().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            args.add(like);args.add(like);args.add(like);
        }
        return new Query(sql.toString(),args);
    }
    public JsonNode batches(UUID dataset) {
        var q=query(dataset,null,null);var out=json.createObjectNode();var items=out.putArray("items");
        jdbc.query("SELECT "+BATCH+" batch_id,count(*) total"+FROM+q.sql+" GROUP BY "+BATCH+" ORDER BY batch_id DESC",(r,n)->{
            items.addObject().put("id",r.getString("batch_id")).put("count",r.getLong("total"));return 0;
        },q.args.toArray());return out;
    }
    public JsonNode experiments(UUID dataset,String batch,String keyword,int page,int size) {
        if(page<1||size<1||size>50)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"分页大小应在1至50之间");
        var q=query(dataset,batch,keyword);
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*)"+FROM+q.sql,Long.class,q.args.toArray()));
        var out=json.createObjectNode().put("total",total).put("page",page).put("size",size);var items=out.putArray("items");
        q.args.add(size);q.args.add((long)(page-1)*size);
        jdbc.query("SELECT e.id,e.experiment_no,e.title,e.owner_name,e.experiment_date,v.id version_id"+FROM+q.sql+" ORDER BY e.created_at DESC,e.id LIMIT ? OFFSET ?",(r,n)->{
            items.addObject().put("id",r.getString("id")).put("versionId",r.getString("version_id")).put("label",r.getString("experiment_no")).put("title",r.getString("title")).put("owner",r.getString("owner_name")).put("date",Objects.toString(r.getObject("experiment_date"),""));return 0;
        },q.args.toArray());return out;
    }
    private record Query(String sql,ArrayList<Object> args){}
}
