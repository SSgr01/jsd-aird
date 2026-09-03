package com.jsd.aird.rnd.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.rnd.application.port.ResearchTestRepository;
import com.jsd.aird.rnd.domain.ResearchTestModels.*;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Repository
public class JdbcResearchTestRepository implements ResearchTestRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public JdbcResearchTestRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    @Override public PageResponse<Summary> search(UUID org,Search q){
        var w=new StringBuilder(" WHERE r.organization_id=? AND r.record_type=? AND r.deleted=false");var a=new ArrayList<Object>(List.of(org,q.type().name()));
        if(text(q.keyword())){w.append(" AND (lower(r.name) LIKE ? OR lower(r.business_no) LIKE ?)");var k="%"+q.keyword().toLowerCase()+"%";a.add(k);a.add(k);}
        if(text(q.category())){w.append(" AND r.category=?");a.add(q.category());}if(text(q.status())){w.append(" AND r.status=?");a.add(q.status());}
        if(text(q.ownerName())){w.append(" AND lower(r.owner_name)=lower(?)");a.add(q.ownerName());}if(q.projectId()!=null){w.append(" AND r.project_id=?");a.add(q.projectId());}
        if(q.dateFrom()!=null){w.append(" AND r.business_date>=?");a.add(q.dateFrom());}if(q.dateTo()!=null){w.append(" AND r.business_date<=?");a.add(q.dateTo());}
        long total=Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM rnd.research_test_record r"+w,Long.class,a.toArray())).orElse(0L);
        var args=new ArrayList<>(a);args.add(q.size());args.add((q.page()-1)*q.size());
        var items=jdbc.query(BASE+w+" ORDER BY r.updated_at DESC LIMIT ? OFFSET ?",this::summary,args.toArray());
        return new PageResponse<>(items,q.page(),q.size(),total,(total+q.size()-1)/q.size());
    }
    @Override public Optional<Detail> detail(UUID org,UUID id){return jdbc.query(BASE+" WHERE r.organization_id=? AND r.id=? AND r.deleted=false",this::detail,org,id).stream().findFirst();}
    @Override @Transactional public Detail create(Create c){
        var id=UUID.randomUUID();var vid=UUID.randomUUID();var no=text(c.businessNo())?c.businessNo().trim():nextNo(c.type());
        if(exists(c.organizationId(),c.type(),no,null))conflict("编号 "+no+" 已存在");
        jdbc.update("INSERT INTO rnd.research_test_record(id,organization_id,record_type,business_no,name,category,applicable_scope,owner_id,owner_name,business_date,document_format,source_type,status,visibility,project_id,stage_id,task_id,source_file_id,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,c.organizationId(),c.type().name(),no,c.name().trim(),c.category(),c.scope(),c.actorId(),c.ownerName(),c.date(),c.format(),c.sourceType(),"DRAFT",c.visibility(),c.projectId(),c.stageId(),c.taskId(),c.sourceFileId(),c.actorId(),c.actorId());
        jdbc.update("INSERT INTO rnd.research_test_version(id,organization_id,record_id,version_no,status,template_version_id,template_snapshot_hash,template_snapshot_jsonb,edit_model_jsonb,member_snapshot_jsonb,effective_from,effective_to,created_by) VALUES(?,?,?,1,'DRAFT',?,?,?,?,?,?,?,?)",
                vid,c.organizationId(),id,c.templateVersionId(),c.templateHash(),pg(c.templateSnapshot()),pg(orObject(c.editModel())),pg(orArray(c.memberSnapshot())),c.effectiveFrom(),c.effectiveTo(),c.actorId());
        jdbc.update("UPDATE rnd.research_test_record SET current_version_id=? WHERE id=?",vid,id);audit(c.organizationId(),id,vid,"CREATED",null,c.editModel(),c.actorId(),c.actorName());return required(c.organizationId(),id);
    }
    @Override @Transactional public Detail save(UUID org,UUID id,Draft d,UUID actor,String name){var old=required(org,id);if(!old.summary().status().editable())conflict("当前状态不可编辑");if(exists(org,old.summary().recordType(),d.businessNo(),id))conflict("编号 "+d.businessNo()+" 已存在");
        var n=jdbc.update("UPDATE rnd.research_test_record SET business_no=?,name=?,category=?,applicable_scope=?,owner_name=?,business_date=?,visibility=?,project_id=?,stage_id=?,task_id=?,lock_version=lock_version+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND lock_version=?",
                d.businessNo().trim(),d.name().trim(),d.category(),d.scope(),d.ownerName(),d.date(),d.visibility(),d.projectId(),d.stageId(),d.taskId(),actor,org,id,d.lockVersion());lock(n);
        jdbc.update("UPDATE rnd.research_test_version SET template_version_id=?,template_snapshot_hash=?,template_snapshot_jsonb=?,edit_model_jsonb=?,member_snapshot_jsonb=?,effective_from=?,effective_to=? WHERE id=?",
                d.templateVersionId(),d.templateHash(),pg(d.templateSnapshot()),pg(orObject(d.editModel())),pg(orArray(d.memberSnapshot())),d.effectiveFrom(),d.effectiveTo(),old.versionId());audit(org,id,old.versionId(),"DRAFT_SAVED",old.editModel(),d.editModel(),actor,name);return required(org,id);}
    @Override @Transactional public Detail rename(UUID org,UUID id,String name,UUID projectId,UUID stageId,UUID taskId,long lockVersion,UUID actor,String actorName){
        var old=required(org,id);
        var n=jdbc.update("UPDATE rnd.research_test_record SET name=?,project_id=?,stage_id=?,task_id=?,lock_version=lock_version+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND lock_version=?",
                name.trim(),projectId,stageId,taskId,actor,org,id,lockVersion);
        lock(n);
        audit(org,id,old.versionId(),"RENAMED",old.editModel(),old.editModel(),actor,actorName);
        return required(org,id);
    }
    @Override @Transactional public Detail transition(UUID org,UUID id,long rev,Status target,String comment,UUID actor,String actorName){var old=required(org,id);validateTransition(old,target,comment);
        if(target==Status.PUBLISHED&&old.summary().recordType()==Type.STANDARD)validateStandardOverlap(org,old);
        var n=jdbc.update("UPDATE rnd.research_test_record SET status=?,lock_version=lock_version+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND lock_version=?",target.name(),actor,org,id,rev);lock(n);
        jdbc.update("UPDATE rnd.research_test_version SET status=?,submitted_at=CASE WHEN ?='PENDING_REVIEW' THEN now() ELSE submitted_at END,published_at=CASE WHEN ?='PUBLISHED' THEN now() ELSE published_at END WHERE id=?",target.name(),target.name(),target.name(),old.versionId());
        jdbc.update("INSERT INTO rnd.research_test_review(id,organization_id,record_id,version_id,action,comment,operator_id,operator_name) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),org,id,old.versionId(),target.name(),comment,actor,actorName);audit(org,id,old.versionId(),"STATUS_"+target,old.editModel(),old.editModel(),actor,actorName);return required(org,id);}
    @Override @Transactional public Detail createRevision(UUID org,UUID id,long rev,String reason,UUID actor,String name){var old=required(org,id);if(old.summary().status()!=Status.PUBLISHED&&old.summary().status()!=Status.ARCHIVED)conflict("仅已发布记录可创建修订");if(!text(reason))invalid("必须填写修订原因");var vid=UUID.randomUUID();var next=old.summary().versionNo()+1;var n=jdbc.update("UPDATE rnd.research_test_record SET status='DRAFT',current_version_id=?,lock_version=lock_version+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND lock_version=?",vid,actor,org,id,rev);lock(n);
        jdbc.update("INSERT INTO rnd.research_test_version(id,organization_id,record_id,version_no,status,template_version_id,template_snapshot_hash,template_snapshot_jsonb,edit_model_jsonb,member_snapshot_jsonb,effective_from,effective_to,change_summary,created_by) VALUES(?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?)",vid,org,id,next,old.templateVersionId(),old.templateSnapshotHash(),pg(old.templateSnapshot()),pg(old.editModel()),pg(old.memberSnapshot()),old.effectiveFrom(),old.effectiveTo(),reason,actor);audit(org,id,vid,"REVISION_CREATED",null,old.editModel(),actor,name);return required(org,id);}
    @Override @Transactional public Detail copy(UUID org,UUID id,UUID actor,String name){var old=required(org,id);var c=new Create(org,actor,name,old.summary().recordType(),null,old.summary().name()+"-副本",old.summary().category(),old.summary().applicableScope(),name,LocalDate.now(),old.summary().documentFormat(),"BLANK",old.summary().visibility(),old.summary().projectId(),old.summary().stageId(),old.summary().taskId(),null,old.templateVersionId(),old.templateSnapshotHash(),old.templateSnapshot(),old.editModel(),old.memberSnapshot(),old.effectiveFrom(),old.effectiveTo());return create(c);}
    @Override @Transactional public void delete(UUID org,UUID id,long rev,UUID actor,String name){var old=required(org,id);if(old.summary().status()!=Status.DRAFT&&old.summary().status()!=Status.RETURNED)conflict("只有草稿或已退回记录可以删除");var n=jdbc.update("UPDATE rnd.research_test_record SET deleted=true,lock_version=lock_version+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND lock_version=?",actor,org,id,rev);lock(n);audit(org,id,old.versionId(),"DELETED",old.editModel(),null,actor,name);}
    @Override public List<Version> versions(UUID org,UUID id){required(org,id);return jdbc.query("SELECT * FROM rnd.research_test_version WHERE organization_id=? AND record_id=? AND status IN ('PUBLISHED','ARCHIVED') ORDER BY version_no DESC",this::version,org,id);}
    @Override public List<Audit> audits(UUID org,UUID id){required(org,id);return jdbc.query("SELECT * FROM rnd.research_test_audit WHERE organization_id=? AND record_id=? ORDER BY created_at DESC",(r,n)->new Audit(r.getObject("id",UUID.class),r.getString("action"),node(r,"before_jsonb"),node(r,"after_jsonb"),r.getString("operator_name"),instant(r,"created_at")),org,id);}
    @Override @Transactional public Upload addUpload(UUID org,UUID actor,UUID recordId,UUID fileId,String name,String ct,long size,String sha){required(org,recordId);var id=UUID.randomUUID();try{jdbc.update("INSERT INTO rnd.research_test_upload(id,organization_id,record_id,file_id,original_name,content_type,file_size,sha256,created_by) VALUES(?,?,?,?,?,?,?,?,?)",id,org,recordId,fileId,name,ct,size,sha,actor);}catch(DuplicateKeyException e){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"相同内容的文件已上传");}return uploads(org,name,1,100).items().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();}
    @Override public Optional<Upload> findUpload(UUID org, UUID id){
        return jdbc.query("SELECT u.*,r.category,r.visibility,p.name project_name,s.name stage_name,t.name task_name FROM rnd.research_test_upload u JOIN rnd.research_test_record r ON r.id=u.record_id LEFT JOIN mdm.project p ON p.id=r.project_id AND p.deleted=false LEFT JOIN mdm.project_stage s ON s.id=r.stage_id AND s.deleted=false LEFT JOIN mdm.project_task t ON t.id=r.task_id AND t.deleted=false WHERE u.organization_id=? AND u.id=? AND u.deleted=false",this::upload,org,id).stream().findFirst();
    }
    @Override @Transactional public Upload retryUpload(UUID org, UUID id){
        if(jdbc.update("UPDATE rnd.research_test_upload SET status='DRAFT_CREATED' WHERE organization_id=? AND id=? AND deleted=false",org,id)==0)
            throw new ApiException(ApiErrorCode.NOT_FOUND,"研发测试上传记录不存在");
        return findUpload(org,id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"研发测试上传记录不存在"));
    }
    @Override public PageResponse<Upload> uploads(UUID org,String keyword,int page,int size){var w=" WHERE u.organization_id=? AND u.deleted=false";var a=new ArrayList<Object>();a.add(org);if(text(keyword)){w+=" AND lower(u.original_name) LIKE ?";a.add("%"+keyword.toLowerCase()+"%");}long total=jdbc.queryForObject("SELECT count(*) FROM rnd.research_test_upload u"+w,Long.class,a.toArray());a.add(size);a.add((page-1)*size);var items=jdbc.query("SELECT u.*,r.category,r.visibility,p.name project_name,s.name stage_name,t.name task_name FROM rnd.research_test_upload u JOIN rnd.research_test_record r ON r.id=u.record_id LEFT JOIN mdm.project p ON p.id=r.project_id AND p.deleted=false LEFT JOIN mdm.project_stage s ON s.id=r.stage_id AND s.deleted=false LEFT JOIN mdm.project_task t ON t.id=r.task_id AND t.deleted=false"+w+" ORDER BY u.created_at DESC LIMIT ? OFFSET ?",(r,n)->new Upload(r.getObject("id",UUID.class),r.getObject("record_id",UUID.class),r.getObject("file_id",UUID.class),r.getString("original_name"),r.getString("content_type"),r.getLong("file_size"),r.getString("status"),r.getString("category"),r.getString("visibility"),r.getString("project_name"),r.getString("stage_name"),r.getString("task_name"),instant(r,"created_at")),a.toArray());return new PageResponse<>(items,page,size,total,(total+size-1)/size);}
    @Override public void deleteUpload(UUID org,UUID id){jdbc.update("UPDATE rnd.research_test_upload SET deleted=true WHERE organization_id=? AND id=?",org,id);}

    private Detail required(UUID org,UUID id){return detail(org,id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"研发测试记录不存在"));}
    private Upload upload(ResultSet r,int n)throws SQLException{return new Upload(r.getObject("id",UUID.class),r.getObject("record_id",UUID.class),r.getObject("file_id",UUID.class),r.getString("original_name"),r.getString("content_type"),r.getLong("file_size"),r.getString("status"),r.getString("category"),r.getString("visibility"),r.getString("project_name"),r.getString("stage_name"),r.getString("task_name"),instant(r,"created_at"));}
    private String nextNo(Type type){if(type==Type.REPORT){var seq=jdbc.queryForObject("SELECT nextval('rnd.comprehensive_report_no_seq')",Long.class);return "CTR-"+LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+"-"+String.format("%03d",seq);}return "STD-"+LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+"-"+UUID.randomUUID().toString().substring(0,6).toUpperCase();}
    private boolean exists(UUID org,Type t,String no,UUID exclude){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rnd.research_test_record WHERE organization_id=? AND record_type=? AND business_no=? AND deleted=false"+(exclude==null?")": " AND id<>?)"),Boolean.class,exclude==null?new Object[]{org,t.name(),no}:new Object[]{org,t.name(),no,exclude}));}
    private void validateTransition(Detail old,Status target,String comment){var s=old.summary().status();boolean ok=(target==Status.PENDING_REVIEW&&(s==Status.DRAFT||s==Status.RETURNED))||(target==Status.RETURNED&&s==Status.PENDING_REVIEW)||(target==Status.PUBLISHED&&s==Status.PENDING_REVIEW)||(target==Status.ARCHIVED&&s==Status.PUBLISHED);if(!ok)conflict("不允许从 "+s+" 转换到 "+target);if(target==Status.RETURNED&&!text(comment))invalid("驳回必须填写原因");}
    private void validateStandardOverlap(UUID org,Detail d){if(d.effectiveFrom()==null)return;var overlap=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rnd.research_test_record r JOIN rnd.research_test_version v ON v.id=r.current_version_id WHERE r.organization_id=? AND r.record_type='STANDARD' AND r.status='PUBLISHED' AND r.deleted=false AND r.id<>? AND COALESCE(r.applicable_scope,'')=COALESCE(?, '') AND COALESCE(v.effective_to,'9999-12-31')>=? AND COALESCE(?,'9999-12-31')>=v.effective_from)",Boolean.class,org,d.summary().id(),d.summary().applicableScope(),d.effectiveFrom(),d.effectiveTo());if(Boolean.TRUE.equals(overlap))conflict("相同适用范围存在有效期重叠的已发布标准");}
    private Summary summary(ResultSet r,int n)throws SQLException{return new Summary(r.getObject("id",UUID.class),Type.valueOf(r.getString("record_type")),r.getString("business_no"),r.getString("name"),r.getString("category"),r.getString("applicable_scope"),r.getString("owner_name"),r.getObject("business_date",LocalDate.class),r.getString("document_format"),r.getString("source_type"),Status.valueOf(r.getString("status")),r.getString("visibility"),r.getObject("project_id",UUID.class),r.getString("project_name"),r.getObject("stage_id",UUID.class),r.getString("stage_name"),r.getObject("task_id",UUID.class),r.getString("task_name"),r.getObject("source_file_id",UUID.class),r.getInt("version_no"),r.getLong("lock_version"),instant(r,"created_at"),instant(r,"updated_at"));}
    private Detail detail(ResultSet r,int n)throws SQLException{var s=summary(r,n);return new Detail(s,r.getObject("current_version_id",UUID.class),r.getObject("template_version_id",UUID.class),r.getString("template_snapshot_hash"),node(r,"template_snapshot_jsonb"),node(r,"edit_model_jsonb"),node(r,"member_snapshot_jsonb"),r.getObject("effective_from",LocalDate.class),r.getObject("effective_to",LocalDate.class),reviews(s.id()));}
    private Version version(ResultSet r,int n)throws SQLException{return new Version(r.getObject("id",UUID.class),r.getInt("version_no"),r.getString("status"),r.getObject("template_version_id",UUID.class),r.getString("template_snapshot_hash"),node(r,"template_snapshot_jsonb"),node(r,"edit_model_jsonb"),node(r,"member_snapshot_jsonb"),r.getObject("effective_from",LocalDate.class),r.getObject("effective_to",LocalDate.class),r.getString("change_summary"),instant(r,"submitted_at"),instant(r,"published_at"),r.getObject("created_by",UUID.class),instant(r,"created_at"));}
    private List<Review> reviews(UUID id){return jdbc.query("SELECT * FROM rnd.research_test_review WHERE record_id=? ORDER BY created_at DESC",(r,n)->new Review(r.getObject("id",UUID.class),r.getString("action"),r.getString("comment"),r.getString("operator_name"),instant(r,"created_at")),id);}
    private void audit(UUID org,UUID id,UUID vid,String action,JsonNode before,JsonNode after,UUID actor,String name){jdbc.update("INSERT INTO rnd.research_test_audit(id,organization_id,record_id,version_id,action,before_jsonb,after_jsonb,operator_id,operator_name) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),org,id,vid,action,pg(before),pg(after),actor,name);}
    private JsonNode orObject(JsonNode n){return n==null?json.createObjectNode():n;}private JsonNode orArray(JsonNode n){return n==null?json.createArrayNode():n;}
    private JsonNode node(ResultSet r,String c)throws SQLException{var v=r.getString(c);if(v==null)return null;try{return json.readTree(v);}catch(Exception e){throw new SQLException(e);}}
    private PGobject pg(JsonNode n){if(n==null)return null;try{var p=new PGobject();p.setType("jsonb");p.setValue(json.writeValueAsString(n));return p;}catch(Exception e){throw new IllegalArgumentException(e);}}
    private static Instant instant(ResultSet r,String c)throws SQLException{var t=r.getTimestamp(c);return t==null?null:t.toInstant();}private static boolean text(String s){return s!=null&&!s.isBlank();}private static void lock(int n){if(n!=1)throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,"记录已被其他会话修改，请刷新后重试");}private static void conflict(String m){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,m);}private static void invalid(String m){throw new ApiException(ApiErrorCode.VALIDATION_ERROR,m);}
    private static final String BASE="SELECT r.*,v.version_no,v.template_version_id,v.template_snapshot_hash,v.template_snapshot_jsonb,v.edit_model_jsonb,v.member_snapshot_jsonb,v.effective_from,v.effective_to,p.name project_name,s.name stage_name,t.name task_name FROM rnd.research_test_record r JOIN rnd.research_test_version v ON v.id=r.current_version_id LEFT JOIN mdm.project p ON p.id=r.project_id AND p.deleted=false LEFT JOIN mdm.project_stage s ON s.id=r.stage_id AND s.deleted=false LEFT JOIN mdm.project_task t ON t.id=r.task_id AND t.deleted=false";
}
