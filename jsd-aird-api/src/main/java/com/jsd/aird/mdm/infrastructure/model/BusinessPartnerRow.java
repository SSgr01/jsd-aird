package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

public record BusinessPartnerRow(UUID id, String partnerCode, String name, String industry, String address,
                                 String status, String remark, String customerLevel, String cooperationStatus,
                                 String mainBusiness, String customFields, long version, Instant createdAt, Instant updatedAt) {
}
