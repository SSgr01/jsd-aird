package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.port.BusinessPartnerRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

@Service
public class BusinessPartnerService {
    static final String OPERATOR = "system";
    private final BusinessPartnerRepository repository;

    public BusinessPartnerService(BusinessPartnerRepository repository) {
        this.repository = repository;
    }

    public PageResponse<BusinessPartner> findPage(String keyword, PartnerStatus status, int page, int size) {
        return repository.findPage(keyword, status, Math.max(page, 1), Math.min(Math.max(size, 1), 100));
    }

    public BusinessPartner get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "公司不存在"));
    }

    @Transactional
    public PartnerCommands.Created create(PartnerCommands.SavePartner c) {
        var normalized = normalize(c.name());
        if (repository.existsByCodeOrNormalizedName(c.partnerCode(), normalized, null)) duplicate();
        var now = Instant.now();
        var partner = new BusinessPartner(UUID.randomUUID(), c.partnerCode().trim(), c.name().trim(),
            c.industry(), c.address(), PartnerStatus.ACTIVE, c.remark(), List.of(), c.customerLevel(),
            c.cooperationStatus(), c.mainBusiness(), c.customFields(), 0, now, now);
        repository.insert(partner, normalized, OPERATOR);
        return new PartnerCommands.Created(partner.id(), 0);
    }

    @Transactional
    public void update(UUID id, PartnerCommands.SavePartner c) {
        var current = get(id);
        requireVersion(c.version());
        var normalized = normalize(c.name());
        if (repository.existsByCodeOrNormalizedName(c.partnerCode(), normalized, id)) duplicate();
        var updated = new BusinessPartner(id, c.partnerCode().trim(), c.name().trim(), c.industry(), c.address(),
            current.status(), c.remark(), current.contacts(), c.customerLevel(),
            c.cooperationStatus(), c.mainBusiness(), c.customFields(), c.version(), current.createdAt(), Instant.now());
        if (!repository.update(updated, normalized, OPERATOR)) conflict();
    }

    @Transactional
    public void changeStatus(UUID id, PartnerCommands.ChangeStatus c) {
        get(id);
        if (!repository.updateStatus(id, c.status(), c.version(), OPERATOR)) conflict();
        repository.audit(id, c.status() == PartnerStatus.INACTIVE ? "SOFT_DELETE" : "ACTIVATE", "status=" + c.status(), OPERATOR);
    }

    static String normalize(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    static void requireVersion(Long value) {
        if (value == null) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "编辑操作必须提供version");
    }

    static void duplicate() {
        throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "客户编号或公司名称已存在");
    }

    static void conflict() {
        throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "数据已被其他用户修改，请刷新后重试");
    }
}
