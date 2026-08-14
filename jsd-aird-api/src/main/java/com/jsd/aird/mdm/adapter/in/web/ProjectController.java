package com.jsd.aird.mdm.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.mdm.application.command.ProjectCommands;
import com.jsd.aird.mdm.application.query.ProjectQuery;
import com.jsd.aird.mdm.application.service.ProjectService;
import com.jsd.aird.mdm.domain.model.Project;
import com.jsd.aird.mdm.domain.model.ProjectPriority;
import com.jsd.aird.mdm.domain.model.ProjectStatus;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "研发项目", description = "研发项目（Project）的分页查询、新增、更新、复制与删除")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "分页查询项目", description = "支持关键字、负责人、优先级、状态与开始日期区间筛选")
    public ApiResponse<PageResponse<ProjectResponse>> search(
        @Parameter(description = "关键字：项目名称/编号/客户/负责人") @RequestParam(required = false) String keyword,
        @Parameter(description = "负责人") @RequestParam(required = false) String owner,
        @Parameter(description = "优先级") @RequestParam(required = false) ProjectPriority priority,
        @Parameter(description = "项目状态") @RequestParam(required = false) ProjectStatus status,
        @Parameter(description = "开始日期起") @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateFrom,
        @Parameter(description = "开始日期止") @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateTo,
        @Parameter(description = "按所属客户（公司）ID 过滤") @RequestParam(required = false) UUID partnerId,
        @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
        @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") int size) {

        var query = new ProjectQuery(keyword, owner, priority, status, startDateFrom, startDateTo, partnerId, page, size);
        var items = service.search(query).stream().map(ProjectResponse::from).toList();
        var total = service.count(query);
        var totalPages = query.size() == 0 ? 0 : (total + query.size() - 1) / query.size();
        return ok(new PageResponse<>(items, query.page(), query.size(), total, totalPages));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询项目详情", description = "根据 ID 获取项目详情")
    public ApiResponse<ProjectResponse> get(@Parameter(description = "项目 ID") @PathVariable UUID id) {
        return ok(ProjectResponse.from(service.get(id)));
    }

    @PostMapping
    @Operation(summary = "新增项目", description = "创建项目，未填写编号时自动生成")
    public ApiResponse<ProjectCommands.Created> create(@Valid @RequestBody SaveProjectRequest r) {
        return ok(service.create(r.toCommand()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新项目", description = "按乐观锁版本更新项目信息")
    public ApiResponse<Void> update(@Parameter(description = "项目 ID") @PathVariable UUID id,
                                    @Valid @RequestBody SaveProjectRequest r) {
        service.update(id, r.toCommand());
        return ok(null);
    }

    @PostMapping("/copy")
    @Operation(summary = "复制项目", description = "批量复制选中的项目，副本状态重置为待启动")
    public ApiResponse<List<ProjectCommands.Created>> copy(@Valid @RequestBody CopyProjectsRequest r) {
        return ok(service.copy(r.ids()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除项目", description = "逻辑删除指定项目")
    public ApiResponse<Void> delete(@Parameter(description = "项目 ID") @PathVariable UUID id) {
        service.delete(id);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record SaveProjectRequest(@Size(max = 64) String projectCode,
                                     @NotBlank @Size(max = 300) String name,
                                     UUID partnerId, @Size(max = 300) String partnerName,
                                     @Size(max = 100) String owner,
                                     LocalDate startDate, LocalDate endDate,
                                     ProjectPriority priority, ProjectStatus status,
                                     @PositiveOrZero Integer teamSize,
                                     String background, JsonNode customFields, JsonNode teamMembers, Long version) {
        ProjectCommands.SaveProject toCommand() {
            return new ProjectCommands.SaveProject(projectCode, name, partnerId, partnerName, owner,
                startDate, endDate, priority, status, teamSize, background, customFields, teamMembers, version);
        }
    }

    public record CopyProjectsRequest(@NotEmpty List<UUID> ids) {
    }

    public record ProjectResponse(UUID id, String projectCode, String name, UUID partnerId, String partnerName,
                                  String owner, LocalDate startDate, LocalDate endDate, ProjectPriority priority,
                                  ProjectStatus status, int teamSize, String background, JsonNode customFields,
                                  JsonNode teamMembers, long version) {
        static ProjectResponse from(Project p) {
            return new ProjectResponse(p.id(), p.projectCode(), p.name(), p.partnerId(), p.partnerName(),
                p.owner(), p.startDate(), p.endDate(), p.priority(), p.status(), p.teamSize(),
                p.background(), p.customFields(), p.teamMembers(), p.version());
        }
    }
}
