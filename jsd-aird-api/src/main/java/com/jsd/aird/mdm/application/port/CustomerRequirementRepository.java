package com.jsd.aird.mdm.application.port;

import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.iam.api.DataScopeResolver.ResolvedDataScope;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRequirementRepository {
    PageResponse<CustomerRequirement> findRequirements(UUID partnerId, UUID projectId, String status, int page, int size);

    default PageResponse<CustomerRequirement> findRequirements(UUID partnerId, UUID projectId, String status,
            int page, int size, UUID organizationId, UUID userId, ResolvedDataScope scope) {
        return findRequirements(partnerId, projectId, status, page, size);
    }

    Optional<CustomerRequirement> findRequirement(UUID id);

    void insertRequirement(CustomerRequirement requirement, String operator);

    boolean updateRequirement(CustomerRequirement requirement, String operator);

    boolean deleteRequirement(UUID id, long version, String operator);
}
