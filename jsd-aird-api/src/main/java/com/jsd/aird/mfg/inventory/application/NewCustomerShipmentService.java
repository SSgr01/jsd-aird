package com.jsd.aird.mfg.inventory.application;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.service.BusinessPartnerService;
import com.jsd.aird.mdm.application.service.PartnerContactService;
import com.jsd.aird.mfg.inventory.application.port.InventoryRepository;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.Shipment;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NewCustomerShipmentService {
    private final BusinessPartnerService partners;
    private final PartnerContactService contacts;
    private final InventoryService inventory;
    private final InventoryRepository inventoryRepository;
    private final AuthorizationService authorization;

    public NewCustomerShipmentService(BusinessPartnerService partners, PartnerContactService contacts,
                                      InventoryService inventory, InventoryRepository inventoryRepository,
                                      AuthorizationService authorization) {
        this.partners = partners;
        this.contacts = contacts;
        this.inventory = inventory;
        this.inventoryRepository = inventoryRepository;
        this.authorization = authorization;
    }

    public record Input(InventoryService.ShipmentInput shipment, String customerName) {}
    public record UpdateInput(InventoryService.ShipmentUpdateInput shipment, String customerName) {}

    @Transactional
    public Shipment create(Input input, String key) {
        if (input == null || input.shipment() == null)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "出货信息不能为空");
        var shipment = input.shipment();
        var customerName = required(input.customerName(), "客户名称");
        var recipient = required(shipment.recipientName(), "收件人");
        var phone = required(shipment.recipientPhone(), "收件人电话");
        var address = required(shipment.shippingAddress(), "收货地址");
        validateLengths(customerName, address);
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(),
                "customer.create", "CUSTOMER", null, "WRITE"));
        var idempotencyKey = key == null || key.isBlank()
                ? "SHIPMENT:" + UUID.randomUUID() : key;
        var prior = inventoryRepository.findByIdempotency(actor.organizationId(), idempotencyKey);
        if (prior.isPresent())
            return inventoryRepository.shipmentByTransaction(actor.organizationId(), prior.get().id())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "幂等请求尚未完成"));
        var customerId = createCustomer(customerName, recipient, phone, address);
        return inventory.createShipment(new InventoryService.ShipmentInput(shipment.shipmentNo(),
                shipment.businessDate(), shipment.productId(), shipment.scope(), shipment.packageQuantity(),
                customerId, shipment.senderName(), shipment.note(), shipment.coaNo(), shipment.batchNo(),
                shipment.specification(), recipient, phone, address, shipment.courierCompany(), shipment.trackingNo(),
                shipment.status()),
                idempotencyKey);
    }

    @Transactional
    public Shipment update(UUID id, UpdateInput input) {
        if (input == null || input.shipment() == null)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "出货信息不能为空");
        var shipment = input.shipment();
        var customerName = required(input.customerName(), "客户名称");
        var recipient = required(shipment.recipientName(), "收件人");
        var phone = required(shipment.recipientPhone(), "收件人电话");
        var address = required(shipment.shippingAddress(), "收货地址");
        validateLengths(customerName, address);
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(),
                "customer.create", "CUSTOMER", null, "WRITE"));
        var customerId = createCustomer(customerName, recipient, phone, address);
        return inventory.updateShipment(id, new InventoryService.ShipmentUpdateInput(shipment.revision(), shipment.shipmentNo(),
                shipment.businessDate(), customerId, shipment.senderName(), shipment.note(), shipment.batchNo(),
                recipient, phone, address, shipment.courierCompany(), shipment.trackingNo(), shipment.coaNo(),
                shipment.status()));
    }

    private UUID createCustomer(String name, String recipient, String phone, String address) {
        var code = "CH-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        var customerId = partners.create(new PartnerCommands.SavePartner(code, name,
                null, address, null, null)).id();
        contacts.addContact(customerId, new PartnerCommands.SaveContact(recipient, null, null,
                phone, null, List.of(), null, null, null, null));
        return customerId;
    }

    private static void validateLengths(String name, String address) {
        if (name.length() > 20 || address.length() > 500)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "客户名称或地址过长");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, label + "不能为空");
        return value.trim();
    }
}
