package com.jsd.aird.mdm.application.service;
import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.mdm.infrastructure.model.ProjectTaskSummaryRow;
import com.jsd.aird.mdm.infrastructure.persistence.ProjectWorkMapper;
import com.jsd.aird.shared.error.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class ProjectWorkService {
 private final ProjectWorkMapper mapper;
 public ProjectWorkService(ProjectWorkMapper mapper){this.mapper=mapper;}
 public List<ProjectTask> tasks(UUID stageId){return mapper.tasks(stageId);}
 @Transactional
 public ProjectTask createTask(UUID projectId,TaskInput input){
  if(!mapper.stageBelongs(input.stageId(),projectId)) throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"所选阶段不属于当前项目");
  String name=required(input.name(),"任务名称不能为空"); Instant now=Instant.now();
  var task=new ProjectTask(UUID.randomUUID(),code("TASK"),projectId,input.stageId(),name,clean(input.owner()),input.plannedDate(),input.status()==null?"PENDING":input.status(),0,0,now,now);
  mapper.insertTask(task); return task;
 }
 public List<ProjectExperiment> experiments(UUID taskId){return mapper.experiments(taskId);}
 public ProjectTask getTask(UUID id){return mapper.task(id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"任务不存在"));}
 @Transactional
 public ProjectTask updateTask(UUID id,TaskInput input){
  ProjectTask existing=mapper.task(id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"任务不存在"));
  if(!mapper.stageBelongs(input.stageId(),existing.projectId())) throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"所选阶段不属于当前项目");
  long version=input.version()==null?existing.version():input.version();
  var task=new ProjectTask(id,existing.taskCode(),existing.projectId(),input.stageId(),required(input.name(),"任务名称不能为空"),clean(input.owner()),input.plannedDate(),input.status()==null?"PENDING":input.status(),existing.experimentCount(),version,existing.createdAt(),existing.updatedAt());
  int rows=mapper.updateTask(task);
  if(rows==0) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"任务已被他人修改，请刷新后重试");
  return getTask(id);
 }

 public List<ProjectTaskSummaryRow> searchTasks(ProjectTaskQuery query) {
  int page = query.page() == null || query.page() < 1 ? 1 : query.page();
  int size = query.size() == null || query.size() < 1 ? 20 : query.size();
  long offset = (long) (page - 1) * size;
  return mapper.findTaskPage(query, offset, size);
 }

 public long countTasks(ProjectTaskQuery query) {
  return mapper.countTasks(query);
 }

 public List<String> taskOwners() {
  return mapper.findTaskOwners();
 }

 @Transactional
 public ProjectExperiment createExperiment(UUID taskId,ExperimentInput input){
  ProjectTask task=mapper.task(taskId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"任务不存在")); Instant now=Instant.now();
  var experiment=new ProjectExperiment(UUID.randomUUID(),code("EXP"),task.projectId(),task.stageId(),taskId,required(input.title(),"实验标题不能为空"),clean(input.category()),required(input.owner(),"实验人不能为空"),input.experimentDate()==null?LocalDate.now():input.experimentDate(),"DRAFT",input.templateName(),input.templateVersion(),input.workbookContent(),0,now,now);
  mapper.insertExperiment(experiment); return experiment;
 }
 @Transactional
 public ProjectExperiment updateExperiment(UUID id,ExperimentInput input,long version){
  ProjectExperiment existing=mapper.experiment(id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验不存在"));
  int rows=mapper.updateExperiment(id,required(input.title(),"实验标题不能为空"),clean(input.category()),required(input.owner(),"实验人不能为空"),input.experimentDate(),version);
  if(rows==0) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"实验已被他人修改，请刷新后重试");
  return mapper.experiment(id).orElseThrow();
 }
@Transactional
public void deleteExperiment(UUID id,long version){
  mapper.experiment(id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验不存在"));
  int rows=mapper.deleteExperiment(id,version);
  if(rows==0) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"实验已被他人修改，请刷新后重试");
 }
private static String code(String prefix){return prefix+"-"+LocalDate.now().toString().replace("-","")+"-"+UUID.randomUUID().toString().substring(0,5).toUpperCase();}
 private static String clean(String v){return v==null||v.isBlank()?null:v.trim();}
 private static String required(String v,String message){String x=clean(v);if(x==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,message);return x;}
 public record TaskInput(UUID stageId,String name,String owner,LocalDate plannedDate,String status,Long version){}
 public record ExperimentInput(String title,String category,String owner,LocalDate experimentDate,String templateName,String templateVersion,String workbookContent){}
}
