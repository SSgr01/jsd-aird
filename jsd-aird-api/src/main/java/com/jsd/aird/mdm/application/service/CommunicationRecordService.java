package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.port.CommunicationRecordRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.error.ApiErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CommunicationRecordService {
    private final CommunicationRecordRepository repository;
    private final BusinessPartnerService partnerService;

    public CommunicationRecordService(CommunicationRecordRepository repository,
                                      BusinessPartnerService partnerService) {
        this.repository = repository;
        this.partnerService = partnerService;
    }

    public PageResponse<CommunicationRecord> communications(UUID partnerId, String status, int page, int size) {
        var actor = ActorContext.required();
        return repository.findCommunications(partnerId, status, page(page), size(size),
                actor.organizationId(), actor.userId(), partnerService.scope("customer.followup.view"));
    }

    public CommunicationRecord communication(UUID id) {
        var value = repository.findCommunication(id).orElseThrow(notFound("沟通记录不存在"));
        partnerService.requireAccess(value.partnerId(), "customer.followup.view");
        return value;
    }

    @Transactional
    public PartnerCommands.Created createCommunication(CommunicationRecord input) {
        BusinessPartnerService.requireWrite();
        partnerService.requireAccess(input.partnerId(), "customer.followup.create");
        var now = Instant.now();
        var value = new CommunicationRecord(UUID.randomUUID(), CrmServiceSupport.code("COMMU"), input.name(), input.partnerId(),
            input.communicatedAt(), input.internalParticipants(), input.communicationMethod(),
            input.content(), CommunicationRecord.CommunicationStatus.OPEN,
            input.customFields(), 0, now, now);
        repository.insertCommunication(value, CrmServiceSupport.operator());
        partnerService.appendAudit("COMMUNICATION_CREATED", value.partnerId());
        return new PartnerCommands.Created(value.id(), 0);
    }

    @Transactional
    public void updateCommunication(UUID id, CommunicationRecord input) {
        BusinessPartnerService.requireWrite();
        var current = communication(id);
        partnerService.requireAccess(current.partnerId(), "customer.followup.update");
        partnerService.requireAccess(input.partnerId(), "customer.followup.update");
        var value = new CommunicationRecord(id, current.recordCode(), input.name(), input.partnerId(),
            input.communicatedAt(), input.internalParticipants(), input.communicationMethod(),
            input.content(), input.status(), input.customFields(), input.version(), current.createdAt(), Instant.now());
        if (!repository.updateCommunication(value, CrmServiceSupport.operator())) CrmServiceSupport.conflict();
        partnerService.appendAudit("COMMUNICATION_UPDATED", value.partnerId());
    }

    @Transactional
    public void deleteCommunication(UUID id, long version) {
        BusinessPartnerService.requireWrite();
        var current = communication(id);
        partnerService.requireAccess(current.partnerId(), "customer.followup.delete");
        if (!repository.deleteCommunication(id, version, CrmServiceSupport.operator())) CrmServiceSupport.conflict();
        partnerService.appendAudit("COMMUNICATION_DELETED", current.partnerId());
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
