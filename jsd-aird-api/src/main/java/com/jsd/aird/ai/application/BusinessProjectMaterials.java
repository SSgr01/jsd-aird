package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.mdm.application.service.MeetingMinutesService;
import com.jsd.aird.rnd.application.ProjectDocumentService;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

/** Minimal read adapters for the existing project pages. Business access remains owned by the existing policies. */
@Service
public class BusinessProjectMaterials implements BusinessReadPort {
    public static final Set<String> TYPES=Set.of("MEETING_MINUTES","PROJECT_DOCUMENT","PROJECT_REFERENCE","QUALITY_UPLOAD","RESEARCH_TEST_UPLOAD");
    public static final Map<String,String> RELATIONS=Map.ofEntries(
        Map.entry("会议纪要","MEETING_MINUTES"),Map.entry("项目文档","PROJECT_DOCUMENT"),Map.entry("资料参考","PROJECT_REFERENCE"),
        Map.entry("品管附件","QUALITY_UPLOAD"),Map.entry("生产资料","PRODUCTION_UPLOAD"),Map.entry("研发资料","RESEARCH_TEST"),Map.entry("研发附件","RESEARCH_TEST_UPLOAD"),
        Map.entry("检测规程","QUALITY_STANDARD"),Map.entry("检测记录","QUALITY_RECORD"),Map.entry("COA","QUALITY_COA"),Map.entry("技术资料","QUALITY_MANUAL"),
        Map.entry("不良记录","QUALITY_DEFECT"),Map.entry("MSDS","QUALITY_MSDS"),Map.entry("标签","QUALITY_LABEL"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AgentProjectFacade projects;
    private final MeetingMinutesService meetings;
    private final ProjectDocumentService documents;
    private final ResearchKnowledgeFacade knowledge;
    public BusinessProjectMaterials(JdbcTemplate jdbc,ObjectMapper json,AgentProjectFacade projects,MeetingMinutesService meetings,ProjectDocumentService documents,ResearchKnowledgeFacade knowledge) {
        this.jdbc=jdbc;this.json=json;this.projects=projects;this.meetings=meetings;this.documents=documents;this.knowledge=knowledge;
    }
    public Set<String> resourceTypes(){return TYPES;}
    public Set<String> filters(String type){return Set.of("project_id","query");}
    public Set<String> requiredFilters(String type){return Set.of("project_id");}
    public void authorizeProject(UUID project) {
        projects.read("get_project_snapshot",json.createObjectNode().put("project_id",project.toString()));
    }
    static String source(String type) {
        return switch(type) {
            case "MEETING_MINUTES" -> "SELECT m.id,m.project_id,m.title,m.updated_at,p.organization_id FROM mdm.meeting_minutes m JOIN mdm.project p ON p.id=m.project_id";
            case "PROJECT_DOCUMENT" -> "SELECT m.id,m.project_id,m.title,m.updated_at,p.organization_id FROM mdm.project_document m JOIN mdm.project p ON p.id=m.project_id WHERE m.deleted=false";
            case "PROJECT_REFERENCE" -> "SELECT m.id,m.project_id,m.title,m.added_at updated_at,m.organization_id FROM core.project_reference m WHERE m.status='ACTIVE'";
            case "QUALITY_UPLOAD" -> "SELECT m.id,m.project_id,m.original_name title,m.created_at updated_at,m.organization_id FROM quality.upload_file m WHERE m.deleted=false";
            case "RESEARCH_TEST_UPLOAD" -> "SELECT m.id,r.project_id,m.original_name title,m.created_at updated_at,m.organization_id FROM rnd.research_test_upload m JOIN rnd.research_test_record r ON r.id=m.record_id AND r.organization_id=m.organization_id WHERE m.deleted=false AND r.deleted=false";
            default -> throw missing();
        };
    }
    public Page references(String type,ObjectNode filter,int page,int size) {
        UUID project=uuid(filter,"project_id");authorizeProject(project);
        var rows=jdbc.queryForList("SELECT r.id FROM ("+source(type)+") r JOIN mdm.project p ON p.id=r.project_id AND p.organization_id=r.organization_id WHERE p.organization_id=? AND p.deleted=false AND r.project_id=? AND r.title ILIKE ? ORDER BY r.updated_at,r.id LIMIT ? OFFSET ?",
            ActorContext.required().organizationId(),project,"%"+filter.path("query").asText("")+"%",size+1,(long)(page-1)*size);
        return new Page(rows.stream().limit(size).map(row->ref(type,(UUID)row.get("id")).put("project_id",project.toString())).toList(),rows.size()>size);
    }
    public BusinessRecordView read(ObjectNode request) {
        String type=request.path("type").asText();UUID id=id(request);var org=ActorContext.required().organizationId();
        var found=jdbc.queryForList("SELECT r.project_id FROM ("+source(type)+") r JOIN mdm.project p ON p.id=r.project_id AND p.organization_id=r.organization_id WHERE r.id=? AND p.organization_id=? AND p.deleted=false",id,org);
        if(found.isEmpty())throw missing();UUID project=(UUID)found.getFirst().get("project_id");authorizeProject(project);
        var body=json.createObjectNode();var links=new ArrayList<BusinessRecordView.Relation>();projectLinks(links,project,null,null);
        String title,revision,path;
        if("MEETING_MINUTES".equals(type)) {
            var row=meetings.get(id);body=facts(json,row,"title","attendees","summary","occurredAt","archivedToKb","createdAt","updatedAt");
            title=row.title();revision=Long.toString(row.version());path="/projects/"+project;
        } else if("PROJECT_DOCUMENT".equals(type)) {
            var row=documents.get(id);body=facts(json,row,"title","format","source","status","contentData","updatedAt");
            body.set("content",document(row.contentSnapshot()));title=row.title();revision=Long.toString(row.version());path="/projects/"+project;
        } else {
            String table=switch(type){case "PROJECT_REFERENCE"->"core.project_reference";case "QUALITY_UPLOAD"->"quality.upload_file";default->"rnd.research_test_upload";};
            var rows=jdbc.queryForList("SELECT to_jsonb(m)::text value FROM "+table+" m WHERE m.organization_id=? AND m.id=?",org,id);
            try{body=(ObjectNode)json.readTree((String)rows.getFirst().get("value"));}catch(Exception error){throw new IllegalStateException(error);}
            body.remove(List.of("organization_id","created_by","added_by","removed_by"));
            title=body.path("title").asText(body.path("original_name").asText(type));revision=hash(body.toString());path="/projects/"+project;
            if("QUALITY_UPLOAD".equals(type)&&body.hasNonNull("generated_record_id")) {
                var targets=jdbc.queryForList("SELECT business_type FROM quality.data_record WHERE organization_id=? AND id=? AND deleted=false",org,uuid(body,"generated_record_id"));
                if(!targets.isEmpty())links.add(link("生成的品管记录","QUALITY_"+targets.getFirst().get("business_type").toString().toUpperCase(Locale.ROOT),uuid(body,"generated_record_id")));
            }
            if("RESEARCH_TEST_UPLOAD".equals(type))links.add(link("所属研发资料","RESEARCH_TEST",uuid(body,"record_id")));
            if("PROJECT_REFERENCE".equals(type)) {
                String resource=body.path("resource_type").asText();UUID target=uuid(body,"resource_id");
                if("DATA_IMPORT_JOB".equals(resource))links.add(link("资料正文","DATA_IMPORT",target));
                else if("SPECTRUM_CHART".equals(resource))links.add(link("资料正文","SPECTRUM",target));
                else if("KNOWLEDGE_DOCUMENT".equals(resource)) {
                    var args=new ArrayList<Object>(List.of(org,target));String selected="";
                    if(body.hasNonNull("file_version_id")){selected=" AND document_version_id=?";args.add(uuid(body,"file_version_id"));}
                    var publications=jdbc.queryForList("SELECT id FROM kb.publication WHERE organization_id=? AND document_id=? AND status='PUBLISHED'"+selected+" ORDER BY published_at DESC,id DESC LIMIT 1",args.toArray());
                    if(!publications.isEmpty()) {
                        UUID publication=(UUID)publications.getFirst().get("id");knowledge.authorize(List.of(new ResearchKnowledgeFacade.DocumentRef(target,publication)));
                        body.putObject("document_ref").put("document_id",target.toString()).put("publication_id",publication.toString());
                        body.put("reading_notice","使用 document_read、document_outline 或 table_read 读取资料正文");
                    } else body.put("reading_notice","该参考条目尚无可读取的发布正文");
                }
            }
        }
        body.put("projectId",project.toString());
        var result=view(type,id,revision,title,body,path,links);result.ref().put("project_id",project.toString());pinned(request,result);return result;
    }
}
