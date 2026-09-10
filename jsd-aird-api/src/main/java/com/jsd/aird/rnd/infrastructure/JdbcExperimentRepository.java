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
        if(q.projectId()!=null){where.append(" AND e.project_id=?");args.add(q.projectId());} if(q.stageId()!=null){where.append(" AND e.stage_id=?");args.add(q.stageId());} if(q.taskId()!=null){where.append(" AND e.task_id=?");args.add(q.taskId());} if(q.categoryId()!=null){where.append(" AND e.category_id=?");args.add(q.categoryId());}
        if(text(q.ownerName())){where.append(" AND lower(e.owner_name)=lower(?)");args.add(q.ownerName().trim());} if(q.dateFrom()!=null){where.append(" AND e.experiment_date>=?");args.add(q.dateFrom());} if(q.dateTo()!=null){where.append(" AND e.experiment_date<=?");args.add(q.dateTo());}
        args.add(q.size()); args.add(Math.max(0,q.page()-1)*q.size());
        return jdbc.query(BASE+where+" ORDER BY e.updated_at DESC LIMIT ? OFFSET ?", this::summary, args.toArray());
    }
    @Override public long count(UUID org, Search q) {
        var where=new StringBuilder(" WHERE organization_id=? AND deleted=false");var args=new ArrayList<Object>();args.add(org);
        if(text(q.keyword())){where.append(" AND (lower(title) LIKE ? OR lower(experiment_no) LIKE ?)");var k="%"+q.keyword().toLowerCase()+"%";args.add(k);args.add(k);}
        if(text(q.status())){where.append(" AND status=?");args.add(q.status());}if(text(q.sourceType())){where.append(" AND source_type=?");args.add(q.sourceType());}
        if(q.projectId()!=null){where.append(" AND project_id=?");args.add(q.projectId());}if(q.stageId()!=null){where.append(" AND stage_id=?");args.add(q.stageId());}if(q.taskId()!=null){where.append(" AND task_id=?");args.add(q.taskId());}if(q.categoryId()!=null){where.append(" AND category_id=?");args.add(q.categoryId());}
        if(text(q.ownerName())){where.append(" AND lower(owner_name)=lower(?)");args.add(q.ownerName().trim());}if(q.dateFrom()!=null){where.append(" AND experiment_date>=?");args.add(q.dateFrom());}if(q.dateTo()!=null){where.append(" AND experiment_date<=?");args.add(q.dateTo());}
        return Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM rnd.experiment"+where,Long.class,args.toArray())).orElse(0L);
    }
    @Override public Optional<Detail> detail(UUID org,UUID id){return jdbc.query(BASE+" WHERE e.organization_id=? AND e.id=? AND e.deleted=false",(rs,n)->detailRow(rs),org,id).stream().findFirst();}

    @Override public List<CompletedFactsRow> completedFacts(UUID org, CompletedFactsSearch q) {
        var query = completedFactsWhere(org, q);
        var args = new ArrayList<>(query.args());
        args.add(q.size());
        args.add(Math.max(0, q.page() - 1) * q.size());
        return jdbc.query("""
                SELECT e.id AS experiment_id,e.current_version_id AS experiment_version_id,
                       e.experiment_no,e.title,e.source_type,e.project_id,e.stage_id,e.task_id,
                       e.category_id,e.category_name,e.experiment_date,v.edit_model_jsonb
                  FROM rnd.experiment e
                  JOIN rnd.experiment_version v ON v.id=e.current_version_id AND v.organization_id=e.organization_id
                """ + query.where() + " ORDER BY e.id,e.current_version_id LIMIT ? OFFSET ?", (rs, n) ->
                new CompletedFactsRow(
                        rs.getObject("experiment_id", UUID.class), rs.getObject("experiment_version_id", UUID.class),
                        rs.getString("experiment_no"), rs.getString("title"), rs.getString("source_type"),
                        rs.getObject("project_id", UUID.class), rs.getObject("stage_id", UUID.class),
                        rs.getObject("task_id", UUID.class), rs.getObject("category_id", UUID.class),
                        rs.getString("category_name"), rs.getObject("experiment_date", LocalDate.class),
                        node(rs, "edit_model_jsonb")), args.toArray());
    }

    @Override public long countCompletedFacts(UUID org, CompletedFactsSearch q) {
        var query = completedFactsWhere(org, q);
        return Optional.ofNullable(jdbc.queryForObject("""
                SELECT count(*) FROM rnd.experiment e
                JOIN rnd.experiment_version v ON v.id=e.current_version_id AND v.organization_id=e.organization_id
                """ + query.where(), Long.class, query.args().toArray())).orElse(0L);
    }

    @Override @Transactional public Summary create(Create c){
        String no = c.experimentNo();
        if (!text(no)) no = "EXP-" + LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM rnd.experiment WHERE organization_id=? AND experiment_no=? AND deleted=false)",
                Boolean.class, c.organizationId(), no)))
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "实验编号 " + no + " 已存在");
        jdbc.update("INSERT INTO rnd.experiment(id,organization_id,experiment_no,title,category_id,category_name,source_type,status,project_id,stage_id,task_id,owner_id,owner_name,experiment_date,current_version_id,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?)",
                c.id(),c.organizationId(),no,c.title(),c.categoryId(),c.categoryName(),c.sourceType(),c.status().name(),c.projectId(),c.stageId(),c.taskId(),c.ownerId(),c.ownerName(),c.experimentDate(),c.actorId(),c.actorId());
        jdbc.update("INSERT INTO rnd.experiment_version(id,organization_id,experiment_id,version_no,status,template_version_id,template_snapshot_hash,template_snapshot_jsonb,edit_model_jsonb,created_by) VALUES(?,?,?,1,?,?,?,?,?,?)",
                c.versionId(),c.organizationId(),c.id(),c.status().name(),c.templateVersionId(),c.templateHash(),pg(c.templateSnapshot()),pg(c.editModel()),c.actorId());
        jdbc.update("UPDATE rnd.experiment SET current_version_id=? WHERE id=?",c.versionId(),c.id());
        audit(c.organizationId(),c.id(),c.versionId(),"CREATED",null,c.editModel(),c.actorId(),c.actorName()); event(c.organizationId(),c.id(),ExperimentEvents.CREATED,c.versionId(),1);
        return detail(c.organizationId(),c.id()).orElseThrow().summary();
    }
    @Override @Transactional public Summary copy(UUID org, UUID sourceId, UUID actor, String name) {
        var source = required(org, sourceId);
        var id = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var title = source.summary().title() + "-副本";
        var no = "EXP-" + LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.update("INSERT INTO rnd.experiment(id,organization_id,experiment_no,title,category_id,category_name,source_type,status,project_id,stage_id,task_id,owner_id,owner_name,experiment_date,current_version_id,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?)",
                id,org,no,title,source.summary().categoryId(),source.summary().categoryName(),"MANUAL","DRAFT",source.summary().projectId(),source.summary().stageId(),source.summary().taskId(),actor,source.summary().ownerName(),source.summary().experimentDate(),actor,actor);
        jdbc.update("INSERT INTO rnd.experiment_version(id,organization_id,experiment_id,version_no,status,template_version_id,template_snapshot_hash,template_snapshot_jsonb,edit_model_jsonb,created_by) VALUES(?,?,?,1,'DRAFT',?,?,?,?,?)",
                versionId,org,id,source.templateVersionId(),source.templateSnapshotHash(),pg(source.templateSnapshot()),pg(source.editModel()),actor);
        jdbc.update("UPDATE rnd.experiment SET current_version_id=? WHERE id=?",versionId,id);
        audit(org,id,versionId,"COPIED",null,source.editModel(),actor,name);
        return detail(org,id).orElseThrow().summary();
    }
    @Override @Transactional public Detail saveDraft(UUID org,UUID id,long revision,Draft d,UUID actor,String name){
        var old=required(org,id); if(!old.summary().status().isEditable()) conflict("待审核、已完成或作废实验不可直接编辑");
        if(!text(d.experimentNo())) invalid("实验编号不能为空");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rnd.experiment WHERE organization_id=? AND experiment_no=? AND id<>? AND deleted=false)",Boolean.class,org,d.experimentNo().trim(),id))) conflict("实验编号 " + d.experimentNo().trim() + " 已存在");
        int n=jdbc.update("UPDATE rnd.experiment SET experiment_no=?,title=?,category_id=?,category_name=?,project_id=?,stage_id=?,task_id=?,owner_name=?,experiment_date=?,revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",
                d.experimentNo().trim(),d.title(),d.categoryId(),d.categoryName(),d.projectId(),d.stageId(),d.taskId(),d.ownerName(),d.experimentDate(),actor,org,id,revision); lock(n);
        jdbc.update("UPDATE rnd.experiment_version SET template_version_id=?,template_snapshot_hash=?,template_snapshot_jsonb=?,edit_model_jsonb=? WHERE id=?",d.templateVersionId(),d.templateHash(),pg(d.templateSnapshot()),pg(d.editModel()),old.currentVersionId());
        audit(org,id,old.currentVersionId(),"DRAFT_SAVED",old.editModel(),d.editModel(),actor,name); return required(org,id);
    }
    @Override @Transactional public void delete(UUID org,UUID id,long revision,UUID actor,String name){var old=required(org,id);if(old.summary().status()==ExperimentStatus.COMPLETED)conflict("已完成实验不可删除，请使用作废");int n=jdbc.update("UPDATE rnd.experiment SET deleted=true,revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=? AND deleted=false",actor,org,id,revision);lock(n);audit(org,id,old.currentVersionId(),"DELETED",old.editModel(),null,actor,name);}
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
    @Override @Transactional public Detail rollback(UUID org,UUID id,long revision,int targetVersion,String reason,UUID actor,String name){
        var old=required(org,id);
        if(old.summary().status()!=ExperimentStatus.COMPLETED) conflict("仅已完成实验可回退历史版本");
        if(!text(reason)) invalid("必须填写回退原因");
        var target=versions(org,id).stream().filter(v->v.versionNo()==targetVersion).findFirst()
                .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"历史版本不存在"));
        var vid=UUID.randomUUID();
        int next=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM rnd.experiment_version WHERE organization_id=? AND experiment_id=?",Integer.class,org,id);
        int n=jdbc.update("UPDATE rnd.experiment SET status='DRAFT',current_version_id=NULL,revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",actor,org,id,revision);lock(n);
        jdbc.update("INSERT INTO rnd.experiment_version(id,organization_id,experiment_id,version_no,status,template_version_id,template_snapshot_hash,template_snapshot_jsonb,edit_model_jsonb,revision_reason,created_by) VALUES(?,?,?,?,'DRAFT',?,?,?,?,?,?)",vid,org,id,next,target.templateVersionId(),target.snapshotHash(),pg(target.templateSnapshot()),pg(target.editModel()),reason,actor);
        jdbc.update("UPDATE rnd.experiment SET current_version_id=? WHERE id=?",vid,id);
        audit(org,id,vid,"ROLLED_BACK",null,target.editModel(),actor,name);
        return required(org,id);
    }
    @Override public JsonNode compare(UUID org,UUID id,int from,int to){var vs=versions(org,id);var a=vs.stream().filter(v->v.versionNo()==from).findFirst().orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));var b=vs.stream().filter(v->v.versionNo()==to).findFirst().orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));var out=json.createObjectNode();out.put("from",from);out.put("to",to);out.set("before",a.editModel());out.set("after",b.editModel());out.put("changed",!a.editModel().equals(b.editModel()));return out;}
    @Override public List<Audit> audits(UUID org,UUID id){return jdbc.query("SELECT * FROM rnd.experiment_audit WHERE organization_id=? AND experiment_id=? ORDER BY created_at DESC",(r,n)->new Audit(r.getObject("id",UUID.class),r.getString("action"),node(r,"before_jsonb"),node(r,"after_jsonb"),r.getString("operator_name"),r.getTimestamp("created_at").toInstant()),org,id);}
    @Override public List<Category> categories(UUID org,boolean all){return jdbc.query("SELECT * FROM rnd.experiment_category WHERE organization_id=?"+(all?"":" AND active=true")+" ORDER BY name",(r,n)->new Category(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("description"),r.getBoolean("active"),r.getLong("revision")),org);}
    @Override @Transactional public Category createCategory(UUID org,String code,String name,String description,UUID actor){if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rnd.experiment_category WHERE organization_id=? AND lower(name)=lower(?) AND active=true)",Boolean.class,org,name.trim())))conflict("实验分类名称已存在");var id=UUID.randomUUID();jdbc.update("INSERT INTO rnd.experiment_category(id,organization_id,code,name,description) VALUES(?,?,?,?,?)",id,org,code,name.trim(),description.trim());return new Category(id,code,name.trim(),description.trim(),true,0);}
    @Override @Transactional public Category updateCategory(UUID org,UUID id,long revision,String name,String description,UUID actor){var current=jdbc.query("SELECT * FROM rnd.experiment_category WHERE organization_id=? AND id=?",(r,n)->new Category(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("description"),r.getBoolean("active"),r.getLong("revision")),org,id).stream().findFirst().orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验分类不存在"));if(!current.name().equalsIgnoreCase(name)&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rnd.experiment_category WHERE organization_id=? AND lower(name)=lower(?) AND id<>? AND active=true)",Boolean.class,org,name.trim(),id)))conflict("实验分类名称已存在");int n=jdbc.update("UPDATE rnd.experiment_category SET name=?,description=?,revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",name.trim(),description.trim(),org,id,revision);lock(n);jdbc.update("UPDATE rnd.experiment SET category_name=? WHERE organization_id=? AND category_id=? AND deleted=false",name.trim(),org,id);return categories(org,true).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();}
    @Override @Transactional public Category setCategoryActive(UUID org,UUID id,long rev,boolean active){if(!active&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rnd.experiment WHERE organization_id=? AND category_id=? AND deleted=false)",Boolean.class,org,id)))conflict("该分类仍有关联实验，请先移动或删除实验");int n=jdbc.update("UPDATE rnd.experiment_category SET active=?,revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",active,org,id,rev);lock(n);return categories(org,true).stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();}

    private Detail required(UUID o,UUID id){return detail(o,id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验不存在"));}

    private CompletedQuery completedFactsWhere(UUID org, CompletedFactsSearch q) {
        var where = new StringBuilder(" WHERE e.organization_id=? AND e.deleted=false AND e.status='COMPLETED' AND v.status='COMPLETED'");
        var args = new ArrayList<Object>();
        args.add(org);
        if (!q.experimentIds().isEmpty()) appendIn(where, args, "e.id", q.experimentIds());
        if (q.projectId() != null) { where.append(" AND e.project_id=?"); args.add(q.projectId()); }
        if (q.categoryId() != null) { where.append(" AND e.category_id=?"); args.add(q.categoryId()); }
        var scope = q.scope();
        switch (scope.type()) {
            case "ALL" -> { }
            case "SELF" -> {
                where.append(" AND (e.created_by=? OR e.owner_id=?)");
                args.add(scope.actorId());
                args.add(scope.actorId());
            }
            case "ASSIGNED" -> {
                where.append(" AND e.owner_id=?");
                args.add(scope.actorId());
            }
            case "PROJECT" -> appendIn(where, args, "e.project_id", scope.targetIds());
            case "CATEGORY" -> appendIn(where, args, "e.category_id", scope.targetIds());
            case "SELECTED" -> appendIn(where, args, "e.id", scope.targetIds());
            default -> where.append(" AND 1=0");
        }
        return new CompletedQuery(where.toString(), List.copyOf(args));
    }

    private void appendIn(StringBuilder where, List<Object> args, String column, Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            where.append(" AND 1=0");
            return;
        }
        where.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", ids.stream().map(ignored -> "?").toList())).append(')');
        args.addAll(ids);
    }

    private record CompletedQuery(String where, List<Object> args) {}
    private String generateExperimentNo(){
        long seq = jdbc.queryForObject("SELECT nextval('rnd.experiment_no_seq')", Long.class);
        return "EXP-" + LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-" + seq;
    }
    private void validate(Detail d,ExperimentStatus t,String comment){if(t==ExperimentStatus.PENDING_REVIEW||t==ExperimentStatus.COMPLETED){var m=d.editModel();if(!text(m.path("purpose").asText())||!text(m.path("conclusion").path("mainConclusion").asText()))invalid("提交审核前必须填写实验目的和主要结论");if("FAILED".equals(m.path("conclusion").path("resultStatus").asText())&&!text(m.path("conclusion").path("failureCategory").asText()))invalid("失败实验必须填写失败原因分类");}if(t==ExperimentStatus.RETURNED&&!text(comment))invalid("退回必须填写原因");if(t==ExperimentStatus.VOIDED&&!text(comment))invalid("作废必须填写原因");}
    private Detail detailRow(ResultSet r)throws SQLException{var s=summary(r,0);var vid=r.getObject("current_version_id",UUID.class);return new Detail(s,vid,r.getObject("template_version_id",UUID.class),r.getString("template_snapshot_hash"),node(r,"template_snapshot_jsonb"),node(r,"edit_model_jsonb"),reviews(s.id()),attachments(s.id()));}
    private List<Review> reviews(UUID id){return jdbc.query("SELECT * FROM rnd.experiment_review WHERE experiment_id=? ORDER BY created_at DESC",(r,n)->new Review(r.getObject("id",UUID.class),r.getString("action"),r.getString("comment"),r.getString("operator_name"),r.getTimestamp("created_at").toInstant()),id);}
    private List<Attachment> attachments(UUID id){return jdbc.query("SELECT * FROM rnd.experiment_attachment WHERE experiment_id=? ORDER BY created_at DESC",(r,n)->new Attachment(r.getObject("id",UUID.class),r.getObject("file_id",UUID.class),r.getObject("file_version_id",UUID.class),r.getString("attachment_type"),r.getString("section_key"),r.getString("file_name"),r.getString("description"),r.getTimestamp("created_at").toInstant()),id);}
    private Summary summary(ResultSet r,int n)throws SQLException{return new Summary(r.getObject("id",UUID.class),r.getString("experiment_no"),r.getString("title"),r.getObject("category_id",UUID.class),r.getString("category_name"),r.getString("source_type"),ExperimentStatus.valueOf(r.getString("status")),r.getObject("project_id",UUID.class),r.getString("project_name"),r.getObject("stage_id",UUID.class),r.getString("stage_name"),r.getObject("task_id",UUID.class),r.getString("task_name"),r.getString("owner_name"),r.getObject("experiment_date",LocalDate.class),r.getInt("version_no"),r.getLong("revision"),r.getTimestamp("updated_at").toInstant());}
    private Version version(ResultSet r,int n)throws SQLException{return new Version(r.getObject("id",UUID.class),r.getInt("version_no"),r.getString("status"),r.getObject("template_version_id",UUID.class),r.getString("template_snapshot_hash"),node(r,"template_snapshot_jsonb"),node(r,"edit_model_jsonb"),r.getString("revision_reason"),instant(r,"submitted_at"),instant(r,"published_at"),r.getTimestamp("created_at").toInstant(),r.getObject("created_by",UUID.class));}
    private void audit(UUID o,UUID e,UUID v,String a,JsonNode before,JsonNode after,UUID actor,String name){jdbc.update("INSERT INTO rnd.experiment_audit(id,organization_id,experiment_id,experiment_version_id,action,before_jsonb,after_jsonb,operator_id,operator_name) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),o,e,v,a,pg(before),pg(after),actor,name);}
    private void event(UUID o,UUID e,String type,UUID v,int no){var p=json.createObjectNode().put("experimentId",e.toString()).put("versionId",v.toString()).put("versionNo",no);jdbc.update("INSERT INTO rnd.experiment_outbox(id,organization_id,aggregate_id,event_type,payload_jsonb) VALUES(?,?,?,?,?)",UUID.randomUUID(),o,e,type,pg(p));}
    private JsonNode node(ResultSet r,String c)throws SQLException{var value=r.getString(c);if(value==null||value.isBlank())return null;try{return json.readTree(value);}catch(Exception e){throw new SQLException("Invalid JSON in "+c,e);}}private PGobject pg(JsonNode n){if(n==null)return null;try{var p=new PGobject();p.setType("jsonb");p.setValue(json.writeValueAsString(n));return p;}catch(Exception e){throw new IllegalArgumentException(e);}}
    private static Instant instant(ResultSet r,String c)throws SQLException{var t=r.getTimestamp(c);return t==null?null:t.toInstant();}private static boolean text(String s){return s!=null&&!s.isBlank();}private static void lock(int n){if(n!=1)throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,"实验已被其他会话修改，请刷新后重试");}private static void conflict(String m){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,m);}private static void invalid(String m){throw new ApiException(ApiErrorCode.VALIDATION_ERROR,m);}
    private static final String BASE="SELECT e.*,p.name AS project_name,s.name AS stage_name,t.name AS task_name,v.version_no,v.template_version_id,v.template_snapshot_hash,v.template_snapshot_jsonb,v.edit_model_jsonb FROM rnd.experiment e LEFT JOIN mdm.project p ON p.id=e.project_id AND p.deleted=false LEFT JOIN mdm.project_stage s ON s.id=e.stage_id AND s.deleted=false LEFT JOIN mdm.project_task t ON t.id=e.task_id AND t.deleted=false JOIN rnd.experiment_version v ON v.id=e.current_version_id";
}
