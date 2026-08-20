package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import com.jsd.aird.mdm.application.query.ProjectTaskSummary;
import com.jsd.aird.mdm.application.service.ProjectWorkService;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ProjectWorkController {
    private final ProjectWorkService service;

    public ProjectWorkController(ProjectWorkService service) {
        this.service = service;
    }

    @GetMapping("/stages/{stageId}/tasks")
    public ApiResponse<List<ProjectTask>> tasks(@PathVariable UUID stageId) {
        return ok(service.tasks(stageId));
    }

    @PostMapping("/projects/{projectId}/tasks")
    public ApiResponse<ProjectTask> createTask(@PathVariable UUID projectId, @Valid @RequestBody TaskRequest r) {
        return ok(service.createTask(projectId, new ProjectWorkService.TaskInput(r.stageId, r.name, r.owner, r.plannedDate, r.status, null)));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ProjectTask> getTask(@PathVariable UUID taskId) {
        return ok(service.getTask(taskId));
    }

    @PutMapping("/tasks/{taskId}")
    public ApiResponse<ProjectTask> updateTask(@PathVariable UUID taskId, @Valid @RequestBody TaskRequest r) {
        return ok(service.updateTask(taskId, new ProjectWorkService.TaskInput(r.stageId, r.name, r.owner, r.plannedDate, r.status, r.version)));
    }

    @GetMapping("/tasks/{taskId}/experiments")
    public ApiResponse<List<ProjectExperiment>> experiments(@PathVariable UUID taskId) {
        return ok(service.experiments(taskId));
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResponse<ProjectTaskSummary>> searchTasks(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String projectId,
        @RequestParam(required = false) String stageId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String owner,
        @RequestParam(required = false) String priority,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
        var query = new ProjectTaskQuery(keyword, projectId, stageId, status, owner, priority, page, size);
        var items = service.searchTasks(query);
        var total = service.countTasks(query);
        var totalPages = size == 0 ? 0 : (total + size - 1) / size;
        return ok(new PageResponse<>(items, page, size, total, totalPages));
    }

    @GetMapping("/tasks/owners")
    public ApiResponse<List<String>> taskOwners() {
        return ok(service.taskOwners());
    }

    @PostMapping("/tasks/{taskId}/experiments")
    public ApiResponse<ProjectExperiment> createExperiment(@PathVariable UUID taskId, @Valid @RequestBody ExperimentRequest r) {
        return ok(service.createExperiment(taskId, new ProjectWorkService.ExperimentInput(r.title, r.category, r.owner, r.experimentDate, r.templateName, r.templateVersion, r.workbookContent)));
    }

    @PutMapping("/experiments/{id}")
    public ApiResponse<ProjectExperiment> updateExperiment(@PathVariable UUID id, @Valid @RequestBody ExperimentRequest r) {
        return ok(service.updateExperiment(id, new ProjectWorkService.ExperimentInput(r.title, r.category, r.owner, r.experimentDate, r.templateName, r.templateVersion, r.workbookContent), r.version == null ? 0 : r.version));
    }

    @DeleteMapping("/experiments/{id}")
    public ApiResponse<Void> deleteExperiment(@PathVariable UUID id, @RequestParam long version) {
        service.deleteExperiment(id, version);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record TaskRequest(@NotNull UUID stageId, @NotBlank @Size(max = 300) String name,
                              @Size(max = 100) String owner, LocalDate plannedDate, String status, Long version) {
    }

    public record ExperimentRequest(@NotBlank @Size(max = 300) String title, String category, @NotBlank String owner,
                                    LocalDate experimentDate, String templateName, String templateVersion,
                                    String workbookContent, Long version) {
    }
}
