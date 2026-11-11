package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

@Service
public class BusinessStructuredReader implements BusinessReadPort {
    private final DataRepository records;
    private final ObjectMapper json;
    public BusinessStructuredReader(DataRepository records, ObjectMapper json) { this.records = records; this.json = json; }
    public Set<String> resourceTypes() { return Set.of("DATA_IMPORT", "STRUCTURED_RECORD"); }
    public Set<String> requiredFilters(String type) { return "STRUCTURED_RECORD".equals(type) ? Set.of("import_job_id") : Set.of(); }
    public Set<String> filters(String type) { return "DATA_IMPORT".equals(type) ? Set.of("query", "status") : Set.of("query", "status", "import_job_id"); }
    public Page references(String type, ObjectNode filter, int page, int size) {
        var org = ActorContext.required().organizationId();
        if ("DATA_IMPORT".equals(type)) {
            var result = records.listJobs(org, null, filter.path("status").asText(null), filter.path("query").asText(""), "DATA_CENTER", page, size);
            return new Page(result.items().stream().map(row -> ref(type, row.id())).toList(), page < result.totalPages());
        }
        var parent = uuid(filter, "import_job_id");
        records.findJob(org, parent).orElseThrow(BusinessViews::missing);
        var rows = records.listRows(org, parent);
        var refs = rows.stream().skip((long)(page - 1) * size).limit(size)
            .map(row -> ref(type, row.id()).put("import_job_id", parent.toString())).toList();
        return new Page(refs, (long)page * size < rows.size());
    }
    public BusinessRecordView read(ObjectNode request) {
        var org = ActorContext.required().organizationId(); var type = request.path("type").asText(); var id = id(request);
        if ("DATA_IMPORT".equals(type)) {
            var row = records.findJob(org, id).orElseThrow(BusinessViews::missing);
            var result = view(type, id, null, row.sourceFileName(), facts(json, row, "sourceFileName", "sourceFormat", "status", "categoryId", "parserVersion", "importContractVersion", "compatibilityStatus", "importPurpose"), "/data/import-jobs/" + id, List.of());
            pinned(request, result); return result;
        }
        var parent = uuid(request, "import_job_id");
        records.findJob(org, parent).orElseThrow(BusinessViews::missing);
        var row = records.listRows(org, parent).stream().filter(value -> id.equals(value.id())).findFirst().orElseThrow(BusinessViews::missing);
        var body = facts(json, row, "sheetId", "rowNumber", "rawValues", "normalizedValues", "correctedValues", "status", "sourceMetadata", "excluded", "exclusionReason");
        body.put("value_semantics", "原值、规范化值和人工修正分别保留；排除或未发布记录不能冒充合格实测数据");
        var result = view(type, id, null, "结构化研发记录 · 行" + row.rowNumber(), body, "/data/import-jobs/" + parent, List.of(link("来源文件", "DATA_IMPORT", parent)));
        result.ref().put("import_job_id", parent.toString()); pinned(request, result); return result;
    }
}
