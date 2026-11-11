package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.rnd.application.port.ResearchTestRepository;
import com.jsd.aird.rnd.domain.ResearchTestModels;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

@Service
public class BusinessResearchTestReader implements BusinessReadPort {
    private final ResearchTestRepository records;
    private final ObjectMapper json;
    public BusinessResearchTestReader(ResearchTestRepository records, ObjectMapper json) { this.records = records; this.json = json; }
    public Set<String> resourceTypes() { return Set.of("RESEARCH_TEST"); }
    public Set<String> filters(String type) { return Set.of("query", "project_id", "category", "status"); }
    public Page references(String type, ObjectNode filter, int page, int size) {
        var result = records.search(ActorContext.required().organizationId(), new ResearchTestRepository.Search(null,
            filter.path("query").asText(""), filter.path("category").asText(null), filter.path("status").asText(null),
            null, optionalId(filter, "project_id"), null, null, null, page, size));
        return new Page(result.items().stream().map(row -> ref(type, row.id())).toList(), page < result.totalPages());
    }
    public BusinessRecordView read(ObjectNode request) {
        var org = ActorContext.required().organizationId(); var id = id(request);
        var row = records.detail(org, id).orElseThrow(BusinessViews::missing); var summary = row.summary();
        var body = facts(json, summary, "recordType", "businessNo", "name", "category", "projectId", "applicableScope", "ownerName", "businessDate", "status", "versionNo");
        body.set("editModel", clean(row.editModel()));
        body.set("content", document(row.editModel().has("documentSnapshot") ? row.editModel().path("documentSnapshot") : row.templateSnapshot()));
        String revision = "CURRENT:" + summary.lockVersion();
        if (request.hasNonNull("version_id")) {
            var version = records.versions(org, id).stream().filter(value -> value.id().toString().equals(request.path("version_id").asText())).findFirst().orElseThrow(BusinessViews::missing);
            body.set("editModel", clean(version.editModel())); body.put("status", version.status()).put("versionNo", version.versionNo());
            body.set("content", document(version.editModel().has("documentSnapshot") ? version.editModel().path("documentSnapshot") : version.templateSnapshot()));
            body.put("metadata_note", "正文与状态取自指定版本；名称及项目归属为当前索引信息");
            revision = version.id().toString();
        }
        var links = new ArrayList<BusinessRecordView.Relation>(); projectLinks(links, summary.projectId(), summary.stageId(), summary.taskId());
        var result = view("RESEARCH_TEST", id, revision, summary.name(), body,
            "/research-test/" + (summary.recordType() == ResearchTestModels.Type.STANDARD ? "standards/" : "reports/") + id, links);
        if (request.hasNonNull("version_id")) result.ref().set("version_id", request.get("version_id"));
        pinned(request, result); return result;
    }
}
