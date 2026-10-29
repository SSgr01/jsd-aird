package com.jsd.aird.mfg.inventory.application;

import com.jsd.aird.mdm.application.command.PartnerCommands;
import com.jsd.aird.mdm.application.service.BusinessPartnerService;
import com.jsd.aird.mdm.domain.model.PartnerStatus;
import com.jsd.aird.mfg.inventory.application.port.InventoryRepository;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.Scope;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.Shipment;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
public class ShipmentWorkbookImportService {
    private final InventoryService inventory;
    private final InventoryRepository repository;
    private final BusinessPartnerService partners;

    public ShipmentWorkbookImportService(InventoryService inventory, InventoryRepository repository,
                                         BusinessPartnerService partners) {
        this.inventory = inventory;
        this.repository = repository;
        this.partners = partners;
    }

    public record Detail(String shipmentNo, LocalDate businessDate, String productName, String customerName,
                         String batchNo, String specification, BigDecimal packageQuantity,
                         String courierCompany, String note) {}

    @Transactional
    public List<Shipment> importWorkbook(String shipmentNo, List<Detail> details) {
        if (shipmentNo == null || shipmentNo.isBlank() || details == null || details.isEmpty() || details.size() > 100)
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "请选择 1 至 100 条出货明细并填写出货单号");
        var number = shipmentNo.trim();
        var productIds = new HashMap<String, UUID>();
        var customerIds = new HashMap<String, UUID>();
        var inputs = new ArrayList<InventoryService.ShipmentInput>();
        var organization = ActorContext.required().organizationId();
        for (int index = 0; index < details.size(); index++) {
            var row = details.get(index);
            var line = index + 4;
            if (row == null || !number.equals(row.shipmentNo()) || row.businessDate() == null ||
                    row.productName() == null || row.productName().isBlank() ||
                    row.customerName() == null || row.customerName().isBlank())
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "第 " + line + " 行缺少单号、日期、品名或客户名");
            var productName = row.productName().trim();
            var customerName = row.customerName().trim();
            var productId = productIds.get(productName);
            if (productId == null) {
                var matches = repository.productOptionsExact(organization, Scope.PRODUCTION, productName);
                if (matches.size() > 1)
                    throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "第 " + line + " 行品名对应多个产品：" + productName);
                productId = matches.isEmpty()
                        ? inventory.createProduct(new InventoryService.ProductInput(productName, Scope.PRODUCTION)).id()
                        : matches.get(0).id();
                productIds.put(productName, productId);
            }
            var customerId = customerIds.get(customerName);
            if (customerId == null) {
                var matches = new ArrayList<com.jsd.aird.mdm.domain.model.BusinessPartner>();
                var first = partners.findPage(customerName, null, null, null, null, PartnerStatus.ACTIVE, 1, 100);
                first.items().stream().filter(item -> customerName.equals(item.name()) || customerName.equals(item.partnerCode())).forEach(matches::add);
                for (int page = 2; page <= first.totalPages(); page++)
                    partners.findPage(customerName, null, null, null, null, PartnerStatus.ACTIVE, page, 100)
                            .items().stream().filter(item -> customerName.equals(item.name()) || customerName.equals(item.partnerCode())).forEach(matches::add);
                if (matches.size() > 1)
                    throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "第 " + line + " 行客户名对应多个客户：" + customerName);
                customerId = matches.isEmpty()
                        ? partners.create(new PartnerCommands.SavePartner(
                                "CH-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(),
                                customerName, null, null, null, null)).id()
                        : matches.get(0).id();
                customerIds.put(customerName, customerId);
            }
            inputs.add(new InventoryService.ShipmentInput(number, row.businessDate(), productId, Scope.PRODUCTION,
                    row.packageQuantity(), customerId, null, row.note(), null, row.batchNo(),
                    row.specification(), null, null, null, row.courierCompany(), null, "PENDING"));
        }
        return inventory.importShipment(number, inputs);
    }
}
