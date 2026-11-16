package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

/** Child activity does not depend on the parent project's updated_at. No inferred before/after content. */
@Service
public class ProjectRelatedActivity {
    private final JdbcTemplate jdbc;
    private final BusinessDataService business;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private DomainBusinessAccess access;
    public ProjectRelatedActivity(JdbcTemplate jdbc,BusinessDataService business,ObjectMapper json){this.jdbc=jdbc;this.business=business;this.json=json;}
    private static final String SOURCES="""
        SELECT 'CUSTOMER_REQUIREMENT' type,r.id,r.project_id,r.title,r.updated_at changed_at,'UPDATED' operation,r.organization_id FROM mdm.customer_requirement r WHERE r.deleted=false
        UNION SELECT 'CUSTOMER_REQUIREMENT',r.id,pid.value::uuid,r.title,r.updated_at,'UPDATED',r.organization_id FROM mdm.customer_requirement r CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(r.assigned_project_ids,'[]'::jsonb)) pid(value) WHERE r.deleted=false AND pid.value ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        UNION ALL SELECT 'MEETING_MINUTES',m.id,m.project_id,m.title,m.updated_at,'UPDATED',p.organization_id FROM mdm.meeting_minutes m JOIN mdm.project p ON p.id=m.project_id
        UNION ALL SELECT 'PROJECT_DOCUMENT',m.id,m.project_id,m.title,m.updated_at,'UPDATED',p.organization_id FROM mdm.project_document m JOIN mdm.project p ON p.id=m.project_id WHERE m.deleted=false
        UNION ALL SELECT 'PROJECT_REFERENCE',id,project_id,title,added_at,'ADDED',organization_id FROM core.project_reference
        UNION ALL SELECT 'PROJECT_REFERENCE',r.id,r.project_id,r.title,j.updated_at,'SOURCE_UPDATED',r.organization_id FROM core.project_reference r JOIN data.import_job j ON j.id=r.resource_id AND j.organization_id=r.organization_id WHERE r.status='ACTIVE' AND r.resource_type='DATA_IMPORT_JOB'
        UNION ALL SELECT 'PROJECT_REFERENCE',r.id,r.project_id,r.title,p.published_at,'SOURCE_UPDATED',r.organization_id FROM core.project_reference r JOIN kb.publication p ON p.document_id=r.resource_id AND p.organization_id=r.organization_id AND (r.file_version_id IS NULL OR r.file_version_id=p.document_version_id) WHERE r.status='ACTIVE' AND r.resource_type='KNOWLEDGE_DOCUMENT' AND p.status='PUBLISHED'
        UNION ALL SELECT 'PROJECT_REFERENCE',r.id,r.project_id,r.title,c.updated_at,'SOURCE_UPDATED',r.organization_id FROM core.project_reference r JOIN spc.chart_asset c ON c.id=r.resource_id AND c.organization_id=r.organization_id WHERE r.status='ACTIVE' AND r.resource_type='SPECTRUM_CHART' AND c.status<>'DELETED'
        UNION ALL SELECT 'PROJECT_REFERENCE',r.id,r.project_id,r.title,r.removed_at,'REMOVED',r.organization_id FROM core.project_reference r WHERE r.removed_at IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM ops.audit_log a WHERE a.organization_id=r.organization_id AND a.aggregate_type='PROJECT_REFERENCE' AND a.aggregate_id=r.id AND a.action='REMOVED' AND a.created_at>=r.added_at)
        UNION ALL SELECT 'QUALITY_'||upper(business_type),id,project_id,display_name,updated_at,'UPDATED',organization_id FROM quality.data_record WHERE deleted=false
        UNION ALL SELECT 'QUALITY_UPLOAD',id,project_id,original_name,created_at,'ADDED',organization_id FROM quality.upload_file WHERE deleted=false
        UNION ALL SELECT 'PRODUCTION_UPLOAD',id,project_id,production_name,updated_at,'UPDATED',organization_id FROM mfg.production_upload WHERE status<>'DELETED'
        UNION ALL SELECT 'RESEARCH_TEST',id,project_id,name,updated_at,'UPDATED',organization_id FROM rnd.research_test_record WHERE deleted=false
        UNION ALL SELECT 'RESEARCH_TEST_UPLOAD',u.id,r.project_id,u.original_name,u.created_at,'ADDED',u.organization_id FROM rnd.research_test_upload u JOIN rnd.research_test_record r ON r.id=u.record_id AND r.organization_id=u.organization_id WHERE u.deleted=false AND r.deleted=false
        UNION ALL SELECT aggregate_type,aggregate_id,(detail_jsonb->>'projectId')::uuid,detail_jsonb->>'name',created_at,action,organization_id FROM ops.audit_log
          WHERE aggregate_type IN ('MEETING_MINUTES','PROJECT_DOCUMENT','PROJECT_REFERENCE','QUALITY_UPLOAD','QUALITY_STANDARD','QUALITY_RECORD','QUALITY_COA','QUALITY_MANUAL','QUALITY_DEFECT','QUALITY_MSDS','QUALITY_LABEL','PRODUCTION_UPLOAD','RESEARCH_TEST','RESEARCH_TEST_UPLOAD','CUSTOMER_REQUIREMENT')
          AND detail_jsonb->>'projectId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' AND (action LIKE '%DELET%' OR action LIKE '%REMOV%')
        """;
    public ObjectNode context(ObjectNode args) {
        int offset=BusinessDataService.offset(args);Instant end=Instant.parse(args.path("window_end").asText());
        Instant start=args.hasNonNull("window_start")?Instant.parse(args.path("window_start").asText()):end.minusSeconds(86400);
        var params=new ArrayList<Object>(List.of(ActorContext.required().organizationId(),Timestamp.from(start),Timestamp.from(end)));
        String filter="";
        if(args.path("project_ids").isArray()&&!args.path("project_ids").isEmpty()){
            if(args.path("project_ids").size()>20)throw new ApiException(ApiErrorCode.BAD_REQUEST);
            filter=" AND p.id IN ("+String.join(",",Collections.nCopies(args.path("project_ids").size(),"?"))+")";
            args.path("project_ids").forEach(pid->params.add(UUID.fromString(pid.asText())));
        }
        if("project-morning".equals(args.path("task_type").asText()))filter+=" AND p.status NOT IN ('COMPLETED','CANCELLED')";
        var rows=jdbc.queryForList("SELECT DISTINCT a.* FROM ("+SOURCES+") a JOIN mdm.project p ON p.id=a.project_id AND p.organization_id=a.organization_id WHERE p.organization_id=? AND p.deleted=false AND a.changed_at>=? AND a.changed_at<?"+filter+" ORDER BY changed_at,type,id,project_id,operation LIMIT 5001",params.toArray());
        if(rows.size()>5000)throw new ApiException(ApiErrorCode.BAD_REQUEST,"资料变化超过5000条，请缩小项目范围；未返回不完整索引");
        var events=json.createArrayNode();var parents=new LinkedHashMap<UUID,ObjectNode>();var denied=new HashSet<UUID>();
        for(var row:rows){
            UUID project=(UUID)row.get("project_id");if(denied.contains(project))continue;
            if(!parents.containsKey(project))try{
                var request=json.createObjectNode();request.set("ref",ref("PROJECT",project));
                var result=business.invoke("business_read",request);parents.put(project,(ObjectNode)result.path("candidates").path(0));
            }catch(ApiException error){if(hidden(error)){denied.add(project);continue;}throw error;}
            String type=(String)row.get("type");UUID id=(UUID)row.get("id");
            var ref=ref(type,id).put("project_id",project.toString());
            String operation=(String)row.get("operation");boolean removed=operation.contains("DELET")||operation.contains("REMOV");
            if(removed ? !access.removedActivity(ActorContext.required(),type,id,project) : !business.allowed(type,id,"READ",ref))continue;
            var event=json.createObjectNode().put("project_id",project.toString()).put("resource_type",type).put("resource_id",id.toString())
                .put("title",Objects.toString(row.get("title"),type)).put("changed_at",((Timestamp)row.get("changed_at")).toInstant().toString()).put("operation",operation);
            if(!removed)try{var request=json.createObjectNode();request.set("ref",ref);var found=business.invoke("business_read",request);event.set("ref",found.path("candidates").path(0).path("ref"));}
            catch(ApiException error){if(!hidden(error))throw error;event.put("reading_notice","资料已移除或当前不可读取");}
            else event.put("reading_notice","该资料已移除，仅保留已记录的活动信息");
            events.add(event);
        }
        var result=json.createObjectNode();var selected=result.putArray("candidates");
        for(int i=offset;i<Math.min(offset+20,events.size());i++)selected.add(events.get(i));
        if(offset+20<events.size())result.put("next_offset",offset+20);
        var related=result.putArray("related_projects");var selectedProjects=new HashSet<String>();
        for(var event:selected)if(selectedProjects.add(event.path("project_id").asText()))related.add(parents.get(UUID.fromString(event.path("project_id").asText())));
        result.put("section","RELATED_ACTIVITY").put("total",events.size()).put("index_revision",hash(events.toString()));return result;
    }
    private static boolean hidden(ApiException error){return Set.of(ApiErrorCode.NOT_FOUND,ApiErrorCode.DATA_SCOPE_DENIED,ApiErrorCode.PERMISSION_DENIED).contains(error.errorCode());}
}
