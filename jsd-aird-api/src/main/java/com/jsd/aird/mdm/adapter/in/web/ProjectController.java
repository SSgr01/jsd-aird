package com.jsd.aird.mdm.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.mdm.application.command.ProjectCommands;
import com.jsd.aird.mdm.application.query.ProjectQuery;
import com.jsd.aird.mdm.application.service.ProjectService;
import com.jsd.aird.mdm.application.service.ProjectAuditLogService;
import com.jsd.aird.mdm.application.service.CustomerRequirementService;
import com.jsd.aird.mdm.domain.model.CustomerRequirement;
import com.jsd.aird.mdm.domain.model.Project;
import com.jsd.aird.mdm.domain.model.ProjectPriority;
import com.jsd.aird.mdm.domain.model.ProjectStatus;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.api.AllowedActions;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.ProjectTemplateFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "研发项目", description = "研发项目（Project）的分页查询、新增、更新、复制与删除")
public class ProjectController {

    private final ProjectService service;
    private final ProjectAuditLogService auditLogService;
    private final ProjectTemplateFacade projectTemplateFacade;
    private final CustomerRequirementService customerRequirementService;

    public ProjectController(ProjectService service, ProjectAuditLogService auditLogService,
                             ProjectTemplateFacade projectTemplateFacade,
                             CustomerRequirementService customerRequirementService) {
        this.service = service;
        this.auditLogService = auditLogService;
        this.projectTemplateFacade = projectTemplateFacade;
        this.customerRequirementService = customerRequirementService;
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

    // Do not constrain the mapping by produces: the shared axios client sends
    // Accept: application/json even for downloads. The response below still
    // advertises the correct Excel content type.
    @GetMapping(value = { "/export", "/export.csv" })
    @Operation(summary = "导出项目列表", description = "按当前筛选条件导出全部项目 Excel")
    public ResponseEntity<byte[]> exportExcel(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String owner,
        @RequestParam(required = false) ProjectPriority priority,
        @RequestParam(required = false) ProjectStatus status,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateTo,
        @RequestParam(required = false) UUID partnerId) {
        var query = new ProjectQuery(keyword, owner, priority, status, startDateFrom, startDateTo, partnerId, 1, 200);
        var content = service.exportXlsx(query);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("projects.xlsx", StandardCharsets.UTF_8).build().toString())
            .body(content);
    }

    @GetMapping("/{id:[0-9a-fA-F-]+}")
    @Operation(summary = "查询项目详情", description = "根据 ID 获取项目详情")
    public ApiResponse<ProjectResponse> get(@Parameter(description = "项目 ID") @PathVariable UUID id) {
        return ok(ProjectResponse.from(service.get(id)));
    }

    /**
     * 项目详情选择模板时使用的已发布模板列表。
     * 模板内容和发布规则仍由模板中心实现，项目模块只提供项目维度入口。
     */
    @GetMapping("/{id:[0-9a-fA-F-]+}/templates")
    @Operation(summary = "查询项目可用模板", description = "查询当前组织已发布的项目可用模板")
    public ApiResponse<List<ProjectTemplateFacade.ProjectTemplateOption>> templates(
            @Parameter(description = "项目 ID") @PathVariable UUID id) {
        service.get(id);
        return ok(projectTemplateFacade.listPublishedForProject(
                ActorContext.required().organizationId()));
    }

    /**
     * 返回已发布模板的可直接创建实验的快照，避免项目角色再调用模板中心
     * 或文件下载接口，从而保持项目详情的权限边界。
     */
    @GetMapping("/{id:[0-9a-fA-F-]+}/templates/{versionId:[0-9a-fA-F-]+}/edit-model")
    @Operation(summary = "读取项目模板快照", description = "读取已发布模板的实验创建快照")
    public ApiResponse<ProjectTemplateFacade.ProjectTemplateEditModel> templateEditModel(
            @Parameter(description = "项目 ID") @PathVariable UUID id,
            @Parameter(description = "模板版本 ID") @PathVariable UUID versionId) {
        service.get(id);
        return ok(projectTemplateFacade.getPublishedEditModel(
                ActorContext.required().organizationId(), versionId));
    }

    @GetMapping("/{id:[0-9a-fA-F-]+}/requirements")
    @Operation(summary = "查询项目客户需求", description = "复用客户需求服务，按项目分页查询客户需求")
    public ApiResponse<PageResponse<CustomerRequirement>> requirements(
            @Parameter(description = "项目 ID") @PathVariable UUID id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        service.get(id);
        return ok(customerRequirementService.requirements(null, id, status, page, size));
    }

    @GetMapping("/{id:[0-9a-fA-F-]+}/requirements/{requirementId:[0-9a-fA-F-]+}")
    @Operation(summary = "查询项目客户需求详情", description = "查询指定项目下的客户需求")
    public ApiResponse<CustomerRequirement> requirement(
            @Parameter(description = "项目 ID") @PathVariable UUID id,
            @Parameter(description = "需求 ID") @PathVariable UUID requirementId) {
        return ok(requirementInProject(id, requirementId));
    }

    @PostMapping("/{id:[0-9a-fA-F-]+}/requirements")
    @Operation(summary = "创建项目客户需求", description = "复用客户需求服务创建并关联当前项目")
    public ApiResponse<UUID> createRequirement(
            @Parameter(description = "项目 ID") @PathVariable UUID id,
            @Valid @RequestBody CustomerRequirementController.RequirementRequest request) {
        service.get(id);
        return ok(customerRequirementService.createRequirement(request.toDomain(id)).id());
    }

    @PutMapping("/{id:[0-9a-fA-F-]+}/requirements/{requirementId:[0-9a-fA-F-]+}")
    @Operation(summary = "更新项目客户需求", description = "复用客户需求服务更新指定项目下的需求")
    public ApiResponse<Void> updateRequirement(
            @Parameter(description = "项目 ID") @PathVariable UUID id,
            @Parameter(description = "需求 ID") @PathVariable UUID requirementId,
            @Valid @RequestBody CustomerRequirementController.RequirementRequest request) {
        requirementInProject(id, requirementId);
        customerRequirementService.updateRequirement(requirementId, request.toDomain(id));
        return ok(null);
    }

    @DeleteMapping("/{id:[0-9a-fA-F-]+}/requirements/{requirementId:[0-9a-fA-F-]+}")
    @Operation(summary = "删除项目客户需求", description = "按版本号删除指定项目下的客户需求")
    public ApiResponse<Void> deleteRequirement(
            @Parameter(description = "项目 ID") @PathVariable UUID id,
            @Parameter(description = "需求 ID") @PathVariable UUID requirementId,
            @RequestParam @PositiveOrZero long version) {
        requirementInProject(id, requirementId);
        customerRequirementService.deleteRequirement(requirementId, version);
        return ok(null);
    }

    @GetMapping("/{id:[0-9a-fA-F-]+}/logs")
    @Operation(summary = "查询项目日志", description = "从通用 ops.audit_log 查询当前项目及其阶段、任务、实验、文档相关审计记录")
    public ApiResponse<PageResponse<ProjectAuditLogService.ProjectAuditLog>> logs(
            @Parameter(description = "项目 ID") @PathVariable UUID id,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        // Check that the project exists before querying audit rows so a deleted or
        // misspelled project id is reported consistently with the other detail APIs.
        service.get(id);
        return ok(auditLogService.page(id,
                new ProjectAuditLogService.Query(keyword, objectType, action, operator,
                        createdFrom, createdTo, page, size)));
    }

    @PostMapping
    @Operation(summary = "新增项目", description = "创建项目，未填写编号时自动生成")
    public ApiResponse<ProjectCommands.Created> create(@Valid @RequestBody SaveProjectRequest r) {
        return ok(service.create(r.toCommand()));
    }

    @PutMapping("/{id:[0-9a-fA-F-]+}")
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

    @DeleteMapping("/{id:[0-9a-fA-F-]+}")
    @Operation(summary = "删除项目", description = "逻辑删除指定项目")
    public ApiResponse<Void> delete(@Parameter(description = "项目 ID") @PathVariable UUID id) {
        service.delete(id);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    private CustomerRequirement requirementInProject(UUID projectId, UUID requirementId) {
        service.get(projectId);
        var requirement = customerRequirementService.requirement(requirementId);
        var projectIds = requirement.projectIds();
        if (!projectId.equals(requirement.projectId())
                && (projectIds == null || !projectIds.contains(projectId))) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "项目客户需求不存在");
        }
        return requirement;
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
        public List<String> getAllowedActions() {
            return AllowedActions.project(status == null ? null : status.name());
        }
        static ProjectResponse from(Project p) {
            return new ProjectResponse(p.id(), p.projectCode(), p.name(), p.partnerId(), p.partnerName(),
                p.owner(), p.startDate(), p.endDate(), p.priority(), p.status(), p.teamSize(),
                p.background(), p.customFields(), p.teamMembers(), p.version());
        }
    }
}
