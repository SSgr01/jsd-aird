package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.port.ContactProjectVector;
import com.jsd.aird.mdm.application.port.PartnerContactRepository;
import com.jsd.aird.mdm.application.port.ProjectRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.error.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PartnerContactService {
    private final PartnerContactRepository repository;
    private final BusinessPartnerService partnerService;
    private final ProjectRepository projectRepository;
    private final AuditLogFacade audit;

    public PartnerContactService(PartnerContactRepository repository, BusinessPartnerService partnerService,
                                 ProjectRepository projectRepository, AuditLogFacade audit) {
        this.repository = repository;
        this.partnerService = partnerService;
        this.projectRepository = projectRepository;
        this.audit = audit;
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
        BusinessPartnerService.requireWrite();
        var partner = partnerService.get(partnerId);
        requireNotDuplicate(partnerId, c.phone(), c.name(), null);
        var now = Instant.now();
        var contact = new PartnerContact(UUID.randomUUID(), partnerId, trim(c.name()), c.department(), c.title(),
            c.phone(), c.email(), PartnerStatus.ACTIVE, c.assignedProjectIds(), c.members(), c.wechat(), 0, c.customFields(), now, now);
        repository.insertContact(contact, BusinessPartnerService.OPERATOR);
        ensureProjectPartners(partnerId, partner.name(), contact.assignedProjectIds());
        partnerService.appendAudit("CONTACT_CREATED", partnerId);
        return new PartnerCommands.Created(contact.id(), 0);
    }

    @Transactional
    public void updateContact(UUID partnerId, UUID contactId, PartnerCommands.SaveContact c) {
        BusinessPartnerService.requireWrite();
        var partner = partnerService.get(partnerId);
        BusinessPartnerService.requireVersion(c.version());
        requireNotDuplicate(partnerId, c.phone(), c.name(), contactId);
        var previousProjects = repository.findContacts(partnerId).stream()
            .filter(item -> item.id().equals(contactId))
            .findFirst()
            .map(PartnerContact::assignedProjectIds)
            .orElse(List.of());
        var contact = new PartnerContact(contactId, partnerId, trim(c.name()), c.department(), c.title(),
            c.phone(), c.email(), PartnerStatus.ACTIVE, c.assignedProjectIds(), c.members(), c.wechat(), c.version(),
            c.customFields(), null, Instant.now());
        if (!repository.updateContact(contact, BusinessPartnerService.OPERATOR)) {
            BusinessPartnerService.conflict();
        }
        synchronizeProjectPartners(partnerId, partner.name(), previousProjects, contact.assignedProjectIds());
        partnerService.appendAudit("CONTACT_UPDATED", partnerId);
    }

    private void requireNotDuplicate(UUID partnerId, String phone, String name, UUID excludedId) {
        if (phone == null && name == null) return;
        if (repository.existsContact(partnerId, phone == null ? null : phone.trim(),
            name == null ? null : name.trim(), excludedId))
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "该客户下已存在相同" + (phone == null || phone.isBlank() ? "姓名" : "手机号") + "的负责人");
    }

    @Transactional
    public void changeContactStatus(UUID partnerId, UUID contactId, PartnerCommands.ChangeStatus c) {
        BusinessPartnerService.requireWrite();
        var partner = partnerService.get(partnerId);
        var assignedProjects = repository.findContacts(partnerId).stream()
            .filter(item -> item.id().equals(contactId))
            .findFirst()
            .map(PartnerContact::assignedProjectIds)
            .orElse(List.of());
        if (!repository.updateContactStatus(partnerId, contactId, c.status(), c.version(), BusinessPartnerService.OPERATOR)) {
            BusinessPartnerService.conflict();
        }
        if (c.status() == PartnerStatus.ACTIVE) {
            ensureProjectPartners(partnerId, partner.name(), assignedProjects);
        } else {
            clearProjectPartnersWhenUnassigned(partnerId, assignedProjects);
        }
        partnerService.appendAudit(c.status() == PartnerStatus.ACTIVE ? "CONTACT_ACTIVATED" : "CONTACT_DEACTIVATED", partnerId);
    }

    private void synchronizeProjectPartners(UUID partnerId, String partnerName,
                                            List<String> previousProjects, List<String> currentProjects) {
        var before = normalizeProjectIds(previousProjects);
        var after = normalizeProjectIds(currentProjects);
        var added = new HashSet<>(after);
        added.removeAll(before);
        ensureProjectPartners(partnerId, partnerName, added);
        var removed = new HashSet<>(before);
        removed.removeAll(after);
        clearProjectPartnersWhenUnassigned(partnerId, removed);
    }

    private void ensureProjectPartners(UUID partnerId, String partnerName, List<String> projectIds) {
        ensureProjectPartners(partnerId, partnerName, normalizeProjectIds(projectIds));
    }

    private void ensureProjectPartners(UUID partnerId, String partnerName, Set<UUID> projectIds) {
        for (UUID projectId : projectIds) {
            projectRepository.assignPartner(projectId, partnerId, partnerName, BusinessPartnerService.OPERATOR);
        }
    }

    private void clearProjectPartnersWhenUnassigned(UUID partnerId, List<String> projectIds) {
        clearProjectPartnersWhenUnassigned(partnerId, normalizeProjectIds(projectIds));
    }

    private void clearProjectPartnersWhenUnassigned(UUID partnerId, Set<UUID> projectIds) {
        for (UUID projectId : projectIds) {
            if (!repository.hasActiveProjectAssignment(partnerId, projectId)) {
                projectRepository.clearPartner(projectId, partnerId, BusinessPartnerService.OPERATOR);
            }
        }
    }

    private static Set<UUID> normalizeProjectIds(List<String> projectIds) {
        var normalized = new HashSet<UUID>();
        if (projectIds == null) return normalized;
        for (String projectId : projectIds) {
            try {
                if (projectId != null && !projectId.isBlank()) normalized.add(UUID.fromString(projectId));
            } catch (IllegalArgumentException ignored) {
                // Ignore stale/non-UUID project references from legacy data.
            }
        }
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
