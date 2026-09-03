package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.port.ResearchTestRepository;
import com.jsd.aird.rnd.domain.ResearchTestModels.*;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ResearchTestService {
    private static final long MAX_FILE_SIZE=100L*1024*1024;
    private final ResearchTestRepository repository;private final FileStorageFacade files;
    private final ExperimentImportService sourceParser;
    public ResearchTestService(ResearchTestRepository repository,FileStorageFacade files){this(repository,files,null);}
    @Autowired public ResearchTestService(ResearchTestRepository repository,FileStorageFacade files,ExperimentImportService sourceParser){this.repository=repository;this.files=files;this.sourceParser=sourceParser;}
    public PageResponse<Summary> search(Type type,String keyword,String category,String status,String owner,UUID projectId,LocalDate from,LocalDate to,int page,int size){var a=ActorContext.required();return repository.search(a.organizationId(),new ResearchTestRepository.Search(type,keyword,category,status,owner,projectId,from,to,Math.max(1,page),Math.min(100,Math.max(1,size))));}
    public Detail detail(UUID id){var a=ActorContext.required();return repository.detail(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"研发测试记录不存在"));}
    public Detail create(CreateCommand c){var a=ActorContext.required();validateCreate(c);return repository.create(new ResearchTestRepository.Create(a.organizationId(),a.userId(),a.username(),c.type(),c.businessNo(),c.name(),c.category(),c.scope(),blank(c.ownerName())?a.username():c.ownerName(),c.date()==null?LocalDate.now():c.date(),upper(c.format()),upper(c.sourceType()),blank(c.visibility())?"ALL":upper(c.visibility()),c.projectId(),c.stageId(),c.taskId(),c.sourceFileId(),c.templateVersionId(),c.templateHash(),c.templateSnapshot(),c.editModel(),c.memberSnapshot(),c.effectiveFrom(),c.effectiveTo()));}
    public Detail save(UUID id,ResearchTestRepository.Draft d){var a=ActorContext.required();if(blank(d.businessNo())||blank(d.name()))invalid("编号和名称不能为空");validateDates(d.effectiveFrom(),d.effectiveTo());return repository.save(a.organizationId(),id,d,a.userId(),a.username());}
    public Detail rename(UUID id, RenameCommand c){
        var a=ActorContext.required();
        if (blank(c.name())) invalid("名称不能为空");
        return repository.rename(a.organizationId(), id, c.name().trim(), c.projectId(), c.stageId(), c.taskId(),
                c.revision(), a.userId(), a.username());
    }
    public Detail transition(UUID id,long revision,Status status,String comment){var a=ActorContext.required();return repository.transition(a.organizationId(),id,revision,status,comment,a.userId(),a.username());}
    public Detail revision(UUID id,long revision,String reason){var a=ActorContext.required();return repository.createRevision(a.organizationId(),id,revision,reason,a.userId(),a.username());}
    public Detail copy(UUID id){var a=ActorContext.required();return repository.copy(a.organizationId(),id,a.userId(),a.username());}
    public void delete(UUID id,long revision){var a=ActorContext.required();repository.delete(a.organizationId(),id,revision,a.userId(),a.username());}
    public List<Version> versions(UUID id){var a=ActorContext.required();return repository.versions(a.organizationId(),id);}
    public List<Audit> audits(UUID id){var a=ActorContext.required();return repository.audits(a.organizationId(),id);}
    @Transactional public Upload upload(UploadCommand c){var a=ActorContext.required();validateFile(c);try(var ignored=files.open(a.organizationId(),c.fileId())){}catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(ApiErrorCode.FILE_NOT_READY,"上传文件不可读取");}
        var sourceFormat=formatOf(c.originalName());
        var format=sourceFormat==TemplateFormat.IMAGE?"EXCEL":isExcel(c.originalName())?"EXCEL":"WORD";
        var edit=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("documentFormat",format.toLowerCase(Locale.ROOT)).put("sourceFileId",c.fileId().toString()).put("sourceFileName",c.originalName()).put("sourceContentType",c.contentType());
        if(sourceParser!=null){var parsed=sourceParser.parseSourceFile(a.organizationId(),c.fileId(),c.originalName(),c.sha256(),sourceFormat);if(parsed.initialEditorSnapshot()!=null&&parsed.initialEditorSnapshot().isObject())edit.set("documentSnapshot",parsed.initialEditorSnapshot());if((sourceFormat==TemplateFormat.IMAGE||sourceFormat==TemplateFormat.PDF)&&parsed.structureSummary()!=null)edit.set("ocrResult",parsed.structureSummary());}
        var detail=create(new CreateCommand(Type.REPORT,null,baseName(c.originalName()),c.category(),null,c.ownerName(),LocalDate.now(),format,"UPLOAD",c.visibility(),c.projectId(),c.stageId(),c.taskId(),c.fileId(),null,null,null,edit,null,null,null));
        return repository.addUpload(a.organizationId(),a.userId(),detail.summary().id(),c.fileId(),c.originalName(),c.contentType(),c.size(),c.sha256());}
    public PageResponse<Upload> uploads(String keyword,int page,int size){var a=ActorContext.required();return repository.uploads(a.organizationId(),keyword,Math.max(1,page),Math.min(100,Math.max(1,size)));}
    /** Revalidates the source attachment and refreshes the upload ledger row. */
    public Upload retryUpload(UUID id){
        var a=ActorContext.required();
        var current=repository.findUpload(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"研发测试上传记录不存在"));
        try(var ignored=files.open(a.organizationId(),current.fileId())){}catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(ApiErrorCode.FILE_NOT_READY,"上传文件不可读取");}
        return repository.retryUpload(a.organizationId(),id);
    }
    public void deleteUpload(UUID id){repository.deleteUpload(ActorContext.required().organizationId(),id);}
    private void validateCreate(CreateCommand c){if(c.type()==null||blank(c.name()))invalid("类型和名称不能为空");if(!List.of("WORD","EXCEL").contains(upper(c.format())))invalid("文档格式仅支持 WORD 或 EXCEL");if(c.type()==Type.STANDARD&&blank(c.businessNo()))invalid("标准编号不能为空");if(c.type()==Type.STANDARD&&c.effectiveFrom()==null)invalid("测试标准必须填写生效日期");validateDates(c.effectiveFrom(),c.effectiveTo());}
    private void validateDates(LocalDate from,LocalDate to){if(from!=null&&to!=null&&to.isBefore(from))invalid("失效日期不能早于生效日期");}
    private void validateFile(UploadCommand c){if(c.fileId()==null||blank(c.originalName())||c.size()<=0)invalid("文件不能为空");if(c.size()>MAX_FILE_SIZE)invalid("单个文件不能超过 100MB");var n=c.originalName().toLowerCase(Locale.ROOT);if(!n.matches(".*\\.(pdf|doc|docx|xls|xlsx|csv|png|jpg|jpeg|gif|webp|bmp|tif|tiff)$"))invalid("不支持的文件格式");}
    private static boolean isExcel(String n){return n.toLowerCase(Locale.ROOT).matches(".*\\.(xls|xlsx|csv)$");}
    private static TemplateFormat formatOf(String name){var n=name.toLowerCase(Locale.ROOT);if(n.endsWith(".xlsx"))return TemplateFormat.XLSX;if(n.endsWith(".xls"))return TemplateFormat.XLS;if(n.endsWith(".csv"))return TemplateFormat.CSV;if(n.endsWith(".doc")||n.endsWith(".docx"))return TemplateFormat.DOCX;if(n.endsWith(".pdf"))return TemplateFormat.PDF;return TemplateFormat.IMAGE;}
    private static String baseName(String n){return n.replaceFirst("(?i)\\.[^.]+$","");}private static boolean blank(String s){return s==null||s.isBlank();}private static String upper(String s){return s==null?"":s.toUpperCase(Locale.ROOT);}private static void invalid(String m){throw new ApiException(ApiErrorCode.VALIDATION_ERROR,m);}
    public record CreateCommand(Type type,String businessNo,String name,String category,String scope,String ownerName,LocalDate date,String format,String sourceType,String visibility,UUID projectId,UUID stageId,UUID taskId,UUID sourceFileId,UUID templateVersionId,String templateHash,JsonNode templateSnapshot,JsonNode editModel,JsonNode memberSnapshot,LocalDate effectiveFrom,LocalDate effectiveTo){}
    public record RenameCommand(long revision, String name, UUID projectId, UUID stageId, UUID taskId) {}
    public record UploadCommand(UUID fileId,String originalName,String contentType,long size,String sha256,String category,String ownerName,String visibility,UUID projectId,UUID stageId,UUID taskId){}
}
