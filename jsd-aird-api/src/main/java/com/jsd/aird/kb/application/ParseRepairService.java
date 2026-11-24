package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.*;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.kb.api.*;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.kb.application.port.ParseRepairStore.Repair;
import com.jsd.aird.kb.domain.DocumentParsingFailure;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ParseRepairService implements AsyncJobHandler {
    public static final String JOB = "KB_REPAIR_PARSE";
    public record Target(UUID documentId, UUID versionId) { }
    public record Selection(List<Target> files, String requestKey, boolean retryFailed) { }
    private final ParseRepairStore repairs;
    private final KnowledgeRepository knowledge;
    private final KnowledgeGovernanceRepository governance;
    private final AuthorizationService authorization;
    private final OpsAsyncFacade async;
    private final FileStorageFacade storage;
    private final ObjectMapper json;
    private final List<DocumentParser> parsers;
    private final PreciseDocumentParser precise;
    private final FileSafetyScanner scanner;
    private final TransactionTemplate tx;
    public ParseRepairService(ParseRepairStore repairs, KnowledgeRepository knowledge, KnowledgeGovernanceRepository governance,
            AuthorizationService authorization, OpsAsyncFacade async, FileStorageFacade storage, ObjectMapper json,
            List<DocumentParser> parsers, PreciseDocumentParser precise, FileSafetyScanner scanner, PlatformTransactionManager transactions) {
        this.repairs=repairs; this.knowledge=knowledge; this.governance=governance; this.authorization=authorization;
        this.async=async; this.storage=storage; this.json=json; this.parsers=parsers; this.precise=precise;
        this.scanner=scanner;
        tx=new TransactionTemplate(transactions);
    }
    private void require(UUID org, UUID actor, UUID document, boolean write) {
        authorization.require(new PermissionCheck(org, actor, "knowledge.view", "KNOWLEDGE", document, "READ"));
        if (write) authorization.require(new PermissionCheck(org, actor, "knowledge.update", "KNOWLEDGE", document, "UPDATE"));
    }
    private KnowledgeRepository.VersionRow version(UUID org, Target target) {
        var version = knowledge.findVersion(org, target.versionId()).filter(v -> target.documentId().equals(v.documentId()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        var doc = knowledge.findDocument(org, target.documentId()).filter(d -> "ACTIVE".equals(d.lifecycleStatus())).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (!target.versionId().equals(doc.currentVersionId())) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "文件版本已变化，请重新清点");
        return version;
    }
    private void validate(Selection selection) {
        if (selection == null || selection.files()==null || selection.files().isEmpty() || selection.files().size()>100
                || selection.requestKey()==null || selection.requestKey().isBlank() || selection.requestKey().length()>200)
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "每次修复需指定 1–100 个文件版本及持久化请求键");
        if (selection.files().stream().anyMatch(t -> t == null || t.documentId()==null || t.versionId()==null)
                || selection.files().stream().map(Target::versionId).distinct().count()!=selection.files().size())
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件版本必须明确且不能重复");
    }
    public JsonNode preview(Selection selection) {
        validate(selection); var actor=ActorContext.required(); var result=json.createArrayNode();
        for (var target:selection.files()) {
            require(actor.organizationId(), actor.userId(), target.documentId(), true);
            var version=version(actor.organizationId(),target);
            var previous=repairs.previousResult(actor.organizationId(), target.versionId()).orElse(json.createObjectNode());
            result.addObject().put("documentId",target.documentId().toString()).put("versionId",target.versionId().toString())
                    .put("originalName",version.originalName()).put("hasArchivedZip",previous.hasNonNull("resultFileId"))
                    .set("previousParse",previous);
        }
        return result;
    }
    public List<Repair> submit(Selection selection) {
        validate(selection); var actor=ActorContext.required();
        // Validate every target before enqueuing anything. Each immutable version owns its own task/checkpoint.
        preview(selection);
        return tx.execute(status -> {
            var result=new ArrayList<Repair>();
            for (var target:selection.files()) {
                var repair=repairs.reserve(actor.organizationId(),actor.userId(),target.documentId(),target.versionId(),selection.requestKey());
                if (repair.jobId()==null || (selection.retryFailed() && repair.status().equals("FAILED"))) {
                    var payload=json.createObjectNode().put("organizationId",actor.organizationId().toString()).put("repairId",repair.id().toString());
                    UUID job=async.enqueue(actor.organizationId(), JOB, payload, "kb-repair:"+repair.id()+":"+UUID.randomUUID(), 110);
                    repairs.queued(actor.organizationId(),repair.id(),job);
                }
                result.add(repairs.find(actor.organizationId(),repair.id()).orElseThrow());
            }
            return List.copyOf(result);
        });
    }
    public Repair get(UUID id) {
        var actor=ActorContext.required(); var repair=repairs.find(actor.organizationId(),id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        require(actor.organizationId(),actor.userId(),repair.documentId(),false);
        return repair;
    }
    public Repair adopt(UUID id) {
        var actor=ActorContext.required(); var repair=get(id);
        require(actor.organizationId(),actor.userId(),repair.documentId(),true);
        return tx.execute(status -> {
            if (!repairs.lockUnchanged(repair)) throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "修复期间正文或发布版本发生变化，候选结果已保留");
            var current=repairs.find(actor.organizationId(),id).orElseThrow();
            if (!current.status().equals("READY")) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "候选结果尚未就绪或已经采用");
            var parsed=json.convertValue(current.result(),DocumentParser.ParsedDocument.class);
            governance.createParseRun(actor.organizationId(),actor.userId(),current.documentId(),current.versionId(),"SUCCEEDED",
                    parsed.parserVersion(),"MineruDocumentParser",parsed.providerTaskId(),null,json.valueToTree(parsed.metadata()),parsed.blocks(),parsed.sourceTables());
            repairs.adopted(actor.organizationId(),id);
            // The normal review/approval/publish workflow is still required. No index/publication is changed here.
            return repairs.find(actor.organizationId(),id).orElseThrow();
        });
    }
    @Override public boolean supports(String type) { return JOB.equals(type); }
    @Override public JsonNode handle(JsonNode payload) { return handle(payload,null); }
    @Override public JsonNode handle(JsonNode payload, ExecutionContext execution) {
        UUID org=UUID.fromString(payload.path("organizationId").asText());
        UUID id=UUID.fromString(payload.path("repairId").asText());
        var repair=repairs.find(org,id).orElseThrow();
        if (execution!=null && !Objects.equals(repair.jobId(),execution.jobId()))
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        if (Set.of("READY","ADOPTED").contains(repair.status())) return json.createObjectNode().put("repairId",id.toString()).put("status",repair.status());
        try {
            async.requireExecution(execution,false);
            require(org,repair.requestedBy(),repair.documentId(),true);
            var source=version(org,new Target(repair.documentId(),repair.versionId()));
            repairs.running(org,id);
            var context=new DocumentParser.ParseContext(org,repair.requestedBy(),source.fileObjectId(),source.contentType(),source.size(),
                    OcrMode.ON,false,repair.documentId(),repair.versionId(),"advanced");
            var previous=repairs.previousResult(org,repair.versionId()).orElse(json.createObjectNode());
            DocumentParser.ParsedDocument parsed=archive(previous,source,context);
            if (parsed==null) {
                try (var file=storage.open(org,source.fileObjectId())) {
                    var scan=scanner.scan(file.stream(),source.originalName(),source.contentType(),source.size());
                    if (scan.status()!=FileSafetyScanner.ScanResult.Status.SAFE)
                        throw new ApiException(scan.status()==FileSafetyScanner.ScanResult.Status.UNAVAILABLE ? ApiErrorCode.SERVER_BUSY : ApiErrorCode.BAD_REQUEST,
                                "历史原文件未通过安全扫描："+scan.reason());
                }
                var parser=parsers.stream().filter(p -> p.supports(source.originalName(),source.contentType())).findFirst()
                        .orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST,"该资料格式不支持重新解析"));
                if (!parser.isConfigured()) throw new ApiException(ApiErrorCode.BAD_REQUEST,parser.unavailableReason());
                try (var file=storage.open(org,source.fileObjectId())) { parsed=parser.parse(file.stream(),source.originalName(),context); }
                var metadata=new LinkedHashMap<>(parsed.metadata()); metadata.put("repairSource","ORIGINAL_FILE");
                parsed=new DocumentParser.ParsedDocument(parsed.blocks(),parsed.parserVersion(),parsed.providerTaskId(),metadata,parsed.sourceTables());
            }
            var candidate=parsed;
            tx.executeWithoutResult(status -> {
                async.requireExecution(execution,false);
                var run=governance.createCandidateParseRun(org,repair.requestedBy(),repair.documentId(),repair.versionId(),candidate,json.valueToTree(candidate.metadata()));
                repairs.ready(org,id,run.id(),json.valueToTree(candidate));
            });
            return json.createObjectNode().put("repairId",id.toString()).put("status","READY");
        } catch (Exception error) {
            if (!isRetryable(error)) repairs.failed(org,id,error.getMessage()==null ? error.getClass().getSimpleName() : error.getMessage());
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(error);
        }
    }
    private DocumentParser.ParsedDocument archive(JsonNode previous, KnowledgeRepository.VersionRow source, DocumentParser.ParseContext context) throws Exception {
        if (!previous.hasNonNull("resultFileId") || previous.path("split").asBoolean()) return null;
        Path zip=null;
        try {
            UUID fileId=UUID.fromString(previous.path("resultFileId").asText());
            zip=Files.createTempFile("mineru-repair-", ".zip");
            try (var file=storage.open(context.organizationId(),fileId); var out=Files.newOutputStream(zip)) {
                com.jsd.aird.shared.resource.BoundedStreams.copy(file.stream(),out,512L*1024*1024,null);
            }
            Map<String,Object> provenance=json.convertValue(previous,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            var parsed=precise.adaptArchive(zip,source.originalName(),fileId,context,provenance);
            Object warnings=parsed.metadata().get("completenessWarnings");
            if (warnings instanceof List<?> values && values.stream().anyMatch(v -> String.valueOf(v).startsWith("REFERENCE_NUMBER_GAP"))) return null;
            return parsed;
        } catch (RuntimeException error) {
            if (error instanceof DocumentParsingFailure failure && failure.retryable()) throw error;
            return null;
        } catch (java.io.IOException error) { return null; }
        finally { if (zip!=null) try { Files.deleteIfExists(zip); } catch (java.io.IOException ignored) { } }
    }
    @Override public boolean isRetryable(Exception error) { return !(error instanceof DocumentParsingFailure f) || f.retryable(); }
    @Override public void handleTerminalFailure(JsonNode payload, Exception error, ExecutionContext context) {
        repairs.failed(UUID.fromString(payload.path("organizationId").asText()),UUID.fromString(payload.path("repairId").asText()),error.getMessage());
    }
}
