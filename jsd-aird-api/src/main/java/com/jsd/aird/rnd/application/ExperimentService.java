package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.*;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.*;
import com.jsd.aird.rnd.domain.ExperimentModels.*;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class ExperimentService {
    private final ExperimentRepository repo; private final ObjectMapper json; private final FileStorageFacade files;
    public ExperimentService(ExperimentRepository repo,ObjectMapper json,FileStorageFacade files){this.repo=repo;this.json=json;this.files=files;}
    public PageResponse<Summary> search(ExperimentRepository.Search q){ExperimentAccessPolicy.requireRead();var a=ActorContext.required();var items=repo.search(a.organizationId(),q);var total=repo.count(a.organizationId(),q);return new PageResponse<>(items,q.page(),q.size(),total,(total+q.size()-1)/q.size());}
    public Detail detail(UUID id){ExperimentAccessPolicy.requireRead();var a=ActorContext.required();return repo.detail(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验不存在"));}
    @Transactional
    public Summary create(CreateCommand c){ExperimentAccessPolicy.requireWrite();var a=ActorContext.required();if(c.title()==null||c.title().isBlank())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"实验名称不能为空");if(c.ownerName()==null||c.ownerName().isBlank())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"实验人不能为空");var id=UUID.randomUUID();var vid=UUID.randomUUID();var no=c.experimentNo()==null||c.experimentNo().isBlank()?"EXP-"+LocalDate.now().getYear()+"-"+id.toString().substring(0,8).toUpperCase():c.experimentNo();var model=c.editModel()==null?emptyModel(c.title()):c.editModel();var summary=repo.create(new ExperimentRepository.Create(id,a.organizationId(),no,c.title().trim(),c.categoryId(),c.categoryName(),defaultText(c.sourceType(),"MANUAL"),ExperimentStatus.DRAFT,c.projectId(),c.stageId(),c.taskId(),a.userId(),c.ownerName().trim(),c.experimentDate()==null?LocalDate.now():c.experimentDate(),vid,c.templateVersionId(),c.templateSnapshotHash(),object(c.templateSnapshot()),object(model),a.userId()));if(c.sourceFileId()!=null)files.activate(c.sourceFileId());return summary;}
    public Summary copy(UUID id){ExperimentAccessPolicy.requireWrite();var a=ActorContext.required();return repo.copy(a.organizationId(),id,a.userId(),a.username());}
    public Detail save(UUID id,long rev,DraftCommand c){ExperimentAccessPolicy.requireWrite();var a=ActorContext.required();return repo.saveDraft(a.organizationId(),id,rev,new ExperimentRepository.Draft(c.experimentNo(),c.title(),c.categoryId(),c.categoryName(),c.projectId(),c.stageId(),c.taskId(),c.ownerName(),c.experimentDate(),c.templateVersionId(),c.templateSnapshotHash(),object(c.templateSnapshot()),object(c.editModel())),a.userId(),a.username());}
    public void delete(UUID id,long rev){ExperimentAccessPolicy.requireDelete();var a=ActorContext.required();repo.delete(a.organizationId(),id,rev,a.userId(),a.username());}
    public Detail transition(UUID id,long rev,ExperimentStatus target,String comment){if(target==ExperimentStatus.COMPLETED)ExperimentAccessPolicy.requireReview();else if(target==ExperimentStatus.RETURNED)ExperimentAccessPolicy.requireReview();else ExperimentAccessPolicy.requireWrite();var a=ActorContext.required();return repo.transition(a.organizationId(),id,rev,target,comment,a.userId(),a.username());}
    public Detail revision(UUID id,long rev,String reason){ExperimentAccessPolicy.requireWrite();var a=ActorContext.required();return repo.createRevision(a.organizationId(),id,rev,reason,a.userId(),a.username());}
    public Detail rollback(UUID id,long rev,int targetVersion,String reason){ExperimentAccessPolicy.requireWrite();var a=ActorContext.required();return repo.rollback(a.organizationId(),id,rev,targetVersion,reason,a.userId(),a.username());}
    public List<Version> versions(UUID id){ExperimentAccessPolicy.requireRead();var a=ActorContext.required();return repo.versions(a.organizationId(),id);}public JsonNode compare(UUID id,int from,int to){ExperimentAccessPolicy.requireRead();var a=ActorContext.required();return repo.compare(a.organizationId(),id,from,to);}public List<Audit> audits(UUID id){ExperimentAccessPolicy.requireRead();var a=ActorContext.required();return repo.audits(a.organizationId(),id);}
    public List<Category> categories(boolean all){ExperimentAccessPolicy.requireRead();var a=ActorContext.required();return repo.categories(a.organizationId(),all);}public Category createCategory(String code,String name,String description){ExperimentAccessPolicy.requireWrite();var a=ActorContext.required();return repo.createCategory(a.organizationId(),code,name,description,a.userId());}public Category updateCategory(UUID id,long rev,String name,String description){ExperimentAccessPolicy.requireWrite();var a=ActorContext.required();return repo.updateCategory(a.organizationId(),id,rev,name.trim(),description.trim(),a.userId());}public Category categoryActive(UUID id,long rev,boolean active){ExperimentAccessPolicy.requireWrite();var a=ActorContext.required();return repo.setCategoryActive(a.organizationId(),id,rev,active);}
    private JsonNode emptyModel(String title){var n=json.createObjectNode();n.put("title",title);n.put("purpose","");n.put("plan","");n.set("dynamicValues",json.createObjectNode());n.set("tables",json.createArrayNode());n.set("formulaItems",json.createArrayNode());n.set("processSteps",json.createArrayNode());n.set("testResults",json.createArrayNode());n.set("events",json.createArrayNode());var c=json.createObjectNode();c.put("resultStatus","");c.put("mainConclusion","");c.put("failureCategory","");n.set("conclusion",c);return n;}private JsonNode object(JsonNode n){return n==null?json.createObjectNode():n;}private static String defaultText(String v,String d){return v==null||v.isBlank()?d:v;}
    public record CreateCommand(String experimentNo,String title,UUID categoryId,String categoryName,String sourceType,UUID projectId,UUID stageId,UUID taskId,String ownerName,LocalDate experimentDate,UUID sourceFileId,UUID templateVersionId,String templateSnapshotHash,JsonNode templateSnapshot,JsonNode editModel){}
    public record DraftCommand(String experimentNo,String title,UUID categoryId,String categoryName,UUID projectId,UUID stageId,UUID taskId,String ownerName,LocalDate experimentDate,UUID templateVersionId,String templateSnapshotHash,JsonNode templateSnapshot,JsonNode editModel){}
}
