package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.port.CustomerRequirementRepository;
import com.jsd.aird.mdm.domain.model.CustomerRequirement;
import com.jsd.aird.mdm.infrastructure.model.RequirementRow;
import com.jsd.aird.shared.api.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisCustomerRequirementRepository implements CustomerRequirementRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CustomerRequirementMapper mapper;

    public MyBatisCustomerRequirementRepository(CustomerRequirementMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResponse<CustomerRequirement> findRequirements(UUID partnerId, UUID projectId, String status, int page, int size) {
        var total = mapper.count(partnerId, projectId, status);
        var rows = mapper.findPage(partnerId, projectId, status, (page - 1) * size, size);
        var items = rows.stream().map(MyBatisCustomerRequirementRepository::toDomain).toList();
        return new PageResponse<>(items, page, size, total, (total + size - 1) / size);
    }

    @Override
    public Optional<CustomerRequirement> findRequirement(UUID id) {
        return mapper.findById(id).map(MyBatisCustomerRequirementRepository::toDomain);
    }

    @Override
    public void insertRequirement(CustomerRequirement requirement, String operator) {
        mapper.insertRequirement(requirement.id(), requirement.requirementCode(), requirement.partnerId(),
            requirement.title(), requirement.rawRequirement(), requirement.urgency(), requirement.raisedAt(),
            requirement.deliveryDate(), requirement.status().name(), requirement.customStatusName(),
            requirement.projectId(), json(requirement.projectIds()), json(requirement.customFields()), Instant.now(), operator);
    }

    @Override
    public boolean updateRequirement(CustomerRequirement requirement, String operator) {
        return mapper.updateRequirement(requirement.id(), requirement.title(), requirement.rawRequirement(),
            requirement.urgency(), requirement.raisedAt(), requirement.deliveryDate(),
            requirement.status().name(), requirement.customStatusName(),
            requirement.projectId(), json(requirement.projectIds()), json(requirement.customFields()),
            requirement.version(), operator) == 1;
    }

    private static CustomerRequirement toDomain(RequirementRow r) {
        return new CustomerRequirement(r.id(), r.requirementCode(), r.partnerId(), r.title(), r.rawRequirement(),
            r.urgency(), r.raisedAt(), r.deliveryDate(),
            CustomerRequirement.RequirementStatus.valueOf(r.status()), r.customStatusName(),
            r.projectId(), parseProjectIds(r.assignedProjectIds(), r.projectId()), parse(r.customFields()),
            r.version(), r.createdAt(), r.updatedAt());
    }

    private static String json(JsonNode value) {
        return value == null || value.isNull() ? "{}" : value.toString();
    }

    private static String json(List<UUID> value) {
        return value == null ? "[]" : value.stream().map(UUID::toString).map(s -> "\"" + s + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<UUID> parseProjectIds(String value, UUID legacyProjectId) {
        try {
            var node = JSON.readTree(value == null ? "[]" : value);
            var ids = new ArrayList<UUID>();
            if (node != null && node.isArray()) {
                node.forEach(item -> {
                    if (item.isTextual()) ids.add(UUID.fromString(item.asText()));
                });
            }
            if (ids.isEmpty() && legacyProjectId != null) ids.add(legacyProjectId);
            return List.copyOf(ids);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid JSON stored in assigned_project_ids", exception);
        }
    }

    private static JsonNode parse(String value) {
        try {
            return JSON.readTree(value == null ? "{}" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON stored in custom_fields", exception);
        }
    }
}
