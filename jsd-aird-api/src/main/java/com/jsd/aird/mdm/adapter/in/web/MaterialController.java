package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.service.MaterialService;
import com.jsd.aird.mdm.domain.model.Material;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/materials")
@Tag(name = "资料主数据", description = "全局资料主数据（独立于项目）")
public class MaterialController {

    private final MaterialService service;

    public MaterialController(MaterialService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "分页查询资料")
    ApiResponse<PageResponse<Material>> list(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ok(service.list(page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单条资料")
    ApiResponse<Material> get(@Parameter(description = "资料 ID") @PathVariable UUID id) {
        return ok(service.get(id));
    }

    @GetMapping("/project/{projectId}")
    @Operation(summary = "按项目查询资料（带关联标记）", description = "用于关联弹窗，返回每条资料是否已关联该项目")
    ApiResponse<PageResponse<Material>> listByProject(@Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String category,
                                                       @RequestParam(required = false) String owner,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        return ok(service.listByProject(projectId, keyword, category, owner, page, size));
    }

    @PostMapping
    @Operation(summary = "新建资料")
    ApiResponse<UUID> create(@Valid @RequestBody MaterialRequest r) {
        var now = Instant.now();
        var domain = new Material(null, r.code(), r.name(), r.category(),
                r.sourceCategory(), r.sourceModule(), r.stage(), r.contactPerson(),
                r.status() == null ? "DRAFT" : r.status(), r.description(), 0, now, now, false);
        return ok(service.create(domain));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新资料")
    ApiResponse<Void> update(@Parameter(description = "资料 ID") @PathVariable UUID id,
                             @Valid @RequestBody MaterialRequest r) {
        var now = Instant.now();
        var domain = new Material(id, r.code(), r.name(), r.category(),
                r.sourceCategory(), r.sourceModule(), r.stage(), r.contactPerson(),
                r.status(), r.description(), r.version() == null ? 0 : r.version(), null, now, false);
        service.update(id, domain);
        return ok(null);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除资料")
    ApiResponse<Void> delete(@Parameter(description = "资料 ID") @PathVariable UUID id,
                             @RequestParam @PositiveOrZero long version) {
        service.delete(id, version);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record MaterialRequest(@NotBlank @Size(max = 64) String code,
                                  @NotBlank @Size(max = 200) String name,
                                  @NotBlank @Size(max = 50) String category,
                                  @NotBlank @Size(max = 50) String sourceCategory,
                                  @NotBlank @Size(max = 50) String sourceModule,
                                  @Size(max = 30) String stage,
                                  @Size(max = 50) String contactPerson,
                                  @Size(max = 30) String status,
                                  @Size(max = 5000) String description,
                                  @PositiveOrZero Long version) {
    }
}