package com.jsd.aird.kb.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeGovernanceService {

    private final KnowledgeGovernanceRepository governance;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeService knowledge;
    private final FileStorageFacade storage;
    private final OpsAsyncFacade async;
    private final AuditLogFacade audit;
    private final ObjectMapper objectMapper;
    private final StructuredDocumentCodec documents;
    private final TransactionTemplate itemTransaction;

    public KnowledgeGovernanceService(KnowledgeGovernanceRepository governance,
                                      KnowledgeRepository knowledgeRepository,
                                      KnowledgeService knowledge,
                                      FileStorageFacade storage,
                                      OpsAsyncFacade async,
                                      AuditLogFacade audit,
                                      ObjectMapper objectMapper,
                                      StructuredDocumentCodec documents,
                                      PlatformTransactionManager transactionManager) {
        this.governance = governance;
        this.knowledgeRepository = knowledgeRepository;
        this.knowledge = knowledge;
        this.storage = storage;
        this.async = async;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.documents = documents;
        this.itemTransaction = new TransactionTemplate(transactionManager);
        this.itemTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PreflightResult preflight(PreflightCommand command) {
        var actor = ActorContext.required();
        var categoryId = requireCategory(actor.organizationId(), command.categoryId(), null);
        try (var file = storage.open(actor.organizationId(), requireFileId(command.fileId()))) {
            var exact = governance.exactMatches(actor.organizationId(), file.sha256());
            if (!exact.isEmpty()) {
                auditPreflight(actor.organizationId(), actor.userId(), "KB_UPLOAD_PREFLIGHT_EXACT_DUPLICATE", file.fileId(),
                        objectMapper.createObjectNode().put("sha256", file.sha256())
                                .put("existingDocumentId", exact.getFirst().documentId().toString()));
                return new PreflightResult("EXACT_DUPLICATE", file.fileId(), file.originalName(), file.sha256(), exact, List.of());
            }
            var matches = governance.possibleMatches(actor.organizationId(),
                            KnowledgeDuplicateDetector.normalizedStem(file.originalName()), categoryId).stream()
                    .map(match -> withSimilarity(match, KnowledgeDuplicateDetector.similarity(file.originalName(), match.originalName())))
                    .filter(match -> match.similarity() >= 0.90)
                    .toList();
            var byDocument = new java.util.LinkedHashMap<UUID, KnowledgeGovernanceRepository.DuplicateMatch>();
            matches.forEach(match -> byDocument.merge(match.documentId(), match,
                    (left, right) -> right.similarity() > left.similarity() ? right : left));
            var possible = List.copyOf(byDocument.values());
            var decision = possible.isEmpty() ? "NEW_DOCUMENT" : "POSSIBLE_VERSION";
            auditPreflight(actor.organizationId(), actor.userId(), "KB_UPLOAD_PREFLIGHT", file.fileId(),
                    objectMapper.createObjectNode().put("decision", decision).put("candidateCount", possible.size()));
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
        var categoryId = requireCategory(actor.organizationId(), command.categoryId(), scope);
        var checked = preflight(new PreflightCommand(command.fileId(), categoryId));
        if ("EXACT_DUPLICATE".equals(checked.decision())) {
            var duplicate = checked.exactMatches().getFirst();
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                    "相同文件已存在：" + duplicate.title() + " V" + duplicate.versionNo());
        }
        var resolution = normalizeResolution(command.resolution());
        if ("POSSIBLE_VERSION".equals(checked.decision()) && resolution == null) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "疑似已有文档的新版本，请明确上传方式");
        }
        KnowledgeService.DocumentView created;
        if ("NEW_VERSION".equals(resolution)) {
            if (command.targetDocumentId() == null) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "作为新版本时必须指定目标文档");
            }
            created = knowledge.createVersion(command.targetDocumentId(), new KnowledgeService.CreateVersionCommand(command.fileId()));
            governance.updateDraftMetadata(actor.organizationId(), created.id(),
                    StringUtils.hasText(command.title()) ? normalizeTitle(command.title()) : created.title(),
                    scope, categoryId);
        } else {
            created = knowledge.create(new KnowledgeService.CreateCommand(command.fileId(), command.title(), scope, categoryId));
        }
        governance.updateSourceInfo(actor.organizationId(), created.id(), created.currentVersionId(), command.sourceInfo());
        governance.replaceTags(actor.organizationId(), actor.userId(), created.id(), normalizeTags(command.tags()));
        var detail = objectMapper.createObjectNode().put("versionId", created.currentVersionId().toString())
                .put("libraryScope", scope).put("categoryId", categoryId.toString())
                .put("resolution", resolution == null ? "NEW_DOCUMENT" : resolution);
        detail.set("tags", objectMapper.valueToTree(normalizeTags(command.tags())));
        detail.set("sourceInfo", command.sourceInfo() == null ? objectMapper.createObjectNode() : command.sourceInfo());
        audit(actor.organizationId(), actor.userId(), "KB_DOCUMENT_GOVERNANCE_INITIALIZED", created.id(), detail);
        return knowledge.get(created.id());
    }

    public List<KnowledgeGovernanceRepository.ReviewQueueItem> reviewQueue(String status, int limit) {
        return governance.reviewQueue(ActorContext.required().organizationId(), status, limit);
    }

    public KnowledgeGovernanceRepository.ReviewView review(UUID documentId, UUID versionId) {
        return governance.review(ActorContext.required().organizationId(), documentId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "待校对版本不存在"));
    }

    @Transactional
    public KnowledgeGovernanceRepository.ReviewView saveReview(UUID documentId, UUID versionId, ReviewCommand command) {
        var actor = ActorContext.required();
        requireReviewIdentity(documentId, versionId, command.documentId(), command.versionId());
        var scope = normalizeScope(command.libraryScope());
        var update = reviewUpdate(documentId, versionId, command.reviewRevisionId(), command.lockVersion(),
                command.basePublicationId(), command.title(), scope, command.categoryId(), command.tags(),
                command.confirmedDocument(), command.excludedReviewNodeIds(), command.issueActions());
        if (!governance.saveReview(actor.organizationId(), actor.userId(), update)) optimisticConflict(documentId, versionId);
        var saved = review(documentId, versionId);
        audit(actor.organizationId(), actor.userId(), "KB_REVIEW_SAVED", documentId,
                objectMapper.createObjectNode().put("versionId", versionId.toString())
                        .put("reviewRevisionId", command.reviewRevisionId().toString())
                        .put("previousLockVersion", command.lockVersion()));
        return saved;
    }

    public IndexBuildView publish(UUID documentId, UUID versionId, UUID reviewRevisionId,
                                  int lockVersion, UUID basePublicationId) {
        var actor = ActorContext.required();
        var review = review(documentId, versionId);
        if (review.reviewRevision() == null || !review.reviewRevision().id().equals(reviewRevisionId)
                || review.reviewRevision().lockVersion() != lockVersion
                || !java.util.Objects.equals(review.reviewRevision().basePublicationId(), basePublicationId)) {
            optimisticConflict(documentId, versionId);
        }
        validatePublish(review);
        var projection = documents.project(review.reviewRevision().confirmedDocument(),
                review.reviewRevision().excludedReviewNodeIds());
        var tableText = governance.largeTableRows(actor.organizationId(), reviewRevisionId).stream()
                .map(KnowledgeGovernanceRepository.LargeTableRow::projectedText)
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
        var confirmedText = projection.confirmedText();
        if (StringUtils.hasText(tableText)) confirmedText = StringUtils.hasText(confirmedText)
                ? confirmedText + "\n\n" + tableText : tableText;
        if (!governance.reservePublication(actor.organizationId(), documentId, versionId, reviewRevisionId,
                lockVersion, basePublicationId, confirmedText)) {
            optimisticConflict(documentId, versionId);
        }
        knowledge.markIndexStale(actor.organizationId(), documentId, versionId, reviewRevisionId);
        enqueueIndex(actor.organizationId(), actor.userId(), documentId, versionId, reviewRevisionId, lockVersion);
        audit(actor.organizationId(), actor.userId(), "KB_PUBLICATION_INDEX_QUEUED", documentId,
                objectMapper.createObjectNode().put("versionId", versionId.toString())
                        .put("reviewRevisionId", reviewRevisionId.toString()));
        return new IndexBuildView(documentId, versionId, reviewRevisionId, "BUILDING");
    }

    @Transactional
    public KnowledgeGovernanceRepository.ReviewView createRevision(UUID documentId, RevisionCommand command) {
        var actor = ActorContext.required();
        var current = governance.currentPublication(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "当前发布不存在"));
        if (!current.id().equals(command.basePublicationId())) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "当前发布已经变化，请刷新后重试");
        }
        var revision = governance.createRevision(actor.organizationId(), actor.userId(), documentId,
                        command.basePublicationId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "内容已被其他修订更新，请刷新后重试"));
        audit(actor.organizationId(), actor.userId(), "KB_REVIEW_REVISION_CREATED", documentId,
                objectMapper.createObjectNode().put("basePublicationId", command.basePublicationId().toString())
                        .put("reviewRevisionId", revision.reviewRevisionId().toString()));
        return review(documentId, revision.versionId());
    }

    @Transactional
    public void reject(UUID documentId, UUID versionId, UUID reviewRevisionId, int lockVersion, String reason) {
        var actor = ActorContext.required();
        var normalizedReason = requireText(reason, "驳回原因");
        if (!governance.reject(actor.organizationId(), actor.userId(), documentId, versionId,
                reviewRevisionId, lockVersion, normalizedReason)) {
            optimisticConflict(documentId, versionId);
        }
        audit(actor.organizationId(), actor.userId(), "KB_DOCUMENT_REJECTED", documentId,
                objectMapper.createObjectNode().put("versionId", versionId.toString()).put("reason", normalizedReason));
    }

    @Transactional
    public KnowledgeService.DocumentView reparse(UUID documentId, UUID versionId, UUID reviewRevisionId,
                                                 Integer lockVersion) {
        var actor = ActorContext.required();
        var current = review(documentId, versionId);
        if (current.parseRun() != null && Set.of("QUEUED", "PROCESSING").contains(current.parseRun().status())) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "当前已有解析或索引任务执行中");
        }
        if (reviewRevisionId == null) {
            if (!governance.reserveReparseWithoutRevision(actor.organizationId(), actor.userId(), documentId, versionId)) {
                optimisticConflict(documentId, versionId);
            }
        } else {
            if (current.reviewRevision() == null || lockVersion == null
                    || !current.reviewRevision().id().equals(reviewRevisionId)
                    || current.reviewRevision().lockVersion() != lockVersion) optimisticConflict(documentId, versionId);
            if (!governance.reserveReparse(actor.organizationId(), actor.userId(), documentId, versionId,
                    reviewRevisionId, lockVersion)) {
                optimisticConflict(documentId, versionId);
            }
        }
        var result = knowledge.reindex(documentId, versionId);
        audit(actor.organizationId(), actor.userId(), "KB_DOCUMENT_REPARSE_REQUESTED", documentId,
                objectMapper.createObjectNode().put("versionId", versionId.toString()));
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
                objectMapper.createObjectNode().put("before", before.lifecycleStatus()).put("after", normalized)
                        .put("reason", reason == null ? "" : reason));
    }

    public List<BatchResult> batchMove(List<UUID> documentIds, UUID categoryId) {
        var actor = ActorContext.required();
        requireCategory(actor.organizationId(), categoryId, null);
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
        return batch(documentIds, id -> {
            var result = knowledge.updateAiGrant(id, new KnowledgeService.GrantCommand(action, reason));
            return objectMapper.createObjectNode().put("aiStatus", result.aiStatus());
        }, "KB_BATCH_AI_USAGE");
    }

    public List<KnowledgeGovernanceRepository.PublicationRow> publications(UUID documentId) {
        var actor = ActorContext.required();
        knowledge.get(documentId);
        return governance.publications(actor.organizationId(), documentId);
    }

    public KnowledgeGovernanceRepository.PublishedContentView publishedContent(UUID documentId, UUID publicationId) {
        var actor = ActorContext.required();
        knowledge.get(documentId);
        return governance.publishedContent(actor.organizationId(), documentId, publicationId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "已发布内容不存在"));
    }

    public KnowledgeGovernanceRepository.TableWindow reviewTable(UUID documentId, UUID versionId,
                                                                  UUID reviewRevisionId, UUID sourceTableId,
                                                                  int rowOffset, int rowLimit,
                                                                  int columnOffset, int columnLimit) {
        var actor = ActorContext.required();
        var review = review(documentId, versionId);
        if (review.reviewRevision() == null || !review.reviewRevision().id().equals(reviewRevisionId)) {
            optimisticConflict(documentId, versionId);
        }
        return governance.reviewTableWindow(actor.organizationId(), reviewRevisionId, sourceTableId,
                        rowOffset, rowLimit, columnOffset, columnLimit)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "工作表不存在"));
    }

    @Transactional
    public KnowledgeGovernanceRepository.TableWindow saveReviewTable(UUID documentId, UUID versionId,
                                                                      UUID reviewRevisionId, UUID sourceTableId,
                                                                      TableReviewCommand command) {
        var actor = ActorContext.required();
        if (!governance.saveTableReview(actor.organizationId(), actor.userId(), reviewRevisionId,
                command.lockVersion(), sourceTableId, command.patches(), command.rows())) {
            optimisticConflict(documentId, versionId);
        }
        return reviewTable(documentId, versionId, reviewRevisionId, sourceTableId,
                command.rowOffset(), command.rowLimit(), command.columnOffset(), command.columnLimit());
    }

    public KnowledgeGovernanceRepository.TableWindow publishedTable(UUID documentId, UUID publicationId,
                                                                     UUID sourceTableId, int rowOffset, int rowLimit,
                                                                     int columnOffset, int columnLimit) {
        var actor = ActorContext.required();
        knowledge.get(documentId);
        var publication = governance.publications(actor.organizationId(), documentId).stream()
                .filter(value -> value.id().equals(publicationId)).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "发布版本不存在"));
        return governance.publishedTableWindow(actor.organizationId(), publication.id(), sourceTableId,
                        rowOffset, rowLimit, columnOffset, columnLimit)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "工作表不存在"));
    }

    private KnowledgeGovernanceRepository.ReviewUpdate reviewUpdate(UUID documentId, UUID versionId,
                                                                    UUID reviewRevisionId, int lockVersion,
                                                                    UUID basePublicationId, String title, String scope,
                                                                    UUID categoryId, List<String> tags, JsonNode confirmedDocument,
                                                                    List<UUID> excludedReviewNodeIds,
                                                                    List<KnowledgeGovernanceRepository.IssueAction> issueActions) {
        var actor = ActorContext.required();
        return new KnowledgeGovernanceRepository.ReviewUpdate(documentId, versionId, reviewRevisionId, lockVersion,
                basePublicationId,
                normalizeTitle(title), scope, requireCategory(actor.organizationId(), categoryId, scope),
                normalizeTags(tags), documents.validate(confirmedDocument), safe(excludedReviewNodeIds),
                normalizeIssueActions(issueActions));
    }

    private void validatePublish(KnowledgeGovernanceRepository.ReviewView review) {
        var reasons = new ArrayList<String>();
        if (!"READY".equals(review.processingStatus())) reasons.add("解析未完成");
        if (review.categoryId() == null) reasons.add("分类为空");
        if (review.parseRun() == null || !"SUCCEEDED".equals(review.parseRun().status())
                || review.reviewRevision() == null || !"DRAFT".equals(review.reviewRevision().status())) {
            reasons.add("没有可发布的校对内容");
        }
        if (review.reviewRevision() == null || (!StringUtils.hasText(documents.project(
                review.reviewRevision().confirmedDocument(), review.reviewRevision().excludedReviewNodeIds()).confirmedText())
                && governance.largeTableRows(ActorContext.required().organizationId(),
                review.reviewRevision().id()).isEmpty())) {
            reasons.add("确认文本不能为空");
        }
        if (review.issues().stream().anyMatch(issue -> "BLOCKER".equals(issue.severity()) && "OPEN".equals(issue.status()))) {
            reasons.add("仍有阻断级解析问题未解决");
        }
        try (var ignored = storage.open(ActorContext.required().organizationId(),
                knowledgeRepository.findVersion(ActorContext.required().organizationId(), review.versionId())
                        .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "原文件版本不存在")).fileObjectId())) {
            // Opening the immutable source is the final publication gate.
        } catch (Exception exception) {
            reasons.add("原文件不可读取");
        }
        if (!reasons.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, String.join("；", reasons));
    }

    private void enqueueIndex(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                              UUID reviewRevisionId, int lockVersion) {
        var payload = objectMapper.createObjectNode().put("organizationId", organizationId.toString())
                .put("actorId", actorId.toString()).put("documentId", documentId.toString())
                .put("versionId", versionId.toString()).put("reviewRevisionId", reviewRevisionId.toString())
                .put("lockVersion", lockVersion);
        async.enqueue(organizationId, "KB_BUILD_KNOWLEDGE_INDEX", payload,
                "kb-index:" + reviewRevisionId, 50);
    }

    private List<BatchResult> batch(List<UUID> documentIds, BatchAction action, String auditAction) {
        var actor = ActorContext.required();
        if (documentIds == null || documentIds.isEmpty() || documentIds.size() > 200) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "批量操作文档数必须在 1 到 200 之间");
        }
        var result = new ArrayList<BatchResult>();
        for (var id : documentIds.stream().distinct().toList()) {
            try {
                itemTransaction.executeWithoutResult(ignored -> {
                    var detail = action.run(id);
                    audit(actor.organizationId(), actor.userId(), auditAction, id,
                            detail == null ? objectMapper.createObjectNode() : detail);
                });
                result.add(new BatchResult(id, true, null, null));
            } catch (ApiException exception) {
                result.add(new BatchResult(id, false, exception.errorCode().code(), exception.getMessage()));
            } catch (RuntimeException exception) {
                result.add(new BatchResult(id, false, ApiErrorCode.INTERNAL_ERROR.code(), "操作失败"));
            }
        }
        return result;
    }

    private List<KnowledgeGovernanceRepository.IssueAction> normalizeIssueActions(
            List<KnowledgeGovernanceRepository.IssueAction> actions) {
        return safe(actions).stream().map(action -> {
            var status = action.status() == null ? "OPEN" : action.status().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("OPEN", "RESOLVED", "IGNORED").contains(status)) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "无效问题处理状态");
            }
            return new KnowledgeGovernanceRepository.IssueAction(action.issueId(), status, action.resolution());
        }).toList();
    }

    private List<String> normalizeTags(List<String> tags) {
        var values = new LinkedHashSet<String>();
        for (var value : safe(tags)) {
            if (!StringUtils.hasText(value)) continue;
            var normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
            if (normalized.length() > 120) throw new ApiException(ApiErrorCode.BAD_REQUEST, "标签不能超过120个字符");
            values.add(normalized);
        }
        if (values.size() > 50) throw new ApiException(ApiErrorCode.BAD_REQUEST, "标签不能超过50个");
        return List.copyOf(values);
    }

    private UUID requireCategory(UUID organizationId, UUID categoryId, String scope) {
        if (categoryId == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "请选择分类");
        var category = knowledgeRepository.findCategory(organizationId, categoryId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识库分类不存在"));
        if (scope != null && !scope.equals(category.scope())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类与资料范围不匹配");
        }
        return category.id();
    }

    private String normalizeScope(String value) {
        var normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "INTERNAL";
        if (!Set.of("INTERNAL", "EXTERNAL").contains(normalized)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "资料范围只能是 INTERNAL 或 EXTERNAL");
        }
        return normalized;
    }
    private String normalizeResolution(String value) {
        if (!StringUtils.hasText(value)) return null;
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NEW_DOCUMENT", "NEW_VERSION").contains(normalized)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "无效上传处理方式");
        }
        return normalized;
    }
    private String normalizeTitle(String value) {
        var normalized = requireText(value, "文件名称");
        if (normalized.length() > 260) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件名称不能超过260个字符");
        return normalized;
    }
    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST, name + "不能为空");
        return value.trim();
    }
    private UUID requireFileId(UUID value) {
        if (value == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件不能为空");
        return value;
    }
    private void requireReviewIdentity(UUID documentId, UUID versionId, UUID bodyDocumentId, UUID bodyVersionId) {
        if ((bodyDocumentId != null && !documentId.equals(bodyDocumentId))
                || (bodyVersionId != null && !versionId.equals(bodyVersionId))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请求路径与审核对象不一致");
        }
    }
    private void optimisticConflict(UUID documentId, UUID versionId) {
        throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "内容已被其他用户修改，请刷新后重试：" + documentId + "/" + versionId);
    }
    private KnowledgeGovernanceRepository.DuplicateMatch withSimilarity(
            KnowledgeGovernanceRepository.DuplicateMatch match, double similarity) {
        return new KnowledgeGovernanceRepository.DuplicateMatch(match.documentId(), match.versionId(), match.versionNo(),
                match.title(), match.originalName(), match.sha256(), match.normalizedStem(), similarity,
                match.lifecycleStatus(), match.reviewStatus());
    }
    private void audit(UUID organizationId, UUID actorId, String action, UUID id, com.fasterxml.jackson.databind.JsonNode detail) {
        audit.append(organizationId, actorId, action, "KB_DOCUMENT", id, detail);
    }
    private void auditPreflight(UUID organizationId, UUID actorId, String action, UUID id,
                                com.fasterxml.jackson.databind.JsonNode detail) {
        itemTransaction.executeWithoutResult(ignored -> audit.append(organizationId, actorId, action, "KB_UPLOAD", id, detail));
    }
    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    @FunctionalInterface
    private interface BatchAction { com.fasterxml.jackson.databind.JsonNode run(UUID documentId); }

    public record PreflightCommand(UUID fileId, UUID categoryId) { }
    public record PreflightResult(String decision, UUID fileId, String originalName, String sha256,
                                  List<KnowledgeGovernanceRepository.DuplicateMatch> exactMatches,
                                  List<KnowledgeGovernanceRepository.DuplicateMatch> possibleVersions) { }
    public record CreateCommand(UUID fileId, String title, String libraryScope, UUID categoryId,
                                List<String> tags, String resolution, UUID targetDocumentId,
                                com.fasterxml.jackson.databind.JsonNode sourceInfo) { }
    public record ReviewCommand(UUID documentId, UUID versionId, UUID reviewRevisionId, int lockVersion,
                                UUID basePublicationId, String title, String libraryScope, UUID categoryId,
                                List<String> tags, JsonNode confirmedDocument,
                                List<UUID> excludedReviewNodeIds,
                                List<KnowledgeGovernanceRepository.IssueAction> issueActions) { }
    public record RevisionCommand(UUID basePublicationId) { }
    public record TableReviewCommand(int lockVersion, List<KnowledgeGovernanceRepository.CellPatch> patches,
                                     List<KnowledgeGovernanceRepository.RowState> rows,
                                     int rowOffset, int rowLimit, int columnOffset, int columnLimit) { }
    public record IndexBuildView(UUID documentId, UUID versionId, UUID reviewRevisionId, String status) { }
    public record BatchResult(UUID documentId, boolean success, String errorCode, String message) { }
}
