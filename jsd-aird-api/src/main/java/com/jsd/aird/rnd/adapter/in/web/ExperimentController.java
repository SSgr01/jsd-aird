package com.jsd.aird.rnd.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.ExperimentService;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController @RequestMapping("/api/v1")
public class ExperimentController {
    private final ExperimentService service;public ExperimentController(ExperimentService service){this.service=service;}
    @GetMapping("/experiments") public ApiResponse<?> search(@RequestParam(required=false)String keyword,@RequestParam(required=false)String status,@RequestParam(required=false)String sourceType,@RequestParam(required=false)UUID projectId,@RequestParam(required=false)UUID categoryId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ok(service.search(new ExperimentRepository.Search(keyword,status,sourceType,projectId,categoryId,page,Math.min(100,Math.max(1,size)))));}
    @PostMapping("/experiments") public ApiResponse<?> create(@Valid @RequestBody CreateRequest r){return ok(service.create(new ExperimentService.CreateCommand(r.experimentNo,r.title,r.categoryId,r.categoryName,r.sourceType,r.projectId,r.stageId,r.taskId,r.ownerName,r.experimentDate,r.templateVersionId,r.templateSnapshotHash,r.templateSnapshot,r.editModel)));}
    @GetMapping("/experiments/{id}") public ApiResponse<?> detail(@PathVariable UUID id){return ok(service.detail(id));}
    @GetMapping("/experiments/{id}/edit-model") public ApiResponse<?> edit(@PathVariable UUID id){return ok(service.detail(id));}
    @PostMapping("/experiments/{id}/draft") public ApiResponse<?> draft(@PathVariable UUID id,@Valid @RequestBody DraftRequest r){return ok(service.save(id,r.revision,new ExperimentService.DraftCommand(r.title,r.categoryId,r.categoryName,r.projectId,r.stageId,r.taskId,r.ownerName,r.experimentDate,r.templateVersionId,r.templateSnapshotHash,r.templateSnapshot,r.editModel)));}
    @PostMapping("/experiments/{id}/start") public ApiResponse<?> start(@PathVariable UUID id,@RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.IN_PROGRESS,r.comment));}
    @PostMapping("/experiments/{id}/submit-review") public ApiResponse<?> submit(@PathVariable UUID id,@RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.PENDING_REVIEW,r.comment));}
    @PostMapping("/experiments/{id}/approve") public ApiResponse<?> approve(@PathVariable UUID id,@RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.COMPLETED,r.comment));}
    @PostMapping("/experiments/{id}/return") public ApiResponse<?> returned(@PathVariable UUID id,@RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.RETURNED,r.comment));}
    @PostMapping("/experiments/{id}/void") public ApiResponse<?> voided(@PathVariable UUID id,@RequestBody ActionRequest r){return ok(service.transition(id,r.revision,ExperimentStatus.VOIDED,r.comment));}
    @GetMapping("/experiments/{id}/versions") public ApiResponse<?> versions(@PathVariable UUID id){return ok(service.versions(id));}
    @PostMapping("/experiments/{id}/versions") public ApiResponse<?> revision(@PathVariable UUID id,@RequestBody RevisionRequest r){return ok(service.revision(id,r.revision,r.reason));}
    @GetMapping("/experiments/{id}/versions/compare") public ApiResponse<?> compare(@PathVariable UUID id,@RequestParam int from,@RequestParam int to){return ok(service.compare(id,from,to));}
    @GetMapping("/experiments/{id}/audits") public ApiResponse<?> audits(@PathVariable UUID id){return ok(service.audits(id));}
    @GetMapping("/experiment-categories") public ApiResponse<?> categories(@RequestParam(defaultValue="false")boolean includeInactive){return ok(service.categories(includeInactive));}
    @PostMapping("/experiment-categories") public ApiResponse<?> category(@Valid @RequestBody CategoryRequest r){return ok(service.createCategory(r.code,r.name,r.description));}
    @PutMapping("/experiment-categories/{id}/active") public ApiResponse<?> categoryActive(@PathVariable UUID id,@RequestBody CategoryActiveRequest r){return ok(service.categoryActive(id,r.revision,r.active));}
    private static <T>ApiResponse<T> ok(T data){return ResponseFactory.success(data,RequestIdHolder.currentOrUnknown());}
    public record CreateRequest(String experimentNo,@NotBlank String title,UUID categoryId,String categoryName,String sourceType,UUID projectId,UUID stageId,UUID taskId,String ownerName,LocalDate experimentDate,UUID templateVersionId,String templateSnapshotHash,JsonNode templateSnapshot,JsonNode editModel){}
    public record DraftRequest(@NotNull Long revision,@NotBlank String title,UUID categoryId,String categoryName,UUID projectId,UUID stageId,UUID taskId,@NotBlank String ownerName,@NotNull LocalDate experimentDate,UUID templateVersionId,String templateSnapshotHash,JsonNode templateSnapshot,@NotNull JsonNode editModel){}
    public record ActionRequest(@NotNull Long revision,String comment){} public record RevisionRequest(@NotNull Long revision,@NotBlank String reason){}public record CategoryRequest(@NotBlank String code,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=500) String description){}public record CategoryActiveRequest(@NotNull Long revision,boolean active){}
}
