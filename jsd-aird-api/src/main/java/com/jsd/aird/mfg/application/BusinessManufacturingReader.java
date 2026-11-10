package com.jsd.aird.mfg.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.mfg.inventory.application.port.InventoryRepository;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.Scope;
import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

@Service
public class BusinessManufacturingReader implements BusinessReadPort {
    private final InventoryRepository inventory;
    private final ProductionOrderRepository orders;
    private final ProductionUploadRepository uploads;
    private final ObjectMapper json;
    public BusinessManufacturingReader(InventoryRepository inventory, ProductionOrderRepository orders,
            ProductionUploadRepository uploads, ObjectMapper json) {
        this.inventory = inventory; this.orders = orders; this.uploads = uploads; this.json = json;
    }
    public Set<String> resourceTypes() { return Set.of("INVENTORY_BALANCE", "INVENTORY_TRANSACTION", "SAMPLE_DISPATCH", "SHIPMENT", "PRODUCTION_ORDER", "PRODUCTION_UPLOAD"); }
    public Set<String> filters(String type) { return switch (type) {
        case "INVENTORY_BALANCE", "INVENTORY_TRANSACTION" -> Set.of("query", "scope", "product_id");
        case "SAMPLE_DISPATCH", "SHIPMENT" -> Set.of("query", "scope", "status", "customer_id", "product_id", "business_no");
        case "PRODUCTION_ORDER" -> Set.of("query", "status", "product_id", "business_no");
        default -> Set.of("query", "status", "project_id", "business_no");
    }; }
    public Page references(String type, ObjectNode filter, int page, int size) {
        var org = ActorContext.required().organizationId(); var refs = new ArrayList<ObjectNode>(); boolean more;
        var scope = filter.hasNonNull("scope") ? Scope.valueOf(filter.path("scope").asText()) : null;
        var keyword = filter.path("query").asText("");
        switch (type) {
            case "INVENTORY_BALANCE" -> {
                var rows = inventory.balances(org, keyword, scope, null, page, size, null);
                rows.items().forEach(row -> refs.add(ref(type, row.productId()).put("scope", row.scope().name()))); more = page < rows.totalPages();
            }
            case "INVENTORY_TRANSACTION" -> {
                var rows = inventory.transactions(org, scope, keyword, null, null, page, size);
                rows.items().forEach(row -> refs.add(ref(type, row.id()))); more = page < rows.totalPages();
            }
            case "SAMPLE_DISPATCH" -> {
                var rows = inventory.samples(org, keyword, filter.path("status").asText(null), null, null, page, size);
                rows.items().forEach(row -> refs.add(ref(type, row.id()))); more = page < rows.totalPages();
            }
            case "SHIPMENT" -> {
                var rows = inventory.shipments(org, keyword, page, size);
                rows.items().forEach(row -> refs.add(ref(type, row.id()))); more = page < rows.totalPages();
            }
            case "PRODUCTION_ORDER" -> {
                var rows = orders.list(org, new ProductionOrderRepository.ListQuery(keyword, filter.path("status").asText(null), optionalId(filter, "product_id"), null, null, null, page, size));
                rows.items().forEach(row -> refs.add(ref(type, row.id()))); more = page < rows.totalPages();
            }
            case "PRODUCTION_UPLOAD" -> {
                var rows = uploads.list(org, keyword, filter.path("status").asText(null), optionalId(filter, "project_id"), true, page, size);
                rows.items().forEach(row -> refs.add(ref(type, row.id()))); more = page < rows.totalPages();
            }
            default -> throw missing();
        }
        return new Page(refs, more);
    }
    public BusinessRecordView read(ObjectNode request) {
        var org = ActorContext.required().organizationId(); var type = request.path("type").asText(); var id = id(request);
        var links = new ArrayList<BusinessRecordView.Relation>(); BusinessRecordView result;
        switch (type) {
            case "INVENTORY_BALANCE" -> {
                var scope = Scope.valueOf(request.path("scope").asText());
                var row = inventory.balance(org, id, scope).orElseThrow(BusinessViews::missing);
                var body = facts(json, row, "productId", "productCode", "productName", "scope", "quantityKg", "packageKg", "lowStockKg", "alertStatus", "updatedAt");
                body.put("quantity_semantics", "产品在该库存域的余额；不代表批次库存或已承诺可用量");
                result = view(type, id, null, row.productName() + " · " + scope, body, "/inventory/query", links);
                result.ref().put("scope", scope.name());
            }
            case "INVENTORY_TRANSACTION" -> {
                var row = inventory.findTransaction(org, id).orElseThrow(BusinessViews::missing);
                links.add(new BusinessRecordView.Relation("对应产品余额", ref("INVENTORY_BALANCE", row.productId()).put("scope", row.scope().name())));
                if (row.reversalOf() != null) links.add(link("冲销原流水", type, row.reversalOf()));
                if (row.businessId() != null && "SAMPLE".equals(row.businessType())) links.add(link("发样记录", "SAMPLE_DISPATCH", row.businessId()));
                if (row.businessId() != null && "SHIPMENT".equals(row.businessType())) links.add(link("出货记录", "SHIPMENT", row.businessId()));
                result = view(type, id, row.getRevision(), row.documentNo(), facts(json, row, "productId", "productName", "scope", "direction", "quantityKg", "beforeKg", "afterKg", "businessDate", "documentNo", "businessType", "reason", "note", "reversed"), "/inventory/" + row.scope().name().toLowerCase(Locale.ROOT), links);
            }
            case "SAMPLE_DISPATCH" -> {
                var row = inventory.findSample(org, id).orElseThrow(BusinessViews::missing);
                if (row.customerId() != null) links.add(link("客户", "CUSTOMER", row.customerId()));
                if (row.transactionId() != null) links.add(link("对应库存流水", "INVENTORY_TRANSACTION", row.transactionId()));
                links.add(new BusinessRecordView.Relation("对应产品余额", ref("INVENTORY_BALANCE", row.productId()).put("scope", row.scope().name())));
                result = view(type, id, row.getRevision(), row.dispatchNo(), facts(json, row, "dispatchNo", "businessDate", "productId", "productName", "scope", "quantityKg", "customerId", "customerName", "specification", "status", "customerRequirement", "customerFeedback"), "/inventory/samples", links);
            }
            case "SHIPMENT" -> {
                var row = inventory.findShipment(org, id).orElseThrow(BusinessViews::missing);
                if (row.customerId() != null) links.add(link("客户", "CUSTOMER", row.customerId()));
                if (row.transactionId() != null) links.add(link("对应库存流水", "INVENTORY_TRANSACTION", row.transactionId()));
                links.add(new BusinessRecordView.Relation("对应产品余额", ref("INVENTORY_BALANCE", row.productId()).put("scope", row.scope().name())));
                var body = facts(json, row, "shipmentNo", "businessDate", "productId", "productName", "scope", "quantityKg", "customerId", "customerName", "batchNo", "specification", "status", "coaNo", "note");
                if (row.coaNo() != null && !row.coaNo().isBlank()) {
                    var coa = json.createObjectNode().put("type", "QUALITY_COA"); coa.putObject("filters").put("business_no", row.coaNo());
                    links.add(new BusinessRecordView.Relation("出货COA", coa));
                }
                result = view(type, id, row.getRevision(), row.shipmentNo(), body, "/inventory/shipments", links);
            }
            case "PRODUCTION_ORDER" -> {
                var row = orders.findWorkspace(org, id).orElseThrow(BusinessViews::missing);
                var body = facts(json, row, "orderNo", "status", "productId", "productName", "quantity", "unitCode", "plannedDate", "ownerName", "data", "reconciliationRequired");
                String revision = "DRAFT:" + row.lockVersion();
                if (request.hasNonNull("version_id")) {
                    var version = orders.findRevision(org, id, uuid(request, "version_id")).orElseThrow(BusinessViews::missing);
                    body = facts(json, version, "data", "status"); body.put("versionNo", version.revisionNo());
                    body.put("metadata_note", "固定版本的业务数据；索引名称仅用于定位，不补用当前草稿字段");
                    revision = version.revisionId().toString();
                }
                result = view(type, id, revision, row.orderNo(), body, "/production-orders/" + id + "/workspace", links);
                if (request.hasNonNull("version_id")) result.ref().set("version_id", request.get("version_id"));
            }
            case "PRODUCTION_UPLOAD" -> {
                var row = uploads.find(org, id).orElseThrow(BusinessViews::missing);
                var body = facts(json, row, "productionName", "orderNo", "productName", "category", "manufactureDate", "projectId", "status", "originalName", "sourceType");
                projectLinks(links, row.projectId(), row.stageId(), row.taskId());
                String revision = "CURRENT:" + row.lockVersion();
                if (request.hasNonNull("version_id")) {
                    var version = uploads.versions(org, id).stream().filter(value -> value.id().toString().equals(request.path("version_id").asText())).findFirst().orElseThrow(BusinessViews::missing);
                    body = facts(json, version, "versionNo", "changeType", "createdAt");
                    body.set("content", document(version.workbookSnapshot()));
                    body.put("value_semantics", "该发布版本保存的工作表数值及坐标；不使用当前识别字段代替历史内容");
                    revision = version.id().toString();
                } else {
                    body.set("content", document(row.workbookSnapshot()));
                    body.set("recognitionFields", clean(json.valueToTree(uploads.listRecognitionFields(org, id))));
                    body.put("value_semantics", "保留每个字段的原值、规范值和确认状态；待确认识别值不能作为正式生产事实");
                }
                result = view(type, id, revision, row.productionName(), body, "/production-orders/uploads/" + id + "/workspace", links);
                if (request.hasNonNull("version_id")) result.ref().set("version_id", request.get("version_id"));
            }
            default -> throw missing();
        }
        pinned(request, result); return result;
    }
}
