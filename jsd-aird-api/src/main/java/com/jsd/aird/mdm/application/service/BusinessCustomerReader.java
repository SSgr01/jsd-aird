package com.jsd.aird.mdm.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mdm.application.port.*;
import com.jsd.aird.shared.business.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

/** Uses the existing customer repositories; access is decided by the business gateway. */
@Service
public class BusinessCustomerReader implements BusinessReadPort {
    private final BusinessPartnerRepository customers;
    private final PartnerContactRepository contacts;
    private final CommunicationRecordRepository communications;
    private final CustomerRequirementRepository requirements;
    private final ObjectMapper json;
    public BusinessCustomerReader(BusinessPartnerRepository customers, PartnerContactRepository contacts,
            CommunicationRecordRepository communications, CustomerRequirementRepository requirements, ObjectMapper json) {
        this.customers = customers; this.contacts = contacts; this.communications = communications;
        this.requirements = requirements; this.json = json;
    }
    public Set<String> resourceTypes() { return Set.of("CUSTOMER", "CUSTOMER_CONTACT", "CUSTOMER_COMMUNICATION", "CUSTOMER_REQUIREMENT"); }
    public Set<String> requiredFilters(String type) { return "CUSTOMER_CONTACT".equals(type) ? Set.of("customer_id") : Set.of(); }
    public Set<String> filters(String type) { return switch (type) {
        case "CUSTOMER" -> Set.of("query", "status");
        case "CUSTOMER_CONTACT", "CUSTOMER_COMMUNICATION" -> Set.of("query", "customer_id", "status");
        default -> Set.of("query", "customer_id", "project_id", "status");
    }; }
    public Page references(String type, ObjectNode filter, int page, int size) {
        var refs = new ArrayList<ObjectNode>(); boolean more;
        switch (type) {
            case "CUSTOMER" -> {
                var result = customers.findPage(filter.path("query").asText(""), null, null, null, null, null, page, size);
                result.items().forEach(row -> refs.add(ref(type, row.id()))); more = page < result.totalPages();
            }
            case "CUSTOMER_CONTACT" -> {
                var parent = uuid(filter, "customer_id"); var rows = contacts.findContacts(parent);
                rows.stream().skip((long)(page - 1) * size).limit(size).forEach(row -> refs.add(ref(type, row.id()).put("customer_id", parent.toString())));
                more = (long)page * size < rows.size();
            }
            case "CUSTOMER_COMMUNICATION" -> {
                var result = communications.findCommunications(optionalId(filter, "customer_id"), filter.path("status").asText(null), page, size);
                result.items().forEach(row -> refs.add(ref(type, row.id()))); more = page < result.totalPages();
            }
            case "CUSTOMER_REQUIREMENT" -> {
                var result = requirements.findRequirements(optionalId(filter, "customer_id"), optionalId(filter, "project_id"), filter.path("status").asText(null), page, size);
                result.items().forEach(row -> refs.add(ref(type, row.id()))); more = page < result.totalPages();
            }
            default -> throw missing();
        }
        return new Page(refs, more);
    }
    public BusinessRecordView read(ObjectNode request) {
        var type = request.path("type").asText(); var id = id(request);
        var links = new ArrayList<BusinessRecordView.Relation>();
        BusinessRecordView result;
        switch (type) {
            case "CUSTOMER" -> {
                var row = customers.findById(id).orElseThrow(BusinessViews::missing);
                for (var relation : Map.of("联系人", "CUSTOMER_CONTACT", "跟进记录", "CUSTOMER_COMMUNICATION", "客户需求", "CUSTOMER_REQUIREMENT", "关联项目", "PROJECT").entrySet()) {
                    var target = json.createObjectNode().put("type", relation.getValue()); target.putObject("filters").put("customer_id", id.toString());
                    links.add(new BusinessRecordView.Relation(relation.getKey(), target));
                }
                var body = facts(json, row, "partnerCode", "name", "industry", "address", "status", "remark", "customerLevel", "cooperationStatus", "mainBusiness", "customFields");
                result = view(type, id, row.version(), row.name(), body, "/partners/" + id, links);
            }
            case "CUSTOMER_CONTACT" -> {
                var parent = uuid(request, "customer_id");
                var row = contacts.findContacts(parent).stream().filter(value -> id.equals(value.id())).findFirst().orElseThrow(BusinessViews::missing);
                links.add(link("所属客户", "CUSTOMER", parent));
                var body = facts(json, row, "name", "department", "title", "phone", "email", "status", "wechat", "customFields");
                result = view(type, id, row.version(), row.name(), body, "/partners/" + parent, links);
                result.ref().put("customer_id", parent.toString());
            }
            case "CUSTOMER_COMMUNICATION" -> {
                var row = communications.findCommunication(id).orElseThrow(BusinessViews::missing);
                links.add(link("所属客户", "CUSTOMER", row.partnerId()));
                result = view(type, id, row.version(), row.name(), facts(json, row, "partnerId", "recordCode", "name", "communicatedAt", "internalParticipants", "communicationMethod", "content", "status", "customFields"), "/partners/" + row.partnerId(), links);
            }
            case "CUSTOMER_REQUIREMENT" -> {
                var row = requirements.findRequirement(id).orElseThrow(BusinessViews::missing);
                if (row.partnerId() != null) links.add(link("所属客户", "CUSTOMER", row.partnerId()));
                var projects = new LinkedHashSet<UUID>();
                if (row.projectId() != null) projects.add(row.projectId());
                if (row.projectIds() != null) projects.addAll(row.projectIds());
                projects.forEach(project -> links.add(link("已关联项目", "PROJECT", project)));
                result = view(type, id, row.version(), row.title(), facts(json, row, "partnerId", "projectId", "projectIds", "requirementCode", "title", "rawRequirement", "urgency", "raisedAt", "deliveryDate", "status", "customStatusName", "customFields"), row.partnerId() == null ? "/partners" : "/partners/" + row.partnerId(), links);
            }
            default -> throw missing();
        }
        pinned(request, result); return result;
    }
}
