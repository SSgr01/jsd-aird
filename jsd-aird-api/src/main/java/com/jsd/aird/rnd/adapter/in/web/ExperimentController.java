package com.jsd.aird.rnd.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.ExperimentExportService;
import com.jsd.aird.rnd.application.ExperimentService;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@RestController @RequestMapping("/api/v1")
public class ExperimentController {
    private final ExperimentService service; private final ExperimentExportService exportService;
    public ExperimentController(ExperimentService service, ExperimentExportService exportService){this.service=service;this.exportService=exportService;}
    @GetMapping("/experiments") public ApiResponse<?> search(@RequestParam(required=false)String keyword,@RequestParam(required=false)String status,@RequestParam(required=false)String sourceType,@RequestParam(required=false)UUID projectId,@RequestParam(required=false)UUID stageId,@RequestParam(required=false)UUID taskId,@RequestParam(required=false)UUID categoryId,@RequestParam(required=false)String ownerName,@RequestParam(required=false)LocalDate dateFrom,@RequestParam(required=false)LocalDate dateTo,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size){return ok(service.search(new ExperimentRepository.Search(keyword,status,sourceType,projectId,stageId,taskId,categoryId,ownerName,dateFrom,dateTo,page,Math.min(100,Math.max(1,size)))));}
    @PostMapping("/experiments") public ApiResponse<?> create(@Valid @RequestBody CreateRequest r){return ok(service.create(new ExperimentService.CreateCommand(r.experimentNo,r.title,r.categoryId,r.categoryName,r.sourceType,r.projectId,r.stageId,r.taskId,r.ownerName,r.experimentDate,r.sourceFileId,r.templateVersionId,r.templateSnapshotHash,r.templateSnapshot,r.editModel)));}
    @PostMapping("/experiments/{id}/copy") public ApiResponse<?> copy(@PathVariable UUID id){return ok(service.copy(id));}
    @GetMapping("/experiments/{id}") public ApiResponse<?> detail(@PathVariable UUID id){return ok(service.detail(id));}
    @GetMapping("/experiments/{id}/edit-model") public ApiResponse<?> edit(@PathVariable UUID id){return ok(service.detail(id));}
    @GetMapping("/experiments/{id}/export") public ResponseEntity<byte[]> export(@PathVariable UUID id){return download(exportService.export(id));}
    @PostMapping("/experiments/{id}/draft") public ApiResponse<?> draft(@PathVariable UUID id,@Valid @RequestBody DraftRequest r){return ok(service.save(id,r.revision,new ExperimentService.DraftCommand(r.experimentNo,r.title,r.categoryId,r.categoryName,r.projectId,r.stageId,r.taskId,r.ownerName,r.experimentDate,r.templateVersionId,r.templateSnapshotHash,r.templateSnapshot,r.editModel)));}
    /** ELN soft-delete endpoint. Project-scoped callers use ProjectExperimentController. */
    @DeleteMapping("/experiments/{id}/eln-delete") public ApiResponse<?> delete(@PathVariable UUID id,@RequestParam long revision){service.delete(id,revision);return ok(null);}
    @PostMapping("/experiments/{id}/publish") public ApiResponse<?> publish(@PathVariable UUID id,@Valid @RequestBody ActionRequest r){return ok(service.publish(id,r.revision));}
    @PostMapping("/experiments/{id}/start") public ApiResponse<?> start(@PathVariable UUID id,@Valid @RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.IN_PROGRESS,r.comment));}
    @PostMapping("/experiments/{id}/submit-review") public ApiResponse<?> submit(@PathVariable UUID id,@Valid @RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.PENDING_REVIEW,r.comment));}
    @PostMapping("/experiments/{id}/approve") public ApiResponse<?> approve(@PathVariable UUID id,@Valid @RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.COMPLETED,r.comment));}
    @PostMapping("/experiments/{id}/return") public ApiResponse<?> returned(@PathVariable UUID id,@Valid @RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.RETURNED,r.comment));}
    @PostMapping("/experiments/{id}/void") public ApiResponse<?> voided(@PathVariable UUID id,@Valid @RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.VOIDED,r.comment));}
    @GetMapping("/experiments/{id}/versions") public ApiResponse<?> versions(@PathVariable UUID id){return ok(service.versions(id));}
    @PostMapping("/experiments/{id}/versions") public ApiResponse<?> revision(@PathVariable UUID id,@Valid @RequestBody RevisionRequest r){return ok(service.revision(id,r.revision,r.reason));}
    @PostMapping("/experiments/{id}/versions/{versionNo}/rollback") public ApiResponse<?> rollback(@PathVariable UUID id,@PathVariable int versionNo,@RequestBody RevisionRequest r){return ok(service.rollback(id,r.revision,versionNo,r.reason));}
    @GetMapping("/experiments/{id}/versions/compare") public ApiResponse<?> compare(@PathVariable UUID id,@RequestParam int from,@RequestParam int to){return ok(service.compare(id,from,to));}
    @GetMapping("/experiments/{id}/audits") public ApiResponse<?> audits(@PathVariable UUID id){return ok(service.audits(id));}
    @GetMapping("/experiment-categories") public ApiResponse<?> categories(@RequestParam(defaultValue="false")boolean includeInactive){return ok(service.categories(includeInactive));}
    @PostMapping("/experiment-categories") public ApiResponse<?> category(@Valid @RequestBody CategoryRequest r){return ok(service.createCategory(r.code,r.name,r.description));}
    @PutMapping("/experiment-categories/{id}") public ApiResponse<?> updateCategory(@PathVariable UUID id,@Valid @RequestBody CategoryUpdateRequest r){return ok(service.updateCategory(id,r.revision,r.name,r.description));}
    @PutMapping("/experiment-categories/{id}/active") public ApiResponse<?> categoryActive(@PathVariable UUID id,@Valid @RequestBody CategoryActiveRequest r){return ok(service.categoryActive(id,r.revision,r.active));}
    private static <T>ApiResponse<T> ok(T data){return ResponseFactory.success(data,RequestIdHolder.currentOrUnknown());}
    private static ResponseEntity<byte[]> download(ExperimentExportService.Download file){
        var disposition=ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).contentLength(file.content().length).body(file.content());
    }
    public record CreateRequest(String experimentNo,@NotBlank String title,UUID categoryId,String categoryName,String sourceType,UUID projectId,UUID stageId,UUID taskId,@NotBlank String ownerName,LocalDate experimentDate,UUID sourceFileId,UUID templateVersionId,String templateSnapshotHash,JsonNode templateSnapshot,JsonNode editModel){}
    public record DraftRequest(@NotNull Long revision,@NotBlank String experimentNo,@NotBlank String title,UUID categoryId,String categoryName,UUID projectId,UUID stageId,UUID taskId,String ownerName,LocalDate experimentDate,UUID templateVersionId,String templateSnapshotHash,JsonNode templateSnapshot,@NotNull JsonNode editModel){}
    public record ActionRequest(@NotNull Long revision,String comment){} public record RevisionRequest(@NotNull Long revision,@NotBlank String reason){}public record CategoryRequest(@NotBlank String code,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=500) String description){}public record CategoryUpdateRequest(@NotNull Long revision,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=500) String description){}public record CategoryActiveRequest(@NotNull Long revision,boolean active){}
}
