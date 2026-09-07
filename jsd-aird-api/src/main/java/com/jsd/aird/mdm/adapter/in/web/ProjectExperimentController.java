package com.jsd.aird.mdm.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.application.ExperimentExportService;
import com.jsd.aird.rnd.application.ExperimentService;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.Detail;
import com.jsd.aird.rnd.domain.ExperimentModels.Summary;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 项目详情下的实验操作入口。
 *
 * <p>项目详情只负责提供项目维度的入口，实验的创建、编辑、审核、版本和
 * 导出规则统一复用实验本的应用服务，避免项目详情再维护一套实验数据模型。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/experiments")
public class ProjectExperimentController {

    private final ExperimentService service;
    private final ExperimentExportService exportService;

    public ProjectExperimentController(ExperimentService service, ExperimentExportService exportService) {
        this.service = service;
        this.exportService = exportService;
    }

    @GetMapping
    public ApiResponse<PageResponse<Summary>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) UUID stageId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String ownerName,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ok(service.search(new ExperimentRepository.Search(
                keyword, status, sourceType, projectId, stageId, taskId, categoryId,
                ownerName, dateFrom, dateTo, page, size)));
    }

    @PostMapping
    public ApiResponse<Summary> create(@PathVariable UUID projectId,
                                       @Valid @RequestBody CreateRequest request) {
        return ok(service.create(request.toCommand(projectId)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@PathVariable UUID projectId, @PathVariable UUID id) {
        return ok(detailInProject(projectId, id));
    }

    @GetMapping("/{id}/edit-model")
    public ApiResponse<Detail> editModel(@PathVariable UUID projectId, @PathVariable UUID id) {
        return ok(detailInProject(projectId, id));
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<Summary> copy(@PathVariable UUID projectId, @PathVariable UUID id) {
        detailInProject(projectId, id);
        return ok(service.copy(id));
    }

    @PostMapping("/{id}/draft")
    public ApiResponse<Detail> draft(@PathVariable UUID projectId,
                                     @PathVariable UUID id,
                                     @Valid @RequestBody DraftRequest request) {
        detailInProject(projectId, id);
        return ok(service.save(id, request.revision(), request.toCommand(projectId)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID projectId,
                                   @PathVariable UUID id,
                                   @RequestParam long revision) {
        detailInProject(projectId, id);
        service.delete(id, revision);
        return ok(null);
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Detail> publish(@PathVariable UUID projectId,
                                       @PathVariable UUID id,
                                       @Valid @RequestBody ActionRequest request) {
        detailInProject(projectId, id);
        return ok(service.publish(id, request.revision()));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<Detail> start(@PathVariable UUID projectId,
                                     @PathVariable UUID id,
                                     @Valid @RequestBody ActionRequest request) {
        return transition(projectId, id, request, ExperimentStatus.IN_PROGRESS);
    }

    @PostMapping("/{id}/submit-review")
    public ApiResponse<Detail> submitReview(@PathVariable UUID projectId,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody ActionRequest request) {
        return transition(projectId, id, request, ExperimentStatus.PENDING_REVIEW);
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Detail> approve(@PathVariable UUID projectId,
                                       @PathVariable UUID id,
                                       @Valid @RequestBody ActionRequest request) {
        return transition(projectId, id, request, ExperimentStatus.COMPLETED);
    }

    @PostMapping("/{id}/return")
    public ApiResponse<Detail> returned(@PathVariable UUID projectId,
                                       @PathVariable UUID id,
                                       @Valid @RequestBody ActionRequest request) {
        return transition(projectId, id, request, ExperimentStatus.RETURNED);
    }

    @PostMapping("/{id}/void")
    public ApiResponse<Detail> voided(@PathVariable UUID projectId,
                                     @PathVariable UUID id,
                                     @Valid @RequestBody ActionRequest request) {
        return transition(projectId, id, request, ExperimentStatus.VOIDED);
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<?> versions(@PathVariable UUID projectId, @PathVariable UUID id) {
        detailInProject(projectId, id);
        return ok(service.versions(id));
    }

    @PostMapping("/{id}/versions")
    public ApiResponse<Detail> revision(@PathVariable UUID projectId,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody RevisionRequest request) {
        detailInProject(projectId, id);
        return ok(service.revision(id, request.revision(), request.reason()));
    }

    @PostMapping("/{id}/versions/{versionNo}/rollback")
    public ApiResponse<Detail> rollback(@PathVariable UUID projectId,
                                        @PathVariable UUID id,
                                        @PathVariable int versionNo,
                                        @Valid @RequestBody RevisionRequest request) {
        detailInProject(projectId, id);
        return ok(service.rollback(id, request.revision(), versionNo, request.reason()));
    }

    @GetMapping("/{id}/versions/compare")
    public ApiResponse<JsonNode> compare(@PathVariable UUID projectId,
                                         @PathVariable UUID id,
                                         @RequestParam int from,
                                         @RequestParam int to) {
        detailInProject(projectId, id);
        return ok(service.compare(id, from, to));
    }

    @GetMapping("/{id}/audits")
    public ApiResponse<?> audits(@PathVariable UUID projectId, @PathVariable UUID id) {
        detailInProject(projectId, id);
        return ok(service.audits(id));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID projectId, @PathVariable UUID id) {
        detailInProject(projectId, id);
        var file = exportService.export(id);
        var disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(file.content().length)
                .body(file.content());
    }

    private ApiResponse<Detail> transition(UUID projectId, UUID id, ActionRequest request,
                                           ExperimentStatus target) {
        detailInProject(projectId, id);
        return ok(service.transition(id, request.revision(), target, request.comment()));
    }

    private Detail detailInProject(UUID projectId, UUID id) {
        var detail = service.detail(id);
        if (!projectId.equals(detail.summary().projectId())) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "项目实验不存在");
        }
        return detail;
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record CreateRequest(
            String experimentNo,
            @NotBlank String title,
            UUID categoryId,
            String categoryName,
            String sourceType,
            UUID stageId,
            UUID taskId,
            @NotBlank String ownerName,
            LocalDate experimentDate,
            UUID sourceFileId,
            UUID templateVersionId,
            String templateSnapshotHash,
            JsonNode templateSnapshot,
            JsonNode editModel) {

        ExperimentService.CreateCommand toCommand(UUID projectId) {
            return new ExperimentService.CreateCommand(
                    experimentNo, title, categoryId, categoryName,
                    sourceType == null || sourceType.isBlank() ? "PROJECT" : sourceType,
                    projectId, stageId, taskId, ownerName, experimentDate,
                    sourceFileId, templateVersionId, templateSnapshotHash,
                    templateSnapshot, editModel);
        }
    }

    public record DraftRequest(
            @NotNull Long revision,
            @NotBlank String experimentNo,
            @NotBlank String title,
            UUID categoryId,
            String categoryName,
            UUID stageId,
            UUID taskId,
            @NotBlank String ownerName,
            @NotNull LocalDate experimentDate,
            UUID templateVersionId,
            String templateSnapshotHash,
            JsonNode templateSnapshot,
            @NotNull JsonNode editModel) {

        ExperimentService.DraftCommand toCommand(UUID projectId) {
            return new ExperimentService.DraftCommand(
                    experimentNo, title, categoryId, categoryName,
                    projectId, stageId, taskId, ownerName, experimentDate,
                    templateVersionId, templateSnapshotHash, templateSnapshot, editModel);
        }
    }

    public record ActionRequest(@NotNull Long revision, String comment) {
    }

    public record RevisionRequest(@NotNull Long revision, @NotBlank @Size(max = 1000) String reason) {
    }
}
