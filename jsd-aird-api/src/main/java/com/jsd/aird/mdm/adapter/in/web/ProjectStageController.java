package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.command.ProjectStageCommands;
import com.jsd.aird.mdm.application.query.ProjectStageQuery;
import com.jsd.aird.mdm.application.service.ProjectStageService;
import com.jsd.aird.mdm.domain.model.ProjectStage;
import com.jsd.aird.mdm.domain.model.StageStatus;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ProjectStageController {
    private final ProjectStageService service;

    public ProjectStageController(ProjectStageService service) {
        this.service = service;
    }

    @GetMapping("/projects/{projectId}/stages")
    public ApiResponse<List<ProjectStage>> list(@PathVariable UUID projectId) {
        return ok(service.listByProject(projectId));
    }

    @PostMapping("/projects/{projectId}/stages")
    public ApiResponse<ProjectStage> create(@PathVariable UUID projectId, @Valid @RequestBody StageRequest request) {
        return ok(service.create(projectId, request.toCreate()));
    }

    @PutMapping("/stages/{stageId}")
    public ApiResponse<ProjectStage> update(@PathVariable UUID stageId, @Valid @RequestBody StageRequest request) {
        return ok(service.update(stageId, request.toUpdate()));
    }

    @DeleteMapping("/stages/{stageId}")
    public ApiResponse<Void> delete(@PathVariable UUID stageId, @RequestParam @PositiveOrZero long version) {
        service.delete(stageId, version);
        return ok(null);
    }

    @PutMapping("/projects/{projectId}/stages/reorder")
    public ApiResponse<List<ProjectStage>> reorder(@PathVariable UUID projectId, @Valid @RequestBody ReorderRequest request) {
        return ok(service.reorder(projectId, new ProjectStageCommands.Reorder(
            request.items().stream().map(it -> new ProjectStageCommands.ReorderItem(it.id(), it.version())).toList())));
    }

    @GetMapping("/project-stages")
    public ApiResponse<PageResponse<ProjectStage>> search(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) UUID projectId,
        @RequestParam(required = false) StageStatus status,
        @RequestParam(required = false) String owner,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedTo,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ok(service.search(new ProjectStageQuery(keyword, projectId, status, owner, plannedFrom, plannedTo, page, size)));
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record StageRequest(@NotBlank @Size(max = 120) String name, @Size(max = 40) String stageCode,
                               StageStatus status, @Size(max = 100) String owner, @Size(max = 5000) String description,
                               LocalDate plannedStart, LocalDate plannedEnd,
                               @Size(max = 1000) String transitionReason, @PositiveOrZero Long version) {
        ProjectStageCommands.Create toCreate() {
            return new ProjectStageCommands.Create(name, stageCode, status, owner, description, plannedStart, plannedEnd);
        }

        ProjectStageCommands.Update toUpdate() {
            if (version == null) throw new com.jsd.aird.shared.error.ApiException(
                com.jsd.aird.shared.error.ApiErrorCode.VALIDATION_ERROR, "更新阶段必须提交版本号");
            return new ProjectStageCommands.Update(name, status, owner, description, plannedStart, plannedEnd,
                transitionReason, version);
        }
    }

    public record ReorderRequest(@NotEmpty List<@Valid ReorderItem> items) {
    }

    public record ReorderItem(@NotNull UUID id, @PositiveOrZero long version) {
    }
}
