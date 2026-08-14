package com.jsd.aird.mdm.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BusinessPartner(UUID id, String partnerCode, String name, String industry, String address,
                              PartnerStatus status, String remark,
                              List<PartnerContact> contacts,
                              String customerLevel, String cooperationStatus, String mainBusiness,
                              JsonNode customFields,
                              long version, Instant createdAt, Instant updatedAt,
                              long requirementCount, long projectCount, Instant latestFollowUpAt,
                              List<String> ownerNames) {
    public BusinessPartner(UUID id, String partnerCode, String name, String industry, String address,
                           PartnerStatus status, String remark,
                           List<PartnerContact> contacts, long version, Instant createdAt, Instant updatedAt) {
        this(id, partnerCode, name, industry, address, status, remark, contacts,
            null, null, null, null, version, createdAt, updatedAt,
            0L, 0L, null, List.of());
    }

    public BusinessPartner(UUID id, String partnerCode, String name, String industry, String address,
                           PartnerStatus status, String remark,
                           List<PartnerContact> contacts, String customerLevel, String cooperationStatus,
                           String mainBusiness, JsonNode customFields, long version, Instant createdAt,
                           Instant updatedAt) {
        this(id, partnerCode, name, industry, address, status, remark, contacts, customerLevel,
            cooperationStatus, mainBusiness, customFields, version, createdAt, updatedAt,
            0L, 0L, null, List.of());
    }
}
