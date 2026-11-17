package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.shared.business.BusinessDataAccess;
import com.jsd.aird.shared.security.Actor;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Uses the same atomic operations and record scopes as the business pages. Never trusts parent ids supplied by the model. */
@Component
public class DomainBusinessAccess implements BusinessDataAccess {
    private static final Set<String> TYPES = Set.of("CUSTOMER", "CUSTOMER_CONTACT", "CUSTOMER_COMMUNICATION", "CUSTOMER_REQUIREMENT",
        "INVENTORY_BALANCE", "INVENTORY_TRANSACTION", "SAMPLE_DISPATCH", "SHIPMENT", "PRODUCTION_ORDER", "PRODUCTION_UPLOAD",
        "QUALITY_STANDARD", "QUALITY_RECORD", "QUALITY_COA", "QUALITY_MANUAL", "QUALITY_MSDS", "QUALITY_LABEL", "QUALITY_DEFECT", "QUALITY_UPLOAD",
        "RESEARCH_TEST", "RESEARCH_TEST_UPLOAD", "DATA_IMPORT", "STRUCTURED_RECORD", "SPECTRUM",
        "MEETING_MINUTES", "PROJECT_DOCUMENT", "PROJECT_REFERENCE");
    private final AuthorizationService authorization;
    private final JdbcTemplate jdbc;
    public DomainBusinessAccess(AuthorizationService authorization, JdbcTemplate jdbc) { this.authorization=authorization; this.jdbc=jdbc; }
    public Set<String> resourceTypes() { return TYPES; }

    public boolean allowed(Actor actor, String type, UUID id, String operation, JsonNode context) {
        if (actor==null || !TYPES.contains(type) || !Set.of("SEARCH","READ","EXPORT","DOWNLOAD").contains(operation)) return false;
        boolean download="DOWNLOAD".equals(operation), export="EXPORT".equals(operation);
        if (type.startsWith("INVENTORY_") || Set.of("SAMPLE_DISPATCH","SHIPMENT").contains(type))
            return inventory(actor,type,id,operation,context);
        if ("RESEARCH_TEST".equals(type) || "RESEARCH_TEST_UPLOAD".equals(type)) {
            boolean upload=type.endsWith("UPLOAD");
            if (id==null) return research(actor,null,upload,"REPORT",operation) || research(actor,null,upload,"STANDARD",operation);
            var rows=rows(actor,id, upload
                ? "SELECT r.record_type FROM rnd.research_test_upload u JOIN rnd.research_test_record r ON r.id=u.record_id AND r.organization_id=u.organization_id WHERE u.organization_id=? AND u.id=? AND u.deleted=false AND r.deleted=false"
                : "SELECT record_type FROM rnd.research_test_record WHERE organization_id=? AND id=? AND deleted=false");
            return !rows.isEmpty() && research(actor,id,upload,rows.getFirst().get("record_type").toString(),operation);
        }
        if (Set.of("MEETING_MINUTES","PROJECT_DOCUMENT","PROJECT_REFERENCE").contains(type)) {
            String page=switch(type){case "MEETING_MINUTES"->"project.meeting";case "PROJECT_DOCUMENT"->"project.document";default->"project.reference";};
            String code=page+(download?".download":export?".export":".view");
            // Meetings and references have no separate transfer operation; their readable text remains source evidence.
            if (!"PROJECT_DOCUMENT".equals(type) && (download||export)) code=page+".view";
            if(id==null)return check(actor,code,"PROJECT",null);
            String sql=switch(type){
                case "MEETING_MINUTES"->"SELECT m.project_id FROM mdm.meeting_minutes m JOIN mdm.project p ON p.id=m.project_id WHERE p.organization_id=? AND m.id=? AND p.deleted=false";
                case "PROJECT_DOCUMENT"->"SELECT m.project_id FROM mdm.project_document m JOIN mdm.project p ON p.id=m.project_id WHERE p.organization_id=? AND m.id=? AND m.deleted=false AND p.deleted=false";
                default->"SELECT project_id FROM core.project_reference WHERE organization_id=? AND id=? AND status='ACTIVE'";
            };
            var found=rows(actor,id,sql);
            if(found.isEmpty() || !check(actor,code,"PROJECT",(UUID)found.getFirst().get("project_id")))return false;
            return !"PROJECT_REFERENCE".equals(type) || !download && !export || referenceSource(actor,id,operation);
        }
        if(Set.of("CUSTOMER_CONTACT","CUSTOMER_COMMUNICATION","CUSTOMER_REQUIREMENT").contains(type)) {
            String code="CUSTOMER_COMMUNICATION".equals(type)?"customer.followup.view":"CUSTOMER_REQUIREMENT".equals(type)?"customer.needs.view":"customer.overview.view";
            if(id==null) return check(actor,code,"CUSTOMER",null) || "CUSTOMER_REQUIREMENT".equals(type)&&check(actor,"project.customer-needs.view","PROJECT",null);
            String table=switch(type){case "CUSTOMER_CONTACT"->"mdm.partner_contact";case "CUSTOMER_COMMUNICATION"->"mdm.communication_record";default->"mdm.customer_requirement";};
            var found=rows(actor,id,"SELECT partner_id"+("CUSTOMER_REQUIREMENT".equals(type)?",project_id,assigned_project_ids":"")+" FROM "+table+" WHERE organization_id=? AND id=? AND deleted=false");
            if(found.isEmpty())return false;
            var parent=(UUID)found.getFirst().get("partner_id");
            if(parent!=null && check(actor,export||download?"customer.export":code,"CUSTOMER",parent))return true;
            if("CUSTOMER_REQUIREMENT".equals(type)) {
                var projects=jdbc.queryForList("SELECT p.id FROM mdm.customer_requirement r JOIN mdm.project p ON p.organization_id=r.organization_id AND (p.id=r.project_id OR r.assigned_project_ids @> jsonb_build_array(p.id::text)) WHERE r.organization_id=? AND r.id=? AND r.deleted=false AND p.deleted=false",UUID.class,actor.organizationId(),id);
                return projects.stream().anyMatch(project->check(actor,"project.customer-needs.view","PROJECT",project));
            }
            return false;
        }
        if("STRUCTURED_RECORD".equals(type)) {
            if(id==null)return check(actor,"data.view.view","DATA",null);
            var found=rows(actor,id,"SELECT import_job_id FROM data.data_record WHERE organization_id=? AND id=?");
            return !found.isEmpty() && allowed(actor,"DATA_IMPORT",(UUID)found.getFirst().get("import_job_id"),operation,context);
        }
        String table,resource,view,transfer;
        if(type.startsWith("QUALITY_")) {
            boolean upload="QUALITY_UPLOAD".equals(type);
            table=upload?"quality.upload_file":"quality.data_record";resource=upload?"QUALITY_UPLOAD":"QUALITY";
            view=upload?"quality.upload.view":"quality.view.view";transfer=(upload?"quality.upload": "quality.view")+(download||upload?".download":".export");
        } else switch(type) {
            case "CUSTOMER" -> {table="mdm.business_partner";resource="CUSTOMER";view="customer.list.view";transfer="customer.export";}
            case "PRODUCTION_ORDER" -> {table="mfg.production_order";resource="PRODUCTION";view="production.view.view";transfer=download?"production.view.download":"production.view.export";}
            case "PRODUCTION_UPLOAD" -> {table="mfg.production_upload";resource="PRODUCTION_UPLOAD";view="production.upload.view";transfer="production.upload.download";}
            case "DATA_IMPORT" -> {table="data.import_job";resource="DATA";view="data.view.view";transfer=download?"data.view.download":"data.export";}
            case "SPECTRUM" -> {table="spc.chart_asset";resource="SPECTRUM";view="spectrum.view.view";transfer=download?"spectrum.view.download":"spectrum.view.export";}
            default -> {return false;}
        }
        String active=type.startsWith("QUALITY_") || "CUSTOMER".equals(type)?" AND deleted=false":"SPECTRUM".equals(type)||"PRODUCTION_UPLOAD".equals(type)?" AND status<>'DELETED'":"";
        if(id!=null && rows(actor,id,"SELECT id FROM "+table+" WHERE organization_id=? AND id=?"+active).isEmpty())return false;
        return check(actor,download||export?transfer:view,resource,id);
    }
    private boolean referenceSource(Actor actor,UUID reference,String operation) {
        var found=rows(actor,reference,"SELECT resource_type,resource_id FROM core.project_reference WHERE organization_id=? AND id=? AND status='ACTIVE'");
        if(found.isEmpty())return false;
        var row=found.getFirst();UUID source=(UUID)row.get("resource_id");
        return switch(row.get("resource_type").toString()) {
            case "DATA_IMPORT_JOB" -> allowed(actor,"DATA_IMPORT",source,"READ",null) && allowed(actor,"DATA_IMPORT",source,operation,null);
            case "SPECTRUM_CHART" -> allowed(actor,"SPECTRUM",source,"READ",null) && allowed(actor,"SPECTRUM",source,operation,null);
            case "KNOWLEDGE_DOCUMENT" -> check(actor,"knowledge.view.view","KNOWLEDGE",source)
                && check(actor,"DOWNLOAD".equals(operation)?"knowledge.download":"knowledge.export","KNOWLEDGE",source);
            default -> false;
        };
    }

    /** Only the server's persisted activity can authorize removed metadata; it never authorizes deleted content. */
    boolean removedActivity(Actor actor,String type,UUID id,UUID project) {
        if(!TYPES.contains(type))return false;
        String code=switch(type) {
            case "MEETING_MINUTES" -> "project.meeting.view";
            case "PROJECT_DOCUMENT" -> "project.document.view";
            case "PROJECT_REFERENCE" -> "project.reference.view";
            case "CUSTOMER_REQUIREMENT" -> "project.customer-needs.view";
            case "QUALITY_UPLOAD" -> "quality.upload.view";
            case "PRODUCTION_UPLOAD" -> "production.upload.view";
            case "RESEARCH_TEST_UPLOAD", "RESEARCH_TEST" -> null;
            default -> type.startsWith("QUALITY_")?"quality.view.view":null;
        };
        if(code==null) {
            if(!Set.of("RESEARCH_TEST","RESEARCH_TEST_UPLOAD").contains(type))return false;
            // A removed record's kind must still be known, rather than guessing a page grant.
            var kinds=rows(actor,id,"RESEARCH_TEST".equals(type)
                ? "SELECT record_type FROM rnd.research_test_record WHERE organization_id=? AND id=?"
                : "SELECT r.record_type FROM rnd.research_test_upload u JOIN rnd.research_test_record r ON r.id=u.record_id AND r.organization_id=u.organization_id WHERE u.organization_id=? AND u.id=?");
            if(kinds.isEmpty())return false;
            code="STANDARD".equals(kinds.getFirst().get("record_type"))?"research-test.standard":"research-test.report";
            code+=(type.endsWith("UPLOAD")?".upload":"STANDARD".equals(kinds.getFirst().get("record_type"))?"":".view")+".view";
        }
        boolean projectResource=code.startsWith("project.");
        if(projectResource)return check(actor,code,"PROJECT",project);
        var scope=authorization.resolveScope(new PermissionCheck(actor.organizationId(),actor.userId(),code,type,null,"READ"));
        if(!scope.allowed())return false;
        if(Set.of("ALL","ORG").contains(scope.scopeType()))return true;
        if("PROJECT".equals(scope.scopeType()))return scope.targetIds().contains(project);
        if("SELECTED".equals(scope.scopeType()))return scope.targetIds().contains(
            code.startsWith("quality.view.")||code.startsWith("research-test.report.view.")?project:id);
        // Old deletion audits have no original creator/department, so these scopes cannot be proven.
        return false;
    }
    private boolean research(Actor actor, UUID id, boolean upload, String kind, String operation) {
        String page="STANDARD".equals(kind)?"research-test.standard":"research-test.report";
        if(upload)page+=".upload";else if(!"STANDARD".equals(kind))page+=".view";
        return check(actor,page+("DOWNLOAD".equals(operation)||upload&&"EXPORT".equals(operation)?".download":"EXPORT".equals(operation)?".export":".view"),upload?"RESEARCH_TEST_UPLOAD":"RESEARCH_TEST",id);
    }
    private boolean check(Actor actor,String code,String resource,UUID id) {
        return authorization.check(new PermissionCheck(actor.organizationId(),actor.userId(),code,resource,id,"READ")).allowed();
    }
    private List<Map<String,Object>> rows(Actor actor,UUID id,String sql) { return jdbc.queryForList(sql,actor.organizationId(),id); }

    private boolean inventory(Actor actor,String type,UUID id,String operation,JsonNode context) {
        boolean balance="INVENTORY_BALANCE".equals(type);
        String table=switch(type){case "INVENTORY_BALANCE"->"mfg.inventory_product";case "INVENTORY_TRANSACTION"->"mfg.inventory_transaction";case "SAMPLE_DISPATCH"->"mfg.inventory_sample_dispatch";default->"mfg.inventory_shipment";};
        var found=id==null?List.<Map<String,Object>>of():rows(actor,id,"SELECT inventory_scope FROM "+table+" WHERE organization_id=? AND id=?");
        if(id!=null&&found.isEmpty())return false;
        String scope=id==null?context==null?"":context.path("scope").asText(""):found.getFirst().get("inventory_scope").toString();
        var pages=new ArrayList<String>();
        if("SAMPLE_DISPATCH".equals(type))pages.add("inventory.sample");
        else if("SHIPMENT".equals(type))pages.add("inventory.shipment");
        else {
            if(balance)pages.add("inventory.query");
            if(!"PRODUCTION".equals(scope))pages.add("inventory.rnd");
            if(!"RND".equals(scope))pages.add("inventory.production");
        }
        for(var page:pages) {
            String suffix="EXPORT".equals(operation)?".export":"DOWNLOAD".equals(operation)?".download":".view";
            var request=new PermissionCheck(actor.organizationId(),actor.userId(),page+suffix,"INVENTORY",null,"READ");
            if(!authorization.check(request).allowed())continue;
            if(id==null)return true;
            var resolved=authorization.resolveScope(request);
            if(!resolved.allowed() && Set.of("EXPORT","DOWNLOAD").contains(operation))
                resolved=authorization.resolveScope(new PermissionCheck(actor.organizationId(),actor.userId(),page+".view","INVENTORY",null,"READ"));
            if(!resolved.allowed())continue;
            var dataScope=new RecordDataScope(resolved.scopeType(),actor.userId(),resolved.targetIds());
            var args=new ArrayList<Object>(List.of(actor.organizationId(),id));
            String from=balance?table+" p":table+" t JOIN mfg.inventory_product p ON p.organization_id=t.organization_id AND p.id=t.product_id";
            String condition;
            if("SELECTED".equals(dataScope.type())&&!balance) {
                if(dataScope.targetIds().isEmpty())continue;
                args.addAll(dataScope.targetIds());args.add(actor.userId());
                condition=" AND (t.id IN ("+String.join(",",Collections.nCopies(dataScope.targetIds().size(),"?"))+") OR t.actor_id=?)";
            } else if("SELECTED".equals(dataScope.type())&&balance&&!"inventory.query".equals(page)) {
                if(dataScope.targetIds().isEmpty())continue;
                args.addAll(dataScope.targetIds());args.add(actor.userId());
                condition=" AND (EXISTS(SELECT 1 FROM mfg.inventory_transaction t WHERE t.organization_id=p.organization_id AND t.product_id=p.id AND t.id IN ("+String.join(",",Collections.nCopies(dataScope.targetIds().size(),"?"))+")) OR p.created_by=?)";
            } else condition=dataScope.sqlCondition("p",args);
            String alias=balance?"p":"t";
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+from+" WHERE "+alias+".organization_id=? AND "+alias+".id=?"+condition+")",Boolean.class,args.toArray())))return true;
        }
        return false;
    }
}
