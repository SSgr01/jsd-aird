package com.jsd.aird.tpl.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
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
    private final ObjectMapper objectMapper;

    public TemplateRecognitionReviewService(
            TemplateImportRepository importRepository,
            TemplateRepository templateRepository,
            FileObjectRepository fileRepository,
            ObjectMapper objectMapper
    ) {
        this.importRepository = importRepository;
        this.templateRepository = templateRepository;
        this.fileRepository = fileRepository;
        this.objectMapper = objectMapper;
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
        var importJobId = UUID.randomUUID();
        importRepository.enqueue(new TemplateImportRepository.NewImportJob(
                importJobId,
                UUID.randomUUID(),
                actor.organizationId(),
                workspace.snapshotFileId(),
                TemplateFormat.XLSX,
                actor.userId(),
                "UNIVER_SNAPSHOT",
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
                        .put("recognitionRunId", importJobId.toString())
                        .put("scope", scope)
                        .put("sheetId", sheetId == null ? "" : sheetId)
                        .put("address", address == null ? "" : address)
        );
        return importRepository.find(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "识别任务创建失败"));
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
        review.items().forEach(item -> byId.put(item.id(), item));
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
            for (UUID suggestionId : item.suggestionIds()) {
                importRepository.decideSuggestion(
                        organizationId, review.recognitionRunId(), suggestionId, decision, actorId
                ).orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST, "识别项目已变化，请刷新后重试"));
            }
        }
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
        if (run != null) {
            importRepository.listSuggestions(organizationId, run.id()).forEach(item ->
                    decisions.put(item.id(), item.decision()));
        }
        for (JsonNode binding : mapping) {
            var recognitionItemId = binding.path("diagnostic").path("recognitionItemId").asText("");
            if (recognitionItemId.isBlank()) continue;
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
            return new RecognitionReview(null, "NONE", emptySummary(), List.of(), List.of(), List.of(), null);
        }
        var suggestions = importRepository.listSuggestions(organizationId, run.id());
        var semanticModel = suggestions.stream()
                .filter(suggestion -> "SEMANTIC_MODEL".equals(suggestion.suggestionType()))
                .findFirst()
                .map(suggestion -> (JsonNode) suggestion.payload().deepCopy())
                .orElse(null);
        var grouped = new LinkedHashMap<String, List<TemplateImportRepository.RecognitionSuggestionView>>();
        suggestions.stream()
                .filter(suggestion -> !"SEMANTIC_MODEL".equals(suggestion.suggestionType()))
                .sorted(suggestionOrder()).forEach(suggestion ->
                grouped.computeIfAbsent(reviewKey(suggestion), ignored -> new ArrayList<>()).add(suggestion)
        );
        var items = new ArrayList<RecognitionReviewItem>();
        var fields = workspace.schema().path(TemplateRecognitionCompiler.FIELD_MODEL_KEY).path("fields");
        for (List<TemplateImportRepository.RecognitionSuggestionView> candidates : grouped.values()) {
            var primary = candidates.getFirst();
            var conflict = candidates.stream().skip(1)
                    .anyMatch(candidate -> !structuralSignature(primary).equals(structuralSignature(candidate)));
            var allRejected = candidates.stream().allMatch(candidate -> "REJECTED".equals(candidate.decision()));
            var anyAccepted = candidates.stream().anyMatch(candidate -> "ACCEPTED".equals(candidate.decision()));
            var status = allRejected ? "IGNORED" : anyAccepted ? "CONFIRMED" : conflict ? "CONFLICT" : "PENDING";
            var payload = primary.payload();
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
                    findFieldId(fields, primary.id(), payload.path("dataPath").asText("")),
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
                    status,
                    conflict ? conflictMessage(primary, candidates) : null,
                    payload.deepCopy()
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
                run.id(), run.status(), summary, List.copyOf(groups), List.copyOf(items), qualityIssues,
                semanticModel
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

    private UUID findFieldId(JsonNode fields, UUID recognitionItemId, String dataPath) {
        if (!fields.isArray()) return null;
        for (JsonNode field : fields) {
            if (recognitionItemId.toString().equals(field.path("recognitionItemId").asText())
                    || (!dataPath.isBlank() && dataPath.equals(field.path("dataPath").asText()))) {
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
            JsonNode semanticModel
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
            String status,
            String conflictReason,
            JsonNode payload
    ) {
    }

    public record RecognitionAction(UUID recognitionItemId, String action) {
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
