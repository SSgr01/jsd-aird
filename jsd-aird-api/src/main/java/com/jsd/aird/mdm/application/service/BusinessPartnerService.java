package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.port.BusinessPartnerRepository;
import com.jsd.aird.mdm.application.port.ProjectRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

@Service
public class BusinessPartnerService {
    static String operator() { return ActorContext.required().userId().toString(); }
    private final BusinessPartnerRepository repository;
    private final ProjectRepository projectRepository;
    private final AuditLogFacade audit;
    private final CustomerDataAccess customerAccess;

    @Autowired
    public BusinessPartnerService(BusinessPartnerRepository repository, ProjectRepository projectRepository,
                                  AuditLogFacade audit, CustomerDataAccess customerAccess) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.audit = audit;
        this.customerAccess = customerAccess;
    }

    public BusinessPartnerService(BusinessPartnerRepository repository, ProjectRepository projectRepository,
                                  AuditLogFacade audit) {
        this(repository, projectRepository, audit, null);
    }

    public PageResponse<BusinessPartner> findPage(String keyword, String industry, String customerLevel,
                                                  String cooperationStatus, String owner, PartnerStatus status,
                                                  int page, int size) {
        if (customerAccess == null) return repository.findPage(keyword, industry, customerLevel, cooperationStatus, owner, status,
                Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        var actor = ActorContext.required();
        return repository.findPage(keyword, industry, customerLevel, cooperationStatus, owner, status,
                Math.max(page, 1), Math.min(Math.max(size, 1), 100), actor.organizationId(), actor.userId(),
                customerAccess.scope("customer.list.view"));
    }

    public BusinessPartner get(UUID id) {
        requireAccess(id, "customer.list.view");
        return repository.findById(id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "公司不存在"));
    }

    public void requireAccess(UUID id, String permissionCode) {
        if (customerAccess != null) customerAccess.require(id, permissionCode);
    }

    public com.jsd.aird.iam.api.DataScopeResolver.ResolvedDataScope scope(String permissionCode) {
        return customerAccess == null ? null : customerAccess.scope(permissionCode);
    }

    public com.jsd.aird.iam.api.DataScopeResolver.ResolvedDataScope optionalScope(String permissionCode) {
        return customerAccess == null ? null : customerAccess.optionalScope(permissionCode);
    }

    public boolean mayReadProject(UUID projectId) {
        return customerAccess == null || customerAccess.mayReadProject(projectId);
    }

    public void requireProjectWrite(UUID projectId) {
        if (customerAccess != null) customerAccess.requireProjectWrite(projectId);
    }

    public void requireProjectAction(UUID projectId, String permissionCode) {
        if (customerAccess != null) customerAccess.requireProjectAction(projectId, permissionCode);
    }

    @Transactional
    public PartnerCommands.Created create(PartnerCommands.SavePartner c) {
        requireWrite();
        var normalized = normalize(c.name());
        if (repository.existsByName(normalized, null)) duplicate();
        var now = Instant.now();
        var partner = new BusinessPartner(UUID.randomUUID(), c.partnerCode().trim(), c.name().trim(),
            c.industry(), c.address(), PartnerStatus.ACTIVE, c.remark(), List.of(), c.customerLevel(),
            c.cooperationStatus(), c.mainBusiness(), c.customFields(), 0, now, now);
        repository.insert(partner, normalized, operator());
        appendAudit("CUSTOMER_CREATED", partner.id());
        return new PartnerCommands.Created(partner.id(), 0);
    }

    @Transactional
    public void update(UUID id, PartnerCommands.SavePartner c) {
        requireWrite();
        requireAccess(id, "customer.list.update");
        var current = get(id);
        requireVersion(c.version());
        var normalized = normalize(c.name());
        if (repository.existsByName(normalized, id)) duplicate();
        var updated = new BusinessPartner(id, c.partnerCode().trim(), c.name().trim(), c.industry(), c.address(),
            current.status(), c.remark(), current.contacts(), c.customerLevel(),
            c.cooperationStatus(), c.mainBusiness(), c.customFields(), c.version(), current.createdAt(), Instant.now());
        if (!repository.update(updated, normalized, operator())) {
            conflict();
        }
        projectRepository.refreshPartnerName(id, updated.name(), operator());
        appendAudit("CUSTOMER_UPDATED", id);
    }

    @Transactional
    public void changeStatus(UUID id, PartnerCommands.ChangeStatus c) {
        requireWrite();
        requireAccess(id, "customer.list.update");
        get(id);
        if (!repository.updateStatus(id, c.status(), c.version(), operator())) conflict();
        appendAudit(c.status() == PartnerStatus.ACTIVE ? "CUSTOMER_ACTIVATED" : "CUSTOMER_DEACTIVATED", id);
    }

    @Transactional
    public void deletePartner(UUID id, long version) {
        requireWrite();
        requireAccess(id, "customer.list.delete");
        get(id);
        if (!repository.deletePartner(id, version, operator())) conflict();
        appendAudit("CUSTOMER_DELETED", id);
    }

    public List<AuditLogFacade.AuditEntry> audits(UUID id) {
        get(id);
        var actor = ActorContext.required();
        return audit.list(actor.organizationId(), "BUSINESS_PARTNER", id, 200);
    }

    void appendAudit(String action, UUID partnerId) {
        var actor = ActorContext.required();
        audit.append(actor.organizationId(), actor.userId(), action, "BUSINESS_PARTNER", partnerId,
            JsonNodeFactory.instance.objectNode().put("operator", actor.username()));
    }

    static void requireWrite() {
        var actor = ActorContext.required();
        if (Set.of("VIEWER", "READ_ONLY", "READONLY").contains(actor.role().trim().toUpperCase(Locale.ROOT))) {
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "当前账号仅可查看客户数据，无权执行写操作");
        }
    }

    static String normalize(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    static void requireVersion(Long value) {
        if (value == null || value < 0) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "编辑操作必须提供version");
    }

    static void duplicate() {
        throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "客户编号或公司名称已存在");
    }

    static void conflict() {
        throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "数据已被其他用户修改，请刷新后重试");
    }
}
