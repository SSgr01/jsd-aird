package com.jsd.aird.kb.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.core.api.ProjectResourceFacade.ResourceType;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.DocumentParsingFailure;
import com.jsd.aird.kb.domain.FileSafetyScanner;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import com.jsd.aird.kb.domain.MediaExtractionException;
import com.jsd.aird.kb.domain.OcrMode;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeService implements KnowledgeSearchFacade {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final KnowledgeRepository repository;
    private final KnowledgeGovernanceRepository governance;
    private final FileStorageFacade storage;
    private final OpsAsyncFacade async;
    private final AuditLogFacade audit;
    private final ObjectMapper objectMapper;
    private final StructuredDocumentCodec documents;
    private final List<DocumentParser> parsers;
    private final FileSafetyScanner scanner;
    private final ObjectProvider<KnowledgeEmbeddingFacade> embeddings;
    private final List<MediaExtractionProvider> mediaProviders;
    private final String embeddingModel;
    private final int embeddingDimension;
    private final Duration presignExpiry;
    private final BlockAwareChunker blockAwareChunker;
    private final LexicalAnalyzer lexicalAnalyzer;
    private final SystemParsingPolicy systemParsingPolicy;
    private final ProjectResourceFacade projectResources;
    private final Executor retrievalExecutor;

    @Autowired
    public KnowledgeService(
            KnowledgeRepository repository,
            KnowledgeGovernanceRepository governance,
            FileStorageFacade storage,
            OpsAsyncFacade async,
            AuditLogFacade audit,
            ObjectMapper objectMapper,
            StructuredDocumentCodec documents,
            List<DocumentParser> parsers,
            FileSafetyScanner scanner,
            ObjectProvider<KnowledgeEmbeddingFacade> embeddings,
            List<MediaExtractionProvider> mediaProviders,
            @org.springframework.beans.factory.annotation.Value("${app.ai.embedding.model:}") String embeddingModel,
            @org.springframework.beans.factory.annotation.Value("${app.ai.embedding.dimension:1024}") int embeddingDimension,
            @org.springframework.beans.factory.annotation.Value("${app.storage.presign-expiry:15m}") Duration presignExpiry,
            BlockAwareChunker blockAwareChunker,
            LexicalAnalyzer lexicalAnalyzer,
            SystemParsingPolicy systemParsingPolicy,
            @Qualifier("ragRetrievalExecutor") Executor retrievalExecutor,
            ProjectResourceFacade projectResources
    ) {
        this.repository = repository;
        this.governance = governance;
        this.storage = storage;
        this.async = async;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.documents = documents;
        this.parsers = List.copyOf(parsers);
        this.scanner = scanner;
        this.embeddings = embeddings;
        this.mediaProviders = List.copyOf(mediaProviders);
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
        this.presignExpiry = presignExpiry;
        this.blockAwareChunker = blockAwareChunker;
        this.lexicalAnalyzer = lexicalAnalyzer;
        this.systemParsingPolicy = systemParsingPolicy;
        this.retrievalExecutor = retrievalExecutor;
        this.projectResources = projectResources;
    }

    public KnowledgeService(
            KnowledgeRepository repository,
            KnowledgeGovernanceRepository governance,
            FileStorageFacade storage,
            OpsAsyncFacade async,
            AuditLogFacade audit,
            ObjectMapper objectMapper,
            StructuredDocumentCodec documents,
            List<DocumentParser> parsers,
            FileSafetyScanner scanner,
            ObjectProvider<KnowledgeEmbeddingFacade> embeddings,
            List<MediaExtractionProvider> mediaProviders,
            String embeddingModel,
            int embeddingDimension,
            Duration presignExpiry,
            BlockAwareChunker blockAwareChunker,
            LexicalAnalyzer lexicalAnalyzer
    ) {
        this(repository, governance, storage, async, audit, objectMapper, documents, parsers, scanner, embeddings,
                mediaProviders, embeddingModel, embeddingDimension, presignExpiry, blockAwareChunker,
                lexicalAnalyzer, new SystemParsingPolicy("AUTO", false), Runnable::run, null);
    }

    KnowledgeService(
            KnowledgeRepository repository, KnowledgeGovernanceRepository governance, FileStorageFacade storage,
            OpsAsyncFacade async, AuditLogFacade audit, ObjectMapper objectMapper, StructuredDocumentCodec documents,
            List<DocumentParser> parsers, FileSafetyScanner scanner,
            ObjectProvider<KnowledgeEmbeddingFacade> embeddings, List<MediaExtractionProvider> mediaProviders,
            String embeddingModel, int embeddingDimension, Duration presignExpiry,
            BlockAwareChunker blockAwareChunker, LexicalAnalyzer lexicalAnalyzer, Executor retrievalExecutor) {
        this(repository, governance, storage, async, audit, objectMapper, documents, parsers, scanner, embeddings,
                mediaProviders, embeddingModel, embeddingDimension, presignExpiry, blockAwareChunker,
                lexicalAnalyzer, new SystemParsingPolicy("AUTO", false), retrievalExecutor, null);
    }

    @Transactional
    public DocumentView create(CreateCommand command) {
        var actor = ActorContext.required();
        var file = storage.open(actor.organizationId(), command.fileId());
        try {
            var duplicate = governance.exactMatches(actor.organizationId(), file.sha256());
            if (!duplicate.isEmpty()) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                        "相同文件已存在：" + duplicate.getFirst().title() + " V" + duplicate.getFirst().versionNo());
            }
            var title = StringUtils.hasText(command.title()) ? command.title().trim() : file.originalName();
            var scope = normalizeScope(command.libraryScope());
            var categoryId = command.categoryId();
            if (categoryId == null) {
                categoryId = repository.findDefaultCategory(actor.organizationId(), scope)
                        .map(KnowledgeRepository.CategoryRow::id).orElse(null);
            } else {
                var category = repository.findCategory(actor.organizationId(), categoryId)
                        .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识库分类不存在"));
                if (!scope.equals(category.scope())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类与资料范围不匹配");
            }
            var documentId = UUID.randomUUID();
            var versionId = UUID.randomUUID();
            var parsePolicy = systemParsingPolicy.forFile(file.originalName(), file.contentType());
            repository.insertDocument(new KnowledgeRepository.NewDocument(
                    documentId, actor.organizationId(), title, actor.userId(), scope, categoryId
            ));
            try {
                repository.insertVersion(new KnowledgeRepository.NewVersion(
                        versionId, documentId, 1, command.fileId(), file.originalName(), file.contentType(), file.size(), file.sha256()
                        , parsePolicy.ocrMode().name(), parsePolicy.allowAgentFallback()
                ));
            } catch (DataIntegrityViolationException exception) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "相同文件已被其他上传请求创建");
            }
            var payload = objectMapper.createObjectNode()
                    .put("organizationId", actor.organizationId().toString())
                    .put("documentId", documentId.toString())
                    .put("versionId", versionId.toString())
                    .put("actorId", actor.userId().toString())
                    .put("fileId", command.fileId().toString());
            async.enqueue(actor.organizationId(), "KB_INGEST_DOCUMENT", payload,
                    "kb-ingest:" + versionId, 40);
            async.appendOutbox("FILE_OBJECT", command.fileId(), "FILE_ACTIVATION_REQUESTED", payload);
            audit.append(actor.organizationId(), actor.userId(), "KB_DOCUMENT_CREATED", "KB_DOCUMENT", documentId,
                    objectMapper.createObjectNode().put("fileId", command.fileId().toString()));
            return get(documentId);
        } finally {
            closeQuietly(file);
        }
    }

    public PageResponse<DocumentView> list(String keyword, String status, String scope, UUID categoryId,
                                           String lifecycleStatus, String reviewStatus, UUID projectId,
                                           int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        var allowed = projectId == null || projectResources == null ? null
                : projectResources.resourceIdsForProject(actor, ResourceType.KNOWLEDGE_DOCUMENT, projectId);
        var items = repository.listDocuments(actor.organizationId(), keyword, status, scope, categoryId,
                        lifecycleStatus, reviewStatus, allowed, safePage, safeSize)
                .stream().map(this::view).toList();
        var total = repository.countDocuments(actor.organizationId(), keyword, status, scope, categoryId,
                lifecycleStatus, reviewStatus, allowed);
        items = withProjects(actor, items);
        return new PageResponse<>(items, safePage, safeSize, total, (total + safeSize - 1) / safeSize);
    }

    public PageResponse<DocumentView> list(String keyword, String status, String scope, UUID categoryId,
                                           String lifecycleStatus, String reviewStatus, int page, int size) {
        return list(keyword, status, scope, categoryId, lifecycleStatus, reviewStatus, null, page, size);
    }

    public PageResponse<DocumentView> list(String keyword, String status, String scope, UUID categoryId,
                                           int page, int size) {
        return list(keyword, status, scope, categoryId, null, null, page, size);
    }

    public List<KnowledgeRepository.CategoryRow> categories(String scope) {
        return repository.listCategories(ActorContext.required().organizationId(), scope);
    }

    @Transactional
    public KnowledgeRepository.CategoryRow createCategory(String scope, String name, String description) {
        var actor = ActorContext.required();
        return repository.createCategory(actor.organizationId(), actor.userId(), normalizeScope(scope), normalizeName(name), normalizeDescription(description));
    }

    @Transactional
    public KnowledgeRepository.CategoryRow renameCategory(UUID categoryId, String name, String description) {
        var actor = ActorContext.required();
        requireCategory(actor.organizationId(), categoryId);
        return repository.renameCategory(actor.organizationId(), categoryId, normalizeName(name), normalizeDescription(description));
    }

    @Transactional
    public void deleteCategory(UUID categoryId, UUID replacementCategoryId) {
        var actor = ActorContext.required();
        var category = requireCategory(actor.organizationId(), categoryId);
        if (replacementCategoryId != null) {
            var replacement = requireCategory(actor.organizationId(), replacementCategoryId);
            if (!category.scope().equals(replacement.scope())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "替代分类必须属于相同资料范围");
            }
        }
        repository.deleteCategory(actor.organizationId(), categoryId, replacementCategoryId);
    }

    @Transactional
    public void assignCategory(UUID documentId, UUID categoryId) {
        var actor = ActorContext.required();
        var document = repository.findDocument(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var category = requireCategory(actor.organizationId(), categoryId);
        if (!document.libraryScope().equals(category.scope())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类与资料范围不匹配");
        }
        repository.assignCategory(actor.organizationId(), documentId, categoryId);
    }

    @Transactional
    public DocumentView renameDocument(UUID documentId, String title) {
        var actor = ActorContext.required();
        requireDocument(actor.organizationId(), documentId);
        repository.renameDocument(actor.organizationId(), documentId, normalizeDocumentTitle(title));
        audit.append(actor.organizationId(), actor.userId(), "KB_DOCUMENT_RENAMED", "KB_DOCUMENT", documentId,
                objectMapper.createObjectNode().put("title", normalizeDocumentTitle(title)));
        return get(documentId);
    }

    @Transactional
    public void deleteDocument(UUID documentId) {
        var actor = ActorContext.required();
        requireDocument(actor.organizationId(), documentId);
        if (governance.hasPublication(actor.organizationId(), documentId)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "已发布文档不可物理删除，请使用停用");
        }
        repository.deleteDocument(actor.organizationId(), documentId);
        audit.append(actor.organizationId(), actor.userId(), "KB_DOCUMENT_DELETED", "KB_DOCUMENT", documentId,
                objectMapper.createObjectNode());
    }

    public DocumentView get(UUID documentId) {
        var actor = ActorContext.required();
        return repository.findDocument(actor.organizationId(), documentId)
                .map(this::view)
                .map(item -> withProjects(actor, List.of(item)).getFirst())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
    }

    public ProcessingView processing(UUID documentId) {
        var actor = ActorContext.required();
        var document = repository.findDocument(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var review = governance.review(actor.organizationId(), documentId, document.currentVersionId()).orElse(null);
        var run = review == null ? null : review.parseRun();
        var job = async.findLatestJob(actor.organizationId(), "kb-reindex:" + document.currentVersionId())
                .or(() -> async.findJob(actor.organizationId(), "kb-ingest:" + document.currentVersionId()))
                .orElse(null);
        return new ProcessingView(document.id(), document.currentVersionId(), document.status(), safeUserError(document.parseError()),
                run == null ? null : run.id(), run == null ? null : run.status(),
                run == null ? null : safeUserError(run.errorMessage()), run == null ? null : run.createdAt(),
                job == null ? null : job.id(), job == null ? null : job.status(), job == null ? 0 : job.progress(),
                job == null ? null : job.currentStage(), job == null ? 0 : job.attemptCount(),
                job == null ? 0 : job.maxAttempts(), job == null ? null : job.nextAttemptAt(),
                job != null && job.terminal(), job == null ? null : safeUserError(job.lastError()));
    }

    public List<VersionView> versions(UUID documentId) {
        var actor = ActorContext.required();
        if (repository.findDocument(actor.organizationId(), documentId).isEmpty()) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在");
        }
        return repository.listVersions(actor.organizationId(), documentId).stream().map(this::versionView).toList();
    }

    @Transactional
    public DocumentView createVersion(UUID documentId, CreateVersionCommand command) {
        var actor = ActorContext.required();
        var document = repository.findDocument(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var file = storage.open(actor.organizationId(), command.fileId());
        try {
            var duplicate = governance.exactMatches(actor.organizationId(), file.sha256());
            if (!duplicate.isEmpty()) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                        "相同文件已存在：" + duplicate.getFirst().title() + " V" + duplicate.getFirst().versionNo());
            }
            var versionId = UUID.randomUUID();
            var versionNo = document.currentVersionNo() + 1;
            var parsePolicy = systemParsingPolicy.forFile(file.originalName(), file.contentType());
            try {
                repository.insertVersion(new KnowledgeRepository.NewVersion(
                        versionId, documentId, versionNo, command.fileId(), file.originalName(), file.contentType(),
                        file.size(), file.sha256(), parsePolicy.ocrMode().name(), parsePolicy.allowAgentFallback()
                ));
            } catch (DataIntegrityViolationException exception) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "文件内容或版本号已被其他上传请求占用，请刷新后重试");
            }
            repository.updateCurrentVersion(actor.organizationId(), documentId, versionNo);
            var payload = objectMapper.createObjectNode()
                    .put("organizationId", actor.organizationId().toString())
                    .put("documentId", documentId.toString())
                    .put("versionId", versionId.toString())
                    .put("actorId", actor.userId().toString())
                    .put("fileId", command.fileId().toString());
            async.enqueue(actor.organizationId(), "KB_INGEST_DOCUMENT", payload,
                    "kb-ingest:" + versionId, 40);
            async.appendOutbox("FILE_OBJECT", command.fileId(), "FILE_ACTIVATION_REQUESTED", payload);
            audit.append(actor.organizationId(), actor.userId(), "KB_DOCUMENT_VERSION_CREATED", "KB_DOCUMENT", documentId,
                    objectMapper.createObjectNode().put("versionNo", versionNo).put("fileId", command.fileId().toString()));
            return get(documentId);
        } finally {
            closeQuietly(file);
        }
    }

    @Transactional
    public DocumentView updateAiGrant(UUID documentId, GrantCommand command) {
        var actor = ActorContext.required();
        requireDocument(actor.organizationId(), documentId);
        var publication = governance.currentPublication(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.AI_DOCUMENT_NOT_APPROVED, "文件尚未审核发布"));
        var action = command.action() == null ? "" : command.action().trim().toUpperCase(Locale.ROOT);
        switch (action) {
            case "APPROVE", "REJECT", "REVOKE" -> { }
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "AI 授权动作只能是 APPROVE、REJECT 或 REVOKE");
        }
        if (!governance.updateAiUsage(actor.organizationId(), actor.userId(), documentId, action, command.reason())) {
            throw new ApiException(ApiErrorCode.AI_DOCUMENT_NOT_APPROVED, "当前文档不可授权 AI 使用");
        }
        if ("APPROVE".equals(action)) {
            enqueueVectorBuild(actor.organizationId(), documentId, publication.id(), publication.reviewRevisionId());
        } else {
            repository.cancelPendingVectorJobs(actor.organizationId(), documentId);
            repository.clearDocumentEmbeddings(actor.organizationId(), documentId);
        }
        audit.append(actor.organizationId(), actor.userId(), "KB_AI_GRANT_" + action, "KB_DOCUMENT", documentId,
                objectMapper.createObjectNode().put("reason", command.reason() == null ? "" : command.reason()));
        return get(documentId);
    }

    @Transactional
    public DocumentView reindex(UUID documentId, UUID versionId) {
        var actor = ActorContext.required();
        var document = repository.findDocument(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var version = repository.findVersion(actor.organizationId(), versionId)
                .filter(item -> documentId.equals(item.documentId()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在"));
        var payload = objectMapper.createObjectNode()
                .put("organizationId", actor.organizationId().toString())
                .put("documentId", document.id().toString())
                .put("versionId", version.id().toString())
                .put("actorId", actor.userId().toString())
                .put("fileId", version.fileObjectId().toString());
        async.enqueue(actor.organizationId(), "KB_INGEST_DOCUMENT", payload,
                "kb-reindex:" + version.id() + ":" + System.currentTimeMillis(), 40);
        return get(documentId);
    }

    public FileStorageFacade.StoredFile openContent(UUID documentId) {
        var actor = ActorContext.required();
        var document = repository.findDocument(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var versionId = governance.currentPublication(actor.organizationId(), documentId)
                .map(KnowledgeGovernanceRepository.PublicationRow::versionId)
                .orElse(document.currentVersionId());
        var version = repository.findVersion(actor.organizationId(), versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在"));
        audit.append(actor.organizationId(), actor.userId(), "KB_DOCUMENT_DOWNLOAD", "KB_DOCUMENT", documentId,
                objectMapper.createObjectNode().put("versionId", version.id().toString()));
        return storage.open(actor.organizationId(), version.fileObjectId());
    }

    public FileStorageFacade.StoredFile openVersionContent(UUID documentId, UUID versionId) {
        var actor = ActorContext.required();
        repository.findDocument(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var version = repository.findVersion(actor.organizationId(), versionId)
                .filter(item -> documentId.equals(item.documentId()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在"));
        audit.append(actor.organizationId(), actor.userId(), "KB_DOCUMENT_DOWNLOAD", "KB_DOCUMENT", documentId,
                objectMapper.createObjectNode().put("versionId", version.id().toString()));
        return storage.open(actor.organizationId(), version.fileObjectId());
    }

    public byte[] exportDocuments(List<UUID> documentIds) {
        var actor = ActorContext.required();
        if (documentIds == null || documentIds.isEmpty() || documentIds.size() > 200) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "一次最多导出 200 个知识文件");
        }
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            var manifest = new StringBuilder("documentId,title,originalName,versionNo,versionId,sha256\n");
            var usedNames = new java.util.HashSet<String>();
            usedNames.add("manifest.csv");
            for (var documentId : documentIds.stream().distinct().toList()) {
                var document = repository.findDocument(actor.organizationId(), documentId)
                        .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在：" + documentId));
                var version = repository.findVersion(actor.organizationId(), document.currentVersionId())
                        .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在：" + document.currentVersionId()));
                var baseName = sanitizeExportName(document.title() + "_V" + version.versionNo() + "_" + version.originalName());
                var entryName = uniqueExportName(baseName, usedNames);
                try (var stored = storage.open(actor.organizationId(), version.fileObjectId())) {
                    zip.putNextEntry(new ZipEntry(entryName));
                    stored.stream().transferTo(zip);
                    zip.closeEntry();
                }
                manifest.append(csv(document.id().toString())).append(',')
                        .append(csv(document.title())).append(',')
                        .append(csv(version.originalName())).append(',')
                        .append(version.versionNo()).append(',')
                        .append(csv(version.id().toString())).append(',')
                        .append(csv(version.sha256())).append('\n');
            }
            zip.putNextEntry(new ZipEntry("manifest.csv"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("知识文件导出失败", exception);
        }
    }

    private String sanitizeExportName(String value) {
        var normalized = value == null ? "document" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        normalized = normalized.replaceAll("^\\.+", "");
        return normalized.isBlank() ? "document" : normalized.substring(0, Math.min(180, normalized.length()));
    }

    private String uniqueExportName(String requested, java.util.Set<String> usedNames) {
        var candidate = requested;
        var suffix = 2;
        while (!usedNames.add(candidate)) {
            var dot = requested.lastIndexOf('.');
            var stem = dot > 0 ? requested.substring(0, dot) : requested;
            var extension = dot > 0 ? requested.substring(dot) : "";
            candidate = stem + "_" + suffix++ + extension;
        }
        return candidate;
    }

    private String csv(String value) {
        var safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public List<KnowledgeSearchFacade.SearchHit> search(UUID organizationId, String query, boolean aiOnly, int limit) {
        return search(new KnowledgeSearchFacade.SearchRequest(organizationId, query, aiOnly, limit,
                List.of(), List.of())).hits();
    }

    @Override
    public KnowledgeSearchFacade.SearchResult search(KnowledgeSearchFacade.SearchRequest request) {
        if (!StringUtils.hasText(request.query())) {
            return new KnowledgeSearchFacade.SearchResult(List.of(),
                    new KnowledgeSearchFacade.RetrievalTrace("BM25_VECTOR_RRF", 0, 0, 0, List.of("EMPTY_QUERY")));
        }
        var organizationId = request.organizationId();
        var safeLimit = Math.min(60, Math.max(1, request.limit()));
        var variants = searchVariants(request);
        var phrases = searchPhrases(request);
        var vectorQueries = vectorQueries(request);
        CompletableFuture<ChannelResult> bm25Future;
        CompletableFuture<ChannelResult> vectorFuture;
        try {
            bm25Future = CompletableFuture.supplyAsync(
                    () -> bm25Channel(organizationId, variants, request.aiOnly(), request.categoryIds()), retrievalExecutor);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            bm25Future = CompletableFuture.completedFuture(new ChannelResult(List.of(), List.of(),
                    List.of("BM25_REJECTED"), Map.of("lexicalAnalyzeMs", 0L, "bm25DbMs", 0L)));
        }
        try {
            vectorFuture = CompletableFuture.supplyAsync(
                    () -> vectorChannel(organizationId, vectorQueries, request.aiOnly(), request.categoryIds()), retrievalExecutor);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            vectorFuture = CompletableFuture.completedFuture(new ChannelResult(List.of(), List.of(),
                    List.of("VECTOR_REJECTED"), Map.of("embeddingMs", 0L, "vectorDbMs", 0L)));
        }
        CompletableFuture.allOf(bm25Future, vectorFuture).join();
        var bm25 = bm25Future.join();
        var vector = vectorFuture.join();

        var fallbacks = new ArrayList<String>();
        fallbacks.addAll(bm25.fallbacks());
        fallbacks.addAll(vector.fallbacks());
        var queryTraces = new ArrayList<KnowledgeSearchFacade.QueryTrace>();
        queryTraces.addAll(bm25.traces());
        queryTraces.addAll(vector.traces());
        var timings = new LinkedHashMap<String, Long>();
        timings.putAll(bm25.timings());
        timings.putAll(vector.timings());
        var scores = new LinkedHashMap<UUID, Double>();
        var retrievalScores = new LinkedHashMap<UUID, Double>();
        var queryCandidates = new LinkedHashMap<String, Set<UUID>>();
        var bm25Ids = new java.util.LinkedHashSet<UUID>();
        var vectorIds = new java.util.LinkedHashSet<UUID>();
        var phraseIds = new java.util.LinkedHashSet<UUID>();
        mergeRanked(bm25.ranks(), variants, 1.0, scores, retrievalScores, bm25Ids, queryCandidates);
        mergeRanked(vector.ranks(), vectorQueries, 1.0, scores, retrievalScores, vectorIds, queryCandidates);

        var fallbackRows = new LinkedHashMap<UUID, KnowledgeRepository.SearchRow>();
        if (bm25.ranks().isEmpty()) {
            fallbacks.add("BM25_EMPTY");
            var fallbackPhrases = phrases.isEmpty() && StringUtils.hasText(request.originalQuery())
                    ? List.of(request.originalQuery().strip()) : phrases;
            if (!fallbackPhrases.isEmpty()) {
                var started = System.nanoTime();
                try {
                    var channel = phrases.isEmpty()
                            ? repository.fullTextSearch(organizationId, fallbackPhrases.getFirst(), request.aiOnly(),
                                    request.categoryIds(), 48)
                            : repository.phraseSearch(organizationId, fallbackPhrases, request.aiOnly(),
                                    request.categoryIds(), 48);
                    mergeRows(channel, 1.2, scores, retrievalScores, fallbackRows);
                    channel.forEach(row -> phraseIds.add(row.chunkId()));
                    queryTraces.add(queryTrace(String.join(" | ", fallbackPhrases), "PHRASE_TRGM", "SUCCEEDED",
                            channel, started, ""));
                    timings.put("phraseDbMs", elapsedMs(started));
                } catch (RuntimeException exception) {
                    fallbacks.add("PHRASE_ERROR");
                    queryTraces.add(queryTrace(String.join(" | ", fallbackPhrases), "PHRASE_TRGM", "FAILED",
                            List.of(), started, exception.getMessage()));
                    timings.put("phraseDbMs", elapsedMs(started));
                }
            }
        }

        var rankedIds = scores.keySet().stream()
                .sorted((a, b) -> Double.compare(scores.getOrDefault(b, 0.0), scores.getOrDefault(a, 0.0)))
                .limit(60)
                .toList();
        var hydrationStarted = System.nanoTime();
        var rows = new LinkedHashMap<UUID, KnowledgeRepository.SearchRow>();
        try {
            repository.loadSearchRows(organizationId, rankedIds).forEach(row -> rows.put(row.chunkId(), row));
        } catch (RuntimeException exception) {
            fallbacks.add("CHUNK_HYDRATION_ERROR");
            log.warn("Chunk hydration failed", exception);
        }
        fallbackRows.forEach(rows::putIfAbsent);
        timings.put("chunkHydrationMs", elapsedMs(hydrationStarted));
        var hits = rankedIds.stream()
                .limit(safeLimit)
                .map(rows::get).filter(java.util.Objects::nonNull)
                .map(row -> toSearchHit(organizationId, row, retrievalScores.getOrDefault(row.chunkId(), 0.0),
                        scores.getOrDefault(row.chunkId(), 0.0)))
                .toList();
        var factCandidates = request.requiredFacts().stream().map(fact -> {
            var ids = queryCandidates.getOrDefault(fact.retrievalQuery(), Set.of()).stream()
                    .sorted((left, right) -> Double.compare(scores.getOrDefault(right, 0d), scores.getOrDefault(left, 0d)))
                    .toList();
            return new KnowledgeSearchFacade.FactCandidateSet(fact.label(), fact.retrievalQuery(), ids);
        }).toList();
        var fallbackSnapshot = fallbacks.stream().distinct().toList();
        var traceSnapshot = queryTraces.stream().sorted(java.util.Comparator
                    .comparing(KnowledgeSearchFacade.QueryTrace::channel)
                    .thenComparing(KnowledgeSearchFacade.QueryTrace::query)).toList();
        return new KnowledgeSearchFacade.SearchResult(hits,
                new KnowledgeSearchFacade.RetrievalTrace("DYNAMIC_BM25_VECTOR_PHRASE_RRF", bm25Ids.size(),
                        vectorIds.size(), rankedIds.size(), phraseIds.size(), fallbackSnapshot, traceSnapshot,
                        variants.size(), timings), factCandidates);
    }

    private ChannelResult bm25Channel(UUID organizationId, List<String> variants, boolean aiOnly,
                                      List<UUID> categoryIds) {
        var analyzeStarted = System.nanoTime();
        var queries = new ArrayList<KnowledgeRepository.AnalyzedQuery>();
        try {
            for (var ordinal = 0; ordinal < variants.size(); ordinal++) {
                var terms = lexicalAnalyzer.analyzeCompatibleQuery(variants.get(ordinal)).stream()
                        .flatMap(family -> family.alternatives().stream().map(term ->
                                new KnowledgeRepository.AnalyzedTerm(family.ordinal(), term.analyzerVersion(), term.term())))
                        .distinct().toList();
                if (!terms.isEmpty()) queries.add(new KnowledgeRepository.AnalyzedQuery(ordinal, variants.get(ordinal), terms));
            }
        } catch (RuntimeException exception) {
            var analyzeMs = elapsedMs(analyzeStarted);
            log.warn("Lexical query analysis failed; continuing with vector retrieval", exception);
            return new ChannelResult(List.of(), rankTraces("BM25", variants, List.of(), analyzeMs, "FAILED"),
                    List.of("BM25_ANALYSIS_ERROR"), Map.of("lexicalAnalyzeMs", analyzeMs, "bm25DbMs", 0L));
        }
        var analyzeMs = elapsedMs(analyzeStarted);
        var dbStarted = System.nanoTime();
        try {
            var ranks = repository.batchBm25Rank(organizationId, queries, aiOnly, categoryIds, 48);
            var dbMs = elapsedMs(dbStarted);
            return new ChannelResult(ranks, rankTraces("BM25", variants, ranks, dbMs, "SUCCEEDED"), List.of(),
                    Map.of("lexicalAnalyzeMs", analyzeMs, "bm25DbMs", dbMs));
        } catch (RuntimeException exception) {
            var dbMs = elapsedMs(dbStarted);
            log.warn("Batch BM25 search failed; continuing with other channels", exception);
            return new ChannelResult(List.of(), rankTraces("BM25", variants, List.of(), dbMs, "FAILED"),
                    List.of("BM25_ERROR"), Map.of("lexicalAnalyzeMs", analyzeMs, "bm25DbMs", dbMs));
        }
    }

    private ChannelResult vectorChannel(UUID organizationId, List<String> queryTexts, boolean aiOnly,
                                        List<UUID> categoryIds) {
        var embeddingStarted = System.nanoTime();
        List<java.util.Optional<String>> vectors;
        try {
            var embedding = embeddings.getIfAvailable();
            vectors = embedding == null ? List.of() : embedding.embedVectors(queryTexts);
            if (vectors == null) vectors = List.of();
        } catch (RuntimeException exception) {
            var embeddingMs = elapsedMs(embeddingStarted);
            log.warn("Query embedding failed; continuing with lexical retrieval", exception);
            return new ChannelResult(List.of(), List.of(new KnowledgeSearchFacade.QueryTrace(
                    String.join(" | ", queryTexts), "EMBEDDING_BATCH", "FAILED", 0, embeddingMs,
                    safeTraceError(exception), List.of())), List.of("EMBEDDING_ERROR"),
                    Map.of("embeddingMs", embeddingMs, "vectorDbMs", 0L));
        }
        var embeddingMs = elapsedMs(embeddingStarted);
        var traces = new ArrayList<KnowledgeSearchFacade.QueryTrace>();
        traces.add(new KnowledgeSearchFacade.QueryTrace(String.join(" | ", queryTexts), "EMBEDDING_BATCH",
                vectors.isEmpty() ? "UNAVAILABLE" : "SUCCEEDED", vectors.size(), embeddingMs, "", List.of()));
        var vectorQueries = new ArrayList<KnowledgeRepository.VectorQuery>();
        for (var ordinal = 0; ordinal < Math.min(queryTexts.size(), vectors.size()); ordinal++) {
            var vector = vectors.get(ordinal);
            if (vector.isPresent()) vectorQueries.add(new KnowledgeRepository.VectorQuery(ordinal,
                    queryTexts.get(ordinal), vector.get()));
        }
        var fallbacks = new ArrayList<String>();
        if (vectors.isEmpty() || vectors.stream().allMatch(java.util.Optional::isEmpty)) {
            fallbacks.add("EMBEDDING_UNAVAILABLE");
        } else if (vectors.stream().anyMatch(java.util.Optional::isEmpty)) {
            fallbacks.add("EMBEDDING_PARTIAL");
        }
        var dbStarted = System.nanoTime();
        try {
            var ranks = repository.batchVectorRank(organizationId, vectorQueries, aiOnly, categoryIds, 48,
                    embeddingDimension);
            var dbMs = elapsedMs(dbStarted);
            traces.addAll(rankTraces("VECTOR", queryTexts, ranks, dbMs, "SUCCEEDED"));
            return new ChannelResult(ranks, List.copyOf(traces), List.copyOf(fallbacks),
                    Map.of("embeddingMs", embeddingMs, "vectorDbMs", dbMs));
        } catch (RuntimeException exception) {
            var dbMs = elapsedMs(dbStarted);
            fallbacks.add("VECTOR_ERROR");
            traces.addAll(rankTraces("VECTOR", queryTexts, List.of(), dbMs, "FAILED"));
            log.warn("Batch vector search failed; continuing with lexical retrieval", exception);
            return new ChannelResult(List.of(), List.copyOf(traces), List.copyOf(fallbacks),
                    Map.of("embeddingMs", embeddingMs, "vectorDbMs", dbMs));
        }
    }

    private List<String> searchVariants(KnowledgeSearchFacade.SearchRequest request) {
        var values = new java.util.LinkedHashSet<String>();
        addQuery(values, request.originalQuery());
        addQuery(values, request.query());
        request.queryVariants().forEach(value -> addQuery(values, value));
        request.requiredFacts().forEach(value -> addQuery(values, value.retrievalQuery()));
        var dynamicTerms = searchPhrases(request);
        if (!dynamicTerms.isEmpty()) addQuery(values, String.join(" ", dynamicTerms));
        return values.stream().limit(16).toList();
    }

    private List<String> searchPhrases(KnowledgeSearchFacade.SearchRequest request) {
        var values = new java.util.LinkedHashSet<String>();
        for (var term : request.retrievalTerms()) {
            addQuery(values, term.text());
            term.aliases().forEach(value -> addQuery(values, value));
        }
        return values.stream().limit(48).toList();
    }

    private List<String> vectorQueries(KnowledgeSearchFacade.SearchRequest request) {
        var values = new java.util.LinkedHashSet<String>();
        addQuery(values, request.originalQuery());
        addQuery(values, request.query());
        request.requiredFacts().forEach(value -> addQuery(values, value.retrievalQuery()));
        return values.stream().limit(5).toList();
    }

    private void addQuery(java.util.LinkedHashSet<String> values, String value) {
        if (StringUtils.hasText(value)) values.add(value.strip());
    }

    private void mergeRanked(List<KnowledgeRepository.RankedChunk> channel, List<String> queries, double weight,
                             Map<UUID, Double> scores, Map<UUID, Double> retrievalScores,
                             Set<UUID> channelIds, Map<String, Set<UUID>> queryCandidates) {
        if (channel == null) return;
        for (var item : channel) {
            channelIds.add(item.chunkId());
            retrievalScores.merge(item.chunkId(), item.score(), Math::max);
            scores.merge(item.chunkId(), weight / (60 + Math.max(1, item.rank())), Double::sum);
            if (item.queryOrdinal() >= 0 && item.queryOrdinal() < queries.size()) {
                queryCandidates.computeIfAbsent(queries.get(item.queryOrdinal()), ignored -> new java.util.LinkedHashSet<>())
                        .add(item.chunkId());
            }
        }
    }

    private void mergeRows(List<KnowledgeRepository.SearchRow> channel, double weight,
                           Map<UUID, Double> scores, Map<UUID, Double> retrievalScores,
                           Map<UUID, KnowledgeRepository.SearchRow> rows) {
        if (channel == null) return;
        for (var index = 0; index < channel.size(); index++) {
            var row = channel.get(index);
            rows.putIfAbsent(row.chunkId(), row);
            retrievalScores.merge(row.chunkId(), row.score(), Math::max);
            scores.merge(row.chunkId(), weight / (60 + index + 1), Double::sum);
        }
    }

    private List<KnowledgeSearchFacade.QueryTrace> rankTraces(String channel, List<String> queries,
                                                               List<KnowledgeRepository.RankedChunk> ranks,
                                                               long elapsedMs, String status) {
        var grouped = new LinkedHashMap<Integer, List<KnowledgeRepository.RankedChunk>>();
        if (ranks != null) {
            ranks.forEach(rank -> grouped.computeIfAbsent(rank.queryOrdinal(), ignored -> new ArrayList<>()).add(rank));
        }
        var result = new ArrayList<KnowledgeSearchFacade.QueryTrace>();
        for (var ordinal = 0; ordinal < queries.size(); ordinal++) {
            var rows = grouped.getOrDefault(ordinal, List.of()).stream()
                    .sorted(java.util.Comparator.comparingInt(KnowledgeRepository.RankedChunk::rank)).toList();
            result.add(new KnowledgeSearchFacade.QueryTrace(queries.get(ordinal), channel, status, rows.size(),
                    elapsedMs, "", rows.stream().limit(10).map(KnowledgeRepository.RankedChunk::chunkId).toList()));
        }
        return List.copyOf(result);
    }

    private KnowledgeSearchFacade.QueryTrace queryTrace(String query, String channel, String status,
                                                        List<KnowledgeRepository.SearchRow> rows,
                                                        long started, String error) {
        var safeRows = rows == null ? List.<KnowledgeRepository.SearchRow>of() : rows;
        var safeError = error == null ? "" : error;
        if (safeError.length() > 300) safeError = safeError.substring(0, 300);
        return new KnowledgeSearchFacade.QueryTrace(query, channel, status, safeRows.size(), elapsedMs(started), safeError,
                safeRows.stream().limit(10).map(KnowledgeRepository.SearchRow::chunkId).toList());
    }

    private String safeTraceError(Exception exception) {
        var value = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return value.substring(0, Math.min(300, value.length()));
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    public void markIndexStale(UUID organizationId, UUID documentId, UUID versionId, UUID reviewRevisionId) {
        for (var step : List.of("CHUNK", "BM25_INDEX", "VECTOR_INDEX")) {
            repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId, step,
                    "knowledge-index", lexicalAnalyzer.version(), null);
            repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, step,
                    "VECTOR_INDEX".equals(step) && !repository.isAiApproved(organizationId, documentId)
                            ? "NOT_REQUIRED" : "STALE", null, null);
        }
    }

    @Transactional
    public KnowledgeGovernanceRepository.PublicationRow buildAndPublish(UUID organizationId, UUID actorId,
                                                                         UUID documentId, UUID versionId,
                                                                         UUID reviewRevisionId, int expectedLockVersion) {
        var review = governance.review(organizationId, documentId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "待发布内容不存在"));
        if (review.reviewRevision() == null || !reviewRevisionId.equals(review.reviewRevision().id())
                || !"BUILDING".equals(review.reviewRevision().status())) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "待发布内容已被新的修订替代");
        }
        var prepared = prepareIndex(organizationId, documentId, versionId, reviewRevisionId, review.title(),
                review.reviewRevision().confirmedDocument(), review.reviewRevision().excludedReviewNodeIds(),
                review.sourceNodes());

        repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId,
                "CHUNK", "block-aware-chunker", lexicalAnalyzer.version(), prepared.textHash());
        repository.replaceChunks(documentId, versionId, reviewRevisionId, prepared.writes());
        repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, "CHUNK", "SUCCEEDED", prepared.textHash(), null);
        repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId,
                "BM25_INDEX", "postgresql-bm25", lexicalAnalyzer.version(), prepared.textHash());
        repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, "BM25_INDEX", "SUCCEEDED", prepared.textHash(), null);
        if (prepared.aiApproved()) {
            repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId,
                    "VECTOR_INDEX", "pgvector", embeddingModel, prepared.textHash());
            repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, "VECTOR_INDEX", "SUCCEEDED", prepared.textHash(), null);
        } else {
            repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, "VECTOR_INDEX", "NOT_REQUIRED", null, null);
        }

        var publication = governance.publish(organizationId, actorId, documentId, versionId,
                reviewRevisionId, expectedLockVersion);
        if (publication == null) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "发布内容已发生变化，请刷新后重试");
        repository.rebuildTermStats(organizationId);
        appendIngestAudit(organizationId, actorId, "KB_DOCUMENT_PUBLISHED", documentId,
                objectMapper.createObjectNode().put("publicationId", publication.id().toString())
                        .put("reviewRevisionId", reviewRevisionId.toString()));
        return publication;
    }

    @Transactional
    public void rebuildPublishedIndex(UUID organizationId, UUID documentId) {
        var document = repository.findDocument(organizationId, documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var publication = governance.currentPublication(organizationId, documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "当前发布不存在"));
        var snapshot = governance.publishedContent(organizationId, documentId, publication.id())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "当前发布快照不存在"));
        var prepared = prepareIndex(organizationId, documentId, publication.versionId(),
                publication.reviewRevisionId(), document.title(), snapshot.confirmedDocument(),
                snapshot.excludedReviewNodeIds(), snapshot.sourceNodes());
        replacePreparedIndex(organizationId, documentId, publication, prepared);
        repository.rebuildTermStats(organizationId);
    }

    @Transactional
    public int rebuildAllPublishedIndexes(UUID organizationId) {
        var preparedPublications = new ArrayList<PreparedPublication>();
        for (var document : repository.listDocuments(organizationId, null, null,
                null, null, "ACTIVE", null, 1, 1000)) {
            var publication = governance.currentPublication(organizationId, document.id()).orElse(null);
            if (publication == null) continue;
            var snapshot = governance.publishedContent(organizationId, document.id(), publication.id())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND,
                            "当前发布快照不存在：" + document.title()));
            var prepared = prepareIndex(organizationId, document.id(), publication.versionId(),
                    publication.reviewRevisionId(), document.title(), snapshot.confirmedDocument(),
                    snapshot.excludedReviewNodeIds(), snapshot.sourceNodes());
            preparedPublications.add(new PreparedPublication(document.id(), publication, prepared));
        }
        if (preparedPublications.isEmpty()) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "没有可重建的当前发布索引");
        }
        for (var item : preparedPublications) {
            replacePreparedIndex(organizationId, item.documentId(), item.publication(), item.prepared());
        }
        repository.rebuildTermStats(organizationId);
        return preparedPublications.size();
    }

    private void replacePreparedIndex(UUID organizationId, UUID documentId,
                                      KnowledgeGovernanceRepository.PublicationRow publication,
                                      PreparedIndex prepared) {
        repository.startProcessingStep(organizationId, documentId, publication.versionId(),
                publication.reviewRevisionId(), "CHUNK", "block-aware-chunker", lexicalAnalyzer.version(),
                prepared.textHash());
        repository.replaceChunks(documentId, publication.versionId(), publication.reviewRevisionId(), prepared.writes());
        repository.finishProcessingStep(organizationId, publication.versionId(), publication.reviewRevisionId(),
                "CHUNK", "SUCCEEDED", prepared.textHash(), null);
        repository.startProcessingStep(organizationId, documentId, publication.versionId(),
                publication.reviewRevisionId(), "BM25_INDEX", "postgresql-bm25", lexicalAnalyzer.version(),
                prepared.textHash());
        repository.finishProcessingStep(organizationId, publication.versionId(), publication.reviewRevisionId(),
                "BM25_INDEX", "SUCCEEDED", prepared.textHash(), null);
        repository.startProcessingStep(organizationId, documentId, publication.versionId(),
                publication.reviewRevisionId(), "VECTOR_INDEX", "pgvector", embeddingModel, prepared.textHash());
        repository.finishProcessingStep(organizationId, publication.versionId(), publication.reviewRevisionId(),
                "VECTOR_INDEX", prepared.aiApproved() ? "SUCCEEDED" : "NOT_REQUIRED", prepared.textHash(), null);
    }

    private PreparedIndex prepareIndex(UUID organizationId, UUID documentId, UUID versionId, UUID reviewRevisionId,
                                       String title, JsonNode confirmedDocument, List<UUID> excludedReviewNodeIds,
                                       List<KnowledgeGovernanceRepository.SourceNodeView> sourceNodes) {
        var projection = documents.project(confirmedDocument, excludedReviewNodeIds);
        var chunks = blockAwareChunker.chunk(title, projection.nodes(), sourceNodes,
                governance.largeTableRows(organizationId, reviewRevisionId));
        if (chunks.stream().noneMatch(chunk -> "CHILD".equals(chunk.role()))) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "确认文本没有可检索内容");
        }
        var textHash = sha256(chunks.stream().map(BlockAwareChunker.ChunkDraft::content)
                .reduce("", (left, right) -> left + "\n" + right));
        var aiApproved = repository.isAiApproved(organizationId, documentId);
        var embedding = aiApproved ? embeddings.getIfAvailable() : null;
        if (aiApproved && embedding == null) throw new IllegalStateException("向量服务暂不可用");
        var writes = new ArrayList<KnowledgeRepository.ChunkWrite>();
        for (var index = 0; index < chunks.size(); index++) {
            var block = chunks.get(index);
            var child = "CHILD".equals(block.role());
            var analysis = child ? lexicalAnalyzer.analyzeDocument(block.content())
                    : new LexicalAnalyzer.Analysis(Map.of(), 0);
            var terms = analysis.frequencies().entrySet().stream()
                    .map(item -> new KnowledgeRepository.TermFrequency(item.getKey(), item.getValue())).toList();
            String vector = null;
            if (aiApproved && child) vector = embedding.embedVector(block.content())
                    .orElseThrow(() -> new IllegalStateException("向量服务未返回结果"));
            var anchor = block.primaryAnchor();
            writes.add(new KnowledgeRepository.ChunkWrite(block.chunkKey(), block.parentKey(), block.role(), index,
                    block.firstPage(), block.headingPath().isEmpty() ? null : String.join(" > ", block.headingPath()),
                    block.content(), vector, analysis.documentLength(), block.modelTokenLength(),
                    lexicalAnalyzer.version(), vector == null ? null : embeddingModel, terms, block.headingPath(),
                    block.reviewNodeIds(), block.sourceNodeKeys(), json(anchor), json(block.anchors()),
                    json(block.relations()), anchorText(anchor, "sheetName"), anchorText(anchor, "range"),
                    anchorText(anchor, "paragraphId"), anchorDoubles(anchor, "polygon"), anchorLong(anchor, "startMs"),
                    anchorLong(anchor, "endMs")));
        }
        return new PreparedIndex(List.copyOf(writes), textHash, aiApproved);
    }

    private record PreparedIndex(List<KnowledgeRepository.ChunkWrite> writes, String textHash, boolean aiApproved) { }

    private record PreparedPublication(UUID documentId,
                                       KnowledgeGovernanceRepository.PublicationRow publication,
                                       PreparedIndex prepared) { }

    private record ChannelResult(List<KnowledgeRepository.RankedChunk> ranks,
                                 List<KnowledgeSearchFacade.QueryTrace> traces,
                                 List<String> fallbacks,
                                 Map<String, Long> timings) {
        private ChannelResult {
            ranks = ranks == null ? List.of() : List.copyOf(ranks);
            traces = traces == null ? List.of() : List.copyOf(traces);
            fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
            timings = timings == null ? Map.of() : Map.copyOf(timings);
        }
    }

    public void buildVectors(UUID organizationId, UUID documentId, UUID publicationId, UUID reviewRevisionId) {
        if (!repository.isAiApproved(organizationId, documentId)) {
            repository.clearDocumentEmbeddings(organizationId, documentId);
            return;
        }
        var publication = governance.currentPublicationById(organizationId, publicationId)
                .filter(value -> reviewRevisionId.equals(value.reviewRevisionId()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "发布版本已经切换"));
        var provider = embeddings.getIfAvailable();
        if (provider == null) throw new IllegalStateException("向量服务暂不可用");
        repository.startProcessingStep(organizationId, documentId, publication.versionId(), reviewRevisionId,
                "VECTOR_INDEX", "pgvector", embeddingModel, null);
        for (var chunk : repository.chunksForEmbedding(organizationId, documentId, reviewRevisionId)) {
            if (!repository.isAiApproved(organizationId, documentId)) {
                repository.clearDocumentEmbeddings(organizationId, documentId);
                return;
            }
            var vector = provider.embedVector(chunk.content())
                    .orElseThrow(() -> new IllegalStateException("向量服务未返回结果"));
            repository.updateChunkEmbedding(organizationId, chunk.id(), vector, embeddingModel);
        }
        repository.finishProcessingStep(organizationId, publication.versionId(), reviewRevisionId,
                "VECTOR_INDEX", "SUCCEEDED", null, null);
    }

    public void failIndex(UUID organizationId, UUID documentId, UUID versionId, UUID reviewRevisionId, Exception exception) {
        var error = safeError(exception);
        governance.failRevision(organizationId, reviewRevisionId, error);
        for (var step : List.of("CHUNK", "BM25_INDEX", "VECTOR_INDEX")) {
            repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, step, "FAILED", null, error);
        }
    }

    public void failVector(UUID organizationId, UUID documentId, UUID publicationId, UUID reviewRevisionId,
                           Exception exception) {
        governance.currentPublicationById(organizationId, publicationId)
                .filter(publication -> reviewRevisionId.equals(publication.reviewRevisionId()))
                .ifPresent(publication -> repository.finishProcessingStep(organizationId, publication.versionId(),
                        reviewRevisionId, "VECTOR_INDEX", "FAILED", null, safeError(exception)));
    }

    private void enqueueVectorBuild(UUID organizationId, UUID documentId, UUID publicationId, UUID reviewRevisionId) {
        var payload = objectMapper.createObjectNode().put("organizationId", organizationId.toString())
                .put("documentId", documentId.toString()).put("publicationId", publicationId.toString())
                .put("reviewRevisionId", reviewRevisionId.toString());
        async.enqueue(organizationId, "KB_BUILD_KNOWLEDGE_VECTOR", payload,
                "kb-vector:" + publicationId + ":" + System.currentTimeMillis(), 55);
    }

    public void ingest(UUID organizationId, UUID actorId, UUID documentId, UUID versionId, UUID fileId) {
        repository.updateProcessing(documentId, versionId);
        var version = repository.findVersion(organizationId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在"));
        String attemptedProvider = null;
        UUID activeParseRunId = null;
        try {
            repository.startProcessingStep(organizationId, documentId, versionId, null,
                    "SCAN", "safety-scanner", null, version.sha256());
            var scan = scan(organizationId, fileId, version);
            if (scan.status() != FileSafetyScanner.ScanResult.Status.SAFE) {
                activeParseRunId = governance.createParseRun(organizationId, actorId, documentId, versionId, "FAILED", null,
                        "safety-scanner", null, scan.reason(),
                        objectMapper.createObjectNode().put("scanStatus", scan.status().name()), List.of(), List.of()).id();
                repository.finishProcessingStep(organizationId, versionId, null, "SCAN",
                        scan.status() == FileSafetyScanner.ScanResult.Status.UNAVAILABLE ? "PENDING_PROVIDER" : "FAILED",
                        null, scan.reason());
                repository.attachProcessingSteps(organizationId, versionId, activeParseRunId);
                repository.updateScanStatus(documentId, scan.status().name());
                repository.markFailed(documentId, versionId,
                        scan.status() == FileSafetyScanner.ScanResult.Status.REJECTED ? "REJECTED" : "FAILED",
                        scan.reason());
                return;
            }
            repository.updateScanStatus(documentId, "SAFE");
            repository.finishProcessingStep(organizationId, versionId, null, "SCAN", "SUCCEEDED", version.sha256(), null);
            var mediaProvider = mediaProviders.stream()
                    .filter(candidate -> candidate.supports(version.originalName(), version.contentType()))
                    .sorted((left, right) -> Boolean.compare(right.isConfigured(), left.isConfigured()))
                    .findFirst().orElse(null);
            var parser = parsers.stream().filter(candidate -> candidate.supports(version.originalName(), version.contentType()))
                    .findFirst().orElse(null);
            if (mediaProvider != null && parser != null) {
                try (var stored = storage.open(organizationId, fileId)) {
                    if (!mediaProvider.requiresExternalExtraction(stored.stream(), version.originalName())) {
                        mediaProvider = null;
                    }
                }
            }
            if (parser == null && mediaProvider == null) {
                activeParseRunId = governance.createParseRun(organizationId, actorId, documentId, versionId, "FAILED", null,
                        "unsupported-format", null, "当前文件格式暂不支持",
                        objectMapper.createObjectNode().put("supported", false), List.of(), List.of()).id();
                repository.attachProcessingSteps(organizationId, versionId, activeParseRunId);
                repository.markFailed(documentId, versionId, "REJECTED", "当前文件格式暂不支持");
                return;
            }
            DocumentParser.ParsedDocument parsed;
            repository.startProcessingStep(organizationId, documentId, versionId, null, "PARSE",
                    mediaProvider == null ? parser.getClass().getSimpleName() : mediaProvider.getClass().getSimpleName(), null, version.sha256());
            attemptedProvider = mediaProvider == null ? parser.getClass().getSimpleName() : mediaProvider.getClass().getSimpleName();
            if (mediaProvider != null) {
                if (!mediaProvider.isConfigured()) {
                    activeParseRunId = governance.createParseRun(organizationId, actorId, documentId, versionId, "FAILED",
                            null, mediaProvider.getClass().getSimpleName(), null, "解析服务暂不可用",
                            objectMapper.createObjectNode().put("configured", false), List.of(), List.of()).id();
                    repository.finishProcessingStep(organizationId, versionId, null, "PARSE", "PENDING_PROVIDER", null,
                            "解析服务暂不可用");
                    repository.attachProcessingSteps(organizationId, versionId, activeParseRunId);
                    repository.markFailed(documentId, versionId, "PENDING_PROVIDER", "解析服务暂不可用");
                    appendIngestAudit(organizationId, actorId, "KB_PARSE_PROVIDER_UNAVAILABLE", documentId,
                            objectMapper.createObjectNode().put("versionId", versionId.toString()));
                    return;
                }
                try (var stored = storage.open(organizationId, fileId)) {
                    var publicUrl = storage.presignedUrl(organizationId, fileId, presignExpiry).orElse(null);
                    parsed = mediaProvider.extract(stored.stream(), version.originalName(),
                            new MediaExtractionProvider.ExtractionContext(fileId, version.contentType(), version.size(), publicUrl));
                }
            } else {
                if (!parser.isConfigured()) {
                    activeParseRunId = governance.createParseRun(organizationId, actorId, documentId, versionId, "FAILED",
                            null, parser.getClass().getSimpleName(), null, parser.unavailableReason(),
                            objectMapper.createObjectNode().put("configured", false), List.of(), List.of()).id();
                    repository.finishProcessingStep(organizationId, versionId, null, "PARSE", "PENDING_PROVIDER", null,
                            parser.unavailableReason());
                    repository.attachProcessingSteps(organizationId, versionId, activeParseRunId);
                    repository.markFailed(documentId, versionId, "PENDING_PROVIDER", parser.unavailableReason());
                    appendIngestAudit(organizationId, actorId, "KB_PARSE_PROVIDER_UNAVAILABLE", documentId,
                            objectMapper.createObjectNode().put("versionId", versionId.toString()));
                    return;
                }
                try (var stored = storage.open(organizationId, fileId)) {
                    parsed = parser.parse(stored.stream(), version.originalName(),
                            new DocumentParser.ParseContext(organizationId, actorId, fileId,
                                    version.contentType(), version.size(), OcrMode.fromNullable(version.ocrMode()),
                                    version.allowAgentFallback(), documentId, versionId));
                }
            }
            var textHash = sha256(parsed.blocks().stream().map(block -> block.content() == null ? "" : block.content())
                    .reduce("", (left, right) -> left + "\n" + right));
            var effectiveOcrValue = parsed.metadata().get("effectiveOcr");
            var effectiveOcr = effectiveOcrValue instanceof Boolean value ? value : null;
            var parserMode = parsed.metadata().get("mode") == null ?
                    (mediaProvider == null ? "LOCAL" : "PRECISE") : String.valueOf(parsed.metadata().get("mode"));
            repository.updateVersionParseOutcome(organizationId, versionId, effectiveOcr, parserMode,
                    objectMapper.writeValueAsString(parsed.metadata()));
            repository.finishProcessingStep(organizationId, versionId, null, "PARSE", "SUCCEEDED", textHash, null);
            activeParseRunId = governance.createParseRun(organizationId, actorId, documentId, versionId, "SUCCEEDED",
                    parsed.parserVersion(), mediaProvider == null ? parser.getClass().getSimpleName()
                            : mediaProvider.getClass().getSimpleName(), parsed.providerTaskId(), null,
                    parseMetadata(parsed, textHash), parsed.blocks(), parsed.sourceTables()).id();
            repository.attachProcessingSteps(organizationId, versionId, activeParseRunId);
            repository.markReady(documentId, versionId, parsed.parserVersion(), textHash);
            appendIngestAudit(organizationId, actorId, "KB_PARSE_COMPLETED", documentId,
                    objectMapper.createObjectNode().put("versionId", versionId.toString())
                            .put("parserVersion", parsed.parserVersion()).put("blockCount", parsed.blocks().size()));
        } catch (Exception exception) {
            var mediaFailure = exception instanceof MediaExtractionException failure ? failure : null;
            var parsingFailure = exception instanceof DocumentParsingFailure failure ? failure : null;
            try {
                if (activeParseRunId != null) {
                    governance.updateParseRunStatus(organizationId, activeParseRunId, "FAILED", safeError(exception));
                } else {
                    var diagnostic = objectMapper.createObjectNode()
                            .put("requestedOcrMode", version.ocrMode())
                            .put("allowAgentFallback", version.allowAgentFallback())
                            .put("retryable", parsingFailure != null && parsingFailure.retryable())
                            .put("fallbackEligible", parsingFailure != null && parsingFailure.fallbackEligible());
                    if (parsingFailure != null && parsingFailure.httpStatus() != null) {
                        diagnostic.put("httpStatus", parsingFailure.httpStatus());
                    }
                    if (parsingFailure != null && parsingFailure.apiCode() != null) {
                        diagnostic.put("apiCode", parsingFailure.apiCode());
                    }
                    activeParseRunId = governance.createParseRun(organizationId, actorId, documentId, versionId, "FAILED",
                            mediaFailure == null ? null : mediaFailure.model(), attemptedProvider,
                            parsingFailure == null ? mediaFailure == null ? null : mediaFailure.providerTaskId()
                                    : parsingFailure.taskId(), safeError(exception), diagnostic, List.of(), List.of()).id();
                    repository.attachProcessingSteps(organizationId, versionId, activeParseRunId);
                }
                if (activeParseRunId != null) {
                    repository.finishProcessingStep(organizationId, versionId, activeParseRunId, "PARSE", "FAILED",
                            null, safeError(exception));
                }
            } catch (RuntimeException ignored) {
                // The original parser/provider error remains authoritative if failure recording itself is unavailable.
            }
            repository.markFailed(documentId, versionId, "FAILED", safeError(exception));
            appendIngestAudit(organizationId, actorId, "KB_PARSE_FAILED", documentId,
                    objectMapper.createObjectNode().put("versionId", versionId.toString()).put("error", safeError(exception)));
            throw exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
        }
    }

    public void ingest(UUID organizationId, UUID documentId, UUID versionId, UUID fileId) {
        ingest(organizationId, null, documentId, versionId, fileId);
    }

    private void appendIngestAudit(UUID organizationId, UUID actorId, String action, UUID documentId, JsonNode detail) {
        if (actorId != null) audit.append(organizationId, actorId, action, "KB_DOCUMENT", documentId, detail);
    }

    private FileSafetyScanner.ScanResult scan(UUID organizationId, UUID fileId, KnowledgeRepository.VersionRow version) {
        try (var file = storage.open(organizationId, fileId)) {
            return scanner.scan(file.stream(), version.originalName(), version.contentType(), version.size());
        } catch (Exception exception) {
            return new FileSafetyScanner.ScanResult(FileSafetyScanner.ScanResult.Status.UNAVAILABLE, "安全扫描服务不可用");
        }
    }

    private JsonNode parseMetadata(DocumentParser.ParsedDocument parsed, String textHash) {
        var result = objectMapper.createObjectNode().put("textSha256", textHash);
        parsed.metadata().forEach((key, value) -> result.set(key, objectMapper.valueToTree(value)));
        return result;
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Chunk 来源信息序列化失败", exception); }
    }

    private String anchorText(JsonNode anchor, String field) {
        return anchor != null && anchor.hasNonNull(field) ? anchor.path(field).asText(null) : null;
    }

    private Long anchorLong(JsonNode anchor, String field) {
        return anchor != null && anchor.path(field).isNumber() ? anchor.path(field).asLong() : null;
    }

    private List<Double> anchorDoubles(JsonNode anchor, String field) {
        if (anchor == null || !anchor.path(field).isArray()) return List.of();
        var result = new ArrayList<Double>();
        anchor.path(field).forEach(value -> {
            if (value.isNumber()) result.add(value.asDouble());
            else if (value.isArray()) value.forEach(number -> result.add(number.asDouble()));
        });
        return List.copyOf(result);
    }

    private DocumentView view(KnowledgeRepository.DocumentRow row) {
        return new DocumentView(row.id(), row.title(), row.status(), row.scanStatus(), row.aiStatus(),
                row.currentVersionNo(), row.currentVersionId(), row.originalName(), row.contentType(), row.size(),
                row.sha256(), safeUserError(row.parseError()), row.createdAt(), row.updatedAt(), row.libraryScope(), row.categoryId(), row.categoryName(),
                row.lifecycleStatus(), row.reviewStatus(), row.reviewRevisionStatus(), row.reviewRevision(),
                row.currentPublicationId(), row.currentPublicationNo(),
                List.of());
    }

    private List<DocumentView> withProjects(com.jsd.aird.shared.security.Actor actor, List<DocumentView> items) {
        if (projectResources == null || items.isEmpty()) return items;
        var links = projectResources.links(actor, ResourceType.KNOWLEDGE_DOCUMENT,
                items.stream().map(DocumentView::id).toList());
        return items.stream().map(item -> new DocumentView(item.id(), item.title(), item.status(), item.scanStatus(),
                item.aiStatus(), item.currentVersionNo(), item.currentVersionId(), item.originalName(), item.contentType(),
                item.size(), item.sha256(), item.parseError(), item.createdAt(), item.updatedAt(), item.libraryScope(),
                item.categoryId(), item.categoryName(), item.lifecycleStatus(), item.reviewStatus(),
                item.reviewRevisionStatus(), item.reviewRevision(),
                item.currentPublicationId(), item.currentPublicationNo(), links.getOrDefault(item.id(), List.of()))).toList();
    }

    private KnowledgeRepository.CategoryRow requireCategory(UUID organizationId, UUID categoryId) {
        return repository.findCategory(organizationId, categoryId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识库分类不存在"));
    }

    private KnowledgeRepository.DocumentRow requireDocument(UUID organizationId, UUID documentId) {
        return repository.findDocument(organizationId, documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
    }

    private String normalizeDocumentTitle(String value) {
        if (!StringUtils.hasText(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件名称不能为空");
        var normalized = value.trim();
        if (normalized.length() > 260) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件名称不能超过 260 个字符");
        return normalized;
    }

    private String normalizeScope(String value) {
        var normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "INTERNAL";
        if (!Set.of("INTERNAL", "EXTERNAL").contains(normalized)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "资料范围只能是 INTERNAL 或 EXTERNAL");
        }
        return normalized;
    }

    private String normalizeName(String value) {
        if (!StringUtils.hasText(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类名称不能为空");
        var normalized = value.trim();
        if (normalized.length() > 120) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类名称不能超过 120 个字符");
        return normalized;
    }

    private String normalizeDescription(String value) {
        if (!StringUtils.hasText(value)) return null;
        var normalized = value.trim();
        if (normalized.length() > 240) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类简介不能超过 240 个字符");
        return normalized;
    }

    private VersionView versionView(KnowledgeRepository.VersionRow row) {
        return new VersionView(row.id(), row.documentId(), row.versionNo(), row.fileObjectId(), row.originalName(),
                row.contentType(), row.size(), row.sha256(), row.status(), safeUserError(row.errorMessage()),
                row.reviewStatus(), row.reviewRevision(), row.effectiveOcr(), row.parserMode(),
                readJsonObject(row.parserMetadataJson()));
    }

    private JsonNode readJsonObject(String value) {
        if (!StringUtils.hasText(value)) return objectMapper.createObjectNode();
        try { return objectMapper.readTree(value); }
        catch (Exception ignored) { return objectMapper.createObjectNode(); }
    }

    private String safeUserError(String value) {
        if (!StringUtils.hasText(value)) return null;
        var normalized = value.trim();
        if (normalized.contains("Exception") || normalized.contains("java.") || normalized.contains(" at ")) {
            return "文件解析失败，请重新解析或联系管理员";
        }
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private KnowledgeSearchFacade.SearchHit toSearchHit(UUID organizationId, KnowledgeRepository.SearchRow row,
                                                         double retrieval, double rrf) {
        var provenance = row.provenance();
        var primary = provenance == null ? null : readJsonNullable(provenance.primaryAnchorJson());
        var pageNo = primary != null && primary.path("page").isNumber()
                ? primary.path("page").asInt() : row.pageNo();
        return new KnowledgeSearchFacade.SearchHit(row.chunkId(), row.documentId(), row.versionId(), row.title(),
                row.originalName(), pageNo, row.section(), row.content(), rrf, retrieval, rrf, rrf,
                "KNOWLEDGE_CHUNK", null, null,
                provenance == null ? null : provenance.primaryAnchorJson(), row.chunkNo(),
                primary,
                provenance == null ? List.of() : readJsonArray(provenance.anchorsJson()),
                provenance == null ? List.of() : provenance.reviewNodeIds(),
                provenance == null ? List.of() : provenance.sourceNodeKeys());
    }

    private JsonNode readJsonNullable(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return objectMapper.readTree(value); } catch (Exception ignored) { return null; }
    }

    private List<JsonNode> readJsonArray(String value) {
        var parsed = readJsonNullable(value);
        if (parsed == null || !parsed.isArray()) return List.of();
        var result = new ArrayList<JsonNode>();
        parsed.forEach(result::add);
        return List.copyOf(result);
    }

    private String safeError(Exception exception) {
        var value = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder();
            for (var item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("摘要计算失败", exception);
        }
    }

    private void closeQuietly(FileStorageFacade.StoredFile file) {
        try { file.close(); } catch (Exception ignored) { }
    }

    public record CreateCommand(UUID fileId, String title, String libraryScope, UUID categoryId) { }
    public record GrantCommand(String action, String reason) { }
    public record CreateVersionCommand(UUID fileId) { }
    public record DocumentView(UUID id, String title, String status, String scanStatus,
                               String aiStatus, int currentVersionNo, UUID currentVersionId, String originalName,
                               String contentType, long size, String sha256, String parseError,
                               java.time.Instant createdAt, java.time.Instant updatedAt, String libraryScope,
                               UUID categoryId, String categoryName, String lifecycleStatus, String reviewStatus,
                               String reviewRevisionStatus, int reviewRevision,
                               UUID currentPublicationId, Integer currentPublicationNo,
                               List<ProjectResourceFacade.RelatedProjectView> relatedProjects) { }
    public record ProcessingView(UUID documentId, UUID versionId, String documentStatus, String documentError,
                                 UUID parseRunId, String parseRunStatus, String parseRunError,
                                 java.time.Instant lastAttemptAt, UUID jobId, String jobStatus, int progress,
                                 String currentStage, int attemptCount, int maxAttempts,
                                 java.time.Instant nextAttemptAt, boolean terminal, String lastError) { }
    public record VersionView(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                               String contentType, long size, String sha256, String status,
                               String errorMessage, String reviewStatus, int reviewRevision,
                               Boolean effectiveOcr, String parserMode,
                               JsonNode parserMetadata) { }
}
