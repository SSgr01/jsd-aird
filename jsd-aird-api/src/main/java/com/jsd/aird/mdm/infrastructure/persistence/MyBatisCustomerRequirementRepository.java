package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.port.CustomerRequirementRepository;
import com.jsd.aird.mdm.domain.model.CustomerRequirement;
import com.jsd.aird.mdm.infrastructure.model.RequirementRow;
import com.jsd.aird.shared.api.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
            projectIdsJson(requirement.projectId()), json(requirement.customFields()), Instant.now(), operator);
    }

    @Override
    public boolean updateRequirement(CustomerRequirement requirement, String operator) {
        return mapper.updateRequirement(requirement.id(), requirement.title(), requirement.rawRequirement(),
            requirement.urgency(), requirement.raisedAt(), requirement.deliveryDate(),
            requirement.status().name(), requirement.customStatusName(),
            projectIdsJson(requirement.projectId()), json(requirement.customFields()),
            requirement.version(), operator) == 1;
    }

    private static CustomerRequirement toDomain(RequirementRow r) {
        return new CustomerRequirement(r.id(), r.requirementCode(), r.partnerId(), r.title(), r.rawRequirement(),
            r.urgency(), r.raisedAt(), r.deliveryDate(),
            CustomerRequirement.RequirementStatus.valueOf(r.status()), r.customStatusName(),
            firstProjectId(r.assignedProjectIds()), parse(r.customFields()), r.version(), r.createdAt(), r.updatedAt());
    }

    private static UUID firstProjectId(String assignedProjectIds) {
        try {
            JsonNode node = JSON.readTree(assignedProjectIds == null ? "[]" : assignedProjectIds);
            if (node.isArray() && node.size() > 0) {
                return UUID.fromString(node.get(0).asText());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String projectIdsJson(UUID projectId) {
        ArrayNode arr = JSON.createArrayNode();
        if (projectId != null) {
            arr.add(projectId.toString());
        }
        return arr.toString();
    }

    private static String json(JsonNode value) {
        return value == null || value.isNull() ? "{}" : value.toString();
    }

    private static JsonNode parse(String value) {
        try {
            return JSON.readTree(value == null ? "{}" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON stored in custom_fields", exception);
        }
    }
}
