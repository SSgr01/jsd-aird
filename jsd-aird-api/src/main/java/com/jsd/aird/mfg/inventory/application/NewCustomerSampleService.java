package com.jsd.aird.mfg.inventory.application;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.service.BusinessPartnerService;
import com.jsd.aird.mdm.application.service.PartnerContactService;
import com.jsd.aird.mfg.inventory.application.port.InventoryRepository;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.Sample;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NewCustomerSampleService {
    private final BusinessPartnerService partners;
    private final PartnerContactService contacts;
    private final InventoryService inventory;
    private final InventoryRepository inventoryRepository;
    private final AuthorizationService authorization;

    public NewCustomerSampleService(BusinessPartnerService partners, PartnerContactService contacts,
                                    InventoryService inventory, InventoryRepository inventoryRepository,
                                    AuthorizationService authorization) {
        this.partners = partners;
        this.contacts = contacts;
        this.inventory = inventory;
        this.inventoryRepository = inventoryRepository;
        this.authorization = authorization;
    }

    public record Input(InventoryService.SampleInput sample, String customerName) {}
    public record UpdateInput(InventoryService.SampleUpdateInput sample, String customerName) {}

    @Transactional
    public Sample update(UUID id, UpdateInput input) {
        if (input == null || input.sample() == null)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "发样信息不能为空");
        var sample = input.sample();
        var customerName = required(input.customerName(), "客户名称");
        var recipient = required(sample.recipientName(), "收件人");
        var phone = required(sample.recipientPhone(), "收件人电话");
        var address = required(sample.sampleAddress(), "发样地址");
        if (customerName.length() > 20 || address.length() > 500)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "客户名称或地址过长");
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(),
                "customer.create", "CUSTOMER", null, "WRITE"));
        var code = "FY-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        var customerId = partners.create(new PartnerCommands.SavePartner(code, customerName,
                null, address, null, null)).id();
        contacts.addContact(customerId, new PartnerCommands.SaveContact(recipient, null, null,
                phone, null, List.of(), null, null, null, null));
        return inventory.updateSample(id, new InventoryService.SampleUpdateInput(sample.revision(), sample.dispatchNo(),
                sample.businessDate(), sample.specification(), sample.packageQuantity(), customerId, recipient, phone, address, sample.courierCompany(),
                sample.trackingNo(), sample.clientContact(), sample.clientContactPhone(), sample.senderName(),
                sample.customerRequirement(), sample.customerFeedback(), sample.status()));
    }

    @Transactional
    public Sample create(Input input, String key) {
        if (input == null || input.sample() == null)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "发样信息不能为空");
        var sample = input.sample();
        var customerName = required(input.customerName(), "客户名称");
        var recipient = required(sample.recipientName(), "收件人");
        var phone = required(sample.recipientPhone(), "收件人电话");
        var address = required(sample.sampleAddress(), "发样地址");
        if (customerName.length() > 20 || address.length() > 500)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "客户名称或地址过长");
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(),
                "customer.create", "CUSTOMER", null, "WRITE"));
        var idempotencyKey = key == null || key.isBlank()
                ? "SAMPLE:" + required(sample.dispatchNo(), "发样单号") : key;
        var prior = inventoryRepository.findByIdempotency(actor.organizationId(), idempotencyKey);
        if (prior.isPresent())
            return inventoryRepository.sampleByTransaction(actor.organizationId(), prior.get().id())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "幂等请求尚未完成"));
        if (inventoryRepository.findSampleByDispatchNo(actor.organizationId(), sample.dispatchNo()).isPresent())
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "发样单号已存在");
        var code = "FY-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        var customerId = partners.create(new PartnerCommands.SavePartner(code, customerName,
                null, address, null, null)).id();
        contacts.addContact(customerId, new PartnerCommands.SaveContact(recipient, null, null,
                phone, null, List.of(), null, null, null, null));
        return inventory.createSample(new InventoryService.SampleInput(sample.dispatchNo(), sample.businessDate(),
                sample.productId(), sample.scope(), sample.quantityKg(), sample.specification(), sample.packageQuantity(), customerId, recipient, phone, address,
                sample.courierCompany(), sample.trackingNo(), sample.clientContact(), sample.clientContactPhone(),
                sample.senderName(), sample.customerRequirement(), sample.customerFeedback(), sample.status()), idempotencyKey);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, label + "不能为空");
        return value.trim();
    }
}
