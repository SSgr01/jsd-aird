package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.service.CustomerRequirementService;
import com.jsd.aird.mdm.domain.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm")
@Tag(name = "客户互动-需求", description = "客户需求（CustomerRequirement）的查询、新增、更新与指标管理")
public class CustomerRequirementController {
    private final CustomerRequirementService service;

    public CustomerRequirementController(CustomerRequirementService service) {
        this.service = service;
    }

    @GetMapping("/requirements")
    @Operation(summary = "分页查询客户需求", description = "按伙伴、项目、状态分页查询客户需求")
    ApiResponse<PageResponse<CustomerRequirement>> requirements(@RequestParam(required = false) UUID partnerId, @RequestParam(required = false) UUID projectId, @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(service.requirements(partnerId, projectId, status, page, size));
    }

    @GetMapping("/requirements/{id}")
    @Operation(summary = "查询单条需求", description = "按 ID 获取客户需求详情（含指标）")
    ApiResponse<CustomerRequirement> requirement(@Parameter(description = "需求 ID") @PathVariable UUID id) {
        return ok(service.requirement(id));
    }

    @PostMapping("/requirements")
    @Operation(summary = "创建客户需求", description = "新增一条客户需求（至少包含一项目标指标）")
    ApiResponse<UUID> createRequirement(@Valid @RequestBody RequirementRequest r) {
        return ok(service.createRequirement(r.toDomain()).id());
    }

    @PutMapping("/requirements/{id}")
    @Operation(summary = "更新客户需求", description = "更新指定需求（需携带版本号）")
    ApiResponse<Void> updateRequirement(@Parameter(description = "需求 ID") @PathVariable UUID id,
                                        @Valid @RequestBody RequirementRequest r) {
        service.updateRequirement(id, r.toDomain());
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record RequirementRequest(UUID partnerId, @NotBlank @Size(max = 200) String title,
                                     String rawRequirement,
                                     @Size(max = 20) String urgency, LocalDate raisedAt,
                                     LocalDate deliveryDate, CustomerRequirement.RequirementStatus status,
                                     @Size(max = 50) String customStatusName, UUID projectId, JsonNode customFields,
                                     @PositiveOrZero Long version) {
        CustomerRequirement toDomain() {
            return new CustomerRequirement(null, null, partnerId, title, rawRequirement, urgency, raisedAt, deliveryDate, status == null ? CustomerRequirement.RequirementStatus.DRAFT : status, customStatusName, projectId, customFields, version == null ? 0 : version, null, null);
        }
    }
}
