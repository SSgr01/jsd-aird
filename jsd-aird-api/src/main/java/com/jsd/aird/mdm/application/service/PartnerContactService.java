package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.port.ContactProjectVector;
import com.jsd.aird.mdm.application.port.PartnerContactRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.error.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PartnerContactService {
    private final PartnerContactRepository repository;
    private final BusinessPartnerService partnerService;

    public PartnerContactService(PartnerContactRepository repository, BusinessPartnerService partnerService) {
        this.repository = repository;
        this.partnerService = partnerService;
    }

    public List<PartnerContact> findContacts(UUID partnerId) {
        partnerService.get(partnerId);
        return repository.findContacts(partnerId);
    }

    public List<ContactProjectVector> findContactProjectVectors(UUID partnerId) {
        partnerService.get(partnerId);
        return repository.findContactProjectVectors(partnerId);
    }

    @Transactional
    public PartnerCommands.Created addContact(UUID partnerId, PartnerCommands.SaveContact c) {
        partnerService.get(partnerId);
        var now = Instant.now();
        var contact = new PartnerContact(UUID.randomUUID(), partnerId, trim(c.name()), c.department(), c.title(),
            c.phone(), c.email(), PartnerStatus.ACTIVE, c.assignedProjectIds(), c.members(), c.wechat(), 0, c.customFields(), now, now);
        repository.insertContact(contact, BusinessPartnerService.OPERATOR);
        return new PartnerCommands.Created(contact.id(), 0);
    }

    @Transactional
    public void updateContact(UUID partnerId, UUID contactId, PartnerCommands.SaveContact c) {
        partnerService.get(partnerId);
        BusinessPartnerService.requireVersion(c.version());
        var contact = new PartnerContact(contactId, partnerId, trim(c.name()), c.department(), c.title(),
            c.phone(), c.email(), PartnerStatus.ACTIVE, c.assignedProjectIds(), c.members(), c.wechat(), c.version(),
            c.customFields(), null, Instant.now());
        if (!repository.updateContact(contact, BusinessPartnerService.OPERATOR)) BusinessPartnerService.conflict();
    }

    @Transactional
    public void changeContactStatus(UUID partnerId, UUID contactId, PartnerCommands.ChangeStatus c) {
        partnerService.get(partnerId);
        if (!repository.updateContactStatus(partnerId, contactId, c.status(), c.version(), BusinessPartnerService.OPERATOR))
            BusinessPartnerService.conflict();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
