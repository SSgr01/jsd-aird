package com.jsd.aird.mdm.application.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.port.CustomerRequirementRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;
import java.util.function.Supplier;

@Service
public class CustomerRequirementService {
    private final CustomerRequirementRepository repository;
    private final BusinessPartnerService partnerService;
    private final AuditLogFacade audit;

    public CustomerRequirementService(CustomerRequirementRepository repository, BusinessPartnerService partnerService,
                                      AuditLogFacade audit) {
        this.repository = repository;
        this.partnerService = partnerService;
        this.audit = audit;
    }

    public PageResponse<CustomerRequirement> requirements(UUID partnerId, UUID projectId, String status, int page, int size) {
        return repository.findRequirements(partnerId, projectId, status, page(page), size(size));
    }

    public CustomerRequirement requirement(UUID id) {
        return repository.findRequirement(id).orElseThrow(notFound("客户需求不存在"));
    }

    @Transactional
    public PartnerCommands.Created createRequirement(CustomerRequirement input) {
        BusinessPartnerService.requireWrite();
        CrmServiceSupport.validatePartnerAndContact(partnerService, input.partnerId(), null);
        var now = java.time.Instant.now();
        var projectIds = normalizeProjectIds(input.projectIds(), input.projectId());
        var value = new CustomerRequirement(UUID.randomUUID(), CrmServiceSupport.code("REQ"), input.partnerId(),
            input.title(), input.rawRequirement(), input.urgency(), input.raisedAt(), input.deliveryDate(), input.status(),
            input.customStatusName(), firstProjectId(projectIds), projectIds, input.customFields(), 0, now, now);
        repository.insertRequirement(value, CrmServiceSupport.OPERATOR);
        appendAudit("CUSTOMER_REQUIREMENT_CREATED", value);
        return new PartnerCommands.Created(value.id(), 0);
    }

    @Transactional
    public void updateRequirement(UUID id, CustomerRequirement input) {
        BusinessPartnerService.requireWrite();
        var current = requirement(id);
        CrmServiceSupport.validatePartnerAndContact(partnerService, input.partnerId(), null);
        var projectIds = normalizeProjectIds(input.projectIds(), input.projectId());
        var value = new CustomerRequirement(id, current.requirementCode(), input.partnerId(), input.title(),
            input.rawRequirement(), input.urgency(), input.raisedAt(), input.deliveryDate(),
            input.status(), input.customStatusName(), firstProjectId(projectIds), projectIds, input.customFields(), input.version(),
            current.createdAt(), java.time.Instant.now());
        if (!repository.updateRequirement(value, CrmServiceSupport.OPERATOR)) CrmServiceSupport.conflict();
        appendAudit("CUSTOMER_REQUIREMENT_UPDATED", value);
    }

    @Transactional
    public void deleteRequirement(UUID id, long version) {
        BusinessPartnerService.requireWrite();
        var current = requirement(id);
        if (!repository.deleteRequirement(id, version)) CrmServiceSupport.conflict();
        if (current.partnerId() != null) {
            partnerService.appendAudit("CUSTOMER_REQUIREMENT_DELETED", current.partnerId());
        } else {
            var actor = ActorContext.required();
            audit.append(actor.organizationId(), actor.userId(), "CUSTOMER_REQUIREMENT_DELETED",
                "CUSTOMER_REQUIREMENT", current.id(),
                JsonNodeFactory.instance.objectNode().put("operator", actor.username())
                    .put("requirementCode", current.requirementCode()));
        }
    }

    private void appendAudit(String action, CustomerRequirement value) {
        if (value.partnerId() != null) {
            partnerService.appendAudit(action, value.partnerId());
            return;
        }
        var actor = ActorContext.required();
        audit.append(actor.organizationId(), actor.userId(), action, "CUSTOMER_REQUIREMENT", value.id(),
            JsonNodeFactory.instance.objectNode().put("operator", actor.username()));
    }

    private static int page(int value) {
        return Math.max(value, 1);
    }

    private static int size(int value) {
        return Math.min(Math.max(value, 1), 100);
    }

    private static List<UUID> normalizeProjectIds(List<UUID> projectIds, UUID legacyProjectId) {
        if (projectIds == null || projectIds.isEmpty()) return legacyProjectId == null ? List.of() : List.of(legacyProjectId);
        return projectIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static UUID firstProjectId(List<UUID> projectIds) {
        return projectIds.isEmpty() ? null : projectIds.get(0);
    }

    private static Supplier<ApiException> notFound(String message) {
        return () -> new ApiException(ApiErrorCode.NOT_FOUND, message);
    }
}
