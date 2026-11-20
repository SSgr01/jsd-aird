package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

/** Thin business transport. The harness owns files, execution and context management. */
@Service
public class BusinessDataService {
    private static final int SCAN_LIMIT = 5000;
    private final Map<String,BusinessReadPort> readers = new TreeMap<>();
    private final Map<String,BusinessDataAccess> access = new TreeMap<>();
    private final BusinessExistingData existing;
    private final ObjectMapper json;
    private LocalBusinessAccess localAccess;
    private BusinessOriginalFiles originalFiles;
    private BusinessFileProjection fileProjection;
    @Autowired(required=false) void setFileProjection(BusinessFileProjection projection){this.fileProjection=projection;}
    private BusinessReportScope reportScope;
    @Autowired(required=false) void setReportScope(BusinessReportScope reportScope){this.reportScope=reportScope;}
    @Autowired(required = false)
    void setOriginalFiles(BusinessOriginalFiles originalFiles) { this.originalFiles = originalFiles; }
    public BusinessDataService(List<BusinessReadPort> readers, List<BusinessDataAccess> access,
            BusinessExistingData existing, ObjectMapper json) {
        for (var reader : readers) for (var type : reader.resourceTypes())
            if (this.readers.putIfAbsent(type, reader) != null) throw new IllegalStateException("Duplicate business reader: " + type);
        for (var policy : access) for (var type : policy.resourceTypes())
            if (this.access.putIfAbsent(type, policy) != null) throw new IllegalStateException("Duplicate business access owner: " + type);
        this.existing = existing; this.json = json;
    }
    @Autowired(required = false)
    void setLocalAccess(LocalBusinessAccess localAccess) { this.localAccess = localAccess; }

    public boolean allowed(String type, UUID id, String operation, JsonNode context) {
        var actor = ActorContext.required(); var policy = access.get(type);
        // A registered domain policy is authoritative, including an explicit denial.
        if (policy != null) return policy.allowed(actor, type, id, operation, context);
        return localAccess != null && localAccess.allowed(actor, type, operation);
    }
    public void require(String type, UUID id, String operation, JsonNode context) {
        if (!allowed(type, id, operation, context)) throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED,
            "该业务能力尚未完成记录权限接入，或当前记录未获授权：" + type);
    }
    public ObjectNode invoke(String action, ObjectNode args) {
        ActorContext.required();
        if ("business_search".equals(action) && !args.hasNonNull("type")) return catalog();
        String type = args.path("type").asText(args.path("ref").path("type").asText());
        if (BusinessExistingData.TYPES.contains(type) && !"business_links".equals(action)) {
            if("PROJECT".equals(type)&&"business_read".equals(action))requireProjectScope(args,id(reference(args)));
            if("PROJECT".equals(type)&&"business_search".equals(action)&&args.has("report_project_ids"))return scopedProjectSearch(args);
            return existing.invoke(action, args);
        }
        if ("business_links".equals(action)) return links(args);
        if ("business_read".equals(action)) {
            var ref = reference(args); var row = read(ref);
            requireReportScope(args,row);
            var result = json.createObjectNode(); result.putArray("candidates").add(readCandidate(row,args));
            if ("content".equals(args.path("section").asText())) {
                result.set("reading", contentPage(row,args).path("reading"));
                if (result.path("reading").has("next_offset")) result.set("next_offset",result.path("reading").get("next_offset"));
            }
            else if (row.facts().has("content")) {
                var reading=result.putObject("reading").put("section","record").put("complete",!row.facts().path("content").isEmpty());
                if(row.facts().has("content_notice"))reading.set("reason",row.facts().get("content_notice"));
            }
            if("PROJECT_REFERENCE".equals(type))for(var relation:row.relations())if("资料正文".equals(relation.name())) {
                var target=read(relation.target());requireReportScope(args,target);
                if(args.path("include_file").asBoolean())require(target.ref().path("type").asText(),id(target.ref()),"DOWNLOAD",target.ref());
            }
            var files = originalFiles == null ? List.<BusinessOriginalFiles.Entry>of() : originalFiles.list(row.ref());
            result.set("files",json.valueToTree(files));
            if (args.path("include_file").asBoolean()) {
                boolean visualPage = "page".equals(args.path("section").asText());
                require(type, id(ref), visualPage ? "READ" : "DOWNLOAD", row.ref());
                var selected = optionalId(args,"file_id");
                var file = visualPage ? imagePageFile(row.ref(), args.path("page").asInt())
                    : originalFiles != null && BusinessOriginalFiles.supports(type) ? originalFiles.open(row.ref(),selected) : reader(type).file(row.ref(),selected);
                if (file.bytes().length > 8 * 1024 * 1024) throw new ApiException(ApiErrorCode.BAD_REQUEST, "原文件超过8MB，请通过业务页面下载后作为原生附件读取");
                var transfer = result.putObject("business_file").put("name", file.name()).put("content_type", file.contentType())
                    .put("bytes", file.bytes().length).put("sha256", file.sha256())
                    .put("content_base64", Base64.getEncoder().encodeToString(file.bytes()));
                transfer.set("ref", row.ref());
                if(fileProjection!=null && !visualPage)fileProjection.attach(transfer,file);
                // Reject a concurrent metadata/content change before publishing a file to the harness.
                validateCandidate(candidate(row).path("source"), null, candidate(read(row.ref())));
            }
            return timed(result);
        }
        if (!Set.of("business_search", "business_query").contains(action)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var rows = scan(type, filters(args));
        if(args.has("report_project_ids"))rows=rows.stream().filter(row->withinReportScope(args,row)).toList();
        if ("business_query".equals(action)) return BusinessTables.query(this, json, type, args, rows);
        int offset = offset(args), limit = limit(args); var result = json.createObjectNode();
        var candidates = result.putArray("candidates");
        rows.stream().skip(offset).limit(limit).forEach(row -> candidates.add(candidate(row)));
        result.put("total", rows.size()); if (rows.size() > offset + limit) result.put("next_offset", offset + limit);
        return timed(result);
    }
    private ObjectNode scopedProjectSearch(ObjectNode args) {
        var allowed=new HashSet<String>();args.path("report_project_ids").forEach(value->allowed.add(value.asText()));
        var matching=json.createArrayNode();int cursor=0,scanned=0;
        while(!allowed.isEmpty()) {
            var request=args.deepCopy().put("offset",cursor).put("limit",40);
            var page=existing.invoke("business_search",request);
            for(var candidate:page.path("candidates")) {
                if(++scanned>SCAN_LIMIT)throw new ApiException(ApiErrorCode.BAD_REQUEST,"项目查询超过5000条，请缩小查询范围");
                if(allowed.contains(candidate.path("ref").path("id").asText()))matching.add(candidate);
            }
            if(!page.has("next_offset"))break;
            int next=page.path("next_offset").asInt();
            if(next<=cursor)throw new IllegalStateException("Non-progressing business page");
            cursor=next;
        }
        var result=json.createObjectNode().put("total",matching.size());var selected=result.putArray("candidates");
        int offset=offset(args),limit=limit(args);
        for(int i=offset;i<Math.min(matching.size(),offset+limit);i++)selected.add(matching.get(i));
        if(offset+limit<matching.size())result.put("next_offset",offset+limit);
        return timed(result);
    }
    private ObjectNode contentPage(BusinessRecordView row, ObjectNode args) {
        int start=offset(args), count=limit(args);
        var content=row.facts().path("content"); var units=json.createArrayNode();
        boolean text=content.has("text");
        if(text) {
            String value=content.path("text").asText();
            int[] points=value.codePoints().toArray();
            for(int i=0;i<points.length;i+=1000) units.add(new String(points,i,Math.min(1000,points.length-i)));
        } else for(var cell:content.path("cells")) units.add(cell);
        var selected=json.createArrayNode();
        for(int i=start;i<Math.min(units.size(),start+count);i++) selected.add(units.get(i));
        var page=json.createObjectNode(); var body=page.putObject("content");
        if(text) {var value=new StringBuilder();selected.forEach(part->value.append(part.asText()));body.put("text",value.toString());}
        else body.set("cells",selected);
        var reading=page.putObject("reading").put("section","content").put("offset",start).put("returned",selected.size())
            .put("total",units.size()).put("complete",!content.isEmpty() && start==0 && selected.size()==units.size());
        if(row.facts().has("content_notice"))reading.set("reason",row.facts().get("content_notice"));
        if(start+selected.size()<units.size()) reading.put("next_offset",start+selected.size());
        return page;
    }
    private ObjectNode readCandidate(BusinessRecordView row,ObjectNode args) {
        if ("page".equals(args.path("section").asText())) {
            int page = args.path("page").asInt();
            var value = candidate(reader(row.ref().path("type").asText()).imagePage(row.ref(), page));
            var source = (ObjectNode)value.path("source");
            var anchor = (ObjectNode)source.path("anchor");
            anchor.put("page_no", page);
            anchor.set("read_options", json.createObjectNode().put("section", "page").put("page", page));
            anchor.put("read_path", "/api/v1/agents/business/images/" + row.ref().path("type").asText() + "/" + row.ref().path("id").asText()
                + "?ref=" + java.net.URLEncoder.encode(row.ref().toString(), java.nio.charset.StandardCharsets.UTF_8) + "&page=" + page + "#page=" + page);
            source.put("source_node_key", source.path("source_node_key").asText() + ":page:" + page);
            return value;
        }
        if(!"content".equals(args.path("section").asText())) return candidate(row);
        var page=contentPage(row,args); var facts=row.facts().deepCopy();facts.set("content",page.path("content"));
        var value=candidate(new BusinessRecordView(row.ref(),row.title(),facts,row.relations(),row.path()));
        var options=json.createObjectNode().put("section","content").put("offset",offset(args)).put("limit",limit(args));
        ((ObjectNode)value.path("source").path("anchor")).set("read_options",options);
        var anchor=(ObjectNode)value.path("source").path("anchor");
        anchor.put("read_path",anchor.path("read_path").asText()+"&section=content&offset="+offset(args)+"&limit="+limit(args));
        ((ObjectNode)value.path("source")).put("source_node_key",value.path("source").path("source_node_key").asText()+":content:"+offset(args)+":"+limit(args));
        value.set("reading",page.path("reading"));
        return value;
    }
    public ObjectNode catalog() {
        var result = json.createObjectNode(); var types = result.putArray("resource_types");
        BusinessExistingData.TYPES.stream().sorted().forEach(type -> {
            var item = json.createObjectNode().put("type", type).put("available", existing.available(type));
            item.set("filters", json.valueToTree(existing.filters(type)));
            item.set("required_filters", json.valueToTree(existing.requiredFilters(type))); types.add(item);
        });
        readers.forEach((type, reader) -> {
            var item = json.createObjectNode().put("type", type).put("available", allowed(type, null, "SEARCH", json.createObjectNode()));
            item.set("filters", json.valueToTree(reader.filters(type)));
            item.set("required_filters", json.valueToTree(reader.requiredFilters(type))); types.add(item);
        }); return result;
    }
    public BusinessRecordView read(ObjectNode ref) {
        var type = ref.path("type").asText(); var id = id(ref);
        require(type, id, "READ", ref);
        var result = reader(type).read(ref); pinned(ref, result); return result;
    }
    List<BusinessRecordView> scan(String type, ObjectNode filters) {
        var reader = reader(type); require(type, null, "SEARCH", filters);
        for (var field : reader.requiredFilters(type)) if (!filters.hasNonNull(field))
            throw invalidFilter(reader.filters(type), reader.requiredFilters(type), field, "required", "缺少必要筛选字段：" + field);
        filters.fieldNames().forEachRemaining(field -> {
            if (!reader.filters(type).contains(field))
                throw invalidFilter(reader.filters(type), reader.requiredFilters(type), field, "unsupported_filter", "不支持该筛选字段：" + field);
            var value = filters.path(field);
            if (!value.isTextual() || value.asText().length() > 1000)
                throw invalidFilter(reader.filters(type), reader.requiredFilters(type), field, "type", "筛选值必须是明确的文本或对象ID：" + field);
            if (field.endsWith("_id")) uuid(filters, field);
        });
        var results = new ArrayList<BusinessRecordView>(); var seen = new HashSet<String>(); int count = 0;
        for (int page = 1; ; page++) {
            var batch = reader.references(type, filters, page, 100);
            for (var ref : batch.references()) {
                if (++count > SCAN_LIMIT) throw new ApiException(ApiErrorCode.BAD_REQUEST, "查询超过5000条，请缩小范围；未返回不完整统计");
                if (!type.equals(ref.path("type").asText())) throw new IllegalStateException("Business reader returned another resource type");
                if (!allowed(type, id(ref), "READ", ref)) continue;
                var row = read(ref);
                if (matches(row, filters) && seen.add(clean(row.ref()).toString())) results.add(row);
            }
            if (!batch.more()) return results;
            if (batch.references().isEmpty()) throw new IllegalStateException("Non-progressing business page");
        }
    }
    static ApiException invalidFilter(Set<String> allowed, Set<String> required, String field, String rule, String message) {
        return new ApiException(ApiErrorCode.BAD_REQUEST, message, Map.of(
            "code", "INVALID_TOOL_ARGUMENTS", "path", List.of("filters", field), "rule", rule,
            "required_filters", required.stream().sorted().toList(), "allowed_filters", allowed.stream().sorted().toList()));
    }
    private boolean matches(BusinessRecordView row, ObjectNode filters) {
        for (var filter : filters.properties()) {
            var fields = switch (filter.getKey()) {
                case "customer_id" -> List.of("customerId", "partnerId", "customer_id"); case "product_id" -> List.of("productId");
                case "project_id" -> List.of("projectId", "projectIds", "project_id"); case "import_job_id" -> List.of("import_job_id");
                case "business_no" -> List.of("businessNo", "orderNo", "shipmentNo", "dispatchNo");
                case "category_id" -> List.of("categoryId"); case "query" -> List.<String>of(); default -> List.of(filter.getKey());
            };
            if (!fields.isEmpty()) {
                boolean found = false;
                for (var field : fields) {
                    var value = row.facts().has(field) ? row.facts().path(field) : row.ref().path(field);
                    if (value.equals(filter.getValue())) found = true;
                    if (value.isArray()) for (var item : value) if (item.equals(filter.getValue())) found = true;
                }
                if (!found) return false;
            }
            if ("query".equals(filter.getKey()) && !filter.getValue().asText().isBlank()
                    && !(row.title() + " " + row.facts()).toLowerCase(Locale.ROOT).contains(filter.getValue().asText().toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }
    private ObjectNode links(ObjectNode args) {
        var request = reference(args); String type = request.path("type").asText();
        if (BusinessExistingData.TYPES.contains(type)) return existingLinks(args, request);
        var origin = read(request); requireReportScope(args,origin); var targets = new ArrayList<ObjectNode>();
        var names = new LinkedHashSet<String>(); origin.relations().forEach(relation -> names.add(relation.name()));
        if (!args.hasNonNull("relation")) {
            var result = json.createObjectNode(); result.set("available_relations", json.valueToTree(names));
            result.putArray("candidates").add(candidate(origin)); return timed(result);
        }
        if (!names.contains(args.path("relation").asText())) throw invalidRelation(names);
        for (var relation : origin.relations()) {
            if (args.hasNonNull("relation") && !relation.name().equals(args.path("relation").asText())) continue;
            var target = relation.target(); String targetType = target.path("type").asText();
            if (!target.hasNonNull("id")) {
                if (BusinessExistingData.TYPES.contains(targetType)) {
                    var query = json.createObjectNode().put("type", targetType).put("offset", offset(args)).put("limit", limit(args)); query.set("filters", target.path("filters"));
                    var found = existing.invoke("business_search", query);
                    var result = json.createObjectNode(); var candidates = result.putArray("candidates"); candidates.add(candidate(origin));
                    for (var item : found.path("candidates")) { var copy = (ObjectNode)item.deepCopy(); copy.put("relation", relation.name()); candidates.add(copy); }
                    if (found.has("next_offset")) result.set("next_offset", found.path("next_offset"));
                    return timed(result);
                } else if (allowed(targetType, null, "SEARCH", target.path("filters")))
                    for (var row : scan(targetType, (ObjectNode)target.path("filters"))) if(withinReportScope(args,row))targets.add(linkResult(relation.name(), row));
            } else if (BusinessExistingData.TYPES.contains(targetType)) {
                try {
                    var found = existing.invoke("business_read", json.createObjectNode().set("ref", target));
                    for (var candidate : found.path("candidates")) { var copy = (ObjectNode)candidate.deepCopy(); copy.put("relation", relation.name()); targets.add(copy); }
                } catch (ApiException error) { if (!hidden(error)) throw error; }
            } else if (allowed(targetType, id(target), "READ", target)) {
                var row=read(target);if(withinReportScope(args,row))targets.add(linkResult(relation.name(),row));
            }
        }
        var result = json.createObjectNode(); var candidates = result.putArray("candidates"); candidates.add(candidate(origin));
        targets.stream().skip(offset(args)).limit(limit(args)).forEach(candidates::add);
        if (targets.size() > offset(args) + limit(args)) result.put("next_offset", offset(args) + limit(args));
        return timed(result);
    }
    private ObjectNode existingLinks(ObjectNode args, ObjectNode ref) {
        var source = existing.invoke("business_read", json.createObjectNode().set("ref", ref));
        if ("PROJECT".equals(ref.path("type").asText()))requireProjectScope(args,id(ref));
        var result = json.createObjectNode(); result.set("candidates", source.path("candidates").deepCopy());
        var links = (com.fasterxml.jackson.databind.node.ArrayNode)result.path("candidates");
        String type = ref.path("type").asText();
        if ("PROJECT".equals(type)) {
            var relations = new TreeMap<>(Map.of("阶段", "STAGE", "任务", "TASK", "实验", "EXPERIMENT", "客户需求", "REQUIREMENT", "资料", "MATERIAL"));
            relations.putAll(BusinessProjectMaterials.RELATIONS);
            result.set("available_relations", json.valueToTree(new TreeSet<>(relations.keySet())));
            if (!args.hasNonNull("relation")) return timed(result);
            if (!relations.containsKey(args.path("relation").asText())) throw invalidRelation(relations.keySet());
            for (var relation : relations.entrySet()) {
                if (args.hasNonNull("relation") && !relation.getKey().equals(args.path("relation").asText())) continue;
                var filter = json.createObjectNode().put("project_id", id(ref).toString());
                var request = json.createObjectNode().put("type", relation.getValue()).put("offset", offset(args)).put("limit", limit(args)); request.set("filters", filter);
                if(args.has("report_project_ids"))request.set("report_project_ids",args.get("report_project_ids"));
                boolean legacy=BusinessExistingData.TYPES.contains(relation.getValue());
                if (legacy ? !existing.available(relation.getValue()) : !allowed(relation.getValue(),null,"SEARCH",filter)) continue;
                var records = legacy ? existing.invoke("business_search", request) : invoke("business_search",request);
                for (var record : records.path("candidates")) {
                    // A project diagnosis needs the experiment's shared background and plan,
                    // even when no sample rows have been entered. Directory counts are not evidence of absence.
                    var details = "EXPERIMENT".equals(relation.getValue())
                        ? existing.invoke("business_read", json.createObjectNode().set("ref", record.path("ref"))).path("candidates")
                        : json.createArrayNode().add(record);
                    for (var detail : details) { var copy = (ObjectNode)detail.deepCopy(); copy.put("relation", relation.getKey()); links.add(copy); }
                }
                if (records.has("next_offset")) result.set("next_offset", records.path("next_offset"));
            }
        } else if ("EXPERIMENT".equals(type) && source.path("batch_sources").isArray()) {
            result.putArray("available_relations").add("来源实验").add("所属项目");
            if(!args.hasNonNull("relation"))return timed(result);
            if("来源实验".equals(args.path("relation").asText())) {
                var targets=new LinkedHashMap<String,ObjectNode>();
                for(var batch:source.path("batch_sources")) {
                    if(!batch.hasNonNull("sourceExperimentId")||!batch.hasNonNull("sourceVersionId"))continue;
                    var target=json.createObjectNode().put("type","EXPERIMENT").put("id",batch.path("sourceExperimentId").asText()).put("version_id",batch.path("sourceVersionId").asText());
                    targets.putIfAbsent(target.toString(),target);
                }
                for(var target:targets.values().stream().skip(offset(args)).limit(limit(args)).toList())try {
                    var found=existing.invoke("business_read",json.createObjectNode().set("ref",target));
                    for(var record:found.path("candidates")) {
                        var copy=(ObjectNode)record.deepCopy();copy.put("relation","来源实验");
                        var associations=copy.putArray("sample_associations");
                        for(var batch:source.path("batch_sources"))if(target.path("id").equals(batch.path("sourceExperimentId"))&&target.path("version_id").equals(batch.path("sourceVersionId")))associations.add(batch.deepCopy());
                        links.add(copy);
                    }
                }catch(ApiException error){if(!hidden(error))throw error;}
                if(targets.size()>offset(args)+limit(args))result.put("next_offset",offset(args)+limit(args));
                return timed(result);
            }
            if(!"所属项目".equals(args.path("relation").asText()))throw invalidRelation(Set.of("来源实验","所属项目"));
            var project=source.path("candidates").path(0).path("ref").path("project_id");
            if(project.isTextual()) {
                var target=json.createObjectNode().put("type","PROJECT").put("id",project.asText());
                var parent=existing.invoke("business_read",json.createObjectNode().set("ref",target));
                for(var record:parent.path("candidates")){var copy=(ObjectNode)record.deepCopy();copy.put("relation","所属项目");links.add(copy);}
            }
        } else if (source.path("candidates").path(0).path("ref").hasNonNull("project_id")) {
            var target = json.createObjectNode().put("type", "PROJECT").put("id", source.path("candidates").path(0).path("ref").path("project_id").asText());
            var parent = existing.invoke("business_read", json.createObjectNode().set("ref", target));
            for (var record : parent.path("candidates")) { var copy = (ObjectNode)record.deepCopy(); copy.put("relation", "所属项目"); links.add(copy); }
        }
        return timed(result);
    }
    private static boolean hidden(ApiException error) { return Set.of(ApiErrorCode.NOT_FOUND, ApiErrorCode.PERMISSION_DENIED, ApiErrorCode.DATA_SCOPE_DENIED).contains(error.errorCode()); }
    private static ApiException invalidRelation(Collection<String> available) {
        return new ApiException(ApiErrorCode.BAD_REQUEST,"不存在该已确认关系",Map.of("code","INVALID_TOOL_ARGUMENTS",
            "path",List.of("relation"),"rule","enum","allowed_values",available.stream().sorted().toList()));
    }
    private void requireProjectScope(ObjectNode args,UUID project){
        if(!args.has("report_project_ids"))return;
        for(var value:args.path("report_project_ids"))if(project.toString().equals(value.asText()))return;
        throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED,"该项目不属于本期报告范围");
    }
    private boolean withinReportScope(ObjectNode args,BusinessRecordView row){return !args.has("report_project_ids") || reportScope!=null && reportScope.contains(args.path("report_project_ids"),row);}
    private void requireReportScope(ObjectNode args,BusinessRecordView row){if(!withinReportScope(args,row))throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED,"该资料不属于本期报告项目范围");}
    private ObjectNode linkResult(String relation, BusinessRecordView row) { return candidate(row).put("relation", relation); }
    private BusinessReadPort reader(String type) { var port = readers.get(type); if (port == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "未支持的业务类型：" + type); return port; }
    public ObjectNode candidate(BusinessRecordView row) {
        String text = markdown(row.title(), row.facts()); var ref = row.ref().deepCopy();
        var source = json.createObjectNode().put("kind", "BUSINESS_RECORD").put("resource_type", ref.path("type").asText())
            .put("resource_id", ref.path("id").asText()).put("document_id", ref.path("id").asText())
            .put("version_id", ref.path("revision").asText()).put("source_node_key", ref.path("type").asText() + ":" + ref.path("id").asText())
            .put("title", row.title()).put("content_sha256", hash(text));
        var anchor = source.putObject("anchor").put("business_adapter", "v1").put("path", row.path()); anchor.set("ref", ref);
        anchor.put("read_path", "/api/v1/agents/business/records/" + ref.path("type").asText() + "/" + ref.path("id").asText()
            + "?ref=" + java.net.URLEncoder.encode(ref.toString(), java.nio.charset.StandardCharsets.UTF_8));
        var candidate = json.createObjectNode().put("text", text).put("model_text", modelMarkdown(row.title(), row.facts()));
        candidate.set("source", source); candidate.set("ref", ref);
        candidate.set("available_relations", json.valueToTree(row.relations().stream().map(BusinessRecordView.Relation::name).distinct().sorted().toList()));
        if(originalFiles!=null && BusinessOriginalFiles.supports(ref.path("type").asText()))candidate.set("files",json.valueToTree(originalFiles.list(ref)));
        return candidate;
    }
    public BusinessReadPort.FileContent imagePageFile(ObjectNode reference, int page) {
        var row = read(reference);
        var file = reader(row.ref().path("type").asText()).imagePageFile(row.ref(), page);
        // Recheck the pinned metadata after rendering, before bytes reach a native attachment.
        read(row.ref());
        return file;
    }
    public void validateSource(JsonNode source, String excerpt) {
        if ("BUSINESS_QUERY".equals(source.path("resource_type").asText())) { BusinessTables.validate(this, source, excerpt); return; }
        var requested = source.path("anchor").path("ref");
        if (!requested.isObject()) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var options=source.path("anchor").path("read_options");
        var actual = readCandidate(read((ObjectNode)requested),options.isObject()?(ObjectNode)options:json.createObjectNode());
        validateCandidate(source, excerpt, actual);
    }
    public void requireExport(JsonNode source) {
        if ("BUSINESS_QUERY".equals(source.path("resource_type").asText())) {
            var query = (ObjectNode)source.path("anchor").path("query");
            for (var row : scan(query.path("type").asText(), filters(query))) require(row.ref().path("type").asText(), id(row.ref()), "EXPORT", row.ref());
        } else require(source.path("resource_type").asText(), uuid(source, "resource_id"), "EXPORT", source.path("anchor"));
    }
    static void validateCandidate(JsonNode source, String excerpt, JsonNode actual) {
        if (!clean(source).equals(clean(actual.path("source"))) || excerpt != null && !actual.path("text").asText().contains(excerpt))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "来源版本或正文已变化");
    }
    static ObjectNode reference(ObjectNode args) {
        if (!(args.path("ref") instanceof ObjectNode ref)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "需要搜索返回的对象引用");
        return ref;
    }
    static ObjectNode filters(ObjectNode args) {
        if (!args.has("filters")) return args.has("query") ? args.objectNode().set("query", args.get("query")) : args.objectNode();
        if (!(args.path("filters") instanceof ObjectNode filters)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var result = filters.deepCopy(); if (args.has("query")) result.set("query", args.get("query")); return result;
    }
    static int offset(ObjectNode args) { int value = args.path("offset").asInt(); if (value < 0 || value > 100000) throw new ApiException(ApiErrorCode.BAD_REQUEST); return value; }
    static int limit(ObjectNode args) { int value = args.path("limit").asInt(20); if (value < 1 || value > 40) throw new ApiException(ApiErrorCode.BAD_REQUEST); return value; }
    ObjectNode timed(ObjectNode result) { result.putObject("trace").put("read_at", Instant.now().toString()); return result; }
}
