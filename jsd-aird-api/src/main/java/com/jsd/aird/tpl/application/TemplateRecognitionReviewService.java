package com.jsd.aird.tpl.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateRecognitionReviewService {

    private final TemplateImportRepository importRepository;
    private final TemplateRepository templateRepository;
    private final FileObjectRepository fileRepository;
    private final ObjectStorage objectStorage;
    private final ObjectMapper objectMapper;
    private final RecognitionModelClient recognitionModelClient;
    private final ModelSemanticViewBuilder semanticViewBuilder;

    public TemplateRecognitionReviewService(
            TemplateImportRepository importRepository,
            TemplateRepository templateRepository,
            FileObjectRepository fileRepository,
            ObjectStorage objectStorage,
            ObjectMapper objectMapper,
            RecognitionModelClient recognitionModelClient
    ) {
        this.importRepository = importRepository;
        this.templateRepository = templateRepository;
        this.fileRepository = fileRepository;
        this.objectStorage = objectStorage;
        this.objectMapper = objectMapper;
        this.recognitionModelClient = recognitionModelClient;
        this.semanticViewBuilder = new ModelSemanticViewBuilder(objectMapper);
    }

    public RecognitionReview get(UUID versionId) {
        var actor = ActorContext.required();
        var workspace = requireWorkspace(actor.organizationId(), versionId);
        return assemble(actor.organizationId(), workspace);
    }

    @Transactional
    public TemplateImportRepository.ImportJobView start(
            UUID versionId, String scope, String sheetId, String address, JsonNode snapshotFragment
    ) {
        var actor = ActorContext.required();
        if (!"WORKBOOK".equals(scope) && !"REGION".equals(scope)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "识别范围只能是整份工作簿或选定区域");
        }
        if ("REGION".equals(scope) && (sheetId == null || sheetId.isBlank()
                || address == null || !address.toUpperCase(Locale.ROOT).matches(
                "^[A-Z]{1,4}[1-9][0-9]*(?::[A-Z]{1,4}[1-9][0-9]*)?$"))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请先在 Excel 中选择需要重新识别的连续区域");
        }
        var workspace = requireWorkspace(actor.organizationId(), versionId);
        if (workspace.status() != TemplateStatus.DRAFT || workspace.format() != TemplateFormat.XLSX) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "只有 Excel 草稿可以重新识别");
        }
        if (workspace.snapshotFileId() == null) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "请先保存当前工作簿，再重新识别");
        }
        fileRepository.find(actor.organizationId(), workspace.snapshotFileId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
        var sourceFileId = recognitionSourceFileId(actor.organizationId(), workspace.versionId(), workspace.snapshotFileId());
        var sourceKind = sourceFileId.equals(workspace.snapshotFileId()) ? "UNIVER_SNAPSHOT" : "OFFICE_FILE";
        var importJobId = UUID.randomUUID();
        importRepository.enqueue(new TemplateImportRepository.NewImportJob(
                importJobId,
                UUID.randomUUID(),
                actor.organizationId(),
                sourceFileId,
                TemplateFormat.XLSX,
                actor.userId(),
                sourceKind,
                scope,
                sheetId,
                address == null ? null : address.toUpperCase(Locale.ROOT),
                snapshotFragment
        ));
        importRepository.linkGeneratedVersion(actor.organizationId(), importJobId, versionId);
        templateRepository.appendAudit(
                actor.organizationId(), actor.userId(), "TEMPLATE_RECOGNITION_RESTARTED",
                "TEMPLATE_VERSION", versionId,
                objectMapper.createObjectNode()
                        .put("importJobId", importJobId.toString())
                        .put("scope", scope)
                        .put("sheetId", sheetId == null ? "" : sheetId)
                        .put("address", address == null ? "" : address)
        );
        return importRepository.find(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "识别任务创建失败"));
    }

    /**
     * A retry normally starts from the saved editor snapshot. Before the border registry
     * was introduced, however, some snapshots had no border facts at all. Rebuilding such
     * a snapshot from the original Office file is safe and makes the retry self-healing.
     */
    private UUID recognitionSourceFileId(UUID organizationId, UUID versionId, UUID snapshotFileId) {
        if (!snapshotNeedsBorderRecovery(organizationId, snapshotFileId)) {
            return snapshotFileId;
        }
        return importRepository.findOriginalSourceFileId(organizationId, versionId)
                .filter(originalFileId -> !originalFileId.equals(snapshotFileId))
                .orElse(snapshotFileId);
    }

    private boolean snapshotNeedsBorderRecovery(UUID organizationId, UUID snapshotFileId) {
        var file = fileRepository.find(organizationId, snapshotFileId).orElse(null);
        if (file == null || !file.contentType().toLowerCase(Locale.ROOT).contains("json")) {
            return false;
        }
        try (var stored = objectStorage.get(file.objectKey())) {
            var snapshot = objectMapper.readTree(stored.stream());
            return snapshot.isObject() && !containsBorderStyle(snapshot);
        } catch (Exception ignored) {
            // A retry must remain available even if a legacy snapshot cannot be inspected.
            return false;
        }
    }

    private boolean containsBorderStyle(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return false;
        if (node.isObject()) {
            if (node.path("bd").isObject() && !node.path("bd").isEmpty()) return true;
            var fields = node.fields();
            while (fields.hasNext()) {
                if (containsBorderStyle(fields.next().getValue())) return true;
            }
            return false;
        }
        if (node.isArray()) {
            for (var item : node) {
                if (containsBorderStyle(item)) return true;
            }
        }
        return false;
    }

    @Transactional
    public void applyActions(
            UUID organizationId,
            UUID actorId,
            UUID versionId,
            List<RecognitionAction> actions
    ) {
        if (actions == null || actions.isEmpty()) return;
        var workspace = requireWorkspace(organizationId, versionId);
        var review = assemble(organizationId, workspace);
        var byId = new java.util.HashMap<UUID, RecognitionReviewItem>();
        review.items().forEach(item -> item.suggestionIds().forEach(id -> byId.put(id, item)));
        var recompiledStructures = new LinkedHashSet<UUID>();
        for (RecognitionAction action : actions) {
            if (!"CONFIRM".equals(action.action())) continue;
            var item = byId.get(action.recognitionItemId());
            if (item == null) continue;
            var selectedId = action.selectedSuggestionId() == null
                    ? action.recognitionItemId() : action.selectedSuggestionId();
            var selected = importRepository.listSuggestions(organizationId, review.recognitionRunId()).stream()
                    .filter(candidate -> selectedId.equals(candidate.id())).findFirst().orElse(null);
            if (selected != null && isStructuralCandidate(selected.payload())
                    && !selected.payload().path("resolutionGroupId").asText("").isBlank()) {
                recompileSelectedStructure(organizationId, workspace, selected);
                recompiledStructures.add(selected.id());
            }
        }
        for (RecognitionAction action : actions) {
            var item = byId.get(action.recognitionItemId());
            if (item == null) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "识别项目已变化，请刷新后重试");
            }
            var decision = switch (action.action()) {
                case "CONFIRM" -> "ACCEPTED";
                case "IGNORE" -> "REJECTED";
                case "RESTORE" -> "PENDING";
                default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的识别处理操作");
            };
            var resolutionGroupId = item.payload().path("resolutionGroupId").asText("");
            var selectedSuggestionId = action.selectedSuggestionId() == null
                    ? action.recognitionItemId() : action.selectedSuggestionId();
            if ("ACCEPTED".equals(decision) && isStructuralCandidate(item.payload())
                    && resolutionGroupId.isBlank()
                    && !RecognitionCandidatePolicy.isFormallyConfirmable(item.payload())) {
                throw new ApiException(ApiErrorCode.BINDING_INVALID,
                        "未进入结构冲突组的结构候选不能单独确认，请重新执行结构识别");
            }
            if ("ACCEPTED".equals(decision) && !resolutionGroupId.isBlank()
                    && !item.suggestionIds().contains(selectedSuggestionId)) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "所选结构候选不属于当前冲突组");
            }
            for (UUID suggestionId : item.suggestionIds()) {
                var itemDecision = decision;
                if ("ACCEPTED".equals(decision) && !resolutionGroupId.isBlank()
                        && !suggestionId.equals(selectedSuggestionId)) {
                    // Structure alternatives are mutually exclusive. The
                    // rejected alternative is retained for audit, but can
                    // never be accepted alongside the selected candidate.
                    itemDecision = "REJECTED_BY_RESOLUTION";
                }
                importRepository.decideSuggestion(
                        organizationId, review.recognitionRunId(), suggestionId, itemDecision, actorId
                ).orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST, "识别项目已变化，请刷新后重试"));
            }
            if ("ACCEPTED".equals(decision) && recompiledStructures.contains(selectedSuggestionId)) {
                importRepository.markStructureResolved(organizationId, review.recognitionRunId(), selectedSuggestionId);
            }
        }
        refreshReviewResolution(organizationId, versionId);
    }

    private void refreshReviewResolution(UUID organizationId, UUID versionId) {
        var workspace = requireWorkspace(organizationId, versionId);
        var run = importRepository.findLatestForVersion(organizationId, workspace.versionId()).orElse(null);
        if (run == null) return;
        var review = assemble(organizationId, workspace);
        var openItems = review.items().stream()
                .anyMatch(item -> Set.of("PENDING", "CONFLICT").contains(item.status()));
        var openBlocker = importRepository.listQualityIssues(organizationId, run.id()).stream()
                .anyMatch(issue -> "BLOCKER".equals(issue.severity())
                        && !Set.of("AUTO_APPLIED", "CONFIRMED", "IGNORED").contains(issue.status()));
        ObjectNode result = run.result() instanceof ObjectNode object
                ? (ObjectNode) object.deepCopy() : objectMapper.createObjectNode();
        var hasOpenStructure = review.items().stream().anyMatch(item ->
                item.payload().path("resolutionGroupId").asText("").length() > 0
                        && !"ACCEPTED".equals(item.status())
                        && !"REJECTED".equals(item.status()));
        var resolved = !openItems && !openBlocker && !hasOpenStructure;
        var canonicalConfirmed = importRepository.listSuggestions(organizationId, run.id()).stream()
                .filter(item -> "ACCEPTED".equals(item.decision()))
                .anyMatch(item -> isStructuralCandidate(item.payload())
                        && "CONFIRMED".equals(item.payload().path("canonicalStatus").asText())
                        && "CONFIRMED".equals(item.payload().path("structureStatus").asText()));
        result.put("reviewResolutionStatus", resolved ? "RESOLVED" : "OPEN")
                .put("resolutionSource", resolved ? "HUMAN_REVIEW" : "")
                .put("canonicalStatus", canonicalConfirmed ? "CONFIRMED" : "PROVISIONAL")
                .put("publicationReadiness", resolved ? "READY" : "NOT_READY");
        importRepository.saveImportResult(run.id(), result);
    }

    private boolean isStructuralCandidate(JsonNode payload) {
        var type = payload.path("kind").asText(payload.path("tableKind").asText(
                payload.path("blockType").asText("")));
        return Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE", "FORM_REGION", "TABLE_REGION").contains(type);
    }

    /**
     * A structure alternative is only accepted after a fresh semantic call has
     * succeeded for that exact geometry.  The old candidate remains in the
     * database for audit; the new semantic suggestions are appended as pending
     * review items, so selecting a geometry can never silently publish fields.
     */
    private void recompileSelectedStructure(
            UUID organizationId,
            TemplateRepository.TemplateWorkspace workspace,
            TemplateImportRepository.RecognitionSuggestionView selected
    ) {
        if (!recognitionModelClient.isConfigured()) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID, "模型服务未配置，无法为所选结构重新识别语义");
        }
        var run = importRepository.findLatestForVersion(organizationId, workspace.versionId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "识别运行不存在"));
        var facts = run.structureSummary();
        if (facts == null || !facts.isObject()) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID, "缺少工作簿物理事实，无法重新编译结构语义");
        }
        var payload = selected.payload();
        var locator = payload.path("locator");
        var type = payload.path("kind").asText(payload.path("tableKind").asText(
                payload.path("blockType").asText("UNKNOWN")));
        var sheetId = locator.path("sheetId").asText(payload.path("sheetId").asText(""));
        var range = locator.path("range").asText(locator.path("address").asText(
                payload.path("range").asText("")));
        if (sheetId.isBlank() || range.isBlank()) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID, "所选结构缺少有效 Sheet 或区域");
        }
        var regionId = selected.payload().path("resolutionGroupId").asText(
                selected.payload().path("candidateRef").asText(selected.id().toString()));
        var region = objectMapper.createObjectNode()
                .put("regionId", regionId).put("blockId", regionId)
                .put("sheetId", sheetId).put("range", range).put("type", type)
                .put("businessName", payload.path("fieldName").asText(payload.path("blockName").asText("待确认区域")))
                .put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED")
                .put("reviewRequired", true)
                .put("pendingReason", "SEMANTIC_RECOGNITION_REQUIRED");
        var geometry = region.putObject("structure");
        var source = payload.path("structure").isObject() ? payload.path("structure") : payload;
        for (var key : List.of("cornerRange", "rowHeaderRange", "columnHeaderRange", "crossDataRange",
                "headerRange", "dataRange", "totalRange", "recordAxis", "recordHeight", "recordWidth",
                "recordStride")) {
            if (source.has(key)) geometry.set(key, source.path(key).deepCopy());
            else if (locator.has(key)) geometry.set(key, locator.path(key).deepCopy());
        }
        var context = semanticViewBuilder.build(facts, "REGION", sheetId, range);
        context.putArray("semanticRegions").add(region);
        RecognitionModelClient.RecognitionBatch batch;
        try {
            batch = recognitionModelClient.recognize(new RecognitionModelClient.RecognitionRequest(
                    run.id(), run.recognitionRunId(), workspace.format(), run.sourceFileName(), regionId,
                    context, null, "REGION_FIELDS"));
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID,
                    "所选结构的语义重新识别失败，请保留原结构候选后重试："
                            + (exception.getMessage() == null ? "协议错误" : exception.getMessage()));
        }
        for (var trace : batch.callTraces()) importRepository.saveRecognitionCall(run.recognitionRunId(), trace);
        var sanitized = sanitizeRecompiledBatch(batch, regionId);
        if (sanitized.suggestions().isEmpty()) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID, "所选结构未生成有效语义结果，不能确认结构");
        }
        importRepository.appendModelSuggestions(run.id(), run.recognitionRunId(), sanitized);
    }

    private RecognitionModelClient.RecognitionBatch sanitizeRecompiledBatch(
            RecognitionModelClient.RecognitionBatch batch, String regionId
    ) {
        var suggestions = batch.suggestions().stream().map(suggestion -> {
            if (!(suggestion.payload() instanceof ObjectNode payload)) return suggestion;
            payload.remove("resolutionGroupId");
            payload.put("canonicalStatus", "CONFIRMED")
                    .put("structureStatus", "CONFIRMED")
                    .put("candidateOnly", false)
                    .put("physicalStructureOnly", false)
                    .put("structureConflict", false)
                    .put("reviewRequired", true)
                    .put("pendingReason", "SEMANTIC_RECOGNITION_REQUIRED")
                    .put("semanticRecompileRegionId", regionId);
            return new RecognitionModelClient.ModelSuggestion(
                    suggestion.suggestionType(), payload, suggestion.confidence(), suggestion.evidence());
        }).toList();
        return new RecognitionModelClient.RecognitionBatch(
                suggestions, batch.qualityIssues(), batch.provider(), batch.model(), batch.promptVersion(),
                batch.requestHash(), batch.responseHash(), batch.callTrace(), batch.callTraces());
    }

    @Transactional
    public void applyQualityActions(
            UUID organizationId,
            UUID actorId,
            UUID versionId,
            List<QualityAction> actions
    ) {
        if (actions == null || actions.isEmpty()) return;
        var workspace = requireWorkspace(organizationId, versionId);
        var run = importRepository.findLatestForVersion(organizationId, workspace.versionId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST, "模板规范结果已变化，请刷新后重试"));
        var available = importRepository.listQualityIssues(organizationId, run.id()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        TemplateImportRepository.QualityIssueView::id, item -> item
                ));
        for (var action : actions) {
            if (!available.containsKey(action.issueId())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "模板规范问题已变化，请刷新后重试");
            }
            importRepository.decideQualityIssue(
                    organizationId, run.id(), action.issueId(), action.action(), actorId
            ).orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST, "模板规范问题处理失败"));
        }
    }

    public boolean hasOpenConflicts(UUID organizationId, UUID versionId) {
        var workspace = requireWorkspace(organizationId, versionId);
        return assemble(organizationId, workspace).summary().conflict() > 0;
    }

    /**
     * The browser may keep recognition candidates in its temporary editing model, but it must not
     * persist one as a formal mapping before the customer has accepted it. Hand-authored mappings
     * have no recognitionItemId and intentionally pass this check.
     */
    public void validateAcceptedMappings(UUID organizationId, UUID versionId, JsonNode mapping) {
        if (mapping == null || !mapping.isArray()) return;
        var workspace = requireWorkspace(organizationId, versionId);
        var run = importRepository.findLatestForVersion(organizationId, workspace.versionId()).orElse(null);
        var decisions = new java.util.HashMap<UUID, String>();
        var acceptedBindingIds = new java.util.HashSet<String>();
        var acceptedRelationIds = new java.util.HashSet<String>();
        if (run != null) {
            importRepository.listSuggestions(organizationId, run.id()).forEach(item -> {
                decisions.put(item.id(), item.decision());
                if ("ACCEPTED".equals(item.decision())) {
                    var payload = item.payload();
                    var bindingId = payload.path("bindingId").asText("");
                    var relationId = payload.path("relationId").asText("");
                    if (!bindingId.isBlank()) acceptedBindingIds.add(bindingId);
                    if (!relationId.isBlank()) acceptedRelationIds.add(relationId);
                }
            });
        }
        for (JsonNode binding : mapping) {
            var recognitionItemId = binding.path("diagnostic").path("recognitionItemId").asText("");
            var bindingId = binding.path("bindingId").asText("");
            var relationId = binding.path("relationId").asText("");
            if (recognitionItemId.isBlank()) continue;
            var acceptedByStableIdentity = (!bindingId.isBlank() && acceptedBindingIds.contains(bindingId))
                    || (!relationId.isBlank() && acceptedRelationIds.contains(relationId));
            if (acceptedByStableIdentity) continue;
            try {
                var decision = decisions.get(UUID.fromString(recognitionItemId));
                if (!"ACCEPTED".equals(decision)) {
                    throw new ApiException(ApiErrorCode.BINDING_INVALID,
                            "请先在识别确认中确认该字段，再保存为正式模板字段");
                }
            } catch (IllegalArgumentException exception) {
                throw new ApiException(ApiErrorCode.BINDING_INVALID, "识别字段标识无效，请刷新后重试");
            }
        }
    }

    public boolean hasOpenBlockingIssues(UUID organizationId, UUID versionId) {
        var workspace = requireWorkspace(organizationId, versionId);
        var run = importRepository.findLatestForVersion(organizationId, workspace.versionId()).orElse(null);
        if (run == null) return false;
        return importRepository.listQualityIssues(organizationId, run.id()).stream()
                .anyMatch(issue -> "BLOCKER".equals(issue.severity())
                        && !java.util.Set.of("AUTO_APPLIED", "CONFIRMED", "IGNORED")
                        .contains(issue.status()));
    }

    private RecognitionReview assemble(UUID organizationId, TemplateRepository.TemplateWorkspace workspace) {
        var run = importRepository.findLatestForVersion(organizationId, workspace.versionId()).orElse(null);
        if (run == null) {
            return new RecognitionReview(null, "NONE", emptySummary(), List.of(), List.of(), List.of(), null,
                    "NO_PHYSICAL_TABLE", objectMapper.createObjectNode());
        }
        var suggestions = importRepository.listSuggestions(organizationId, run.id());
        var semanticModel = suggestions.stream()
                .filter(suggestion -> "SEMANTIC_MODEL".equals(suggestion.suggestionType()))
                .findFirst()
                .map(suggestion -> (JsonNode) suggestion.payload().deepCopy())
                .orElse(null);
        if (semanticModel instanceof com.fasterxml.jackson.databind.node.ObjectNode semanticObject) {
            semanticObject.set("diagnostics", compileDiagnostics(suggestions, semanticModel));
        }
        var grouped = new LinkedHashMap<String, List<TemplateImportRepository.RecognitionSuggestionView>>();
        suggestions.stream()
                .filter(suggestion -> !"SEMANTIC_MODEL".equals(suggestion.suggestionType()))
                .filter(suggestion -> !isProtocolRejected(suggestion.payload()))
                .filter(suggestion -> !isStaticCandidate(suggestion, semanticModel))
                .sorted(suggestionOrder()).forEach(suggestion ->
                grouped.computeIfAbsent(reviewKey(suggestion), ignored -> new ArrayList<>()).add(suggestion)
        );
        var items = new ArrayList<RecognitionReviewItem>();
        var fields = workspace.schema().path(TemplateRecognitionCompiler.FIELD_MODEL_KEY).path("fields");
        for (List<TemplateImportRepository.RecognitionSuggestionView> candidates : grouped.values()) {
            var primary = candidates.getFirst();
            var semanticConflict = candidates.stream()
                    .filter(candidate -> !isResolutionRejected(candidate))
                    .anyMatch(candidate -> candidate.payload().path("semanticConflict").asBoolean(false)
                            && !"ACCEPTED".equals(candidate.decision()));
            var conflict = semanticConflict || candidates.stream().skip(1)
                    .filter(candidate -> !isResolutionRejected(candidate))
                    .anyMatch(candidate -> !structuralSignature(primary).equals(structuralSignature(candidate)));
            var allRejected = candidates.stream().allMatch(this::isRejected);
            var anyAccepted = candidates.stream().anyMatch(candidate -> "ACCEPTED".equals(candidate.decision()));
            // 语义冲突优先于“已有一个候选被接受”：比例/理论投料量等冲突必须
            // 在整个候选组确认后才算正式字段，不能因为单个候选已接受而绕过审核。
            var status = allRejected ? "IGNORED" : conflict ? "CONFLICT" : anyAccepted ? "CONFIRMED" : "PENDING";
            var payload = primary.payload().deepCopy();
            if (payload instanceof ObjectNode payloadObject
                    && payloadObject.path("resolutionGroupId").isTextual()
                    && !payloadObject.path("resolutionGroupId").asText().isBlank()
                    && candidates.size() > 1) {
                var alternatives = payloadObject.putArray("structureAlternatives");
                for (var candidate : candidates) {
                    alternatives.add(objectMapper.createObjectNode()
                            .put("suggestionId", candidate.id().toString())
                            .put("source", candidate.source())
                            .put("kind", kind(candidate))
                            .put("range", locatorAddress(candidate.payload().path("locator")))
                            .put("decision", candidate.decision()));
                }
            }
            var kind = kind(primary);
            var groupName = payload.path("groupName").asText("").strip();
            var blockName = payload.path("blockName").asText("");
            var blockType = payload.path("blockType").asText("");
            groupName = blockName.isBlank()
                    ? GroupNameNormalizer.normalizeModelSuggestion(groupName)
                    .orElse(GroupNameNormalizer.inferFromBlock(blockType, payload.path("fieldName").asText("")))
                    : GroupNameNormalizer.inferFromBlock(blockType, blockName);
            items.add(new RecognitionReviewItem(
                    primary.id(),
                    candidates.stream().map(TemplateImportRepository.RecognitionSuggestionView::id).toList(),
                    findFieldId(fields, primary.id(), payload),
                    payload.path("parentRelationId").asText(""),
                    payload.path("parentFieldId").asText(""),
                    "CHILD".equals(payload.path("suggestionLevel").asText("")),
                    payload.path("fieldName").asText("业务字段"),
                    payload.path("reason").asText("根据模板内容自动识别"),
                    groupName,
                    kind,
                    payload.path("valueType").asText("string"),
                    payload.path("locator").path("sheetId").asText(""),
                    payload.path("locator").path("sheetName").asText(""),
                    payload.path("locator").path("labelAddress").asText(""),
                    locatorAddress(payload.path("locator")),
                    primary.confidence(),
                    confidenceLevel(primary.confidence()),
                    primary.source(),
                    primary.filterReasonCode(),
                    primary.filterDetail(),
                    status,
                    conflict ? semanticConflictMessage(primary, candidates, semanticConflict)
                            : null,
                     payload
             ));
        }
        var active = items.stream().filter(item -> !"IGNORED".equals(item.status())).toList();
        var groups = new LinkedHashSet<String>();
        active.forEach(item -> groups.add(item.groupName()));
        var qualityIssues = importRepository.listQualityIssues(organizationId, run.id()).stream()
                .map(this::qualityItem).toList();
        var summary = new RecognitionSummary(
                active.size(),
                count(active, "CONFIRMED"),
                count(active, "PENDING"),
                (int) active.stream().filter(item -> item.confidence() < 0.65).count(),
                count(active, "CONFLICT"),
                (int) items.stream().filter(item -> "IGNORED".equals(item.status())).count(),
                (int) active.stream().filter(item -> "SCALAR".equals(item.kind())).count(),
                (int) active.stream().filter(item -> "ROW_TABLE".equals(item.kind())).count(),
                (int) active.stream().filter(item -> "MATRIX".equals(item.kind())).count(),
                qualityIssues.size(),
                (int) qualityIssues.stream().filter(item -> "AUTO_APPLIED".equals(item.status())).count(),
                (int) qualityIssues.stream().filter(item -> "BLOCKER".equals(item.severity())
                        && !java.util.Set.of("AUTO_APPLIED", "CONFIRMED", "IGNORED")
                        .contains(item.status())).count()
        );
        return new RecognitionReview(
                run.recognitionRunId(), run.recognitionRunStatus() == null ? run.status() : run.recognitionRunStatus(),
                summary, List.copyOf(groups), List.copyOf(items), qualityIssues,
                semanticModel,
                run.result() == null ? "REVIEW_REQUIRED"
                        : run.result().path("recognitionStatus").asText("REVIEW_REQUIRED"),
                run.result() == null ? objectMapper.createObjectNode()
                        : run.result().path("recognitionCoverage").deepCopy()
        );
    }

    private QualityIssueItem qualityItem(TemplateImportRepository.QualityIssueView issue) {
        return new QualityIssueItem(
                issue.id(), issue.issueType(), issue.severity(), issue.confidence(), issue.sheetId(),
                issue.sheetName(), issue.address(), issue.title(), issue.description(),
                issue.businessImpact(), issue.autoFixable(), issue.status(), issue.suggestedPatch(),
                issue.inversePatch(), issue.evidence()
        );
    }

    private TemplateRepository.TemplateWorkspace requireWorkspace(UUID organizationId, UUID versionId) {
        return templateRepository.findWorkspace(organizationId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "模板版本不存在"));
    }

    private Comparator<TemplateImportRepository.RecognitionSuggestionView> suggestionOrder() {
        return Comparator.comparingDouble(TemplateImportRepository.RecognitionSuggestionView::confidence).reversed()
                .thenComparingInt(item -> "MODEL".equals(item.source()) ? 0 : 1)
                .thenComparing(TemplateImportRepository.RecognitionSuggestionView::createdAt);
    }

    private String reviewKey(TemplateImportRepository.RecognitionSuggestionView suggestion) {
        var payload = suggestion.payload();
        var resolutionGroupId = payload.path("resolutionGroupId").asText("");
        if (!resolutionGroupId.isBlank()) return "resolution:" + resolutionGroupId;
        var regionId = payload.path("regionId").asText("");
        if (!regionId.isBlank() && !"SCALAR".equals(kind(suggestion))) {
            return "region:" + regionId;
        }
        var locator = payload.path("locator");
        var address = locatorAddress(locator);
        var sheet = locator.path("sheetId").asText(locator.path("sheetName").asText(""));
        if (!sheet.isBlank() && !address.isBlank()) return "location:" + sheet + "|" + address;
        var path = payload.path("dataPath").asText("").strip();
        return path.isBlank() ? "suggestion:" + suggestion.id() : "path:" + path;
    }

    private String structuralSignature(TemplateImportRepository.RecognitionSuggestionView suggestion) {
        var payload = suggestion.payload();
        var locator = payload.path("locator");
        return String.join("|",
                kind(suggestion), payload.path("valueType").asText("string"),
                locator.path("sheetId").asText(locator.path("sheetName").asText("")),
                locatorAddress(locator)
        );
    }

    private String conflictMessage(
            TemplateImportRepository.RecognitionSuggestionView primary,
            List<TemplateImportRepository.RecognitionSuggestionView> candidates
    ) {
        var differentKind = candidates.stream().anyMatch(item -> !kind(item).equals(kind(primary)));
        if (differentKind) return "系统对这一区域是普通字段、明细表还是矩阵存在不同判断，请确认正确类型。";
        var differentAddress = candidates.stream().anyMatch(item ->
                !locatorAddress(item.payload().path("locator"))
                        .equals(locatorAddress(primary.payload().path("locator"))));
        if (differentAddress) return "系统识别出多个可能的填写区域，请确认当前高亮位置是否正确。";
        return "系统对该字段的数据类型存在不同判断，请确认字段属性。";
    }

    /**
     * Recognition execution status remains immutable for audit. Human review may
     * resolve it separately, but publication still requires that explicit state.
     */
    public boolean hasIncompleteRecognition(UUID organizationId, UUID versionId) {
        var workspace = requireWorkspace(organizationId, versionId);
        var run = importRepository.findLatestForVersion(organizationId, workspace.versionId()).orElse(null);
        if (run == null || run.result() == null) return true;
        var result = run.result();
        var recognitionStatus = result.path("recognitionStatus").asText("REVIEW_REQUIRED");
        var reviewResolutionStatus = result.path("reviewResolutionStatus").asText(
                "COMPLETE".equals(recognitionStatus) ? "NOT_REQUIRED" : "OPEN");
        var canonicalStatus = result.path("canonicalStatus").asText("PROVISIONAL");
        var readiness = result.path("publicationReadiness").asText(
                "COMPLETE".equals(recognitionStatus) ? "READY" : "NOT_READY");
        return !("CONFIRMED".equals(canonicalStatus)
                && ("COMPLETE".equals(recognitionStatus) || "RESOLVED".equals(reviewResolutionStatus))
                && "READY".equals(readiness));
    }

    private com.fasterxml.jackson.databind.node.ArrayNode compileDiagnostics(
            List<TemplateImportRepository.RecognitionSuggestionView> suggestions,
            JsonNode semanticModel
    ) {
        var result = objectMapper.createArrayNode();
        for (var suggestion : suggestions) {
            if ("SEMANTIC_MODEL".equals(suggestion.suggestionType())) continue;
            if (isStaticCandidate(suggestion, semanticModel)) continue;
            var payload = suggestion.payload();
            var code = compileReasonCode(suggestion);
            if (code == null) continue;
            var locator = payload.path("locator");
            result.add(objectMapper.createObjectNode()
                    .put("stage", "FORMAL_MAPPING_COMPILE")
                    .put("reasonCode", code)
                    .put("message", compileReasonMessage(code))
                    .set("detail", objectMapper.createObjectNode()
                            .put("suggestionId", suggestion.id().toString())
                            .put("decision", suggestion.decision())
                            .put("fieldName", payload.path("fieldName").asText("业务字段"))
                            .put("sheetId", locator.path("sheetId").asText(""))
                            .put("address", locatorAddress(locator))));
        }
        return result;
    }

    private boolean isStaticCandidate(
            TemplateImportRepository.RecognitionSuggestionView suggestion,
            JsonNode semanticModel
    ) {
        if (semanticModel == null) return false;
        var locator = suggestion.payload().path("locator");
        var sheetId = locator.path("sheetId").asText("");
        var address = locatorAddress(locator);
        var candidate = reviewRange(address);
        if (sheetId.isBlank() || candidate == null) return false;
        for (var region : semanticModel.path("staticRegions")) {
            if (!sheetId.equals(region.path("sheetId").asText(""))) continue;
            var fixed = reviewRange(region.path("address").asText(region.path("range").asText("")));
            if (fixed != null && overlaps(candidate, fixed)) return true;
        }
        return false;
    }

    private boolean isProtocolRejected(JsonNode payload) {
        return "RETAINED_REJECTED_CANDIDATE".equals(payload.path("protocolRecovery").asText())
                || "PROTOCOL_REVIEW_REQUIRED".equals(payload.path("pendingReason").asText());
    }

    private boolean isResolutionRejected(TemplateImportRepository.RecognitionSuggestionView suggestion) {
        return "REJECTED_BY_RESOLUTION".equals(suggestion.decision())
                || "REJECTED_BY_RESOLUTION".equals(suggestion.payload().path("resolutionDecision").asText(""))
                || isRejected(suggestion);
    }

    private boolean isRejected(TemplateImportRepository.RecognitionSuggestionView suggestion) {
        return Set.of("REJECTED", "REJECTED_BY_RESOLUTION").contains(suggestion.decision())
                || "REJECTED_BY_RESOLUTION".equals(suggestion.payload().path("resolutionDecision").asText(""));
    }

    private boolean overlaps(int[] left, int[] right) {
        return left[0] <= right[2] && right[0] <= left[2]
                && left[1] <= right[3] && right[1] <= left[3];
    }

    private int[] reviewRange(String address) {
        if (address == null || address.isBlank()) return null;
        var parts = address.toUpperCase(Locale.ROOT).split(":", 2);
        var first = reviewCell(parts[0]);
        var last = reviewCell(parts.length == 1 ? parts[0] : parts[1]);
        if (first == null || last == null) return null;
        return new int[]{Math.min(first[0], last[0]), Math.min(first[1], last[1]),
                Math.max(first[0], last[0]), Math.max(first[1], last[1])};
    }

    private int[] reviewCell(String address) {
        var match = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$")
                .matcher(address == null ? "" : address);
        if (!match.matches()) return null;
        var column = 0;
        for (var letter : match.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return new int[]{column, Integer.parseInt(match.group(2))};
    }

    private String compileReasonCode(
            TemplateImportRepository.RecognitionSuggestionView suggestion
    ) {
        var payload = suggestion.payload();
        if (payload.path("semanticConflict").asBoolean(false)
                && !"ACCEPTED".equals(suggestion.decision())) return "SEMANTIC_CONFLICT";
        if ("CHILD".equals(payload.path("suggestionLevel").asText(""))
                && payload.path("parentRelationId").asText("").isBlank()) {
            return "CHILD_PARENT_MISSING";
        }
        // 标准字段未匹配和普通 PENDING 都是字段级审核提示，已经在对应的
        // 识别项中展示；不要再把每个字段复制成顶部全局诊断。
        if (payload.path("requiresStandardConfirmation").asBoolean(false)) return null;
        if ("UNKNOWN".equals(payload.path("editability").asText("UNKNOWN"))) {
            return "EDITABILITY_UNKNOWN";
        }
        if ("UNKNOWN".equals(payload.path("valueSource").asText("UNKNOWN"))) {
            return "VALUE_SOURCE_UNKNOWN";
        }
        return null;
    }

    private String compileReasonMessage(String code) {
        return switch (code) {
            case "SEMANTIC_CONFLICT" -> "字段含义或单位存在冲突，未自动生成正式 Mapping。";
            case "CHILD_PARENT_MISSING" -> "明细字段缺少有效父级，未生成正式 Mapping。";
            case "STANDARD_FIELD_UNMATCHED" -> "未匹配到标准字段，需要人工确认后才能纳入正式模板。";
            case "EDITABILITY_UNKNOWN" -> "无法确定填写权限，需要人工确认。";
            case "VALUE_SOURCE_UNKNOWN" -> "无法确定数据来源，需要人工确认。";
            default -> "识别建议尚未确认，暂不编译为正式字段。";
        };
    }

    private String semanticConflictMessage(
            TemplateImportRepository.RecognitionSuggestionView primary,
            List<TemplateImportRepository.RecognitionSuggestionView> candidates,
            boolean semanticConflict
    ) {
        if (semanticConflict) {
            var message = primary.payload().path("conflictMessage").asText("");
            if (!message.isBlank()) return message;
            return "该字段的名称、单位或布局存在业务含义冲突，请先选择正确的标准字段。";
        }
        return conflictMessage(primary, candidates);
    }

    private UUID findFieldId(JsonNode fields, UUID recognitionItemId, JsonNode payload) {
        if (!fields.isArray()) return null;
        var bindingId = payload.path("bindingId").asText("");
        var relationId = payload.path("relationId").asText("");
        var fieldId = payload.path("fieldId").asText("");
        for (JsonNode field : fields) {
            var stableMatch = (!bindingId.isBlank() && bindingId.equals(field.path("bindingId").asText()))
                    || (!relationId.isBlank() && relationId.equals(field.path("relationId").asText()))
                    || (!fieldId.isBlank() && fieldId.equals(field.path("fieldId").asText()));
            var recognitionMatch = bindingId.isBlank() && relationId.isBlank() && fieldId.isBlank()
                    && recognitionItemId.toString().equals(field.path("recognitionItemId").asText());
            if (stableMatch || recognitionMatch) {
                try {
                    return UUID.fromString(field.path("id").asText());
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String kind(TemplateImportRepository.RecognitionSuggestionView suggestion) {
        var explicit = suggestion.payload().path("kind").asText("");
        if (!explicit.isBlank()) return explicit;
        if (suggestion.suggestionType().contains("MATRIX")) return "MATRIX";
        if (suggestion.suggestionType().contains("TABLE")
                || "REPEAT_REGION".equals(suggestion.payload().path("role").asText())) return "ROW_TABLE";
        return "SCALAR";
    }

    private String locatorAddress(JsonNode locator) {
        var address = locator.path("address").asText("");
        return address.isBlank() ? locator.path("range").asText("") : address;
    }

    private String confidenceLevel(double confidence) {
        return confidence >= 0.85 ? "HIGH" : confidence >= 0.65 ? "MEDIUM" : "LOW";
    }

    private int count(List<RecognitionReviewItem> items, String status) {
        return (int) items.stream().filter(item -> status.equals(item.status())).count();
    }

    private RecognitionSummary emptySummary() {
        return new RecognitionSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public record RecognitionReview(
            UUID recognitionRunId,
            String runStatus,
            RecognitionSummary summary,
            List<String> groups,
            List<RecognitionReviewItem> items,
            List<QualityIssueItem> qualityIssues,
            JsonNode semanticModel,
            String recognitionStatus,
            JsonNode recognitionCoverage
    ) {
    }

    public record RecognitionSummary(
            int total,
            int confirmed,
            int pending,
            int lowConfidence,
            int conflict,
            int ignored,
            int scalar,
            int rowTable,
            int matrix,
            int qualityIssueCount,
            int autoFixedCount,
            int blockingIssueCount
    ) {
    }

    public record RecognitionReviewItem(
            UUID id,
            List<UUID> suggestionIds,
            UUID fieldId,
            String parentSuggestionId,
            String parentFieldId,
            boolean child,
            String fieldName,
            String description,
            String groupName,
            String kind,
            String valueType,
            String sheetId,
            String sheetName,
            String labelAddress,
            String address,
            double confidence,
            String confidenceLevel,
            String source,
            String reasonCode,
            String reasonDetail,
            String status,
            String conflictReason,
            JsonNode payload
    ) {
    }

    public record RecognitionAction(UUID recognitionItemId, String action, UUID selectedSuggestionId) {
        public RecognitionAction(UUID recognitionItemId, String action) {
            this(recognitionItemId, action, null);
        }
    }

    public record QualityAction(UUID issueId, String action) {
    }

    public record QualityIssueItem(
            UUID id,
            String issueType,
            String severity,
            double confidence,
            String sheetId,
            String sheetName,
            String address,
            String title,
            String description,
            String businessImpact,
            boolean autoFixable,
            String status,
            JsonNode suggestedPatch,
            JsonNode inversePatch,
            JsonNode evidence
    ) {
    }
}
