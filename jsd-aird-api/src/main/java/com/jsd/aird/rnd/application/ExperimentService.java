package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.*;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.*;
import com.jsd.aird.rnd.domain.ExperimentModels.*;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class ExperimentService {
    private final ExperimentRepository repo; private final ObjectMapper json;
    public ExperimentService(ExperimentRepository repo,ObjectMapper json){this.repo=repo;this.json=json;}
    public PageResponse<Summary> search(ExperimentRepository.Search q){var a=ActorContext.required();var items=repo.search(a.organizationId(),q);var total=repo.count(a.organizationId(),q);return new PageResponse<>(items,q.page(),q.size(),total,(total+q.size()-1)/q.size());}
    public Detail detail(UUID id){var a=ActorContext.required();return repo.detail(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验不存在"));}
    public Summary create(CreateCommand c){var a=ActorContext.required();if(c.title()==null||c.title().isBlank())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"实验标题不能为空");var id=UUID.randomUUID();var vid=UUID.randomUUID();var no=c.experimentNo()==null||c.experimentNo().isBlank()?"EXP-"+LocalDate.now().getYear()+"-"+id.toString().substring(0,8).toUpperCase():c.experimentNo();var model=c.editModel()==null?emptyModel(c.title()):c.editModel();return repo.create(new ExperimentRepository.Create(id,a.organizationId(),no,c.title(),c.categoryId(),c.categoryName(),defaultText(c.sourceType(),"MANUAL"),ExperimentStatus.DRAFT,c.projectId(),c.stageId(),c.taskId(),a.userId(),defaultText(c.ownerName(),a.username()),c.experimentDate()==null?LocalDate.now():c.experimentDate(),vid,c.templateVersionId(),c.templateSnapshotHash(),object(c.templateSnapshot()),object(model),a.userId()));}
    public Detail save(UUID id,long rev,DraftCommand c){var a=ActorContext.required();return repo.saveDraft(a.organizationId(),id,rev,new ExperimentRepository.Draft(c.title(),c.categoryId(),c.categoryName(),c.projectId(),c.stageId(),c.taskId(),c.ownerName(),c.experimentDate(),c.templateVersionId(),c.templateSnapshotHash(),object(c.templateSnapshot()),object(c.editModel())),a.userId(),a.username());}
    public Detail transition(UUID id,long rev,ExperimentStatus target,String comment){var a=ActorContext.required();return repo.transition(a.organizationId(),id,rev,target,comment,a.userId(),a.username());}
    public Detail revision(UUID id,long rev,String reason){var a=ActorContext.required();return repo.createRevision(a.organizationId(),id,rev,reason,a.userId(),a.username());}
    public List<Version> versions(UUID id){var a=ActorContext.required();return repo.versions(a.organizationId(),id);}public JsonNode compare(UUID id,int from,int to){var a=ActorContext.required();return repo.compare(a.organizationId(),id,from,to);}public List<Audit> audits(UUID id){var a=ActorContext.required();return repo.audits(a.organizationId(),id);}
    public List<Category> categories(boolean all){var a=ActorContext.required();return repo.categories(a.organizationId(),all);}public Category createCategory(String code,String name,String description){var a=ActorContext.required();return repo.createCategory(a.organizationId(),code,name,description,a.userId());}public Category categoryActive(UUID id,long rev,boolean active){var a=ActorContext.required();return repo.setCategoryActive(a.organizationId(),id,rev,active);}
    private JsonNode emptyModel(String title){var n=json.createObjectNode();n.put("title",title);n.put("purpose","");n.put("plan","");n.set("dynamicValues",json.createObjectNode());n.set("tables",json.createArrayNode());n.set("formulaItems",json.createArrayNode());n.set("processSteps",json.createArrayNode());n.set("testResults",json.createArrayNode());n.set("events",json.createArrayNode());var c=json.createObjectNode();c.put("resultStatus","");c.put("mainConclusion","");c.put("failureCategory","");n.set("conclusion",c);return n;}private JsonNode object(JsonNode n){return n==null?json.createObjectNode():n;}private static String defaultText(String v,String d){return v==null||v.isBlank()?d:v;}
    public record CreateCommand(String experimentNo,String title,UUID categoryId,String categoryName,String sourceType,UUID projectId,UUID stageId,UUID taskId,String ownerName,LocalDate experimentDate,UUID templateVersionId,String templateSnapshotHash,JsonNode templateSnapshot,JsonNode editModel){}
    public record DraftCommand(String title,UUID categoryId,String categoryName,UUID projectId,UUID stageId,UUID taskId,String ownerName,LocalDate experimentDate,UUID templateVersionId,String templateSnapshotHash,JsonNode templateSnapshot,JsonNode editModel){}
}
