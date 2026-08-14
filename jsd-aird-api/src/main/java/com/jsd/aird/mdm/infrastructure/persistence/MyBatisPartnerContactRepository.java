package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.port.ContactProjectVector;
import com.jsd.aird.mdm.application.port.PartnerContactRepository;
import com.jsd.aird.mdm.domain.model.PartnerContact;
import com.jsd.aird.mdm.domain.model.PartnerStatus;
import com.jsd.aird.mdm.infrastructure.model.PartnerContactRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class MyBatisPartnerContactRepository implements PartnerContactRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BusinessPartnerMapper mapper;

    public MyBatisPartnerContactRepository(BusinessPartnerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void replaceContacts(UUID partnerId, List<PartnerContact> contacts, String operator) {
        mapper.deleteContacts(partnerId);
        contacts.forEach(c -> insertContact(c, operator));
    }

    @Override
    public void insertContact(PartnerContact contact, String operator) {
        mapper.insertContact(contact.id(), contact.partnerId(), contact.name(),
            contact.department(), contact.title(), contact.phone(), contact.email(),
            json(contact.assignedProjectIds()), contact.members(), contact.wechat(), json(contact.customFields()), Instant.now(), operator);
        replaceContactProjects(contact.id(), contact.assignedProjectIds());
    }

    @Override
    public boolean updateContact(PartnerContact contact, String operator) {
        boolean ok = mapper.updateContact(contact.id(), contact.partnerId(), contact.name(),
            contact.department(), contact.title(), contact.phone(), contact.email(),
            json(contact.assignedProjectIds()), contact.members(), contact.wechat(), json(contact.customFields()), contact.version(), operator) == 1;
        if (ok) replaceContactProjects(contact.id(), contact.assignedProjectIds());
        return ok;
    }

    @Override
    public boolean updateContactStatus(UUID partnerId, UUID contactId, PartnerStatus status, long version, String operator) {
        return mapper.updateContactStatus(partnerId, contactId, status.name(), version, operator) == 1;
    }

    @Override
    public List<PartnerContact> findContacts(UUID partnerId) {
        return mapper.findContacts(partnerId).stream().map(MyBatisPartnerContactRepository::toContact).toList();
    }

    @Override
    public List<ContactProjectVector> findContactProjectVectors(UUID partnerId) {
        return mapper.findContactProjectVectors(partnerId);
    }

    private static PartnerContact toContact(PartnerContactRow r) {
        return new PartnerContact(r.id(), r.partnerId(), r.name(), r.department(), r.title(), r.phone(),
            r.email(), PartnerStatus.valueOf(r.status()), parseList(r.assignedProjectIds()), r.members(), r.wechat(),
            r.version(), parse(r.customFields()), r.createdAt(), r.updatedAt());
    }

    private void replaceContactProjects(UUID contactId, List<String> projectIds) {
        mapper.deleteContactProjects(contactId);
        if (projectIds == null) return;
        for (var pid : projectIds) {
            try {
                mapper.insertContactProject(contactId, UUID.fromString(pid));
            } catch (IllegalArgumentException ignore) {
            }
        }
    }

    private static String json(List<String> value) {
        if (value == null || value.isEmpty()) return "[]";
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid assigned_project_ids", exception);
        }
    }

    private static String json(JsonNode value) {
        return value == null ? null : value.toString();
    }

    private static JsonNode parse(String value) {
        try {
            return value == null ? null : JSON.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON stored in custom_fields", exception);
        }
    }

    private static List<String> parseList(String value) {
        try {
            JsonNode node = JSON.readTree(value == null ? "[]" : value);
            var list = new java.util.ArrayList<String>();
            if (node.isArray()) {
                node.forEach(item -> list.add(item.asText()));
            }
            return list;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON stored in assigned_project_ids", exception);
        }
    }
}
