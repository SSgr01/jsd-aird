package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.port.CustomerRequirementRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.error.ApiErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CustomerRequirementService {
    private final CustomerRequirementRepository repository;
    private final BusinessPartnerService partnerService;

    public CustomerRequirementService(CustomerRequirementRepository repository, BusinessPartnerService partnerService) {
        this.repository = repository;
        this.partnerService = partnerService;
    }

    public PageResponse<CustomerRequirement> requirements(UUID partnerId, UUID projectId, String status, int page, int size) {
        return repository.findRequirements(partnerId, projectId, status, page(page), size(size));
    }

    public CustomerRequirement requirement(UUID id) {
        return repository.findRequirement(id).orElseThrow(notFound("客户需求不存在"));
    }

    @Transactional
    public PartnerCommands.Created createRequirement(CustomerRequirement input) {
        CrmServiceSupport.validatePartnerAndContact(partnerService, input.partnerId(), null);
        var now = java.time.Instant.now();
        var value = new CustomerRequirement(UUID.randomUUID(), CrmServiceSupport.code("REQ"), input.partnerId(),
            input.title(), input.rawRequirement(), input.urgency(), input.raisedAt(), input.deliveryDate(), CustomerRequirement.RequirementStatus.DRAFT,
            input.customStatusName(), input.projectId(), input.customFields(), 0, now, now);
        repository.insertRequirement(value, CrmServiceSupport.OPERATOR);
        return new PartnerCommands.Created(value.id(), 0);
    }

    @Transactional
    public void updateRequirement(UUID id, CustomerRequirement input) {
        var current = requirement(id);
        CrmServiceSupport.validatePartnerAndContact(partnerService, input.partnerId(), null);
        var value = new CustomerRequirement(id, current.requirementCode(), input.partnerId(), input.title(),
            input.rawRequirement(), input.urgency(), input.raisedAt(), input.deliveryDate(),
            input.status(), input.customStatusName(), input.projectId(), input.customFields(), input.version(),
            current.createdAt(), java.time.Instant.now());
        if (!repository.updateRequirement(value, CrmServiceSupport.OPERATOR)) CrmServiceSupport.conflict();
    }

    private static int page(int value) {
        return Math.max(value, 1);
    }

    private static int size(int value) {
        return Math.min(Math.max(value, 1), 100);
    }

    private static Supplier<ApiException> notFound(String message) {
        return () -> new ApiException(ApiErrorCode.NOT_FOUND, message);
    }
}
