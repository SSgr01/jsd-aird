package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.data.application.ExperimentImportAssembler.AssemblyPlan;
import com.jsd.aird.data.application.ExperimentImportAssembler.Candidate;
import com.jsd.aird.data.application.port.ExperimentAssemblyRepository;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class ExperimentAssemblyService {
    private final ExperimentAssemblyRepository repository;
    private final ExperimentImportAssembler assembler;
    private final TemplateDataImportFacade templates;
    private final ExperimentDraftFacade experiments;
    private final DataWorkbookService workbooks;
    private final ProjectResourceFacade projects;
    private final OpsAsyncFacade jobs;
    private final AuthorizationService authorization;
    private final ObjectMapper json;

    public ExperimentAssemblyService(ExperimentAssemblyRepository repository, ExperimentImportAssembler assembler,
                                     TemplateDataImportFacade templates, ExperimentDraftFacade experiments,
                                     DataWorkbookService workbooks,
                                     ProjectResourceFacade projects, OpsAsyncFacade jobs,
                                     AuthorizationService authorization, ObjectMapper json) {
        this.repository=repository;this.assembler=assembler;this.templates=templates;this.experiments=experiments;
        this.workbooks=workbooks;
        this.projects=projects;this.jobs=jobs;this.authorization=authorization;this.json=json;
    }

    public AssemblyPlan preview(UUID importJobId) {
        var actor=ActorContext.required();
        return plan(actor.organizationId(),importJobId,actor.userId());
    }

    public SyncResult sync(UUID importJobId,UUID categoryId,List<String> selectedKeys) {
        var actor=ActorContext.required();
        requireExperimentCreate(actor.organizationId(),actor.userId());
        var source=repository.load(actor.organizationId(),importJobId);
        var targetCategory=categoryId==null?source.targetExperimentCategoryId():categoryId;
        experiments.requireActiveCategory(actor.organizationId(),targetCategory);
        var plan=plan(actor.organizationId(),importJobId,actor.userId());
        var links=projects.links(actor,ProjectResourceFacade.ResourceType.DATA_IMPORT_JOB,importJobId);
        var project=links.size()==1?links.getFirst():null;
        var keys=selectedKeys==null?Set.<String>of():new HashSet<>(selectedKeys);
        var accepted=new ArrayList<UUID>();var count=0;
        for(var candidate:plan.candidates()){
            if(!"READY".equals(candidate.status())||(!keys.isEmpty()&&!keys.contains(candidate.assemblyKey())))continue;
            var payload=json.createObjectNode().put("organizationId",actor.organizationId().toString())
                    .put("actorId",actor.userId().toString()).put("actorName",actor.username())
                    .put("importJobId",importJobId.toString()).put("assemblyKey",candidate.assemblyKey())
                    .put("planHash",candidate.planHash()).put("categoryId",targetCategory.toString());
            if(project!=null){payload.put("projectId",project.projectId().toString());
                if(project.stageId()!=null)payload.put("stageId",project.stageId().toString());
                if(project.taskId()!=null)payload.put("taskId",project.taskId().toString());}
            var id=jobs.enqueue(actor.organizationId(),"DATA_TO_EXPERIMENT_SYNC",payload,
                    "data-experiment:"+importJobId+":"+candidate.assemblyKey()+":"+candidate.planHash(),40);
            accepted.add(id);count++;
        }
        if(count==0){
            var alreadySynced=plan.candidates().stream().anyMatch(candidate ->
                    "SYNCED".equals(candidate.status())&&(keys.isEmpty()||keys.contains(candidate.assemblyKey())));
            if(alreadySynced)return new SyncResult(0,List.of());
            throw validation("当前文件没有可创建的实验草稿");
        }
        return new SyncResult(count,List.copyOf(accepted));
    }

    public SyncStatus status(UUID importJobId) {
        var actor=ActorContext.required();var plan=plan(actor.organizationId(),importJobId,actor.userId());
        return new SyncStatus(plan,repository.links(actor.organizationId(),importJobId));
    }

    @Transactional
    public ExperimentDraftFacade.ImportedDraft execute(UUID organizationId,UUID actorId,String actorName,
                                                        UUID importJobId,String assemblyKey,String expectedPlanHash,
                                                        UUID categoryId,UUID projectId,UUID stageId,UUID taskId) {
        requireExperimentCreate(organizationId,actorId);
        experiments.requireActiveCategory(organizationId,categoryId);
        var plan=plan(organizationId,importJobId,actorId);
        var candidate=plan.candidates().stream().filter(c->c.assemblyKey().equals(assemblyKey)).findFirst()
                .orElseThrow(()->validation("组装计划已变化，请重新预览"));
        if(!candidate.planHash().equals(expectedPlanHash))throw validation("组装计划已变化，请重新预览");
        repository.lockAssembly(organizationId,importJobId,assemblyKey);
        var existing=repository.find(organizationId,importJobId,assemblyKey).orElseThrow();
        if(existing.experimentId()!=null)return new ExperimentDraftFacade.ImportedDraft(existing.experimentId(),
                existing.experimentVersionId(),existing.experimentNo(),candidate.editModel().path("title").asText());
        if(!"READY".equals(candidate.status()))throw validation("组装计划仍有冲突或已在处理中，请重新预览");
        if(!repository.markRunning(organizationId,importJobId,assemblyKey))throw validation("该组装项已被处理");
        var source=repository.load(organizationId,importJobId);
        var template=templates.getExperimentTemplateVersion(organizationId,source.templateVersionId());
        var model=(com.fasterxml.jackson.databind.node.ObjectNode)candidate.editModel().deepCopy();
        model.set("documentSnapshot",workbooks.sourceSnapshotForExperiment(organizationId,importJobId));
        var created=experiments.createImportedDraft(new ExperimentDraftFacade.ImportedDraftCommand(
                organizationId,actorId,actorName,model.path("title").asText(),categoryId,"EXCEL_IMPORT",
                projectId,stageId,taskId,blank(model.path("sourceOwnerName").asText()),date(model.path("sourceExperimentDate").asText()),
                template.versionId(),template.snapshotHash(),template.snapshot(),model));
        repository.markSynced(organizationId,importJobId,assemblyKey,created.experimentId(),created.experimentVersionId(),created.experimentNo());
        return created;
    }

    public void fail(UUID org,UUID job,String key,Exception error){repository.markFailed(org,job,key,error.getMessage());}

    private AssemblyPlan plan(UUID org,UUID job,UUID actor){
        var source=repository.load(org,job);if(!"COMPLETED".equals(source.status()))throw validation("数据导入尚未提交完成");
        if(source.importContractVersion()!=9)throw validation("只有V9实验数据模板可以生成实验草稿");
        var definition=templates.getVersion(org,source.templateVersionId());var contract=definition.importContract();
        if(contract==null||!"EXPERIMENT_DATA".equals(contract.path("templateUsage").asText()))throw validation("当前模板不是实验数据模板");
        var plan=assembler.assemble(source,contract.path("experimentImport"));
        plan.candidates().forEach(c->repository.upsertPlan(org,job,new ExperimentAssemblyRepository.PlanRow(
                c.assemblyKey(),c.parentAssemblyKey(),c.sourceIdentity(),c.sourceIdentityType(),json.valueToTree(c.sourceRecordKeys()),
                json.valueToTree(c.sharedContextRecordKeys()),c.planHash(),c.contentHash(),c.status(),c.conflicts(),c.warnings()),actor));
        repository.supersedeOtherPlans(org,job,plan.candidates().getFirst().assemblyKey());
        var persisted=repository.links(org,job).stream().collect(java.util.stream.Collectors.toMap(
                ExperimentAssemblyRepository.Link::assemblyKey,item->item,(a,b)->a));
        var candidates=plan.candidates().stream().map(c->{
            var link=persisted.get(c.assemblyKey());
            if(link==null||Set.of("READY","NEEDS_REVIEW","BLOCKED").contains(link.status()))return c;
            return new Candidate(c.assemblyKey(),c.parentAssemblyKey(),c.sourceIdentity(),c.sourceIdentityType(),
                    link.status(),c.sourceRecordKeys(),c.sharedContextRecordKeys(),c.conflicts(),c.warnings(),
                    c.editModel(),c.planHash(),c.contentHash());
        }).toList();
        return new AssemblyPlan(plan.importJobId(),candidates,
                (int)candidates.stream().filter(c->"READY".equals(c.status())).count(),
                (int)candidates.stream().filter(c->"NEEDS_REVIEW".equals(c.status())).count(),
                (int)candidates.stream().filter(c->"BLOCKED".equals(c.status())).count(),
                plan.sourceRecordCount(),plan.sampleGroupCount(),plan.sourceGroupCount(),
                plan.sourceContextCount(),plan.sourceIdentityCount(),plan.logicalSampleCount());
    }
    private void requireExperimentCreate(UUID organizationId,UUID userId){
        authorization.require(new PermissionCheck(organizationId,userId,"experiment.create",
                "EXPERIMENT",null,"CREATE"));
    }
    private static String blank(String value){return value==null||value.isBlank()?null:value.strip();}
    private static final List<DateTimeFormatter> SOURCE_DATE_FORMATS=List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("uuuu.M.d"),
            DateTimeFormatter.ofPattern("uuuu/M/d"),
            DateTimeFormatter.ofPattern("uuuu年M月d日"));
    private static LocalDate date(String value){
        if(value==null||value.isBlank())return null;
        var normalized=value.strip();
        for(var format:SOURCE_DATE_FORMATS){
            try{return LocalDate.parse(normalized,format);}catch(DateTimeParseException ignored){}
        }
        return null;
    }
    private static ApiException validation(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
    public record SyncResult(int acceptedCount,List<UUID> jobIds){}
    public record SyncStatus(AssemblyPlan plan,List<ExperimentAssemblyRepository.Link> links){}
}
