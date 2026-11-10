package com.jsd.aird.quality.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

@Service
public class BusinessQualityReader implements BusinessReadPort {
    private static final Map<String,String> TYPES = Map.of("QUALITY_STANDARD", "standard", "QUALITY_RECORD", "record", "QUALITY_COA", "coa",
        "QUALITY_DEFECT", "defect", "QUALITY_MANUAL", "manual", "QUALITY_MSDS", "msds", "QUALITY_LABEL", "label");
    private final QualityDataStore records;
    private final ObjectMapper json;
    public BusinessQualityReader(QualityDataStore records, ObjectMapper json) { this.records = records; this.json = json; }
    public Set<String> resourceTypes() { return TYPES.keySet(); }
    public Set<String> filters(String type) { return Set.of("query", "project_id", "category_id", "business_no"); }
    public Page references(String type, ObjectNode filter, int page, int size) {
        var actor = ActorContext.required(); var refs = new ArrayList<ObjectNode>();
        if (filter.hasNonNull("business_no")) {
            if (page == 1) records.recordByBusinessNo(actor.organizationId(), actor.role(), TYPES.get(type), filter.path("business_no").asText()).ifPresent(row -> refs.add(ref(type, row.id())));
            return new Page(refs, false);
        }
        var rows = records.records(actor.organizationId(), actor.role(), TYPES.get(type), optionalId(filter, "category_id"),
            filter.path("query").asText(""), optionalId(filter, "project_id"), filter.path("status").asText(null), page, size);
        rows.items().forEach(row -> refs.add(ref(type, row.id()))); return new Page(refs, page < rows.totalPages());
    }
    public BusinessRecordView read(ObjectNode request) {
        var actor = ActorContext.required(); var id = id(request); var type = request.path("type").asText();
        var row = records.record(actor.organizationId(), actor.role(), id);
        if (!Objects.equals(TYPES.get(type), row.businessType())) throw missing();
        var body = facts(json, row, "businessNo", "displayName", "categoryId", "categoryName", "projectId", "data", "visibility");
        String revision = Long.toString(row.lockVersion());
        if (request.hasNonNull("version_id")) {
            var version = records.versions(actor.organizationId(), id).stream().filter(value -> value.id().toString().equals(request.path("version_id").asText())).findFirst().orElseThrow(BusinessViews::missing);
            body.set("data", clean(version.data())); body.put("versionNo", version.versionNo());
            body.put("businessNo", version.businessNo()); revision = version.id().toString();
        }
        var links = new ArrayList<BusinessRecordView.Relation>(); projectLinks(links, row.projectId(), row.stageId(), row.taskId());
        var data = body.path("data");
        addBusinessNumberLink(links, data, "internalRecordNo", "QUALITY_RECORD", "COA对应测试记录");
        addBusinessNumberLink(links, data, "standardNo", "QUALITY_STANDARD", "采用标准");
        addBusinessNumberLink(links, data, "sourceRecordNo", "QUALITY_RECORD", "不良来源记录");
        addBusinessNumberLink(links, data, "relatedManualNo", "QUALITY_MANUAL", "关联说明书");
        addBusinessNumberLink(links, data, "relatedMsdsNo", "QUALITY_MSDS", "关联MSDS");
        var result = view(type, id, revision, row.displayName(), body, "/quality/records/" + id, links);
        if (request.hasNonNull("version_id")) result.ref().set("version_id", request.get("version_id"));
        pinned(request, result); return result;
    }
    private void addBusinessNumberLink(List<BusinessRecordView.Relation> links, com.fasterxml.jackson.databind.JsonNode data, String field, String type, String label) {
        if (!data.hasNonNull(field) || data.path(field).asText().isBlank()) return;
        var actor = ActorContext.required();
        records.recordByBusinessNo(actor.organizationId(), actor.role(), TYPES.get(type), data.path(field).asText()).ifPresent(row -> links.add(link(label, type, row.id())));
    }
}
