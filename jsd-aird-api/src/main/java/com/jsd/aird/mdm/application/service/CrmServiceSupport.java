package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.error.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class CrmServiceSupport {
    static final String OPERATOR = "system";

    private CrmServiceSupport() {
    }

    static void validatePartnerAndContact(BusinessPartnerService partnerService, UUID partnerId, UUID contactId) {
        if (partnerId == null) return;
        var partner = partnerService.get(partnerId);
        if (contactId != null && partner.contacts().stream().noneMatch(c -> c.id().equals(contactId) && c.status() == PartnerStatus.ACTIVE))
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "联系人不属于该公司或已停用");
    }

    static String code(String prefix) {
        return prefix + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    static void conflict() {
        throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "数据已被其他用户修改，请刷新后重试");
    }
}
