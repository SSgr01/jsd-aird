package com.jsd.aird.rnd.infrastructure;

import com.fasterxml.jackson.databind.*;
import com.jsd.aird.rnd.api.ExperimentEvents;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.*;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.error.*;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
public class JdbcExperimentRepository implements ExperimentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcExperimentRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @Override public List<Summary> search(UUID org, Search q) {
        var where=new StringBuilder(" WHERE e.organization_id=? AND e.deleted=false"); var args=new ArrayList<Object>(); args.add(org);
        if (text(q.keyword())) { where.append(" AND (lower(e.title) LIKE ? OR lower(e.experiment_no) LIKE ?)"); var k="%"+q.keyword().toLowerCase()+"%"; args.add(k);args.add(k); }
        if(text(q.status())){where.append(" AND e.status=?");args.add(q.status());} if(text(q.sourceType())){where.append(" AND e.source_type=?");args.add(q.sourceType());}
        if(q.projectId()!=null){where.append(" AND e.project_id=?");args.add(q.projectId());} if(q.categoryId()!=null){where.append(" AND e.category_id=?");args.add(q.categoryId());}
        args.add(q.size()); args.add(Math.max(0,q.page()-1)*q.size());
        return jdbc.query(BASE+where+" ORDER BY e.updated_at DESC LIMIT ? OFFSET ?", this::summary, args.toArray());
    }
    @Override public long count(UUID org, Search q) {
        var where=new StringBuilder(" WHERE organization_id=? AND deleted=false");var args=new ArrayList<Object>();args.add(org);
        if(text(q.keyword())){where.append(" AND (lower(title) LIKE ? OR lower(experiment_no) LIKE ?)");var k="%"+q.keyword().toLowerCase()+"%";args.add(k);args.add(k);}
        if(text(q.status())){where.append(" AND status=?");args.add(q.status());}if(text(q.sourceType())){where.append(" AND source_type=?");args.add(q.sourceType());}
        if(q.projectId()!=null){where.append(" AND project_id=?");args.add(q.projectId());}if(q.categoryId()!=null){where.append(" AND category_id=?");args.add(q.categoryId());}
        return Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM rnd.experiment"+where,Long.class,args.toArray())).orElse(0L);
    }
    @Override public Optional<Detail> detail(UUID org,UUID id){return jdbc.query(BASE+" WHERE e.organization_id=? AND e.id=? AND e.deleted=false",(rs,n)->detailRow(rs),org,id).stream().findFirst();}

    @Override @Transactional public Summary create(Create c){
        jdbc.update("INSERT INTO rnd.experiment(id,organization_id,experiment_no,title,category_id,category_name,source_type,status,project_id,stage_id,task_id,owner_id,owner_name,experiment_date,current_version_id,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?)",
                c.id(),c.organizationId(),c.experimentNo(),c.title(),c.categoryId(),c.categoryName(),c.sourceType(),c.status().name(),c.projectId(),c.stageId(),c.taskId(),c.ownerId(),c.ownerName(),c.experimentDate(),c.actorId(),c.actorId());
        jdbc.update("INSERT INTO rnd.experiment_version(id,organization_id,experiment_id,version_no,status,template_version_id,template_snapshot_hash,template_snapshot_jsonb,edit_model_jsonb,created_by) VALUES(?,?,?,1,?,?,?,?,?,?)",
                c.versionId(),c.organizationId(),c.id(),c.status().name(),c.templateVersionId(),c.templateHash(),pg(c.templateSnapshot()),pg(c.editModel()),c.actorId());
        jdbc.update("UPDATE rnd.experiment SET current_version_id=? WHERE id=?",c.versionId(),c.id());
        audit(c.organizationId(),c.id(),c.versionId(),"CREATED",null,c.editModel(),c.actorId(),c.ownerName()); event(c.organizationId(),c.id(),ExperimentEvents.CREATED,c.versionId(),1);
        return detail(c.organizationId(),c.id()).orElseThrow().summary();
    }
    @Override @Transactional public Detail saveDraft(UUID org,UUID id,long revision,Draft d,UUID actor,String name){
        var old=required(org,id); if(old.summary().status()==ExperimentStatus.COMPLETED||old.summary().status()==ExperimentStatus.VOIDED) conflict("已完成或作废实验不可直接编辑");
        int n=jdbc.update("UPDATE rnd.experiment SET title=?,category_id=?,category_name=?,project_id=?,stage_id=?,task_id=?,owner_name=?,experiment_date=?,revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",
                d.title(),d.categoryId(),d.categoryName(),d.projectId(),d.stageId(),d.taskId(),d.ownerName(),d.experimentDate(),actor,org,id,revision); lock(n);
        jdbc.update("UPDATE rnd.experiment_version SET template_version_id=?,template_snapshot_hash=?,template_snapshot_jsonb=?,edit_model_jsonb=? WHERE id=?",d.templateVersionId(),d.templateHash(),pg(d.templateSnapshot()),pg(d.editModel()),old.currentVersionId());
        audit(org,id,old.currentVersionId(),"DRAFT_SAVED",old.editModel(),d.editModel(),actor,name); return required(org,id);
    }
    @Override @Transactional public Detail transition(UUID org,UUID id,long revision,ExperimentStatus target,String comment,UUID actor,String name){
        var old=required(org,id); if(!old.summary().status().canTransitionTo(target)) conflict("不允许从 "+old.summary().status()+" 转换到 "+target);
        validate(old,target,comment); int n=jdbc.update("UPDATE rnd.experiment SET status=?,revision=revision+1,void_reason=?,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",target.name(),target==ExperimentStatus.VOIDED?comment:null,actor,org,id,revision);lock(n);
        jdbc.update("UPDATE rnd.experiment_version SET status=?,submitted_at=CASE WHEN ?='PENDING_REVIEW' THEN now() ELSE submitted_at END,published_at=CASE WHEN ?='COMPLETED' THEN now() ELSE published_at END WHERE id=?",target.name(),target.name(),target.name(),old.currentVersionId());
        jdbc.update("INSERT INTO rnd.experiment_review(id,organization_id,experiment_id,experiment_version_id,action,comment,operator_id,operator_name) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),org,id,old.currentVersionId(),target.name(),comment,actor,name);
        String ev=target==ExperimentStatus.PENDING_REVIEW?ExperimentEvents.SUBMITTED:target==ExperimentStatus.COMPLETED?ExperimentEvents.PUBLISHED:target==ExperimentStatus.VOIDED?ExperimentEvents.VOIDED:null;if(ev!=null)event(org,id,ev,old.currentVersionId(),old.summary().versionNo());
        audit(org,id,old.currentVersionId(),"STATUS_"+target,old.editModel(),old.editModel(),actor,name);return required(org,id);
    }
    @Override public List<Version> versions(UUID org,UUID id){return jdbc.query("SELECT * FROM rnd.experiment_version WHERE organization_id=? AND experiment_id=? ORDER BY version_no DESC",this::version,org,id);}
    @Override @Transactional public Detail createRevision(UUID org,UUID id,long revision,String reason,UUID actor,String name){
        var old=required(org,id);if(old.summary().status()!=ExperimentStatus.COMPLETED)conflict("仅已完成实验可创建修订版本");if(!text(reason))invalid("必须填写修订原因");var vid=UUID.randomUUID();int next=old.summary().versionNo()+1;
        int n=jdbc.update("UPDATE rnd.experiment SET status='DRAFT',current_version_id=NULL,revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",actor,org,id,revision);lock(n);
        jdbc.update("INSERT INTO rnd.experiment_version(id,organization_id,experiment_id,version_no,status,template_version_id,template_snapshot_hash,template_snapshot_jsonb,edit_model_jsonb,revision_reason,created_by) VALUES(?,?,?,?,'DRAFT',?,?,?,?,?,?)",vid,org,id,next,old.templateVersionId(),old.templateSnapshotHash(),pg(old.templateSnapshot()),pg(old.editModel()),reason,actor);
        jdbc.update("UPDATE rnd.experiment SET current_version_id=? WHERE id=?",vid,id);audit(org,id,vid,"REVISION_CREATED",null,old.editModel(),actor,name);return required(org,id);
    }
    @Override public JsonNode compare(UUID org,UUID id,int from,int to){var vs=versions(org,id);var a=vs.stream().filter(v->v.versionNo()==from).findFirst().orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));var b=vs.stream().filter(v->v.versionNo()==to).findFirst().orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));var out=json.createObjectNode();out.put("from",from);out.put("to",to);out.set("before",a.editModel());out.set("after",b.editModel());out.put("changed",!a.editModel().equals(b.editModel()));return out;}
    @Override public List<Audit> audits(UUID org,UUID id){return jdbc.query("SELECT * FROM rnd.experiment_audit WHERE organization_id=? AND experiment_id=? ORDER BY created_at DESC",(r,n)->new Audit(r.getObject("id",UUID.class),r.getString("action"),node(r,"before_jsonb"),node(r,"after_jsonb"),r.getString("operator_name"),r.getTimestamp("created_at").toInstant()),org,id);}
    @Override public List<Category> categories(UUID org,boolean all){return jdbc.query("SELECT * FROM rnd.experiment_category WHERE organization_id=?"+(all?"":" AND active=true")+" ORDER BY name",(r,n)->new Category(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("description"),r.getBoolean("active"),r.getLong("revision")),org);}
    @Override public Category createCategory(UUID org,String code,String name,String description,UUID actor){var id=UUID.randomUUID();jdbc.update("INSERT INTO rnd.experiment_category(id,organization_id,code,name,description) VALUES(?,?,?,?,?)",id,org,code,name,description);return new Category(id,code,name,description,true,0);}
    @Override public Category setCategoryActive(UUID org,UUID id,long rev,boolean active){int n=jdbc.update("UPDATE rnd.experiment_category SET active=?,revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",active,org,id,rev);lock(n);return categories(org,true).stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();}

    private Detail required(UUID o,UUID id){return detail(o,id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验不存在"));}
    private void validate(Detail d,ExperimentStatus t,String comment){if(t==ExperimentStatus.PENDING_REVIEW||t==ExperimentStatus.COMPLETED){var m=d.editModel();if(!text(m.path("purpose").asText())||!text(m.path("conclusion").path("mainConclusion").asText()))invalid("提交审核前必须填写实验目的和主要结论");if("FAILED".equals(m.path("conclusion").path("resultStatus").asText())&&!text(m.path("conclusion").path("failureCategory").asText()))invalid("失败实验必须填写失败原因分类");}if(t==ExperimentStatus.RETURNED&&!text(comment))invalid("退回必须填写原因");if(t==ExperimentStatus.VOIDED&&!text(comment))invalid("作废必须填写原因");}
    private Detail detailRow(ResultSet r)throws SQLException{var s=summary(r,0);var vid=r.getObject("current_version_id",UUID.class);return new Detail(s,vid,r.getObject("template_version_id",UUID.class),r.getString("template_snapshot_hash"),node(r,"template_snapshot_jsonb"),node(r,"edit_model_jsonb"),reviews(s.id()),attachments(s.id()));}
    private List<Review> reviews(UUID id){return jdbc.query("SELECT * FROM rnd.experiment_review WHERE experiment_id=? ORDER BY created_at DESC",(r,n)->new Review(r.getObject("id",UUID.class),r.getString("action"),r.getString("comment"),r.getString("operator_name"),r.getTimestamp("created_at").toInstant()),id);}
    private List<Attachment> attachments(UUID id){return jdbc.query("SELECT * FROM rnd.experiment_attachment WHERE experiment_id=? ORDER BY created_at DESC",(r,n)->new Attachment(r.getObject("id",UUID.class),r.getObject("file_id",UUID.class),r.getObject("file_version_id",UUID.class),r.getString("attachment_type"),r.getString("section_key"),r.getString("file_name"),r.getString("description"),r.getTimestamp("created_at").toInstant()),id);}
    private Summary summary(ResultSet r,int n)throws SQLException{return new Summary(r.getObject("id",UUID.class),r.getString("experiment_no"),r.getString("title"),r.getString("category_name"),r.getString("source_type"),ExperimentStatus.valueOf(r.getString("status")),r.getObject("project_id",UUID.class),r.getObject("stage_id",UUID.class),r.getObject("task_id",UUID.class),r.getString("owner_name"),r.getObject("experiment_date",LocalDate.class),r.getInt("version_no"),r.getLong("revision"),r.getTimestamp("updated_at").toInstant());}
    private Version version(ResultSet r,int n)throws SQLException{return new Version(r.getObject("id",UUID.class),r.getInt("version_no"),r.getString("status"),r.getObject("template_version_id",UUID.class),r.getString("template_snapshot_hash"),node(r,"edit_model_jsonb"),r.getString("revision_reason"),instant(r,"submitted_at"),instant(r,"published_at"),r.getTimestamp("created_at").toInstant());}
    private void audit(UUID o,UUID e,UUID v,String a,JsonNode before,JsonNode after,UUID actor,String name){jdbc.update("INSERT INTO rnd.experiment_audit(id,organization_id,experiment_id,experiment_version_id,action,before_jsonb,after_jsonb,operator_id,operator_name) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),o,e,v,a,pg(before),pg(after),actor,name);}
    private void event(UUID o,UUID e,String type,UUID v,int no){var p=json.createObjectNode().put("experimentId",e.toString()).put("versionId",v.toString()).put("versionNo",no);jdbc.update("INSERT INTO rnd.experiment_outbox(id,organization_id,aggregate_id,event_type,payload_jsonb) VALUES(?,?,?,?,?)",UUID.randomUUID(),o,e,type,pg(p));}
    private JsonNode node(ResultSet r,String c)throws SQLException{var value=r.getString(c);if(value==null||value.isBlank())return null;try{return json.readTree(value);}catch(Exception e){throw new SQLException("Invalid JSON in "+c,e);}}private PGobject pg(JsonNode n){if(n==null)return null;try{var p=new PGobject();p.setType("jsonb");p.setValue(json.writeValueAsString(n));return p;}catch(Exception e){throw new IllegalArgumentException(e);}}
    private static Instant instant(ResultSet r,String c)throws SQLException{var t=r.getTimestamp(c);return t==null?null:t.toInstant();}private static boolean text(String s){return s!=null&&!s.isBlank();}private static void lock(int n){if(n!=1)throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,"实验已被其他会话修改，请刷新后重试");}private static void conflict(String m){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,m);}private static void invalid(String m){throw new ApiException(ApiErrorCode.VALIDATION_ERROR,m);}
    private static final String BASE="SELECT e.*,v.version_no,v.template_version_id,v.template_snapshot_hash,v.template_snapshot_jsonb,v.edit_model_jsonb FROM rnd.experiment e JOIN rnd.experiment_version v ON v.id=e.current_version_id";
}
