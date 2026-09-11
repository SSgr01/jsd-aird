package com.jsd.aird.rnd.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.ResearchTestService;
import com.jsd.aird.rnd.application.ResearchTestExportService;
import com.jsd.aird.rnd.application.port.ResearchTestRepository;
import com.jsd.aird.rnd.domain.ResearchTestModels.*;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class ResearchTestController {
    private final ResearchTestService service;private final ResearchTestExportService exportService;
    public ResearchTestController(ResearchTestService service,ResearchTestExportService exportService){this.service=service;this.exportService=exportService;}
    @GetMapping("/comprehensive-reports") public ApiResponse<?> reports(@RequestParam(required=false)String keyword,@RequestParam(required=false)String category,@RequestParam(required=false)String status,@RequestParam(required=false)String ownerName,@RequestParam(required=false)UUID projectId,@RequestParam(required=false)LocalDate dateFrom,@RequestParam(required=false)LocalDate dateTo,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size){return ok(service.search(Type.REPORT,keyword,category,status,ownerName,projectId,dateFrom,dateTo,page,size));}
    @PostMapping("/comprehensive-reports") public ApiResponse<?> createReport(@Valid @RequestBody CreateRequest r){return ok(service.create(r.command(Type.REPORT)));}
    @PostMapping("/comprehensive-reports/generate") public ApiResponse<?> generate(@Valid @RequestBody CreateRequest r){return ok(service.create(r.command(Type.REPORT)));}
    @PostMapping("/comprehensive-reports/uploads") public ApiResponse<?> upload(@Valid @RequestBody UploadRequest r){return ok(service.upload(r.command(Type.REPORT)));}
    @GetMapping("/comprehensive-reports/uploads") public ApiResponse<?> uploads(@RequestParam(required=false)String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size){return ok(service.uploads(Type.REPORT,keyword,page,size));}
    @PostMapping("/comprehensive-reports/uploads/{id}/retry") public ApiResponse<?> retryUpload(@PathVariable UUID id){return ok(service.retryUpload(Type.REPORT,id));}
    @DeleteMapping("/comprehensive-reports/uploads/{id}") public ApiResponse<?> deleteUpload(@PathVariable UUID id){service.deleteUpload(Type.REPORT,id);return ok(null);}
    @GetMapping("/test-standards") public ApiResponse<?> standards(@RequestParam(required=false)String keyword,@RequestParam(required=false)String category,@RequestParam(required=false)String status,@RequestParam(required=false)String ownerName,@RequestParam(required=false)UUID projectId,@RequestParam(required=false)LocalDate dateFrom,@RequestParam(required=false)LocalDate dateTo,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size){return ok(service.search(Type.STANDARD,keyword,category,status,ownerName,projectId,dateFrom,dateTo,page,size));}
    @PostMapping("/test-standards") public ApiResponse<?> createStandard(@Valid @RequestBody CreateRequest r){return ok(service.create(r.command(Type.STANDARD)));}
    @PostMapping("/test-standards/uploads") public ApiResponse<?> uploadStandard(@Valid @RequestBody UploadRequest r){return ok(service.upload(r.command(Type.STANDARD)));}
    @GetMapping("/test-standards/uploads") public ApiResponse<?> standardUploads(@RequestParam(required=false)String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size){return ok(service.uploads(Type.STANDARD,keyword,page,size));}
    @PostMapping("/test-standards/uploads/{id}/retry") public ApiResponse<?> retryStandardUpload(@PathVariable UUID id){return ok(service.retryUpload(Type.STANDARD,id));}
    @DeleteMapping("/test-standards/uploads/{id}") public ApiResponse<?> deleteStandardUpload(@PathVariable UUID id){service.deleteUpload(Type.STANDARD,id);return ok(null);}
    @GetMapping({"/comprehensive-reports/{id}","/test-standards/{id}"}) public ApiResponse<?> detail(@PathVariable UUID id){return ok(service.detail(id));}
    @PostMapping({"/comprehensive-reports/{id}/draft","/test-standards/{id}/draft"}) public ApiResponse<?> draft(@PathVariable UUID id,@Valid @RequestBody DraftRequest r){return ok(service.save(id,r.command()));}
    @PutMapping({"/comprehensive-reports/{id}/rename","/test-standards/{id}/rename"}) public ApiResponse<?> rename(@PathVariable UUID id,@Valid @RequestBody RenameRequest r){return ok(service.rename(id,new ResearchTestService.RenameCommand(r.revision(),r.name(),r.businessNo(),r.ownerName(),r.date(),r.category(),r.scope(),r.effectiveFrom(),r.effectiveTo(),r.projectId(),r.stageId(),r.taskId())));}
    @PostMapping({"/comprehensive-reports/{id}/copy","/test-standards/{id}/copy"}) public ApiResponse<?> copy(@PathVariable UUID id){return ok(service.copy(id));}
    @DeleteMapping({"/comprehensive-reports/{id}","/test-standards/{id}"}) public ApiResponse<?> delete(@PathVariable UUID id,@RequestParam long revision){service.delete(id,revision);return ok(null);}
    @PostMapping({"/comprehensive-reports/{id}/submit-review","/test-standards/{id}/submit-review"}) public ApiResponse<?> submit(@PathVariable UUID id,@RequestBody ActionRequest r){return ok(service.transition(id,r.revision,Status.PENDING_REVIEW,r.comment));}
    @PostMapping({"/comprehensive-reports/{id}/approve","/comprehensive-reports/{id}/publish","/test-standards/{id}/approve","/test-standards/{id}/publish"}) public ApiResponse<?> publish(@PathVariable UUID id,@RequestBody ActionRequest r){return ok(service.transition(id,r.revision,Status.PUBLISHED,r.comment));}
    @PostMapping({"/comprehensive-reports/{id}/return","/test-standards/{id}/return"}) public ApiResponse<?> returned(@PathVariable UUID id,@RequestBody ActionRequest r){return ok(service.transition(id,r.revision,Status.RETURNED,r.comment));}
    @PostMapping({"/comprehensive-reports/{id}/archive","/test-standards/{id}/archive"}) public ApiResponse<?> archive(@PathVariable UUID id,@RequestBody ActionRequest r){return ok(service.transition(id,r.revision,Status.ARCHIVED,r.comment));}
    @PostMapping({"/comprehensive-reports/{id}/versions","/test-standards/{id}/versions"}) public ApiResponse<?> revision(@PathVariable UUID id,@RequestBody RevisionRequest r){return ok(service.revision(id,r.revision,r.reason));}
    @GetMapping({"/comprehensive-reports/{id}/versions","/test-standards/{id}/versions"}) public ApiResponse<?> versions(@PathVariable UUID id){return ok(service.versions(id));}
    @GetMapping({"/comprehensive-reports/{id}/audits","/test-standards/{id}/audits"}) public ApiResponse<?> audits(@PathVariable UUID id){return ok(service.audits(id));}
    @GetMapping({"/comprehensive-reports/{id}/export","/test-standards/{id}/export"}) public ResponseEntity<byte[]> export(@PathVariable UUID id){var f=exportService.export(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(f.fileName(),StandardCharsets.UTF_8).build().toString()).contentLength(f.content().length).body(f.content());}
    private <T>ApiResponse<T> ok(T data){return ResponseFactory.success(data,RequestIdHolder.currentOrUnknown());}
    public record CreateRequest(String businessNo,@NotBlank String name,String category,String scope,String ownerName,LocalDate date,@NotBlank String format,@NotBlank String sourceType,String visibility,UUID projectId,UUID stageId,UUID taskId,UUID sourceFileId,UUID templateVersionId,String templateHash,JsonNode templateSnapshot,JsonNode editModel,JsonNode memberSnapshot,LocalDate effectiveFrom,LocalDate effectiveTo){ResearchTestService.CreateCommand command(Type t){return new ResearchTestService.CreateCommand(t,businessNo,name,category,scope,ownerName,date,format,sourceType,visibility,projectId,stageId,taskId,sourceFileId,templateVersionId,templateHash,templateSnapshot,editModel,memberSnapshot,effectiveFrom,effectiveTo);}}
    public record DraftRequest(@NotNull Long revision,@NotBlank String businessNo,@NotBlank String name,String category,String scope,String ownerName,LocalDate date,String visibility,UUID projectId,UUID stageId,UUID taskId,UUID templateVersionId,String templateHash,JsonNode templateSnapshot,@NotNull JsonNode editModel,JsonNode memberSnapshot,LocalDate effectiveFrom,LocalDate effectiveTo){ResearchTestRepository.Draft command(){return new ResearchTestRepository.Draft(revision,businessNo,name,category,scope,ownerName,date,visibility,projectId,stageId,taskId,templateVersionId,templateHash,templateSnapshot,editModel,memberSnapshot,effectiveFrom,effectiveTo);}}
    public record RenameRequest(@NotNull Long revision,@NotBlank @Size(max=300) String name,@Size(max=120) String businessNo,@Size(max=120) String ownerName,LocalDate date,@Size(max=120) String category,@Size(max=300) String scope,LocalDate effectiveFrom,LocalDate effectiveTo,UUID projectId,UUID stageId,UUID taskId){}
    public record UploadRequest(@NotNull UUID fileId,@NotBlank String originalName,@NotBlank String contentType,@Positive long size,String sha256,String category,String scope,String ownerName,String visibility,UUID projectId,UUID stageId,UUID taskId,LocalDate effectiveFrom,LocalDate effectiveTo){ResearchTestService.UploadCommand command(Type type){return new ResearchTestService.UploadCommand(type,fileId,originalName,contentType,size,sha256,category,scope,ownerName,visibility,projectId,stageId,taskId,effectiveFrom,effectiveTo);}}
    public record ActionRequest(@NotNull Long revision,String comment){} public record RevisionRequest(@NotNull Long revision,@NotBlank String reason){}
}
