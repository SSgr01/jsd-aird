package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.service.CommunicationRecordService;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm")
@Tag(name = "客户互动-沟通记录", description = "沟通记录（CommunicationRecord）的查询、新增、更新、跟进与转项目")
public class CommunicationRecordController {
    private final CommunicationRecordService service;

    public CommunicationRecordController(CommunicationRecordService service) {
        this.service = service;
    }

    @GetMapping("/communications")
    @Operation(summary = "分页查询沟通记录", description = "按伙伴、状态分页查询沟通记录")
    ApiResponse<PageResponse<CommunicationRecord>> communications(@RequestParam(required = false) UUID partnerId, @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(service.communications(partnerId, status, page, size));
    }

    @GetMapping("/communications/{id}")
    @Operation(summary = "查询单条沟通记录", description = "按 ID 获取沟通记录详情")
    ApiResponse<CommunicationRecord> communication(@Parameter(description = "沟通记录 ID") @PathVariable UUID id) {
        return ok(service.communication(id));
    }

    @PostMapping("/communications")
    @Operation(summary = "创建沟通记录", description = "新增一条客户沟通记录")
    ApiResponse<PartnerCommands.Created> createCommunication(@Valid @RequestBody CommunicationRequest r) {
        return ok(service.createCommunication(r.toDomain()));
    }

    @PutMapping("/communications/{id}")
    @Operation(summary = "更新沟通记录", description = "更新指定沟通记录（需携带版本号）")
    ApiResponse<Void> updateCommunication(@Parameter(description = "沟通记录 ID") @PathVariable UUID id,
                                           @Valid @RequestBody CommunicationRequest r) {
        service.updateCommunication(id, r.toDomain());
        return ok(null);
    }

    @DeleteMapping("/communications/{id}")
    @Operation(summary = "删除沟通记录", description = "删除指定沟通记录（需携带版本号）")
    ApiResponse<Void> deleteCommunication(@Parameter(description = "沟通记录 ID") @PathVariable UUID id,
                                           @RequestParam @PositiveOrZero Long version) {
        service.deleteCommunication(id, version);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record CommunicationRequest(@NotNull UUID partnerId,
                                       @NotBlank @Size(max = 200) String name,
                                       @NotNull Instant communicatedAt,
                                       @Size(max = 500) String internalParticipants,
                                       @NotBlank @Size(max = 30) String communicationMethod,
                                       @NotBlank String content, CommunicationRecord.CommunicationStatus status,
                                       JsonNode customFields,
                                       @PositiveOrZero Long version) {
        CommunicationRecord toDomain() {
            return new CommunicationRecord(null, null, name, partnerId, communicatedAt, internalParticipants, communicationMethod, content, status == null ? CommunicationRecord.CommunicationStatus.OPEN : status, customFields, version == null ? 0 : version, null, null);
        }
    }
}
