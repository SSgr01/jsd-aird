package com.jsd.aird.mdm.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.service.BusinessPartnerService;
import com.jsd.aird.mdm.domain.model.BusinessPartner;
import com.jsd.aird.mdm.domain.model.PartnerStatus;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/business-partners")
@Tag(name = "业务伙伴", description = "公司主体（BusinessPartner）的增删改查与状态管理")
public class BusinessPartnerController {
    private final BusinessPartnerService service;

    public BusinessPartnerController(BusinessPartnerService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "分页查询业务伙伴", description = "按关键字、状态分页查询公司列表")
    public ApiResponse<PageResponse<BusinessPartner>> findPage(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) PartnerStatus status, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ok(service.findPage(keyword, status, page, size));
    }

    @GetMapping("/{id:[0-9a-fA-F-]{36}}")
    @Operation(summary = "查询单个业务伙伴", description = "按 ID 获取公司详情")
    public ApiResponse<BusinessPartner> get(@Parameter(description = "公司 ID") @PathVariable UUID id) {
        return ok(service.get(id));
    }

    @PostMapping
    @Operation(summary = "创建业务伙伴", description = "新增一家公司")
    public ApiResponse<PartnerCommands.Created> create(@Valid @RequestBody SavePartnerRequest r) {
        return ok(service.create(r.toCommand()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新业务伙伴", description = "更新公司信息（需携带版本号做乐观锁）")
    public ApiResponse<Void> update(@Parameter(description = "公司 ID") @PathVariable UUID id,
                                    @Valid @RequestBody SavePartnerRequest r) {
        service.update(id, r.toCommand());
        return ok(null);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "变更业务伙伴状态", description = "启用 / 停用公司")
    public ApiResponse<Void> status(@Parameter(description = "公司 ID") @PathVariable UUID id,
                                    @Valid @RequestBody StatusRequest r) {
        service.changeStatus(id, new PartnerCommands.ChangeStatus(r.status(), r.version()));
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record SavePartnerRequest(@NotBlank @Size(max = 32) String partnerCode,
                                     @NotBlank @Size(max = 200) String name, @Size(max = 100) String industry,
                                     @Size(max = 500) String address, @Size(max = 1000) String remark,
                                     @Size(max = 50) String customerLevel,
                                     @Size(max = 50) String cooperationStatus,
                                     @Size(max = 500) String mainBusiness, JsonNode customFields,
                                     @PositiveOrZero Long version) {
        PartnerCommands.SavePartner toCommand() {
            return new PartnerCommands.SavePartner(partnerCode, name, industry, address, remark,
                customerLevel, cooperationStatus, mainBusiness, customFields, version);
        }
    }

    public record StatusRequest(@NotNull PartnerStatus status, @PositiveOrZero long version) {
    }
}
