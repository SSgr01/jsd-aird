package com.jsd.aird.kb.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.TermAnalyzer;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeGovernanceService {

    private static final Set<String> BLOCK_STATUSES = Set.of("PENDING", "CONFIRMED", "IGNORED", "ISSUE");
    private final KnowledgeGovernanceRepository governance;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeService knowledge;
    private final FileStorageFacade storage;
    private final AuditLogFacade audit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate itemTransaction;

    public KnowledgeGovernanceService(KnowledgeGovernanceRepository governance,
                                      KnowledgeRepository knowledgeRepository,
                                      KnowledgeService knowledge,
                                      FileStorageFacade storage,
                                      AuditLogFacade audit,
                                      ObjectMapper objectMapper,
                                      PlatformTransactionManager transactionManager) {
        this.governance = governance;
        this.knowledgeRepository = knowledgeRepository;
        this.knowledge = knowledge;
        this.storage = storage;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.itemTransaction = new TransactionTemplate(transactionManager);
        this.itemTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PreflightResult preflight(PreflightCommand command) {
        var actor = ActorContext.required();
        try (var file = storage.open(actor.organizationId(), requireFileId(command.fileId()))) {
            var exact = governance.exactMatches(actor.organizationId(), file.sha256());
            if (!exact.isEmpty()) {
                auditPreflight(actor.organizationId(), actor.userId(), "KB_UPLOAD_PREFLIGHT_EXACT_DUPLICATE", file.fileId(),
                        objectMapper.createObjectNode().put("fileId", file.fileId().toString()).put("sha256", file.sha256())
                                .put("existingDocumentId", exact.getFirst().documentId().toString())
                                .put("existingVersionId", exact.getFirst().versionId().toString()));
                return new PreflightResult("EXACT_DUPLICATE", file.fileId(), file.originalName(), file.sha256(), exact, List.of());
            }
            var stem = KnowledgeDuplicateDetector.normalizedStem(file.originalName());
            var matches = governance.possibleMatches(actor.organizationId(), stem, normalizeType(command.documentType(), file.originalName()),
                            safe(command.objectRefIds())).stream()
                    .map(match -> withSimilarity(match, KnowledgeDuplicateDetector.similarity(file.originalName(), match.originalName())))
                    .filter(match -> match.similarity() >= 0.90)
                    .toList();
            var byDocument = new java.util.LinkedHashMap<UUID, KnowledgeGovernanceRepository.DuplicateMatch>();
            for (var match : matches) {
                byDocument.merge(match.documentId(), match, (left, right) ->
                        right.similarity() > left.similarity() ? right : left);
            }
            var possible = List.copyOf(byDocument.values());
            var decision = possible.isEmpty() ? "NEW_DOCUMENT" : "POSSIBLE_VERSION";
            auditPreflight(actor.organizationId(), actor.userId(), "KB_UPLOAD_PREFLIGHT", file.fileId(),
                    objectMapper.createObjectNode().put("fileId", file.fileId().toString()).put("decision", decision)
                            .put("candidateCount", possible.size()));
            return new PreflightResult(decision, file.fileId(), file.originalName(), file.sha256(), List.of(), possible);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("上传预检失败", exception);
        }
    }

    @Transactional
    public KnowledgeService.DocumentView create(CreateCommand command) {
        var actor = ActorContext.required();
        var scope = normalizeScope(command.libraryScope());
        var categoryId = resolveCategory(actor.organizationId(), command.categoryId(), scope);
        var checked = preflight(new PreflightCommand(command.fileId(), command.documentType(), command.objectRefIds()));
        var documentType = normalizeType(command.documentType(), checked.originalName());
        if ("EXACT_DUPLICATE".equals(checked.decision())) {
            var duplicate = checked.exactMatches().getFirst();
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                    "相同文件已存在：" + duplicate.title() + " V" + duplicate.versionNo());
        }
        var resolution = normalizeResolution(command.resolution());
        if ("POSSIBLE_VERSION".equals(checked.decision()) && resolution == null) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "疑似已有文档的新版本，必须明确选择新文档或指定文档的新版本");
        }
        KnowledgeService.DocumentView created;
        if ("NEW_VERSION".equals(resolution)) {
            if (command.targetDocumentId() == null) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "作为新版本时必须指定目标文档");
            }
            created = knowledge.createVersion(command.targetDocumentId(), new KnowledgeService.CreateVersionCommand(command.fileId()));
            governance.updateDraftMetadata(actor.organizationId(), created.id(),
                    StringUtils.hasText(command.title()) ? normalizeTitle(command.title()) : created.title(),
                    documentType, scope, categoryId);
        } else {
            created = knowledge.create(new KnowledgeService.CreateCommand(command.fileId(), command.title(),
                    documentType, scope, categoryId));
        }
        governance.updateMediaConsent(actor.organizationId(), created.currentVersionId(), command.mediaProcessingConsent(), actor.userId());
        governance.updateSourceInfo(actor.organizationId(), created.id(), created.currentVersionId(), command.sourceInfo());
        governance.replaceTags(actor.organizationId(), actor.userId(), created.id(), normalizeTags(command.tags()));
        governance.replaceRelations(actor.organizationId(), actor.userId(), created.id(), safe(command.objectRefIds()));
        validateRelations(actor.organizationId(), created.id(), command.objectRefIds());
        audit(actor.organizationId(), actor.userId(), "KB_MEDIA_PROCESSING_CONSENT", created.id(),
                objectMapper.createObjectNode().put("versionId", created.currentVersionId().toString())
                        .put("consent", command.mediaProcessingConsent()));
        var governanceDetail = objectMapper.createObjectNode()
                .put("versionId", created.currentVersionId().toString()).put("documentType", documentType)
                .put("libraryScope", scope).put("categoryId", categoryId.toString())
                .put("resolution", resolution == null ? "NEW_DOCUMENT" : resolution);
        governanceDetail.set("tags", objectMapper.valueToTree(normalizeTags(command.tags())));
        governanceDetail.set("objectRefIds", objectMapper.valueToTree(safe(command.objectRefIds())));
        governanceDetail.set("sourceInfo", command.sourceInfo() == null
                ? objectMapper.createObjectNode() : command.sourceInfo());
        audit(actor.organizationId(), actor.userId(), "KB_DOCUMENT_GOVERNANCE_INITIALIZED", created.id(), governanceDetail);
        return knowledge.get(created.id());
    }

    public List<KnowledgeGovernanceRepository.ReviewQueueItem> reviewQueue(String status, int limit) {
        return governance.reviewQueue(ActorContext.required().organizationId(), status, limit);
    }

    public KnowledgeGovernanceRepository.ReviewView review(UUID documentId, UUID versionId) {
        return governance.review(ActorContext.required().organizationId(), documentId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "待审核版本不存在"));
    }

    @Transactional
    public KnowledgeGovernanceRepository.ReviewView saveReview(UUID documentId, UUID versionId, ReviewCommand command) {
        var actor = ActorContext.required();
        requireReviewIdentity(documentId, versionId, command.documentId(), command.versionId());
        var before = review(documentId, versionId);
        var scope = normalizeScope(command.libraryScope());
        var update = new KnowledgeGovernanceRepository.ReviewUpdate(documentId, versionId, command.reviewRevision(),
                normalizeTitle(command.title()), normalizeType(command.documentType(), null),
                scope, requireCategory(actor.organizationId(), command.categoryId(), scope),
                normalizeTags(command.tags()), safe(command.objectRefIds()), normalizeBlocks(command.blocks()),
                normalizeFields(command.fields()));
        if (!governance.saveReview(actor.organizationId(), actor.userId(), update)) optimisticConflict(documentId, versionId);
        validateRelations(actor.organizationId(), documentId, command.objectRefIds());
        var saved = review(documentId, versionId);
        synchronizeReviewedChunks(saved);
        var auditDetail = objectMapper.createObjectNode().put("versionId", versionId.toString())
                .put("previousRevision", command.reviewRevision());
        auditDetail.set("before", reviewAuditSnapshot(before));
        auditDetail.set("after", reviewAuditSnapshot(saved));
        audit(actor.organizationId(), actor.userId(), "KB_REVIEW_SAVED", documentId, auditDetail);
        return saved;
    }

    @Transactional
    public KnowledgeGovernanceRepository.PublicationRow publish(UUID documentId, UUID versionId, int reviewRevision) {
        var actor = ActorContext.required();
        var review = review(documentId, versionId);
        if (review.reviewRevision() != reviewRevision) optimisticConflict(documentId, versionId);
        validatePublish(review);
        var previous = governance.currentPublication(actor.organizationId(), documentId).orElse(null);
        var publication = governance.publish(actor.organizationId(), actor.userId(), documentId, versionId, reviewRevision);
        if (publication == null) optimisticConflict(documentId, versionId);
        knowledgeRepository.rebuildTermStats(actor.organizationId());
        audit(actor.organizationId(), actor.userId(), "KB_DOCUMENT_PUBLISHED", documentId,
                objectMapper.createObjectNode().put("versionId", versionId.toString())
                        .put("publicationId", publication.id().toString()).put("publicationNo", publication.publicationNo())
                        .put("previousPublicationId", previous == null ? null : previous.id().toString())
                        .put("previousVersionId", previous == null ? null : previous.versionId().toString()));
        return publication;
    }

    @Transactional
    public void reject(UUID documentId, UUID versionId, int reviewRevision, String reason) {
        var actor = ActorContext.required();
        var normalizedReason = requireText(reason, "驳回原因");
        var before = review(documentId, versionId);
        if (!governance.reject(actor.organizationId(), actor.userId(), documentId, versionId, reviewRevision, normalizedReason)) {
            optimisticConflict(documentId, versionId);
        }
        audit(actor.organizationId(), actor.userId(), "KB_DOCUMENT_REJECTED", documentId,
                objectMapper.createObjectNode().put("versionId", versionId.toString()).put("reason", normalizedReason)
                        .put("beforeStatus", before.reviewStatus()).put("afterStatus", "REJECTED"));
    }

    @Transactional
    public KnowledgeService.DocumentView reparse(UUID documentId, UUID versionId, int reviewRevision, Boolean mediaConsent) {
        var actor = ActorContext.required();
        var current = review(documentId, versionId);
        if (current.reviewRevision() != reviewRevision) optimisticConflict(documentId, versionId);
        if (current.parseRun() == null || "PROCESSING".equals(current.parseRun().status())) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "当前版本尚在首次解析或已有解析任务执行中");
        }
        if (!governance.reserveReparse(actor.organizationId(), actor.userId(), documentId, versionId, reviewRevision)) {
            optimisticConflict(documentId, versionId);
        }
        if (mediaConsent != null) {
            governance.updateMediaConsent(actor.organizationId(), versionId, mediaConsent, actor.userId());
        }
        var result = knowledge.reindex(documentId, versionId);
        audit(actor.organizationId(), actor.userId(), "KB_DOCUMENT_REPARSE_REQUESTED", documentId,
                objectMapper.createObjectNode().put("versionId", versionId.toString())
                        .put("previousRevision", reviewRevision).put("reservedRevision", reviewRevision + 1)
                        .put("beforeMediaConsent", current.mediaProcessingConsent())
                        .put("afterMediaConsent", mediaConsent == null ? current.mediaProcessingConsent() : mediaConsent));
        return result;
    }

    @Transactional
    public void lifecycle(UUID documentId, String target, String reason) {
        var actor = ActorContext.required();
        var before = knowledge.get(documentId);
        var normalized = target.toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "DISABLED").contains(normalized)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "无效生命周期状态");
        if ("DISABLED".equals(normalized) && !StringUtils.hasText(reason)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "停用原因不能为空");
        }
        if (!governance.updateLifecycle(actor.organizationId(), actor.userId(), documentId, normalized, reason)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在");
        }
        knowledgeRepository.rebuildTermStats(actor.organizationId());
        audit(actor.organizationId(), actor.userId(), "KB_DOCUMENT_" + ("ACTIVE".equals(normalized) ? "RESTORED" : "DISABLED"), documentId,
                objectMapper.createObjectNode().put("reason", reason == null ? "" : reason)
                        .put("before", before.lifecycleStatus()).put("after", normalized));
    }

    @Transactional
    public KnowledgeGovernanceRepository.PublicationRow aiUsage(UUID publicationId, String action, String reason) {
        var actor = ActorContext.required();
        var normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "REJECT", "REVOKE").contains(normalized)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "AI授权动作只能是 APPROVE、REJECT 或 REVOKE");
        }
        var before = governance.currentPublicationById(actor.organizationId(), publicationId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "发布记录不存在"));
        if (!governance.updateAiUsage(actor.organizationId(), actor.userId(), publicationId, normalized, reason)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "只能授权当前有效且已发布的版本");
        }
        var publication = governance.currentPublicationById(actor.organizationId(), publicationId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "发布记录不存在"));
        audit(actor.organizationId(), actor.userId(), "KB_AI_GRANT_" + normalized, publication.documentId(),
                objectMapper.createObjectNode().put("publicationId", publicationId.toString())
                        .put("reason", reason == null ? "" : reason)
                        .put("before", before.aiStatus()).put("after", publication.aiStatus()));
        return publication;
    }

    public List<BatchResult> batchMove(List<UUID> documentIds, UUID categoryId) {
        var actor = ActorContext.required();
        requireCategory(actor.organizationId(), categoryId);
        return batch(documentIds, id -> {
            var before = knowledge.get(id);
            knowledge.assignCategory(id, categoryId);
            return objectMapper.createObjectNode()
                    .put("beforeCategoryId", before.categoryId() == null ? null : before.categoryId().toString())
                    .put("afterCategoryId", categoryId.toString());
        }, "KB_BATCH_MOVE");
    }

    public List<BatchResult> batchTags(List<UUID> documentIds, List<String> add, List<String> remove) {
        var actor = ActorContext.required();
        var additions = normalizeTags(add);
        var removals = normalizeTags(remove).stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        return batch(documentIds, id -> {
            knowledge.get(id);
            var before = governance.tags(actor.organizationId(), id);
            var tags = new LinkedHashSet<>(before);
            tags.removeIf(value -> removals.contains(value.toLowerCase(Locale.ROOT)));
            tags.addAll(additions);
            governance.replaceTags(actor.organizationId(), actor.userId(), id, List.copyOf(tags));
            var detail = objectMapper.createObjectNode();
            detail.set("before", objectMapper.valueToTree(before));
            detail.set("after", objectMapper.valueToTree(governance.tags(actor.organizationId(), id)));
            return detail;
        }, "KB_BATCH_TAGS");
    }

    public List<BatchResult> batchAiUsage(List<UUID> documentIds, String action, String reason) {
        var actor = ActorContext.required();
        return batch(documentIds, id -> {
            var publication = governance.currentPublication(actor.organizationId(), id)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "文档没有当前发布版本"));
            var updated = aiUsage(publication.id(), action, reason);
            return objectMapper.createObjectNode().put("publicationId", publication.id().toString())
                    .put("before", publication.aiStatus()).put("after", updated.aiStatus());
        }, "KB_BATCH_AI_USAGE");
    }

    public List<KnowledgeGovernanceRepository.PageListItem> pages() {
        return governance.listPages(ActorContext.required().organizationId());
    }

    public List<AuditLogFacade.AuditEntry> auditTrail(UUID documentId) {
        var actor = ActorContext.required();
        knowledge.get(documentId);
        return audit.list(actor.organizationId(), "KB_DOCUMENT", documentId, 200);
    }

    public List<KnowledgeGovernanceRepository.PublicationRow> publications(UUID documentId) {
        var actor = ActorContext.required();
        knowledge.get(documentId);
        return governance.publications(actor.organizationId(), documentId);
    }

    public KnowledgeGovernanceRepository.PageView page(UUID pageId) {
        return governance.page(ActorContext.required().organizationId(), pageId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识页不存在"));
    }

    @Transactional
    public KnowledgeGovernanceRepository.PageView savePageDraft(UUID pageId, String title, String summary, int revision) {
        var actor = ActorContext.required();
        var before = page(pageId);
        if (!governance.savePageDraft(actor.organizationId(), pageId, requireText(title, "知识页标题"),
                summary == null ? "" : summary.trim(), revision)) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "知识页草稿已更新，请刷新后重试");
        }
        var after = page(pageId);
        auditPage(actor.organizationId(), actor.userId(), "KB_PAGE_DRAFT_SAVED", pageId,
                objectMapper.createObjectNode().put("previousRevision", revision)
                        .put("beforeTitle", before.page().draftTitle()).put("afterTitle", after.page().draftTitle())
                        .put("beforeSummary", before.page().draftSummary()).put("afterSummary", after.page().draftSummary()));
        return after;
    }

    @Transactional
    public KnowledgeGovernanceRepository.PageVersionRow publishPage(UUID pageId, int revision) {
        var actor = ActorContext.required();
        var before = page(pageId);
        var version = governance.publishPage(actor.organizationId(), actor.userId(), pageId, revision);
        if (version == null) throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "知识页草稿已更新，请刷新后重试");
        auditPage(actor.organizationId(), actor.userId(), "KB_PAGE_PUBLISHED", pageId,
                objectMapper.createObjectNode().put("versionNo", version.versionNo()).put("sourceCount", version.sources().size())
                        .put("previousVersionNo", before.page().currentVersionNo()));
        return version;
    }

    private List<BatchResult> batch(List<UUID> documentIds, BatchAction action, String auditAction) {
        if (documentIds == null || documentIds.isEmpty() || documentIds.size() > 200) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "批量操作每次需包含 1 至 200 个文档");
        }
        var actor = ActorContext.required();
        var result = new ArrayList<BatchResult>();
        for (var id : documentIds.stream().distinct().toList()) {
            try {
                itemTransaction.executeWithoutResult(ignored -> {
                    var detail = action.run(id);
                    var auditDetail = detail != null && detail.isObject()
                            ? (com.fasterxml.jackson.databind.node.ObjectNode) detail.deepCopy()
                            : objectMapper.createObjectNode();
                    auditDetail.put("success", true);
                    audit(actor.organizationId(), actor.userId(), auditAction, id, auditDetail);
                });
                result.add(new BatchResult(id, true, null, null));
            } catch (ApiException exception) {
                result.add(new BatchResult(id, false, exception.errorCode().code(), exception.getMessage()));
                audit(actor.organizationId(), actor.userId(), auditAction, id,
                        objectMapper.createObjectNode().put("success", false).put("errorCode", exception.errorCode().code())
                                .put("message", exception.getMessage()));
            } catch (RuntimeException exception) {
                result.add(new BatchResult(id, false, ApiErrorCode.INTERNAL_ERROR.code(), "操作失败"));
                audit(actor.organizationId(), actor.userId(), auditAction, id,
                        objectMapper.createObjectNode().put("success", false).put("errorCode", ApiErrorCode.INTERNAL_ERROR.code()));
            }
        }
        return result;
    }

    private void validatePublish(KnowledgeGovernanceRepository.ReviewView review) {
        var reasons = new ArrayList<String>();
        if (!"READY".equals(review.processingStatus())) reasons.add("解析未完成");
        if (!StringUtils.hasText(review.documentType())) reasons.add("文档类型为空");
        if (review.categoryId() == null) reasons.add("分类为空");
        if (review.parseRun() == null || !Set.of("PENDING_REVIEW", "REJECTED").contains(review.parseRun().status())) reasons.add("没有可发布的解析结果");
        if (review.blocks().isEmpty()) reasons.add("解析文本为空");
        if (review.blocks().stream().anyMatch(block -> !Set.of("CONFIRMED", "IGNORED").contains(block.reviewStatus()))) {
            reasons.add("仍有解析文本块未确认");
        }
        if (review.fields().stream().anyMatch(field -> field.required()
                && (!"CONFIRMED".equals(field.reviewStatus()) || !StringUtils.hasText(effectiveValue(field))))) {
            reasons.add("必填抽取字段未确认");
        }
        if (review.fields().stream().anyMatch(field -> field.confidence() != null && field.confidence() < 0.80
                && !"CONFIRMED".equals(field.reviewStatus()))) {
            reasons.add("低置信度抽取字段未处理");
        }
        if (review.fields().stream().anyMatch(field -> field.conflict()
                && (!"CONFIRMED".equals(field.reviewStatus()) || !StringUtils.hasText(field.confirmedValue())))) {
            reasons.add("抽取字段冲突未处理");
        }
        if (review.issues().stream().anyMatch(issue -> "BLOCKER".equals(issue.severity()) && "OPEN".equals(issue.status()))) {
            reasons.add("仍有阻断级解析问题未解决");
        }
        var types = review.relations().stream().map(KnowledgeGovernanceRepository.ObjectRelation::objectType).collect(java.util.stream.Collectors.toSet());
        var documentType = review.documentType().toUpperCase(Locale.ROOT);
        if (Set.of("COA", "CERTIFICATE_OF_ANALYSIS").contains(documentType)
                && !(types.contains("PRODUCT") && types.contains("BATCH"))) reasons.add("COA必须关联产品和批次");
        if (Set.of("PRODUCT_INFO", "TDS", "SDS").contains(documentType) && !types.contains("PRODUCT")) reasons.add("产品资料必须关联产品");
        if ("EXPERIMENT_REPORT".equals(documentType) && !(types.contains("EXPERIMENT") || types.contains("PROJECT"))) reasons.add("实验报告必须关联实验或项目");
        if (Set.of("FORMULA", "FORMULA_INFO").contains(documentType) && !types.contains("FORMULA")) reasons.add("配方资料必须关联配方");
        try (var ignored = storage.open(ActorContext.required().organizationId(),
                knowledgeRepository.findVersion(ActorContext.required().organizationId(), review.versionId())
                        .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "原文件版本不存在")).fileObjectId())) {
            // Opening the immutable source is the final publication gate.
        } catch (ApiException exception) {
            reasons.add("原文件不存在");
        } catch (Exception exception) {
            reasons.add("原文件不可读取");
        }
        if (!reasons.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, String.join("；", reasons));
    }

    private String effectiveValue(KnowledgeGovernanceRepository.ExtractedFieldView field) {
        if (StringUtils.hasText(field.confirmedValue())) return field.confirmedValue();
        return field.normalizedValue();
    }

    private void synchronizeReviewedChunks(KnowledgeGovernanceRepository.ReviewView review) {
        if (review.parseRun() == null) return;
        var chunks = review.blocks().stream().map(block -> {
            var content = "IGNORED".equals(block.reviewStatus()) ? ""
                    : StringUtils.hasText(block.confirmedText()) ? block.confirmedText() : block.normalizedText();
            var terms = TermAnalyzer.frequencies(content).entrySet().stream()
                    .map(item -> new KnowledgeRepository.TermFrequency(item.getKey(), item.getValue())).toList();
            return new KnowledgeRepository.ReviewedChunk(block.blockNo(), content, terms);
        }).toList();
        knowledgeRepository.updateReviewedChunks(review.parseRun().id(), chunks);
    }

    private com.fasterxml.jackson.databind.JsonNode reviewAuditSnapshot(KnowledgeGovernanceRepository.ReviewView review) {
        var value = objectMapper.createObjectNode();
        value.put("reviewRevision", review.reviewRevision()).put("title", review.title())
                .put("documentType", review.documentType()).put("libraryScope", review.libraryScope())
                .put("categoryId", review.categoryId() == null ? null : review.categoryId().toString());
        value.set("tags", objectMapper.valueToTree(review.tags()));
        value.set("objectRefIds", objectMapper.valueToTree(review.relations().stream()
                .map(KnowledgeGovernanceRepository.ObjectRelation::id).toList()));
        value.set("blocks", objectMapper.valueToTree(review.blocks().stream().map(block -> java.util.Map.of(
                "id", block.id(), "confirmedText", block.confirmedText() == null ? "" : block.confirmedText(),
                "reviewStatus", block.reviewStatus())).toList()));
        value.set("fields", objectMapper.valueToTree(review.fields().stream().map(field -> java.util.Map.of(
                "id", field.id(), "confirmedValue", field.confirmedValue() == null ? "" : field.confirmedValue(),
                "reviewStatus", field.reviewStatus())).toList()));
        return value;
    }

    private List<KnowledgeGovernanceRepository.BlockUpdate> normalizeBlocks(List<KnowledgeGovernanceRepository.BlockUpdate> blocks) {
        return safe(blocks).stream().map(block -> {
            var status = normalizeReviewStatus(block.reviewStatus());
            return new KnowledgeGovernanceRepository.BlockUpdate(block.id(), block.confirmedText(), status);
        }).toList();
    }

    private List<KnowledgeGovernanceRepository.FieldUpdate> normalizeFields(List<KnowledgeGovernanceRepository.FieldUpdate> fields) {
        return safe(fields).stream().map(field -> new KnowledgeGovernanceRepository.FieldUpdate(
                field.id(), field.confirmedValue(), normalizeReviewStatus(field.reviewStatus()))).toList();
    }

    private String normalizeReviewStatus(String value) {
        var normalized = value == null ? "PENDING" : value.trim().toUpperCase(Locale.ROOT);
        if (!BLOCK_STATUSES.contains(normalized)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "无效的确认状态");
        return normalized;
    }

    private UUID requireCategory(UUID organizationId, UUID categoryId) {
        if (categoryId == null || knowledgeRepository.findCategory(organizationId, categoryId).isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "必须选择有效分类");
        }
        return categoryId;
    }

    private UUID requireCategory(UUID organizationId, UUID categoryId, String scope) {
        requireCategory(organizationId, categoryId);
        var category = knowledgeRepository.findCategory(organizationId, categoryId).orElseThrow();
        if (!scope.equals(category.scope())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类与资料范围不匹配");
        return categoryId;
    }

    private UUID resolveCategory(UUID organizationId, UUID categoryId, String scope) {
        if (categoryId != null) return requireCategory(organizationId, categoryId, scope);
        return knowledgeRepository.findDefaultCategory(organizationId, scope)
                .map(KnowledgeRepository.CategoryRow::id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST, "必须选择有效分类"));
    }

    private void validateRelations(UUID organizationId, UUID documentId, List<UUID> requested) {
        var expected = new LinkedHashSet<>(safe(requested));
        var actual = governance.relations(organizationId, documentId).stream()
                .map(KnowledgeGovernanceRepository.ObjectRelation::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!actual.equals(expected)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "关联对象不存在、已停用或不属于当前组织");
    }

    private void optimisticConflict(UUID documentId, UUID versionId) {
        var current = review(documentId, versionId);
        throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "审核内容已被更新，当前版本号为 " + current.reviewRevision(), current);
    }

    private void requireReviewIdentity(UUID documentId, UUID versionId, UUID bodyDocumentId, UUID bodyVersionId) {
        if (bodyDocumentId != null && !documentId.equals(bodyDocumentId)
                || bodyVersionId != null && !versionId.equals(bodyVersionId)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "路径与审核对象不一致");
        }
    }

    private KnowledgeGovernanceRepository.DuplicateMatch withSimilarity(
            KnowledgeGovernanceRepository.DuplicateMatch match, double similarity) {
        return new KnowledgeGovernanceRepository.DuplicateMatch(match.documentId(), match.versionId(), match.versionNo(),
                match.title(), match.originalName(), match.documentType(), match.sha256(),
                KnowledgeDuplicateDetector.normalizedStem(match.originalName()), similarity,
                match.lifecycleStatus(), match.reviewStatus());
    }

    private String normalizeResolution(String value) {
        if (!StringUtils.hasText(value)) return null;
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NEW_DOCUMENT", "NEW_VERSION").contains(normalized)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "resolution只能是NEW_DOCUMENT或NEW_VERSION");
        }
        return normalized;
    }

    private String normalizeType(String value, String fileName) {
        final String normalized;
        if (StringUtils.hasText(value)) normalized = value.trim().toUpperCase(Locale.ROOT);
        else {
            if (fileName == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文档类型不能为空");
            var dot = fileName.lastIndexOf('.');
            normalized = dot < 0 ? "UNKNOWN" : fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
        }
        if (normalized.length() > 32) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文档类型不能超过32个字符");
        return normalized;
    }

    private String normalizeScope(String value) {
        var normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "INTERNAL";
        if (!Set.of("INTERNAL", "EXTERNAL").contains(normalized)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "资料范围无效");
        return normalized;
    }

    private List<String> normalizeTags(List<String> tags) {
        var result = new LinkedHashSet<String>();
        for (var tag : safe(tags)) {
            if (!StringUtils.hasText(tag)) continue;
            var normalized = Normalizer.normalize(tag.trim(), Normalizer.Form.NFKC);
            if (normalized.length() > 120) throw new ApiException(ApiErrorCode.BAD_REQUEST, "标签不能超过120个字符");
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private String requireText(String value, String label) {
        if (!StringUtils.hasText(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST, label + "不能为空");
        return value.trim();
    }

    private String normalizeTitle(String value) {
        var normalized = requireText(value, "文件名称");
        if (normalized.length() > 260) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件名称不能超过260个字符");
        return normalized;
    }

    private UUID requireFileId(UUID value) {
        if (value == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "fileId不能为空");
        return value;
    }

    private void audit(UUID organizationId, UUID actorId, String action, UUID id, com.fasterxml.jackson.databind.JsonNode detail) {
        audit.append(organizationId, actorId, action, "KB_DOCUMENT", id, detail);
    }

    private void auditPage(UUID organizationId, UUID actorId, String action, UUID id,
                           com.fasterxml.jackson.databind.JsonNode detail) {
        audit.append(organizationId, actorId, action, "KB_KNOWLEDGE_PAGE", id, detail);
    }

    private void auditPreflight(UUID organizationId, UUID actorId, String action, UUID id,
                                com.fasterxml.jackson.databind.JsonNode detail) {
        itemTransaction.executeWithoutResult(ignored ->
                audit.append(organizationId, actorId, action, "KB_UPLOAD", id, detail));
    }

    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    @FunctionalInterface
    private interface BatchAction { com.fasterxml.jackson.databind.JsonNode run(UUID documentId); }

    public record PreflightCommand(UUID fileId, String documentType, List<UUID> objectRefIds) { }
    public record PreflightResult(String decision, UUID fileId, String originalName, String sha256,
                                  List<KnowledgeGovernanceRepository.DuplicateMatch> exactMatches,
                                  List<KnowledgeGovernanceRepository.DuplicateMatch> possibleVersions) { }
    public record CreateCommand(UUID fileId, String title, String documentType, String libraryScope, UUID categoryId,
                                List<String> tags, List<UUID> objectRefIds, boolean mediaProcessingConsent,
                                String resolution, UUID targetDocumentId, com.fasterxml.jackson.databind.JsonNode sourceInfo) { }
    public record ReviewCommand(UUID documentId, UUID versionId, int reviewRevision, String title,
                                String documentType, String libraryScope, UUID categoryId, List<String> tags,
                                List<UUID> objectRefIds, List<KnowledgeGovernanceRepository.BlockUpdate> blocks,
                                List<KnowledgeGovernanceRepository.FieldUpdate> fields) { }
    public record BatchResult(UUID documentId, boolean success, String errorCode, String message) { }
}
