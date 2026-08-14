package com.jsd.aird.mdm.application.port;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.api.PageResponse;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BusinessPartnerRepository {
    PageResponse<BusinessPartner> findPage(String keyword, PartnerStatus status, int page, int size);

    Optional<BusinessPartner> findById(UUID id);

    boolean existsByCodeOrNormalizedName(String code, String normalizedName, UUID excludedId);

    void insert(BusinessPartner partner, String normalizedName, String operator);

    boolean update(BusinessPartner partner, String normalizedName, String operator);

    boolean updateStatus(UUID id, PartnerStatus status, long version, String operator);

    List<PartnerContact> findContacts(UUID partnerId);

    void audit(UUID objectId, String action, String detail, String operator);
}
