package com.jsd.aird.mdm.adapter.in.web;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.port.ContactProjectVector;
import com.jsd.aird.mdm.application.service.PartnerContactService;
import com.jsd.aird.mdm.domain.model.PartnerContact;
import com.jsd.aird.mdm.domain.model.PartnerStatus;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.shared.api.ApiResponse;
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
@Tag(name = "业务伙伴-联系人", description = "公司联系人（PartnerContact）的查询、新增、更新与状态管理")
public class PartnerContactController {
    private final PartnerContactService service;

    public PartnerContactController(PartnerContactService service) {
        this.service = service;
    }

    @GetMapping("/{id}/contacts")
    @Operation(summary = "查询联系人列表", description = "获取指定公司的全部联系人")
    public ApiResponse<List<PartnerContact>> findContacts(
        @Parameter(description = "公司 ID") @PathVariable UUID id) {
        return ok(service.findContacts(id));
    }

    @PostMapping("/{id}/contacts")
    @Operation(summary = "新增联系人", description = "为指定公司新增一个联系人")
    public ApiResponse<PartnerCommands.Created> addContact(@Parameter(description = "公司 ID") @PathVariable UUID id,
                                                           @Valid @RequestBody SaveContactRequest r) {
        return ok(service.addContact(id, r.toCommand()));
    }

    @PutMapping("/{id}/contacts/{contactId}")
    @Operation(summary = "更新联系人", description = "更新指定联系人信息（需携带版本号）")
    public ApiResponse<Void> updateContact(@Parameter(description = "公司 ID") @PathVariable UUID id,
                                           @Parameter(description = "联系人 ID") @PathVariable UUID contactId,
                                           @Valid @RequestBody SaveContactRequest r) {
        service.updateContact(id, contactId, r.toCommand());
        return ok(null);
    }

    @PatchMapping("/{id}/contacts/{contactId}/status")
    @Operation(summary = "变更联系人状态", description = "启用 / 停用联系人")
    public ApiResponse<Void> contactStatus(@Parameter(description = "公司 ID") @PathVariable UUID id,
                                           @Parameter(description = "联系人 ID") @PathVariable UUID contactId,
                                           @Valid @RequestBody StatusRequest r) {
        service.changeContactStatus(id, contactId, new PartnerCommands.ChangeStatus(r.status(), r.version()));
        return ok(null);
    }

    @GetMapping("/{id}/contacts/projects")
    @Operation(summary = "查询联系人-项目关联向量", description = "通过 partner_contact_project 关联表，获取指定公司下每个联系人所关联的项目向量数据")
    public ApiResponse<List<ContactProjectVector>> findContactProjectVectors(
        @Parameter(description = "公司 ID") @PathVariable UUID id) {
        return ok(service.findContactProjectVectors(id));
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record SaveContactRequest(@Size(max = 100) String name, @Size(max = 100) String department,
                                     @Size(max = 100) String title, @Size(max = 50) String phone,
                                     @Email @Size(max = 200) String email,
                                     List<String> assignedProjectIds, String members, String wechat,
                                     JsonNode customFields, @PositiveOrZero Long version) {
        PartnerCommands.SaveContact toCommand() {
            return new PartnerCommands.SaveContact(name, department, title, phone, email, assignedProjectIds, members, wechat, customFields, version);
        }
    }

    public record StatusRequest(@NotNull PartnerStatus status, @PositiveOrZero long version) {
    }
}
