package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.service.ProjectMaterialService;
import com.jsd.aird.mdm.domain.model.ProjectMaterial;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/materials")
@Tag(name = "项目-关联资料", description = "项目维度的资料关联（关联表模式）")
public class ProjectMaterialController {

    private final ProjectMaterialService service;

    public ProjectMaterialController(ProjectMaterialService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "分页查询项目关联资料")
    ApiResponse<PageResponse<ProjectMaterial>> list(@Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                     @RequestParam(required = false) String category,
                                                     @RequestParam(required = false) String stage,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ok(service.listByProject(projectId, category, stage, status, keyword, page, size));
    }

    @PostMapping("/link")
    @Operation(summary = "关联项目与资料", description = "在关联表插入一行")
    ApiResponse<Void> link(@Parameter(description = "项目 ID") @PathVariable UUID projectId,
                           @Valid @RequestBody LinkRequest r) {
        service.link(projectId, r.materialId(), r.remark());
        return ok(null);
    }

    @PostMapping("/unlink")
    @Operation(summary = "解除项目与资料关联")
    ApiResponse<Void> unlink(@Parameter(description = "项目 ID") @PathVariable UUID projectId,
                             @RequestParam @NotNull UUID materialId) {
        service.unlink(projectId, materialId);
        return ok(null);
    }

    @PostMapping("/associations")
    @Operation(summary = "批量保存关联", description = "保存勾选的资料集合，自动新增未关联项并解除已取消项")
    ApiResponse<Void> saveAssociations(@Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                       @RequestBody @NotNull Set<UUID> materialIds) {
        service.saveAssociations(projectId, materialIds);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record LinkRequest(@NotNull UUID materialId, @Size(max = 500) String remark) {
    }
}