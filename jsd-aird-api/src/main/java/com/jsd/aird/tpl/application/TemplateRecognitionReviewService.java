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
import java.util.stream.StreamSupport;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        var workspace = requireWorkspace(actor.organizationId(), versionId);
        if (workspace.status() != TemplateStatus.DRAFT) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "只有草稿模板可以重新识别");
        }
        if (workspace.format() == TemplateFormat.DOCX) {
            if (!"WORKBOOK".equals(scope)) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word 只能重新识别整份文档");
            }
            var sourceText = workspace.wordDocument().path("sourceDocxFileId").asText(
                    workspace.wordDocument().path("workingDocxFileId").asText(""));
            var sourceFileId = sourceText.isBlank()
                    ? importRepository.findOriginalSourceFileId(actor.organizationId(), versionId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "当前草稿没有可用的 Word 原文件"))
                    : UUID.fromString(sourceText);
            fileRepository.find(actor.organizationId(), sourceFileId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
            var importJobId = UUID.randomUUID();
            importRepository.enqueue(new TemplateImportRepository.NewImportJob(
                    importJobId, UUID.randomUUID(), actor.organizationId(), sourceFileId,
                    TemplateFormat.DOCX, actor.userId(), "OFFICE_FILE"));
            importRepository.linkGeneratedVersion(actor.organizationId(), importJobId, versionId);
            templateRepository.appendAudit(
                    actor.organizationId(), actor.userId(), "TEMPLATE_RECOGNITION_RESTARTED",
                    "TEMPLATE_VERSION", versionId,
                    objectMapper.createObjectNode().put("importJobId", importJobId.toString())
                            .put("scope", "WORKBOOK").put("format", "DOCX"));
            return importRepository.find(actor.organizationId(), importJobId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "识别任务创建失败"));
        }
        if ("REGION".equals(scope) && (sheetId == null || sheetId.isBlank()
                || address == null || !address.toUpperCase(Locale.ROOT).matches(
                "^[A-Z]{1,4}[1-9][0-9]*(?::[A-Z]{1,4}[1-9][0-9]*)?$"))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请先在 Excel 中选择需要重新识别的连续区域");
        }
        if (workspace.format() != TemplateFormat.XLSX) {
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
        var currentImport = importRepository.findLatestForVersion(organizationId, workspace.versionId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "识别运行不存在"));
        var review = assemble(organizationId, workspace);
        var byId = new java.util.HashMap<UUID, RecognitionReviewItem>();
        review.items().forEach(item -> item.suggestionIds().forEach(id -> byId.put(id, item)));
        // listSuggestions is scoped by importJobId and internally selects that
        // job's latest recognition run. Passing recognitionRunId here returns
        // an empty candidate set and makes every valid alternative look stale.
        var suggestions = importRepository.listSuggestions(organizationId, currentImport.id());
        var suggestionsById = new LinkedHashMap<UUID, TemplateImportRepository.RecognitionSuggestionView>();
        suggestions.forEach(suggestion -> suggestionsById.put(suggestion.id(), suggestion));
        var recompiledStructures = new LinkedHashSet<UUID>();
        var selectedByAction = new LinkedHashMap<UUID, List<TemplateImportRepository.RecognitionSuggestionView>>();
        for (RecognitionAction action : actions) {
            if (!"CONFIRM".equals(action.action())) continue;
            var item = byId.get(action.recognitionItemId());
            if (item == null) continue;
            var selected = selectedCandidates(action, item, suggestionsById);
            selectedByAction.put(action.recognitionItemId(), selected);
            var explicitStructureRecognition = action.selectedAlternativeId() != null
                    && !action.selectedAlternativeId().isBlank();
            if (!selected.isEmpty() && selected.stream().allMatch(this::isStructuralCandidate)
                    && (explicitStructureRecognition
                    || selected.stream().anyMatch(candidate -> requiresStructureRecompile(candidate.payload())))) {
                recompileSelectedStructures(organizationId, workspace, selected);
                selected.forEach(candidate -> recompiledStructures.add(candidate.id()));
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
            var selectedCandidates = selectedByAction.getOrDefault(action.recognitionItemId(), List.of());
            var selectedSuggestionIds = selectedCandidates.stream()
                    .map(TemplateImportRepository.RecognitionSuggestionView::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var structuralItem = isStructuralCandidate(item);
            if ("ACCEPTED".equals(decision) && structuralItem
                    && resolutionGroupId.isBlank()
                    && !RecognitionCandidatePolicy.isFormallyConfirmable(item.payload())
                    && selectedSuggestionIds.stream().noneMatch(recompiledStructures::contains)) {
                throw new ApiException(ApiErrorCode.BINDING_INVALID,
                        "结构候选尚未完成区域语义识别，不能确认");
            }
            if ("ACCEPTED".equals(decision) && structuralItem
                    && !resolutionGroupId.isBlank() && selectedSuggestionIds.isEmpty()) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "请选择当前冲突组中的结构方案");
            }
            for (UUID suggestionId : item.suggestionIds()) {
                var itemDecision = decision;
                if ("ACCEPTED".equals(decision) && structuralItem && !resolutionGroupId.isBlank()
                        && !selectedSuggestionIds.contains(suggestionId)) {
                    // Alternatives are mutually exclusive, but one alternative
                    // may contain multiple complementary structure regions.
                    itemDecision = "REJECTED_BY_RESOLUTION";
                }
                importRepository.decideSuggestion(
                        organizationId, review.recognitionRunId(), suggestionId, itemDecision, actorId
                ).orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST, "识别项目已变化，请刷新后重试"));
            }
            if ("ACCEPTED".equals(decision)) {
                selectedSuggestionIds.stream().filter(recompiledStructures::contains).forEach(selectedSuggestionId ->
                        importRepository.markStructureResolved(
                                organizationId, review.recognitionRunId(), selectedSuggestionId));
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
                isStructuralCandidate(item)
                        && item.payload().path("resolutionGroupId").asText("").length() > 0
                        && !Set.of("ACCEPTED", "CONFIRMED").contains(item.status())
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
        refreshRecognitionCoverage(result, review.regions());
        importRepository.saveImportResult(run.id(), result);
    }

    private void refreshRecognitionCoverage(ObjectNode result, JsonNode reviewRegions) {
        if (!result.path("recognitionCoverage").isObject()
                || !result.path("recognitionCoverage").path("regions").isArray()) return;
        var coverage = (ObjectNode) result.path("recognitionCoverage");
        var covered = 0;
        var unresolved = 0;
        var issues = objectMapper.createArrayNode();
        for (var detail : coverage.withArray("regions")) {
            var reviewRegion = findReviewRegion(reviewRegions, detail);
            var complete = reviewRegion != null
                    && "CONFIRMED".equals(reviewRegion.path("canonicalStatus").asText(""))
                    && "CONFIRMED".equals(reviewRegion.path("structureStatus").asText(""))
                    && hasRegionSemanticContent(reviewRegion);
            if (complete) {
                covered++;
                if (detail instanceof ObjectNode object) {
                    object.put("status", "COVERED")
                            .put("structureStatus", "CONFIRMED")
                            .put("structureConflict", false)
                            .put("semanticSuggestionPresent", true);
                    if (!reviewRegion.path("resolutionGroupId").asText("").isBlank()) {
                        object.put("modelAssessmentVerdict", "HUMAN_RESOLVED");
                    }
                }
            } else {
                unresolved++;
                issues.add("区域 " + detail.path("range").asText("") + " 尚未完成结构确认和字段识别");
            }
        }
        var expected = coverage.withArray("regions").size();
        coverage.put("status", unresolved == 0 ? "COMPLETE" : "REVIEW_REQUIRED")
                .put("expectedRegionCount", expected)
                .put("coveredRegionCount", covered)
                .put("unresolvedRegionCount", unresolved)
                .put("coverageRatio", expected == 0 ? 1.0 : covered / (double) expected)
                .set("issues", issues);
    }

    private JsonNode findReviewRegion(JsonNode reviewRegions, JsonNode detail) {
        if (reviewRegions == null || !reviewRegions.isArray()) return null;
        var expectedSheet = detail.path("sheetId").asText("");
        var expectedRange = RecognitionIdentity.normalizeRange(detail.path("range").asText(""));
        var expectedType = detail.path("type").asText(detail.path("kind").asText(""));
        for (var region : reviewRegions) {
            if (expectedSheet.equals(region.path("sheetId").asText(""))
                    && expectedRange.equals(RecognitionIdentity.normalizeRange(region.path("range").asText("")))
                    && expectedType.equals(region.path("kind").asText(""))) return region;
        }
        return null;
    }

    private boolean hasRegionSemanticContent(JsonNode region) {
        return !region.path("fields").isEmpty()
                || !region.path("runtimeSlots").isEmpty()
                || !region.path("recordSlots").isEmpty()
                || !region.path("structures").isEmpty();
    }

    private boolean isStructuralCandidate(JsonNode payload) {
        // Semantic children inherit the parent's table kind so they can be
        // attached back to the exact region.  That inherited kind must never
        // promote a TABLE_CHILD_FIELD/MATRIX_FIELD into another region root.
        if ("CHILD".equals(payload.path("suggestionLevel").asText(""))
                || "REPEAT_FIELD".equals(payload.path("mappingKind").asText(""))
                || "MATRIX_FIELD".equals(payload.path("mappingKind").asText(""))) {
            return false;
        }
        var type = payload.path("kind").asText(payload.path("tableKind").asText(
                payload.path("blockType").asText("")));
        // A FORM_REGION with label/value coordinates is compiled into a
        // scalar field, so blockType alone must not turn every form field into
        // a region root.  The uncompiled root carries the FORM_REGION kind or
        // a repeat-region role with an object/array value.
        if ("SCALAR".equals(type)
                && "FORM_REGION".equals(payload.path("blockType").asText())
                && "REPEAT_REGION".equals(payload.path("role").asText())
                && Set.of("object", "array").contains(payload.path("valueType").asText(""))) {
            type = "FORM_REGION";
        }
        return Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE", "FORM_REGION", "TABLE_REGION").contains(type);
    }

    private boolean isStructuralCandidate(RecognitionReviewItem item) {
        return !item.child() && isStructuralCandidate(item.payload());
    }

    private boolean requiresStructureRecompile(JsonNode payload) {
        return payload.path("candidateOnly").asBoolean(false)
                || payload.path("physicalStructureOnly").asBoolean(false)
                || payload.path("structureConflict").asBoolean(false)
                || !"CONFIRMED".equals(payload.path("canonicalStatus").asText("PROVISIONAL"))
                || !"CONFIRMED".equals(payload.path("structureStatus").asText("PROVISIONAL"));
    }

    private List<TemplateImportRepository.RecognitionSuggestionView> selectedCandidates(
            RecognitionAction action,
            RecognitionReviewItem item,
            Map<UUID, TemplateImportRepository.RecognitionSuggestionView> suggestionsById
    ) {
        var candidates = item.suggestionIds().stream().map(suggestionsById::get)
                .filter(java.util.Objects::nonNull).toList();
        if (action.selectedAlternativeId() != null && !action.selectedAlternativeId().isBlank()) {
            var selected = candidates.stream().filter(candidate -> action.selectedAlternativeId().equals(
                    alternativeKey(candidate))).toList();
            if (selected.isEmpty()) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "所选结构方案不属于当前冲突组");
            }
            return selected;
        }
        if (action.selectedSuggestionId() != null) {
            var selected = suggestionsById.get(action.selectedSuggestionId());
            if (selected == null || !item.suggestionIds().contains(selected.id())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "所选结构候选不属于当前冲突组");
            }
            var alternativeId = alternativeKey(selected);
            if (!alternativeId.isBlank()) {
                var members = candidates.stream().filter(candidate -> alternativeId.equals(
                        alternativeKey(candidate))).toList();
                if (members.size() > 1) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "该结构方案包含多个区域，请刷新页面后按方案确认");
                }
            }
            return List.of(selected);
        }
        if (isStructuralCandidate(item)
                && !item.payload().path("resolutionGroupId").asText("").isBlank()
                && candidates.size() > 1) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请选择要采用的结构方案");
        }
        var selected = suggestionsById.get(action.recognitionItemId());
        return selected == null ? List.of() : List.of(selected);
    }

    /**
     * A structure alternative is only accepted after a fresh semantic call has
     * succeeded for that exact geometry.  The old candidate remains in the
     * database for audit; the new semantic suggestions are appended as pending
     * review items, so selecting a geometry can never silently publish fields.
     */
    private void recompileSelectedStructures(
            UUID organizationId,
            TemplateRepository.TemplateWorkspace workspace,
            List<TemplateImportRepository.RecognitionSuggestionView> selected
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
        var regions = selected.stream().map(candidate -> semanticRegionForReview(candidate, facts)).toList();
        validateSelectedRegionSet(regions);
        var first = regions.getFirst();
        var context = semanticViewBuilder.build(facts, regions.size() == 1 ? "REGION" : "WORKBOOK",
                regions.size() == 1 ? first.path("sheetId").asText() : null,
                regions.size() == 1 ? first.path("range").asText() : null);
        var semanticRegions = context.putArray("semanticRegions");
        regions.forEach(semanticRegions::add);
        var targetId = selected.getFirst().payload().path("resolutionGroupId").asText(
                selected.getFirst().payload().path("resolutionAlternativeId").asText(
                        selected.getFirst().id().toString()));
        RecognitionModelClient.RecognitionBatch batch;
        try {
            batch = recognitionModelClient.recognize(new RecognitionModelClient.RecognitionRequest(
                    run.id(), run.recognitionRunId(), workspace.format(), run.sourceFileName(), targetId,
                    context, null, "REGION_FIELDS"));
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID,
                    "所选结构的语义重新识别失败，请保留原结构候选后重试："
                            + (exception.getMessage() == null ? "协议错误" : exception.getMessage()));
        }
        for (var trace : batch.callTraces()) importRepository.saveRecognitionCall(run.recognitionRunId(), trace);
        var regionIds = regions.stream().map(region -> region.path("regionId").asText())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var sanitized = sanitizeRecompiledBatch(batch, regionIds);
        var missing = regions.stream().filter(region -> !hasValidSemanticResult(region, sanitized.suggestions()))
                .map(region -> region.path("regionId").asText()).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID,
                    "所选结构有区域未生成有效语义结果，不能确认：" + String.join("、", missing));
        }
        importRepository.appendModelSuggestions(run.id(), run.recognitionRunId(), sanitized);
    }

    private RecognitionModelClient.RecognitionBatch sanitizeRecompiledBatch(
            RecognitionModelClient.RecognitionBatch batch, Set<String> regionIds
    ) {
        var suggestions = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        for (var suggestion : batch.suggestions()) {
            if ("SEMANTIC_MODEL".equals(suggestion.suggestionType())
                    || !(suggestion.payload().deepCopy() instanceof ObjectNode payload)) continue;
            // REGION_FIELDS compiles the semantic region into a stable blockId.
            // candidateRef preserves the exact selected structure identity and
            // therefore takes precedence when binding the result back to the
            // review alternative.
            var regionId = payload.path("candidateRef").asText("");
            if (regionId.isBlank()) regionId = semanticRegionId(payload);
            if (regionId.isBlank() || !regionIds.contains(regionId)) continue;
            payload.remove("resolutionGroupId");
            payload.remove("resolutionAlternativeId");
            payload.put("canonicalStatus", "CONFIRMED")
                    .put("structureStatus", "CONFIRMED")
                    .put("candidateOnly", false)
                    .put("physicalStructureOnly", false)
                    .put("structureConflict", false)
                    .put("reviewRequired", true)
                    .put("pendingReason", "SEMANTIC_RECOGNITION_REQUIRED")
                    .put("semanticRecompileRegionId", regionId);
            suggestions.add(new RecognitionModelClient.ModelSuggestion(
                    suggestion.suggestionType(), payload, suggestion.confidence(), suggestion.evidence()));
        }
        return new RecognitionModelClient.RecognitionBatch(
                suggestions, batch.qualityIssues(), batch.provider(), batch.model(), batch.promptVersion(),
                batch.requestHash(), batch.responseHash(), batch.callTrace(), batch.callTraces());
    }

    private boolean hasValidSemanticResult(
            JsonNode region, List<RecognitionModelClient.ModelSuggestion> suggestions
    ) {
        var regionId = region.path("regionId").asText("");
        var type = region.path("type").asText("");
        if ("FORM_REGION".equals(type)) {
            var actual = 0;
            var expected = 0;
            for (var suggestion : suggestions) {
                if (!regionId.equals(suggestion.payload().path("semanticRecompileRegionId").asText(""))) continue;
                expected = Math.max(expected, suggestion.payload().path("formExpectedFieldCount").asInt(0));
                if ("SCALAR_FIELD".equals(suggestion.suggestionType())
                        && "FIELD".equals(suggestion.payload().path("role").asText(""))
                        && !suggestion.payload().path("fieldName").asText("").strip().isBlank()) actual++;
            }
            return expected > 0 && actual >= expected;
        }
        for (var suggestion : suggestions) {
            if (!regionId.equals(suggestion.payload().path("semanticRecompileRegionId").asText(""))) continue;
            if (Set.of("ROW_TABLE", "COLUMN_TABLE").contains(type)
                    && "TABLE_CHILD_FIELD".equals(suggestion.suggestionType())
                    && !suggestion.payload().path("fieldName").asText("").strip().isBlank()) return true;
            if ("MATRIX".equals(type)
                    && ("MATRIX_FIELD".equals(suggestion.suggestionType())
                    || ("MATRIX".equals(suggestion.payload().path("kind").asText(""))
                    && (suggestion.payload().path("matrixModel").isObject()
                    || suggestion.payload().path("runtimeSlots").isArray())))) return true;
        }
        return false;
    }

    private ObjectNode semanticRegionForReview(
            TemplateImportRepository.RecognitionSuggestionView selected,
            JsonNode physicalFacts
    ) {
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
        var regionId = payload.path("candidateRef").asText(payload.path("regionId").asText(
                payload.path("blockId").asText(selected.id().toString())));
        var region = objectMapper.createObjectNode()
                .put("regionId", regionId).put("blockId", regionId)
                .put("candidateRef", regionId)
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
        if ("COLUMN_TABLE".equals(type)) enrichColumnProjection(region, geometry, physicalFacts);
        return region;
    }

    /**
     * A model alternative often knows that a block is a COLUMN_TABLE but does
     * not carry its runtime record projection. Derive that projection from the
     * workbook facts: the non-empty merged label band at the first row ends
     * immediately before the aligned runtime record surface.
     */
    private void enrichColumnProjection(ObjectNode region, ObjectNode geometry, JsonNode physicalFacts) {
        var total = rangeBounds(region.path("range").asText(""));
        if (total == null) return;
        var sheetId = region.path("sheetId").asText("");
        int labelEnd = 0;
        for (var sheet : physicalFacts.path("sheets")) {
            if (!sheetId.equals(sheet.path("id").asText(""))) continue;
            for (var cell : sheet.path("semanticCells")) {
                var address = cell.path("mergedRange").asText(cell.path("address").asText(""));
                var bounds = rangeBounds(address);
                if (bounds == null || bounds[1] != total[1] || bounds[0] != total[0]
                        || bounds[2] >= total[2] || cell.path("value").asText("").strip().isBlank()) continue;
                labelEnd = Math.max(labelEnd, bounds[2]);
            }
        }
        if (labelEnd < total[0] || labelEnd >= total[2]) return;
        int recordStart = labelEnd + 1;
        geometry.put("recordAxis", "COLUMN")
                .put("headerRange", excelRange(total[0], total[1], total[2], total[1]))
                .put("dataRange", excelRange(total[0], total[1] + 1, total[2], total[3]))
                .put("rowHeaderRange", excelRange(total[0], total[1] + 1, labelEnd, total[3]))
                .put("columnHeaderRange", excelRange(recordStart, total[1], total[2], total[1]))
                .put("crossDataRange", excelRange(recordStart, total[1] + 1, total[2], total[3]));
        var projection = geometry.putObject("recordProjection")
                .put("mode", "COLUMN_RECORDS").put("recordAxis", "COLUMN")
                .put("labelBandRange", excelRange(total[0], total[1], labelEnd, total[3]))
                .put("runtimeColumnMemberRange", excelRange(recordStart, total[1], total[2], total[1]));
        var columns = projection.putArray("recordColumns");
        for (int column = recordStart; column <= total[2]; column++) columns.add(columnName(column));
    }

    private String excelRange(int left, int top, int right, int bottom) {
        return columnName(left) + top + ":" + columnName(right) + bottom;
    }

    private String columnName(int column) {
        var value = new StringBuilder();
        for (int current = column; current > 0; current = (current - 1) / 26) {
            value.append((char) ('A' + (current - 1) % 26));
        }
        return value.reverse().toString();
    }

    private String semanticRegionId(JsonNode payload) {
        for (var key : List.of("regionId", "blockId", "candidateRef")) {
            var value = payload.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private void validateSelectedRegionSet(List<ObjectNode> regions) {
        for (int left = 0; left < regions.size(); left++) {
            var first = regions.get(left);
            if (rangeBounds(first.path("range").asText()) == null) {
                throw new ApiException(ApiErrorCode.BINDING_INVALID, "所选结构包含无效区域：" + first.path("range").asText());
            }
            for (int right = left + 1; right < regions.size(); right++) {
                var second = regions.get(right);
                if (first.path("sheetId").asText().equals(second.path("sheetId").asText())
                        && rangesOverlap(first.path("range").asText(), second.path("range").asText())) {
                    throw new ApiException(ApiErrorCode.BINDING_INVALID, "所选结构方案中的区域相互重叠，不能确认");
                }
            }
        }
    }

    private boolean rangesOverlap(String first, String second) {
        var a = rangeBounds(first);
        var b = rangeBounds(second);
        return a != null && b != null && a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }

    private int[] rangeBounds(String value) {
        var parts = value == null ? new String[0] : value.replace("$", "").toUpperCase(Locale.ROOT).split(":", 2);
        if (parts.length == 0 || parts[0].isBlank()) return null;
        var first = cellCoordinate(parts[0]);
        var last = cellCoordinate(parts.length == 1 ? parts[0] : parts[1]);
        if (first == null || last == null) return null;
        return new int[]{Math.min(first[0], last[0]), Math.min(first[1], last[1]),
                Math.max(first[0], last[0]), Math.max(first[1], last[1])};
    }

    private int[] cellCoordinate(String value) {
        var matcher = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$").matcher(value);
        if (!matcher.matches()) return null;
        var column = 0;
        for (var character : matcher.group(1).toCharArray()) column = column * 26 + character - 'A' + 1;
        return new int[]{column, Integer.parseInt(matcher.group(2))};
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
                    "NO_PHYSICAL_TABLE", objectMapper.createObjectNode(), objectMapper.createArrayNode(),
                    new RecognitionReviewStatistics(0, 0, 0, 0, 0, 0, 0));
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
            var primary = candidates.stream().filter(candidate -> "ACCEPTED".equals(candidate.decision()))
                    .findFirst().orElse(candidates.getFirst());
            var semanticConflict = candidates.stream()
                    .filter(candidate -> !isResolutionRejected(candidate))
                    .anyMatch(candidate -> candidate.payload().path("semanticConflict").asBoolean(false)
                            && !"ACCEPTED".equals(candidate.decision()));
            var resolutionGroupId = primary.payload().path("resolutionGroupId").asText("");
            var activeCandidates = candidates.stream()
                    .filter(candidate -> !isResolutionRejected(candidate))
                    .filter(candidate -> !isAuditOnly(candidate.payload()))
                    .toList();
            var structuralCandidates = activeCandidates.stream().filter(this::isStructuralCandidate).toList();
            var structureConflict = !resolutionGroupId.isBlank() && !structuralCandidates.isEmpty()
                    ? structuralCandidates.stream().map(candidate -> candidate.payload().path("resolutionAlternativeId")
                            .asText(candidate.id().toString())).distinct().count() > 1
                    : structuralCandidates.stream().skip(1)
                            .anyMatch(candidate -> !structuralSignature(structuralCandidates.getFirst())
                                    .equals(structuralSignature(candidate)));
            var conflict = semanticConflict || structureConflict;
            var allRejected = candidates.stream().allMatch(this::isRejected);
            var anyAccepted = candidates.stream().anyMatch(candidate -> "ACCEPTED".equals(candidate.decision()));
            // 语义冲突优先于“已有一个候选被接受”：比例/理论投料量等冲突必须
            // 在整个候选组确认后才算正式字段，不能因为单个候选已接受而绕过审核。
            var status = allRejected ? "IGNORED" : conflict ? "CONFLICT" : anyAccepted ? "CONFIRMED" : "PENDING";
            var payload = primary.payload().deepCopy();
            if (payload instanceof ObjectNode payloadObject
                    && payloadObject.path("resolutionGroupId").isTextual()
                    && !payloadObject.path("resolutionGroupId").asText().isBlank()
                    && structuralCandidates.size() > 1) {
                var alternatives = payloadObject.putArray("structureAlternatives");
                var alternativesById = new LinkedHashMap<String, ObjectNode>();
                for (var candidate : structuralCandidates) {
                    var alternativeId = alternativeKey(candidate);
                    var alternative = alternativesById.get(alternativeId);
                    if (alternative == null) {
                        alternative = objectMapper.createObjectNode()
                                .put("alternativeId", alternativeId)
                                .put("source", candidate.payload().path("alternativeRole")
                                        .asText(candidate.source()));
                        alternative.putArray("regions");
                        copyGeometryDetails(alternative, candidate.payload());
                        alternativesById.put(alternativeId, alternative);
                    }
                    var region = objectMapper.createObjectNode()
                            .put("suggestionId", candidate.id().toString())
                            .put("source", candidate.source())
                            .put("kind", kind(candidate))
                            .put("range", locatorAddress(candidate.payload().path("locator")))
                            .put("decision", candidate.decision());
                    copyGeometryDetails(region, candidate.payload());
                    if (!containsStructuralRegion(alternative.withArray("regions"), region)) {
                        alternative.withArray("regions").add(region);
                    }
                }
                alternativesById.values().forEach(alternative -> {
                    if (alternative.withArray("regions").size() == 1) {
                        alternative.put("suggestionId",
                                alternative.path("regions").get(0).path("suggestionId").asText());
                    }
                    alternatives.add(alternative);
                });
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
        var regionTree = buildRegionTree(suggestions, items, semanticModel);
        var reviewStatistics = buildReviewStatistics(suggestions, regionTree, active);
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
                        : run.result().path("recognitionCoverage").deepCopy(),
                regionTree,
                reviewStatistics
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
        // Resolution groups contain structure roots only. Child fields may carry
        // the parent group for audit, but must remain independently reviewable.
        if (!resolutionGroupId.isBlank() && isStructuralCandidate(suggestion)) {
            return "resolution:" + resolutionGroupId;
        }
        if (!isStructuralCandidate(suggestion)) {
            var fieldIdentity = payload.path("relationId").asText(payload.path("fieldId").asText(""));
            var regionIdentity = payload.path("regionId").asText(payload.path("blockId").asText(""));
            if (!fieldIdentity.isBlank() || !regionIdentity.isBlank()) {
                return "field:" + regionIdentity + "|" + fieldIdentity;
            }
        }
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
                locatorAddress(locator), geometrySignature(payload)
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
        if ("SCALAR".equals(explicit)
                && "FORM_REGION".equals(suggestion.payload().path("blockType").asText())
                && "REPEAT_REGION".equals(suggestion.payload().path("role").asText())
                && Set.of("object", "array").contains(suggestion.payload().path("valueType").asText(""))) {
            return "FORM_REGION";
        }
        if (!explicit.isBlank()) return explicit;
        if (suggestion.suggestionType().contains("MATRIX")) return "MATRIX";
        if (suggestion.suggestionType().contains("TABLE")
                || "REPEAT_REGION".equals(suggestion.payload().path("role").asText())) return "ROW_TABLE";
        return "SCALAR";
    }

    private ArrayNode buildRegionTree(
            List<TemplateImportRepository.RecognitionSuggestionView> suggestions,
            List<RecognitionReviewItem> items,
            JsonNode semanticModel
    ) {
        var tree = objectMapper.createArrayNode();
        var itemBySuggestionId = new LinkedHashMap<UUID, RecognitionReviewItem>();
        for (var item : items) {
            for (var suggestionId : item.suggestionIds()) itemBySuggestionId.put(suggestionId, item);
        }
        var roots = suggestions.stream()
                .filter(suggestion -> !"SEMANTIC_MODEL".equals(suggestion.suggestionType()))
                .filter(suggestion -> !isProtocolRejected(suggestion.payload()))
                .filter(suggestion -> !isResolutionRejected(suggestion))
                .filter(suggestion -> !isAuditOnly(suggestion.payload()))
                .filter(this::isStructuralCandidate)
                .toList();
        var rootsByResolutionGroup = new LinkedHashMap<String, List<TemplateImportRepository.RecognitionSuggestionView>>();
        for (var root : roots) {
            var resolutionGroup = root.payload().path("resolutionGroupId").asText("");
            if (!resolutionGroup.isBlank()) {
                rootsByResolutionGroup.computeIfAbsent(resolutionGroup, ignored -> new ArrayList<>()).add(root);
            }
        }
        var groupedRoots = new LinkedHashMap<String, List<TemplateImportRepository.RecognitionSuggestionView>>();
        for (var root : roots) {
            var resolutionGroup = root.payload().path("resolutionGroupId").asText("");
            var groupMembers = rootsByResolutionGroup.getOrDefault(resolutionGroup, List.of());
            var key = !resolutionGroup.isBlank() && isSingleRegionChoiceGroup(groupMembers)
                    ? "conflict:" + resolutionGroup
                    : regionTreeGroupKey(root);
            groupedRoots.computeIfAbsent(key, ignored -> new ArrayList<>()).add(root);
        }
        var attachedFieldIds = new LinkedHashSet<UUID>();
        for (var rootGroup : groupedRoots.values()) {
            var resolutionGroups = rootGroup.stream()
                    .map(root -> root.payload().path("resolutionGroupId").asText(""))
                    .filter(group -> !group.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var alternativeRoots = new ArrayList<TemplateImportRepository.RecognitionSuggestionView>();
            resolutionGroups.forEach(group -> alternativeRoots.addAll(rootsByResolutionGroup.getOrDefault(group, List.of())));
            if (alternativeRoots.isEmpty()) alternativeRoots.addAll(rootGroup);
            var primary = rootGroup.stream()
                    .filter(root -> "ACCEPTED".equals(root.decision()))
                    .findFirst()
                    .orElse(rootGroup.stream()
                            .filter(this::isPhysicalAlternative)
                            .max(Comparator.comparingDouble(
                                    TemplateImportRepository.RecognitionSuggestionView::confidence))
                            .orElse(rootGroup.stream().max(Comparator.comparingDouble(
                                    TemplateImportRepository.RecognitionSuggestionView::confidence))
                                    .orElse(rootGroup.getFirst())));
            var payload = primary.payload();
            var structureConfirmed = "CONFIRMED".equals(payload.path("canonicalStatus").asText(""))
                    && "CONFIRMED".equals(payload.path("structureStatus").asText(""));
            var itemStatus = itemBySuggestionId.containsKey(primary.id())
                    ? itemBySuggestionId.get(primary.id()).status() : "PENDING";
            var node = objectMapper.createObjectNode()
                    .put("regionId", firstText(payload, "regionId", "blockId", "candidateRef", primary.id().toString()))
                    .put("blockId", firstText(payload, "blockId", "regionId", "candidateRef", ""))
                    .put("kind", kind(primary))
                    .put("sheetId", payload.path("locator").path("sheetId").asText(""))
                    .put("sheetName", payload.path("locator").path("sheetName").asText(""))
                    .put("range", locatorAddress(payload.path("locator")))
                    .put("fieldName", payload.path("fieldName").asText(payload.path("blockName").asText("待确认区域")))
                    .put("status", structureConfirmed ? "CONFIRMED" : itemStatus)
                    .put("canonicalStatus", payload.path("canonicalStatus").asText("PROVISIONAL"))
                    .put("structureStatus", payload.path("structureStatus").asText("PROVISIONAL"))
                    .put("resolutionGroupId", payload.path("resolutionGroupId").asText(""))
                    .put("reviewRequired", payload.path("reviewRequired").asBoolean(false));

            var alternatives = node.putArray("alternatives");
            var alternativesById = new LinkedHashMap<String, ObjectNode>();
            for (var candidate : alternativeRoots) {
                var candidatePayload = candidate.payload();
                var alternativeId = alternativeKey(candidate);
                var alternative = alternativesById.get(alternativeId);
                if (alternative == null) {
                    alternative = objectMapper.createObjectNode()
                            .put("alternativeId", alternativeId)
                            .put("source", candidatePayload.path("alternativeRole").asText(candidate.source()));
                    alternative.putArray("regions");
                    copyGeometryDetails(alternative, candidatePayload);
                    alternativesById.put(alternativeId, alternative);
                }
                var region = objectMapper.createObjectNode()
                        .put("suggestionId", candidate.id().toString())
                        .put("source", candidate.source())
                        .put("kind", kind(candidate))
                        .put("range", locatorAddress(candidatePayload.path("locator")))
                        .put("decision", candidate.decision());
                copyGeometryDetails(region, candidatePayload);
                if (!containsStructuralRegion(alternative.withArray("regions"), region)) {
                    alternative.withArray("regions").add(region);
                }
            }
            alternativesById.values().forEach(alternatives::add);

            attachRegionStructures(node, payload);
            var fields = node.putArray("fields");
            var audit = node.putArray("auditSuggestions");
            for (var suggestion : suggestions) {
                if ("SEMANTIC_MODEL".equals(suggestion.suggestionType())) continue;
                if (!belongsToRegion(suggestion, rootGroup)) continue;
                mergeRegionStructures(node, suggestion.payload());
                var item = itemBySuggestionId.get(suggestion.id());
                if (item == null) continue;
                if (isStructuralCandidate(suggestion)
                        && !isAuditOnly(suggestion.payload())
                        && !isResolutionRejected(suggestion)) continue;
                if (isAuditOnly(suggestion.payload()) || isResolutionRejected(suggestion)) {
                    audit.add(objectMapper.valueToTree(item));
                    continue;
                }
                if (item.fieldName() == null || item.fieldName().isBlank()
                        || item.payload().path("runtimeInputOnly").asBoolean(false)) {
                    audit.add(objectMapper.valueToTree(item));
                    continue;
                }
                var field = (ObjectNode) objectMapper.valueToTree(item);
                field.set("attributes", fieldAttributes(suggestion.payload()));
                fields.add(field);
                attachedFieldIds.add(suggestion.id());
            }
            sortRegionFields(node);
            applyRegionDisplayName(node);
            tree.add(node);
        }

        // REGION_FIELDS may legitimately compile a FORM_REGION directly into
        // scalar fields. Reconstruct its display-only region card from the
        // shared block identity so those fields never fall into “未分区字段”.
        var formFieldsByRegion = new LinkedHashMap<String, List<TemplateImportRepository.RecognitionSuggestionView>>();
        for (var suggestion : suggestions) {
            if (!"SCALAR_FIELD".equals(suggestion.suggestionType())
                    || !"FORM_REGION".equals(suggestion.payload().path("blockType").asText(""))) continue;
            var regionId = suggestion.payload().path("regionId").asText("");
            if (!regionId.isBlank() && !attachedFieldIds.contains(suggestion.id())) {
                formFieldsByRegion.computeIfAbsent(regionId, ignored -> new ArrayList<>()).add(suggestion);
            }
        }
        for (var entry : formFieldsByRegion.entrySet()) {
            var block = semanticModel == null ? null : findSemanticBlock(semanticModel, entry.getKey());
            var first = entry.getValue().getFirst();
            var node = objectMapper.createObjectNode()
                    .put("regionId", entry.getKey()).put("blockId", entry.getKey())
                    .put("kind", "FORM_REGION")
                    .put("sheetId", block == null ? first.payload().path("locator").path("sheetId").asText("")
                            : block.path("sheetId").asText(""))
                    .put("sheetName", first.payload().path("locator").path("sheetName").asText(""))
                    .put("range", block == null ? first.payload().path("regionRange").asText("")
                            : block.path("range").asText(first.payload().path("regionRange").asText("")))
                    .put("fieldName", block == null ? first.payload().path("blockName").asText("基本信息区域")
                            : block.path("businessName").asText("基本信息区域"))
                    .put("status", "CONFIRMED").put("canonicalStatus", "CONFIRMED")
                    .put("structureStatus", "CONFIRMED").put("reviewRequired", true);
            node.putArray("alternatives");
            var fields = node.putArray("fields");
            for (var suggestion : entry.getValue()) {
                var item = itemBySuggestionId.get(suggestion.id());
                if (item == null || item.fieldName() == null || item.fieldName().isBlank()) continue;
                var field = (ObjectNode) objectMapper.valueToTree(item);
                field.set("attributes", fieldAttributes(suggestion.payload()));
                fields.add(field);
                attachedFieldIds.add(suggestion.id());
            }
            node.putArray("runtimeSlots"); node.putArray("recordSlots");
            node.set("staticContents", first.payload().path("regionStaticContents").isArray()
                    ? first.payload().path("regionStaticContents").deepCopy() : objectMapper.createArrayNode());
            node.putArray("auditSuggestions");
            sortRegionFields(node);
            applyRegionDisplayName(node);
            tree.add(node);
        }

        var unassigned = objectMapper.createArrayNode();
        for (var suggestion : suggestions) {
            if ("SEMANTIC_MODEL".equals(suggestion.suggestionType())
                    || isStructuralCandidate(suggestion)
                    || attachedFieldIds.contains(suggestion.id())
                    || isAuditOnly(suggestion.payload())) continue;
            var item = itemBySuggestionId.get(suggestion.id());
            if (item == null) continue;
            if (item.fieldName() == null || item.fieldName().isBlank()
                    || item.payload().path("runtimeInputOnly").asBoolean(false)) continue;
            var field = (ObjectNode) objectMapper.valueToTree(item);
            field.set("attributes", fieldAttributes(suggestion.payload()));
            unassigned.add(field);
        }
        if (unassigned.size() > 0) {
            var node = objectMapper.createObjectNode()
                    .put("regionId", "unassigned")
                    .put("blockId", "")
                    .put("kind", "UNASSIGNED")
                    .put("fieldName", "未分区字段")
                    .put("status", "PENDING")
                    .put("canonicalStatus", "PROVISIONAL")
                    .put("structureStatus", "UNRESOLVED");
            node.putArray("alternatives");
            node.set("fields", unassigned);
            node.putArray("runtimeSlots");
            node.putArray("recordSlots");
            node.putArray("staticContents");
            node.putArray("auditSuggestions");
            tree.add(node);
        }
        if (tree.isEmpty() && semanticModel != null && semanticModel.isObject()) {
            // Keep a visible placeholder for workbooks that only produced an
            // audit envelope; the envelope itself remains outside the field tree.
            var unresolved = objectMapper.createObjectNode()
                    .put("regionId", "unresolved")
                    .put("blockId", "")
                    .put("kind", "UNRESOLVED")
                    .put("fieldName", "待确认区域")
                    .put("status", "PENDING")
                    .put("canonicalStatus", "PROVISIONAL")
                    .put("structureStatus", "UNRESOLVED");
            unresolved.putArray("fields");
            unresolved.putArray("alternatives");
            unresolved.putArray("runtimeSlots");
            unresolved.putArray("recordSlots");
            unresolved.putArray("staticContents");
            unresolved.putArray("auditSuggestions");
            tree.add(unresolved);
        }
        return sortRegionTree(tree);
    }

    private ArrayNode sortRegionTree(ArrayNode tree) {
        var ordered = new ArrayList<JsonNode>();
        tree.forEach(ordered::add);
        ordered.sort(Comparator
                .comparing((JsonNode region) -> region.path("sheetId").asText("~"))
                .thenComparingInt(region -> {
                    var bounds = rangeBounds(region.path("range").asText(""));
                    return bounds == null ? Integer.MAX_VALUE : bounds[1];
                })
                .thenComparingInt(region -> {
                    var bounds = rangeBounds(region.path("range").asText(""));
                    return bounds == null ? Integer.MAX_VALUE : bounds[0];
                }));
        var result = objectMapper.createArrayNode();
        ordered.forEach(result::add);
        return result;
    }

    private JsonNode findSemanticBlock(JsonNode semanticModel, String blockId) {
        for (var block : semanticModel.path("businessBlocks")) {
            if (blockId.equals(block.path("blockId").asText(""))) return block;
        }
        return null;
    }

    private String regionTreeGroupKey(TemplateImportRepository.RecognitionSuggestionView suggestion) {
        var locator = suggestion.payload().path("locator");
        var sheet = locator.path("sheetId").asText(locator.path("sheetName").asText(""));
        var range = locatorAddress(locator);
        // Region cards are physical areas.  resolutionGroupId is only used to
        // populate their alternative sets; using it as the card key collapses
        // a model partition into one oversized “region”.
        return "region:" + sheet + "|" + range;
    }

    /**
     * A conflict between one physical region and one model region is one
     * logical review card even when their outer ranges differ by a row.  A
     * genuine partition keeps its member cards because selecting that
     * alternative creates several canonical regions.
     */
    private boolean isSingleRegionChoiceGroup(
            List<TemplateImportRepository.RecognitionSuggestionView> group
    ) {
        if (group.size() < 2) return false;
        var membersPerAlternative = new LinkedHashMap<String, Integer>();
        for (var candidate : group) {
            membersPerAlternative.merge(alternativeKey(candidate), 1, Integer::sum);
        }
        return membersPerAlternative.size() >= 2
                && membersPerAlternative.values().stream().allMatch(count -> count == 1);
    }

    private boolean isPhysicalAlternative(TemplateImportRepository.RecognitionSuggestionView candidate) {
        return "PHYSICAL".equalsIgnoreCase(candidate.payload().path("alternativeRole").asText(""))
                || "PHYSICAL".equalsIgnoreCase(candidate.source());
    }

    /**
     * A legacy run may contain several physical/model rows for the same
     * geometry without a resolutionAlternativeId.  Those rows are one choice,
     * not several choices.  Geometry is deliberately part of the key so two
     * candidates with the same outer range but different header/data regions
     * remain visibly different alternatives.
     */
    private String alternativeKey(TemplateImportRepository.RecognitionSuggestionView suggestion) {
        var explicit = suggestion.payload().path("resolutionAlternativeId").asText("");
        return explicit.isBlank() ? "signature:" + structuralSignature(suggestion) : explicit;
    }

    private boolean containsStructuralRegion(ArrayNode regions, JsonNode candidate) {
        for (var existing : regions) {
            if (existing.path("kind").asText("").equals(candidate.path("kind").asText(""))
                    && existing.path("range").asText("").equals(candidate.path("range").asText("") )
                    && geometrySignature(existing).equals(geometrySignature(candidate))) return true;
        }
        return false;
    }

    private String geometrySignature(JsonNode payload) {
        return String.join("|",
                payload.path("recordAxis").asText(""),
                payload.path("headerRange").asText(""),
                payload.path("dataRange").asText(""),
                payload.path("rowHeaderRange").asText(""),
                payload.path("columnHeaderRange").asText(""),
                payload.path("crossDataRange").asText(""),
                payload.path("structure").path("recordAxis").asText(""),
                payload.path("structure").path("headerRange").asText(""),
                payload.path("structure").path("dataRange").asText(""),
                payload.path("structure").path("rowHeaderRange").asText(""),
                payload.path("structure").path("columnHeaderRange").asText(""),
                payload.path("structure").path("crossDataRange").asText(""),
                payload.path("locator").path("recordAxis").asText(""),
                payload.path("locator").path("headerRange").asText(""),
                payload.path("locator").path("dataRange").asText(""),
                payload.path("locator").path("rowHeaderRange").asText(""),
                payload.path("locator").path("columnHeaderRange").asText(""),
                payload.path("locator").path("crossDataRange").asText(""));
    }

    private void copyGeometryDetails(ObjectNode target, JsonNode payload) {
        var source = payload.path("structure").isObject() ? payload.path("structure") : payload;
        for (var key : List.of("recordAxis", "headerRange", "dataRange", "totalRange", "rowHeaderRange",
                "columnHeaderRange", "crossDataRange", "cornerRange", "recordWidth",
                "recordHeight", "recordStride")) {
            if (source.has(key)) target.set(key, source.path(key).deepCopy());
            else if (payload.path("locator").has(key)) target.set(key, payload.path("locator").path(key).deepCopy());
        }
    }

    private boolean belongsToRegion(
            TemplateImportRepository.RecognitionSuggestionView field,
            List<TemplateImportRepository.RecognitionSuggestionView> roots
    ) {
        var fieldPayload = field.payload();
        var fieldRegion = firstText(fieldPayload, "regionId", "blockId", "candidateRef", "");
        var fieldParentRelation = fieldPayload.path("parentRelationId").asText("");
        var fieldParentBinding = fieldPayload.path("parentBindingId").asText("");
        var fieldParentBlock = fieldPayload.path("parentBlockId").asText("");
        var fieldParentSuggestion = fieldPayload.path("parentSuggestionId").asText("");
        for (var root : roots) {
            var rootPayload = root.payload();
            var rootRegion = firstText(rootPayload, "regionId", "blockId", "candidateRef", "");
            if (!fieldRegion.isBlank() && fieldRegion.equals(rootRegion)) return true;
            if (!fieldParentBlock.isBlank() && fieldParentBlock.equals(rootPayload.path("blockId").asText(""))) return true;
            if (!fieldParentSuggestion.isBlank() && fieldParentSuggestion.equals(root.id().toString())) return true;
            if (!fieldParentRelation.isBlank()
                    && fieldParentRelation.equals(rootPayload.path("relationId").asText(""))) return true;
            if (!fieldParentBinding.isBlank()
                    && fieldParentBinding.equals(rootPayload.path("bindingId").asText(""))) return true;
        }
        return false;
    }

    private boolean isAuditOnly(JsonNode payload) {
        return payload.path("suppressed").asBoolean(false)
                || Set.of("SUPERSEDED", "REJECTED").contains(payload.path("structureStatus").asText(""))
                || "PHYSICAL_STRUCTURE_SELECTED".equals(payload.path("pendingReason").asText(""));
    }

    private void attachRegionStructures(ObjectNode region, JsonNode payload) {
        region.set("structures", objectMapper.createObjectNode());
        region.putArray("runtimeSlots");
        region.putArray("recordSlots");
        region.putArray("staticContents");
        mergeRegionStructures(region, payload);
    }

    private void mergeRegionStructures(ObjectNode region, JsonNode payload) {
        var structures = region.path("structures") instanceof ObjectNode object
                ? object : region.putObject("structures");
        for (var key : List.of("matrixModel", "recordProjection", "longTableModel", "tableModel")) {
            if (payload.has(key) && !payload.path(key).isNull()) structures.set(key, payload.path(key).deepCopy());
        }
        var slots = region.withArray("runtimeSlots");
        var recordSlots = region.withArray("recordSlots");
        var staticContents = region.withArray("staticContents");
        for (var source : List.of(payload, payload.path("structure"), payload.path("recordProjection"),
                payload.path("matrixModel"), payload.path("tableModel"), payload.path("longTableModel"))) {
            if (source == null || source.isMissingNode()) continue;
            for (var key : List.of("columnSlots", "rowSlots")) {
                if (!source.path(key).isArray()) continue;
                for (var slot : source.path(key)) {
                    if (!containsJson(slots, slot)) slots.add(slot.deepCopy());
                }
            }
            if (source.path("recordSlots").isArray()) for (var slot : source.path("recordSlots")) {
                if (!containsJson(recordSlots, slot)) recordSlots.add(slot.deepCopy());
                if (!containsJson(slots, slot)) slots.add(slot.deepCopy());
            }
            for (var key : List.of("staticContents", "regionStaticContents")) {
                if (!source.path(key).isArray()) continue;
                for (var content : source.path(key)) {
                    if (!containsJson(staticContents, content)) staticContents.add(content.deepCopy());
                }
            }
        }
    }

    /**
     * Region titles are display metadata only. They are derived from the
     * already-compiled field roles and never participate in structure
     * recognition, canonical selection, or Mapping generation.
     */
    private void applyRegionDisplayName(ObjectNode region) {
        var kind = region.path("kind").asText("");
        var fields = region.withArray("fields");
        if (region.path("sheetName").asText("").isBlank()) {
            for (var field : fields) {
                var sheetName = field.path("sheetName").asText("");
                if (!sheetName.isBlank()) {
                    region.put("sheetName", sheetName);
                    break;
                }
            }
        }
        if ("ROW_TABLE".equals(kind) && !region.withArray("recordSlots").isEmpty()) {
            region.put("fieldName", "操作步骤").put("displayRole", "STEP_RECORDS");
            return;
        }
        if ("ROW_TABLE".equals(kind) || "COLUMN_TABLE".equals(kind)) {
            for (var field : fields) {
                if ("配方明细".equals(field.path("groupName").asText(""))) {
                    region.put("fieldName", "配方明细").put("displayRole", "REPEAT_DETAIL");
                    return;
                }
            }
            region.put("displayRole", "REPEAT_DETAIL");
            return;
        }
        if (!"FORM_REGION".equals(kind)) return;
        var hasPackaging = false;
        var hasSignoff = false;
        for (var field : fields) {
            var name = field.path("fieldName").asText("");
            hasPackaging |= name.contains("包装");
            hasSignoff |= name.contains("制单") || name.contains("完成")
                    || name.contains("监管") || name.contains("签字") || name.contains("签章");
        }
        if (hasPackaging && hasSignoff) {
            region.put("fieldName", "包装与签字").put("displayRole", "FORM_FOOTER");
            return;
        }
        var current = region.path("fieldName").asText("基本信息");
        region.put("fieldName", current.replaceFirst("(表单)?区域$", ""))
                .put("displayRole", "FORM_HEADER");
    }

    private void sortRegionFields(ObjectNode region) {
        var fields = region.withArray("fields");
        var ordered = new ArrayList<JsonNode>();
        fields.forEach(ordered::add);
        ordered.sort(Comparator
                .comparingInt((JsonNode field) -> fieldPosition(field)[1])
                .thenComparingInt(field -> fieldPosition(field)[0])
                .thenComparing(field -> field.path("fieldName").asText("")));
        fields.removeAll();
        ordered.forEach(fields::add);
    }

    private int[] fieldPosition(JsonNode field) {
        var address = field.path("labelAddress").asText("");
        if (address.isBlank()) address = field.path("payload").path("locator").path("labelRange").asText("");
        if (address.isBlank()) address = field.path("address").asText("");
        var bounds = rangeBounds(address);
        return bounds == null ? new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE} : bounds;
    }

    private boolean containsJson(ArrayNode array, JsonNode value) {
        for (var existing : array) if (existing.equals(value)) return true;
        return false;
    }

    private ObjectNode fieldAttributes(JsonNode payload) {
        var attributes = objectMapper.createObjectNode();
        for (var key : List.of("nameSource", "semanticFallback", "valueType", "editability",
                "valueSource", "unit", "condition", "mappingKind", "standardMatchStatus",
                "requiresStandardConfirmation", "reviewRequired", "candidateOnly", "pendingReason")) {
            if (payload.has(key)) attributes.set(key, payload.path(key).deepCopy());
        }
        attributes.set("locator", payload.path("locator").deepCopy());
        return attributes;
    }

    private String firstText(JsonNode node, String first, String second, String third, String fallback) {
        for (var key : List.of(first, second, third)) {
            var value = node.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return fallback;
    }

    private RecognitionReviewStatistics buildReviewStatistics(
            List<TemplateImportRepository.RecognitionSuggestionView> suggestions,
            ArrayNode regionTree,
            List<RecognitionReviewItem> activeItems
    ) {
        var structureAlternatives = 0;
        var conflicts = 0;
        var uniqueAlternatives = new LinkedHashSet<String>();
        var uniqueConflictGroups = new LinkedHashSet<String>();
        var runtimeSlots = 0;
        var fields = 0;
        var pendingFields = 0;
        var audit = 0;
        for (var region : regionTree) {
            // A single accepted/confirmed geometry is not a pending choice.
            // Count only unresolved alternatives; child fields never contribute
            // to this number because they live below the region node.
            var alternativeCount = region.path("alternatives").size();
            var unresolved = alternativeCount > 1
                    && Set.of("CONFLICT", "UNRESOLVED", "PROVISIONAL")
                    .contains(region.path("structureStatus").asText(""));
            if (unresolved) {
                var groupKey = region.path("resolutionGroupId").asText(region.path("regionId").asText(""));
                uniqueConflictGroups.add(groupKey);
                for (var alternative : region.path("alternatives")) {
                    uniqueAlternatives.add(groupKey + "|" + alternative.path("alternativeId").asText(""));
                }
            }
            fields += (int) StreamSupport.stream(region.path("fields").spliterator(), false)
                    .filter(field -> !field.path("fieldName").asText("").isBlank())
                    .count();
            for (var field : region.path("fields")) {
                if (!field.path("fieldName").asText("").isBlank()
                        && !field.path("payload").path("runtimeInputOnly").asBoolean(false)
                        && Set.of("PENDING", "CONFLICT").contains(field.path("status").asText(""))) pendingFields++;
            }
            runtimeSlots += region.path("runtimeSlots").size();
            audit += region.path("auditSuggestions").size();
        }
        audit += (int) suggestions.stream().filter(suggestion -> "SEMANTIC_MODEL".equals(suggestion.suggestionType())).count();
        structureAlternatives = uniqueAlternatives.size();
        conflicts = uniqueConflictGroups.size();
        return new RecognitionReviewStatistics(
                regionTree.size(), structureAlternatives, conflicts, fields, pendingFields, audit, runtimeSlots);
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
            JsonNode recognitionCoverage,
            JsonNode regions,
            RecognitionReviewStatistics statistics
    ) {
    }

    public record RecognitionReviewStatistics(
            int regionCount,
            int structureAlternativeCount,
            int structureConflictGroups,
            int fieldCount,
            int pendingFieldCount,
            int auditSuggestionCount,
            int runtimeSlotCount
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

    public record RecognitionAction(
            UUID recognitionItemId,
            String action,
            UUID selectedSuggestionId,
            String selectedAlternativeId
    ) {
        public RecognitionAction(UUID recognitionItemId, String action, UUID selectedSuggestionId) {
            this(recognitionItemId, action, selectedSuggestionId, null);
        }

        public RecognitionAction(UUID recognitionItemId, String action) {
            this(recognitionItemId, action, null, null);
        }
    }

    private boolean isStructuralCandidate(TemplateImportRepository.RecognitionSuggestionView suggestion) {
        var payload = suggestion.payload();
        if ("TABLE_CHILD_FIELD".equals(suggestion.suggestionType())
                || "MATRIX_FIELD".equals(suggestion.suggestionType())
                || "CHILD".equals(payload.path("suggestionLevel").asText(""))) {
            return false;
        }
        var type = kind(suggestion);
        return Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE", "FORM_REGION", "TABLE_REGION")
                .contains(type);
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
