package com.jsd.aird.mdm.application.command;

import com.jsd.aird.mdm.domain.model.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PartnerCommands {
    private PartnerCommands() {
    }

    public record SavePartner(String partnerCode, String name, String industry, String address,
                              String remark, String customerLevel,
                              String cooperationStatus, String mainBusiness, JsonNode customFields,
                              Long version) {
        public SavePartner(String partnerCode, String name, String industry, String address, String remark,
                           Long version) {
            this(partnerCode, name, industry, address, remark, null, null, null, null, version);
        }
    }

    public record SaveContact(String name, String department, String title, String phone, String email,
                              List<String> assignedProjectIds, String members, String wechat, JsonNode customFields,
                              Long version) {
    }

    public record ChangeStatus(PartnerStatus status, long version) {
    }

    public record Created(UUID id, long version) {
    }
}
