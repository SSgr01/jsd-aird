package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.data.application.ExperimentImportAssembler.AssemblyPlan;
import com.jsd.aird.data.application.ExperimentImportAssembler.Candidate;
import com.jsd.aird.data.application.port.ExperimentAssemblyRepository;
import com.jsd.aird.recognition.application.port.SourceRecognitionRepository;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.ops.application.port.AsyncJobHandler.ExecutionContext;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final SourceRecognitionRepository recognition;
    private final TransactionTemplate writes;
    private com.jsd.aird.rnd.api.ExperimentStatusFacade experimentStatuses;
    @org.springframework.beans.factory.annotation.Autowired
    public void setExperimentStatuses(com.jsd.aird.rnd.api.ExperimentStatusFacade statuses) { this.experimentStatuses=statuses; }

    public ExperimentAssemblyService(ExperimentAssemblyRepository repository, ExperimentImportAssembler assembler,
                                     TemplateDataImportFacade templates, ExperimentDraftFacade experiments,
                                     DataWorkbookService workbooks,
                                     ProjectResourceFacade projects, OpsAsyncFacade jobs,
                                     AuthorizationService authorization, ObjectMapper json,
                                     SourceRecognitionRepository recognition, PlatformTransactionManager transactions) {
        this.repository=repository;this.assembler=assembler;this.templates=templates;this.experiments=experiments;
        this.workbooks=workbooks;
        this.projects=projects;this.jobs=jobs;this.authorization=authorization;this.json=json;
        this.recognition=recognition;
        this.writes=new TransactionTemplate(transactions);
        this.writes.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public AssemblyPlan preview(UUID importJobId) {
        var actor=ActorContext.required();
        return plan(actor.organizationId(),importJobId,actor.userId());
    }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public SyncResult sync(UUID importJobId,UUID categoryId,List<String> selectedKeys) {
        var actor=ActorContext.required();
        requireExperimentCreate(actor.organizationId(),actor.userId());
        var source=repository.load(actor.organizationId(),importJobId);
        var targetCategory=categoryId==null?source.targetExperimentCategoryId():categoryId;
        if(targetCategory!=null)experiments.requireActiveCategory(actor.organizationId(),targetCategory);
        var plan=plan(actor.organizationId(),importJobId,actor.userId());
        var links=projects.links(actor,ProjectResourceFacade.ResourceType.DATA_IMPORT_JOB,importJobId);
        var project=links.size()==1?links.getFirst():null;
        var keys=selectedKeys==null?Set.<String>of():new HashSet<>(selectedKeys);
        var accepted=new ArrayList<UUID>();var count=0;boolean alreadyCreated=false;
        for(var candidate:plan.candidates()){
            if(!keys.isEmpty()&&!keys.contains(candidate.assemblyKey()))continue;
            boolean retry="FAILED".equals(candidate.status())&&keys.contains(candidate.assemblyKey());
            if(!retry&&!Set.of("READY","RUNNING").contains(candidate.status()))continue;
            var acceptedId=writes.execute(tx->{
                repository.lockAssembly(actor.organizationId(),importJobId,candidate.assemblyKey());
                var existing=repository.find(actor.organizationId(),importJobId,candidate.assemblyKey()).orElseThrow();
                if(existing.experimentId()!=null)return null;
                if(!candidate.planHash().equals(existing.planHash()))throw validation("组装计划已变化，请重新预览");
                var key=jobKey(importJobId,candidate.assemblyKey(),candidate.planHash());
                var previous=latestJob(actor.organizationId(),importJobId,candidate.assemblyKey(),candidate.planHash()).orElse(null);
                if(previous!=null&&!previous.terminal())return previous.id();
                if(!retry&&("RUNNING".equals(existing.status())||previous!=null&&"SUCCEEDED".equals(previous.status())))
                    throw validation("先前任务结果尚未核对，请刷新后查看实验记录");
                if(retry){
                    if(previous!=null&&!Set.of("FAILED","CANCELLED").contains(previous.status()))
                        throw validation("先前任务结果尚未核对，请刷新后查看实验记录");
                    key+=":retry:"+(previous==null?"initial":previous.id());
                    if(!repository.prepareRetry(actor.organizationId(),importJobId,candidate.assemblyKey(),candidate.planHash()))
                        throw validation("实验草稿状态已变化，请刷新后重试");
                }
                var payload=json.createObjectNode().put("organizationId",actor.organizationId().toString())
                        .put("actorId",actor.userId().toString()).put("actorName",actor.username())
                        .put("importJobId",importJobId.toString()).put("assemblyKey",candidate.assemblyKey())
                        .put("planHash",candidate.planHash());
                if(targetCategory!=null)payload.put("categoryId",targetCategory.toString());
                if(project!=null){payload.put("projectId",project.projectId().toString());
                    if(project.stageId()!=null)payload.put("stageId",project.stageId().toString());
                    if(project.taskId()!=null)payload.put("taskId",project.taskId().toString());}
                return jobs.enqueue(actor.organizationId(),"DATA_TO_EXPERIMENT_SYNC",payload,
                        key,40);
            });
            if(acceptedId==null)alreadyCreated=true;
            else {accepted.add(acceptedId);count++;}
        }
        if(count==0){
            var alreadySynced=plan.candidates().stream().anyMatch(candidate ->
                    "SYNCED".equals(candidate.status())&&(keys.isEmpty()||keys.contains(candidate.assemblyKey())));
            if(alreadyCreated||alreadySynced)return new SyncResult(0,List.of());
            throw validation("当前文件没有可创建的实验草稿");
        }
        return new SyncResult(count,List.copyOf(accepted));
    }

    public SyncStatus status(UUID importJobId) {
        var actor=ActorContext.required();var plan=plan(actor.organizationId(),importJobId,actor.userId(),false);
        var links=repository.links(actor.organizationId(),importJobId).stream()
                .map(link->reconcile(actor.organizationId(),importJobId,link)).toList();
        var visible=experimentStatuses==null?List.<com.jsd.aird.rnd.api.ExperimentStatusFacade.Summary>of()
                :experimentStatuses.current(links.stream().map(ExperimentAssemblyRepository.Link::experimentId).filter(Objects::nonNull).toList());
        var ids=visible.stream().map(com.jsd.aird.rnd.api.ExperimentStatusFacade.Summary::experimentId).collect(java.util.stream.Collectors.toSet());
        if(experimentStatuses!=null)links=links.stream().map(link->link.experimentId()==null||ids.contains(link.experimentId())?link:
                new ExperimentAssemblyRepository.Link(link.assemblyKey(),link.parentAssemblyKey(),link.status(),link.resolutionAction(),
                        link.resolutionReason(),null,null,null,link.planHash(),link.contentHash(),link.errorMessage())).toList();
        return new SyncStatus(plan,links,visible);
    }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public ExperimentDraftFacade.ImportedDraft execute(UUID organizationId,UUID actorId,String actorName,
                                                        UUID importJobId,String assemblyKey,String expectedPlanHash,
                                                        UUID categoryId,UUID projectId,UUID stageId,UUID taskId,
                                                        ExecutionContext context) {
        requireExperimentCreate(organizationId,actorId);
        if(categoryId!=null)experiments.requireActiveCategory(organizationId,categoryId);
        var already=repository.find(organizationId,importJobId,assemblyKey).orElse(null);
        if(already!=null&&already.experimentId()!=null)return created(already);
        var source=repository.load(organizationId,importJobId);
        var plan=plan(organizationId,source,actorId,true);
        var candidate=plan.candidates().stream().filter(c->c.assemblyKey().equals(assemblyKey)).findFirst()
                .orElseThrow(()->validation("组装计划已变化，请重新预览"));
        if(!candidate.planHash().equals(expectedPlanHash))throw validation("组装计划已变化，请重新预览");
        if(!Set.of("READY","RUNNING").contains(candidate.status()))throw validation("组装计划仍有冲突或已在处理中，请重新预览");
        /*
         * The isolated local acceptance profile deliberately uses local object
         * storage while published template rows may still point at a MinIO
         * object from another environment.  The uploaded workbook is already
         * the immutable source for this import, so it is a safe snapshot
         * fallback when only the published template snapshot object is
         * unavailable.  Other template/configuration failures still surface.
         */
        var sourceSnapshot = workbooks.sourceSnapshotForExperiment(organizationId, importJobId);
        UUID templateVersionId = source.templateVersionId();
        String templateSnapshotHash = source.sourceSha256();
        com.fasterxml.jackson.databind.JsonNode templateSnapshot = sourceSnapshot;
        try {
            var template=templates.getExperimentTemplateVersion(organizationId,source.templateVersionId());
            templateVersionId = template.versionId();
            templateSnapshotHash = template.snapshotHash();
            templateSnapshot = template.snapshot();
        } catch (ApiException exception) {
            if (exception.errorCode() != ApiErrorCode.FILE_NOT_READY) throw exception;
        }
        var prepared=new Prepared(source,sourceSnapshot,templateVersionId,templateSnapshotHash,templateSnapshot);
        var draft=experiments.prepareImportedDraft(draftCommand(organizationId,actorId,actorName,categoryId,
                projectId,stageId,taskId,candidate,prepared));
        try (var timing = new com.jsd.aird.shared.observability.OperationTiming("experiment-generation-write")) {
        return Objects.requireNonNull(timing.phase("transaction", () -> writes.execute(tx->{
            jobs.requireExecution(context,false);
            timing.checkpoint("lease-lock");
            requireExperimentCreate(organizationId,actorId);
            if(categoryId!=null)experiments.requireActiveCategory(organizationId,categoryId);
            repository.lockSource(organizationId,importJobId);
            repository.lockAssembly(organizationId,importJobId,assemblyKey);
            timing.checkpoint("source-and-assembly-lock");
            var existing=repository.find(organizationId,importJobId,assemblyKey).orElseThrow();
            if(existing.experimentId()!=null)return created(existing);
            var currentSource=repository.load(organizationId,importJobId);
            if(!expectedPlanHash.equals(existing.planHash())||!candidate.contentHash().equals(existing.contentHash())||!source.equals(currentSource))
                throw validation("读取文件期间导入资料已变化，请重新预览后生成");
            if(!repository.markRunning(organizationId,importJobId,assemblyKey))throw validation("该组装项已被处理");
            timing.checkpoint("source-recheck");
            var created=saveDraft(organizationId,actorId,importJobId,assemblyKey,candidate,prepared,draft);
            timing.checkpoint("create-and-link");
            return created;
        })));
        }
    }

    private ExperimentDraftFacade.ImportedDraftCommand draftCommand(UUID organizationId,UUID actorId,String actorName,
            UUID categoryId,UUID projectId,UUID stageId,UUID taskId,Candidate candidate,Prepared prepared) {
        var source=prepared.source();
        var sourceSnapshot=prepared.sourceSnapshot();
        var model=(com.fasterxml.jackson.databind.node.ObjectNode)candidate.editModel().deepCopy();
        model.set("documentSnapshot",sourceSnapshot);
        model.put("sourceFileId", source.sourceFileId().toString());
        model.put("sourceFileName", source.sourceFileName());
        model.put("documentFormat", "excel");
        return new ExperimentDraftFacade.ImportedDraftCommand(
                organizationId,actorId,actorName,model.path("title").asText(),categoryId,
                source.sourceFileName().toLowerCase(java.util.Locale.ROOT)
                        .matches(".*\\.(docx|png|jpg|jpeg|gif|webp|bmp|tif|tiff)")
                        ?"OCR_IMPORT":"EXCEL_IMPORT",
                projectId,stageId,taskId,blank(model.path("sourceOwnerName").asText()),date(model.path("sourceExperimentDate").asText()),
                prepared.templateVersionId(),prepared.templateSnapshotHash(),prepared.templateSnapshot(),model,sourceSnapshot.path("importConfirmation"));
    }

    private ExperimentDraftFacade.ImportedDraft saveDraft(UUID organizationId,UUID actorId,UUID importJobId,String assemblyKey,
            Candidate candidate,Prepared prepared,ExperimentDraftFacade.PreparedImportedDraft draft) {
        var model=draft.command().editModel();
        var created=experiments.createPreparedImportedDraft(draft);
        repository.markSynced(organizationId,importJobId,assemblyKey,created.experimentId(),created.experimentVersionId(),created.experimentNo());
        var coordinates=json.createObjectNode();
        coordinates.set("sourceRecordKeys",json.valueToTree(candidate.sourceRecordKeys()));
        coordinates.set("sharedContextRecordKeys",json.valueToTree(candidate.sharedContextRecordKeys()));
        var snapshot=json.createObjectNode().put("planHash",candidate.planHash())
                .put("contentHash",candidate.contentHash()).put("sourceIdentity",candidate.sourceIdentity());
        snapshot.set("editModel",model.deepCopy());
        var sourceGroups=candidate.sourceRecordKeys().isEmpty()?java.util.List.of(assemblyKey):candidate.sourceRecordKeys();
        var sampleBoundaryId=assemblyKey+"/sample-1";
        var logicalSampleKey="SOURCE:"+importJobId+":"+sampleBoundaryId;
        var sample=new SourceRecognitionRepository.RecognizedSample(assemblyKey,sampleBoundaryId,
                logicalSampleKey,sourceGroups,model.path("title").asText(),model.deepCopy(),json.createObjectNode(),
                model.deepCopy(),coordinates,candidate.contentHash());
        recognition.linkExperiment(organizationId,importJobId,
                recognition.currentSubmissionId(organizationId,importJobId).orElse(null),assemblyKey,
                created.experimentId(),created.experimentVersionId(),created.experimentNo(),coordinates,snapshot,
                java.util.List.of(sample),candidate.contentHash(),actorId);
        return created;
    }

    public void fail(UUID org,UUID job,String key,String planHash,Exception error,ExecutionContext context){
        writes.executeWithoutResult(tx->{
            jobs.requireExecution(context,true);
            repository.lockAssembly(org,job,key);
            var latest=latestJob(org,job,key,planHash).orElse(null);
            if(context!=null&&latest!=null&&!latest.id().equals(context.jobId()))return;
            repository.markFailed(org,job,key,planHash,failureMessage(error));
        });
    }

    private static String legacyJobKey(UUID job,String key,String hash){return "data-experiment:"+job+":"+key+":"+hash;}
    private static String jobKey(UUID job,String key,String hash){
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256").digest(
                    legacyJobKey(job,key,hash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "data-experiment:"+HexFormat.of().formatHex(digest);
        } catch(java.security.NoSuchAlgorithmException exception){throw new IllegalStateException(exception);}
    }
    private Optional<OpsAsyncFacade.AsyncJobView> latestJob(UUID org,UUID job,String key,String hash){
        var latest=jobs.findLatestJob(org,jobKey(job,key,hash));
        return latest.isPresent()?latest:jobs.findLatestJob(org,legacyJobKey(job,key,hash));
    }
    private static String failureMessage(Exception error){
        return error instanceof ApiException&&error.getMessage()!=null?error.getMessage():"实验草稿生成失败，请重试；若仍未完成，请联系管理员。";
    }
    private static ExperimentDraftFacade.ImportedDraft created(ExperimentAssemblyRepository.Link link){
        return new ExperimentDraftFacade.ImportedDraft(link.experimentId(),link.experimentVersionId(),link.experimentNo(),link.experimentNo());
    }
    private record Prepared(ExperimentAssemblyRepository.SourceImport source,com.fasterxml.jackson.databind.JsonNode sourceSnapshot,
            UUID templateVersionId,String templateSnapshotHash,com.fasterxml.jackson.databind.JsonNode templateSnapshot){}

    private AssemblyPlan plan(UUID org,UUID job,UUID actor){
        return plan(org,job,actor,true);
    }

    private AssemblyPlan plan(UUID org,UUID job,UUID actor,boolean persist){
        return plan(org,repository.load(org,job),actor,persist);
    }

    private AssemblyPlan plan(UUID org,ExperimentAssemblyRepository.SourceImport source,UUID actor,boolean persist){
        var job=source.id();if(!"COMPLETED".equals(source.status()))throw validation("数据导入尚未提交完成");
        if(source.importContractVersion()!=9)throw validation("只有V9实验数据模板可以生成实验草稿");
        var definition=templates.getVersion(org,source.templateVersionId());var contract=definition.importContract();
        if(contract==null||!"EXPERIMENT_DATA".equals(contract.path("templateUsage").asText()))throw validation("当前模板不是实验数据模板");
        var plan=assembler.assemble(source,contract.path("experimentImport"));
        if(persist)plan.candidates().forEach(c->repository.upsertPlan(org,job,new ExperimentAssemblyRepository.PlanRow(
                c.assemblyKey(),c.parentAssemblyKey(),c.sourceIdentity(),c.sourceIdentityType(),json.valueToTree(c.sourceRecordKeys()),
                json.valueToTree(c.sharedContextRecordKeys()),c.planHash(),c.contentHash(),c.status(),c.conflicts(),c.warnings()),actor));
        if(persist)repository.supersedeOtherPlans(org,job,plan.candidates().getFirst().assemblyKey());
        var persisted=repository.links(org,job).stream().map(link->reconcile(org,job,link)).collect(java.util.stream.Collectors.toMap(
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

    private ExperimentAssemblyRepository.Link reconcile(UUID org,UUID job,ExperimentAssemblyRepository.Link link){
        if(link.experimentId()!=null||Set.of("SKIPPED","SYNCED","ALREADY_CREATED").contains(link.status()))return link;
        var latest=latestJob(org,job,link.assemblyKey(),link.planHash()).orElse(null);
        if(latest==null)return link;
        String status=link.status(),error=link.errorMessage();
        if(Set.of("FAILED","CANCELLED").contains(latest.status())){
            error="CANCELLED".equals(latest.status())?"实验草稿生成已取消，可重新发起。":
                    link.errorMessage()!=null?link.errorMessage():"实验草稿生成失败，请重试；若仍未完成，请联系管理员。";
            status="FAILED";
        } else if(Set.of("READY","RUNNING").contains(latest.status())){status="RUNNING";error=null;}
        return new ExperimentAssemblyRepository.Link(link.assemblyKey(),link.parentAssemblyKey(),status,link.resolutionAction(),
                link.resolutionReason(),link.experimentId(),link.experimentVersionId(),link.experimentNo(),link.planHash(),link.contentHash(),error);
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
    public record SyncStatus(AssemblyPlan plan,List<ExperimentAssemblyRepository.Link> links,List<com.jsd.aird.rnd.api.ExperimentStatusFacade.Summary> experiments){}
}
