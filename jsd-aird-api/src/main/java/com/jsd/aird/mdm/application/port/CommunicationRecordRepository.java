package com.jsd.aird.mdm.application.port;

import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.iam.api.DataScopeResolver.ResolvedDataScope;

import java.util.Optional;
import java.util.UUID;

public interface CommunicationRecordRepository {
    PageResponse<CommunicationRecord> findCommunications(UUID partnerId, String status, int page, int size);

    default PageResponse<CommunicationRecord> findCommunications(UUID partnerId, String status, int page, int size,
            UUID organizationId, UUID userId, ResolvedDataScope scope) {
        return findCommunications(partnerId, status, page, size);
    }

    Optional<CommunicationRecord> findCommunication(UUID id);

    void insertCommunication(CommunicationRecord record, String operator);

    boolean updateCommunication(CommunicationRecord record, String operator);

    boolean deleteCommunication(UUID id, long version, String operator);
}
