package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.port.CommunicationRecordRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.api.PageResponse;
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
        return repository.findCommunications(partnerId, status, page(page), size(size));
    }

    public CommunicationRecord communication(UUID id) {
        return repository.findCommunication(id).orElseThrow(notFound("沟通记录不存在"));
    }

    @Transactional
    public PartnerCommands.Created createCommunication(CommunicationRecord input) {
        var now = Instant.now();
        var value = new CommunicationRecord(UUID.randomUUID(), CrmServiceSupport.code("COMMU"), input.name(), input.partnerId(),
            input.communicatedAt(), input.internalParticipants(), input.communicationMethod(),
            input.content(), CommunicationRecord.CommunicationStatus.OPEN,
            input.customFields(), 0, now, now);
        repository.insertCommunication(value, CrmServiceSupport.OPERATOR);
        return new PartnerCommands.Created(value.id(), 0);
    }

    @Transactional
    public void updateCommunication(UUID id, CommunicationRecord input) {
        var current = communication(id);
        var value = new CommunicationRecord(id, current.recordCode(), input.name(), input.partnerId(),
            input.communicatedAt(), input.internalParticipants(), input.communicationMethod(),
            input.content(), input.status(), input.customFields(), input.version(), current.createdAt(), Instant.now());
        if (!repository.updateCommunication(value, CrmServiceSupport.OPERATOR)) CrmServiceSupport.conflict();
    }

    @Transactional
    public void deleteCommunication(UUID id, long version) {
        communication(id);
        if (!repository.deleteCommunication(id, version)) CrmServiceSupport.conflict();
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
