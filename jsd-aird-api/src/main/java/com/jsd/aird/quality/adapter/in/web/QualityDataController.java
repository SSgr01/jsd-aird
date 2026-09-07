package com.jsd.aird.quality.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.quality.application.QualityDataDefinitions;
import com.jsd.aird.quality.application.QualityDataService;
import com.jsd.aird.quality.application.QualityDataExportService;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quality")
public class QualityDataController {
    private final QualityDataService service;
    private final QualityDataExportService exportService;
    public QualityDataController(QualityDataService service, QualityDataExportService exportService){this.service=service;this.exportService=exportService;}
    @GetMapping("/data-types") public ApiResponse<List<QualityDataDefinitions.Type>> types(){return ok(service.definitions());}
    @GetMapping("/categories") public ApiResponse<List<QualityDataStore.Category>> categories(@RequestParam String type){return ok(service.categories(type));}
    @PostMapping("/categories") public ApiResponse<QualityDataStore.Category> createCategory(@Valid @RequestBody CategoryRequest r){return ok(service.createCategory(r.type(),r.name(),r.description()));}
    @PutMapping("/categories/{id}") public ApiResponse<QualityDataStore.Category> updateCategory(@PathVariable UUID id,@Valid @RequestBody RenameCategoryRequest r){return ok(service.updateCategory(id,r.name(),r.description()));}
    @DeleteMapping("/categories/{id}") public ApiResponse<Void> deleteCategory(@PathVariable UUID id){service.deleteCategory(id);return ok(null);}
    @GetMapping("/records") public ApiResponse<PageResponse<QualityDataStore.RecordView>> records(@RequestParam String type,@RequestParam(required=false) UUID categoryId,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ok(service.records(type,categoryId,keyword,page,size));}
    @GetMapping("/records/{id}/export") public ResponseEntity<byte[]> export(@PathVariable UUID id){return download(exportService.export(id));}
    @GetMapping("/records/{id:[0-9a-fA-F-]+}") public ApiResponse<QualityDataStore.RecordView> record(@PathVariable UUID id){return ok(service.record(id));}
    @PutMapping("/records/{id}/rename") public ApiResponse<QualityDataStore.RecordView> rename(@PathVariable UUID id,@Valid @RequestBody RenameRecordRequest r){return ok(service.rename(id,new QualityDataService.RenameCommand(r.revision(),r.name(),r.projectId(),r.projectName(),r.stageId(),r.stageName(),r.taskId(),r.taskName())));}
    @DeleteMapping("/records/{id}") public ApiResponse<Void> deleteRecord(@PathVariable UUID id){service.deleteRecord(id);return ok(null);}
    @PostMapping("/records/{id}/publish") public ApiResponse<QualityDataStore.VersionView> publishRecord(@PathVariable UUID id){return ok(service.publishRecord(id));}
    @GetMapping("/records/{id}/versions") public ApiResponse<List<QualityDataStore.VersionView>> versions(@PathVariable UUID id){return ok(service.versions(id));}
    @PutMapping("/records/batch") public ApiResponse<Void> save(@Valid @RequestBody BatchRequest r){service.saveBatch(r.type(),r.categoryId(),r.records().stream().map(x->new QualityDataService.RecordInput(x.id(),x.businessNo(),x.data(),x.workbookSnapshot(),x.lockVersion())).toList(),r.deleteIds());return ok(null);}
    @PostMapping("/records/move") public ApiResponse<Void> move(@Valid @RequestBody MoveRequest r){service.move(r.ids(),r.categoryId());return ok(null);}
    @PostMapping("/records/{id}/create-defect") public ApiResponse<QualityDataStore.RecordView> createDefect(@PathVariable UUID id){return ok(service.createDefectFromRecord(id));}
    @PostMapping("/uploads") public ApiResponse<QualityDataStore.UploadView> upload(@Valid @RequestBody UploadRequest r){return ok(service.upload(new QualityDataService.UploadInput(r.fileId(),r.categoryId(),r.originalName(),r.contentType(),r.size(),r.sha256(),r.projectId(),r.projectName(),r.stageId(),r.stageName(),r.taskId(),r.taskName(),r.visibility())));}
    @GetMapping("/uploads") public ApiResponse<PageResponse<QualityDataStore.UploadView>> uploads(
            @RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) UUID projectId,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="10") int size){
        return ok(service.uploads(keyword,status,projectId,page,size));
    }
    @DeleteMapping("/uploads/{id}") public ApiResponse<Void> deleteUpload(@PathVariable UUID id){service.deleteUpload(id);return ok(null);}
    @PostMapping("/uploads/{id}/retry") public ApiResponse<QualityDataStore.UploadView> retryUpload(@PathVariable UUID id){return ok(service.retryUpload(id));}
    private <T> ApiResponse<T> ok(T data){return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());}
    private static ResponseEntity<byte[]> download(QualityDataExportService.Download file){
        var disposition= ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).contentLength(file.content().length).body(file.content());
    }
    public record CategoryRequest(@NotBlank String type,@NotBlank @Size(max=20) String name,@NotBlank @Size(max=80) String description){}
    public record RenameCategoryRequest(@NotBlank @Size(max=20) String name,@NotBlank @Size(max=80) String description){}
    public record RenameRecordRequest(@NotNull Long revision,@NotBlank @Size(max=300) String name,UUID projectId,String projectName,UUID stageId,String stageName,UUID taskId,String taskName){}
    public record RecordRequest(UUID id,@NotBlank String businessNo,@NotNull JsonNode data,JsonNode workbookSnapshot,long lockVersion){}
    public record BatchRequest(@NotBlank String type,@NotNull UUID categoryId,@NotNull List<@Valid RecordRequest> records,List<UUID> deleteIds){}
    public record MoveRequest(@NotNull List<@NotNull UUID> ids,@NotNull UUID categoryId){}
        public record UploadRequest(@NotNull UUID fileId,@NotNull UUID categoryId,@NotBlank String originalName,String contentType,long size,String sha256,UUID projectId,String projectName,UUID stageId,String stageName,UUID taskId,String taskName,@NotBlank String visibility){}
}
