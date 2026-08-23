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
import com.jsd.aird.kb.domain.TermAnalyzer;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ProjectResourceFacade projectResources;

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
                lexicalAnalyzer, null);
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
            var parsePolicy = parsingPolicy(file.originalName(), file.contentType(), command.ocrMode(),
                    command.allowAgentFallback());
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

    public PageResponse<DocumentView> list(String keyword, String status, String aiStatus, String scope, UUID categoryId,
                                           String lifecycleStatus, String reviewStatus, UUID projectId,
                                           int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        var allowed = projectId == null || projectResources == null ? null
                : projectResources.resourceIdsForProject(actor, ResourceType.KNOWLEDGE_DOCUMENT, projectId);
        var items = repository.listDocuments(actor.organizationId(), keyword, status, aiStatus, scope, categoryId,
                        lifecycleStatus, reviewStatus, allowed, safePage, safeSize)
                .stream().map(this::view).toList();
        var total = repository.countDocuments(actor.organizationId(), keyword, status, aiStatus, scope, categoryId,
                lifecycleStatus, reviewStatus, allowed);
        items = withProjects(actor, items);
        return new PageResponse<>(items, safePage, safeSize, total, (total + safeSize - 1) / safeSize);
    }

    public PageResponse<DocumentView> list(String keyword, String status, String aiStatus, String scope, UUID categoryId,
                                           String lifecycleStatus, String reviewStatus, int page, int size) {
        return list(keyword, status, aiStatus, scope, categoryId, lifecycleStatus, reviewStatus, null, page, size);
    }

    public PageResponse<DocumentView> list(String keyword, String status, String aiStatus, String scope,
                                           UUID categoryId, int page, int size) {
        return list(keyword, status, aiStatus, scope, categoryId, null, null, page, size);
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
            var parsePolicy = parsingPolicy(file.originalName(), file.contentType(), command.ocrMode(),
                    command.allowAgentFallback());
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
                List.of(), List.of(), List.of())).hits();
    }

    @Override
    public KnowledgeSearchFacade.SearchResult search(KnowledgeSearchFacade.SearchRequest request) {
        if (!StringUtils.hasText(request.query())) {
            return new KnowledgeSearchFacade.SearchResult(List.of(),
                    new KnowledgeSearchFacade.RetrievalTrace("BM25_VECTOR_RRF", 0, 0, 0, List.of("EMPTY_QUERY")));
        }
        var organizationId = request.organizationId();
        var safeLimit = Math.min(50, Math.max(1, request.limit()));
        var variants = request.queryVariants().isEmpty() ? List.of(request.query().trim()) : request.queryVariants();
        var queryTerms = new java.util.LinkedHashSet<KnowledgeRepository.AnalyzedTerm>();
        variants.forEach(value -> {
            TermAnalyzer.frequencies(value).keySet().forEach(term -> queryTerms.add(
                    new KnowledgeRepository.AnalyzedTerm(TermAnalyzer.VERSION, term)));
            lexicalAnalyzer.analyzeQuery(value).frequencies().keySet().forEach(term -> queryTerms.add(
                    new KnowledgeRepository.AnalyzedTerm(lexicalAnalyzer.version(), term)));
        });
        var fallbacks = new ArrayList<String>();
        List<KnowledgeRepository.SearchRow> bm25;
        try {
            bm25 = repository.bm25Search(organizationId, List.copyOf(queryTerms), request.aiOnly(), request.scopeIds(),
                    request.categoryIds(), safeLimit * 4);
            if (bm25 == null || bm25.isEmpty()) {
                bm25 = List.of();
                // Empty is a normal no-hit result, not an index/database outage.
                fallbacks.add("BM25_EMPTY");
            }
        } catch (RuntimeException exception) {
            bm25 = List.of();
            fallbacks.add("BM25_ERROR");
            log.warn("BM25 search failed; falling back to full-text/vector search", exception);
        }
        List<KnowledgeRepository.SearchRow> fullText;
        if (bm25.isEmpty()) {
            try {
                fullText = repository.fullTextSearch(organizationId, request.query().trim(), request.aiOnly(),
                        request.scopeIds(), request.categoryIds(), safeLimit * 2);
            } catch (RuntimeException exception) {
                fullText = List.of();
                fallbacks.add("FULLTEXT_ERROR");
                log.warn("Full-text search failed; continuing with vector search", exception);
            }
        } else {
            fullText = bm25;
        }
        var vector = embeddings.getIfAvailable() == null ? java.util.Optional.<String>empty()
                : embeddings.getIfAvailable().embedVector(request.query().trim());
        var vectorRows = vector.map(value -> repository.vectorSearch(organizationId, value, request.aiOnly(), request.scopeIds(),
                        request.categoryIds(), safeLimit * 4, embeddingDimension))
                .orElse(List.of());
        var scores = new LinkedHashMap<UUID, Double>();
        var retrievalScores = new LinkedHashMap<UUID, Double>();
        var rows = new LinkedHashMap<UUID, KnowledgeRepository.SearchRow>();
        for (int index = 0; index < fullText.size(); index++) {
            var row = fullText.get(index);
            rows.put(row.chunkId(), row);
            retrievalScores.merge(row.chunkId(), row.score(), Math::max);
            scores.merge(row.chunkId(), 1.0 / (60 + index + 1), Double::sum);
        }
        for (int index = 0; index < vectorRows.size(); index++) {
            var row = vectorRows.get(index);
            rows.putIfAbsent(row.chunkId(), row);
            retrievalScores.merge(row.chunkId(), row.score(), Math::max);
            scores.merge(row.chunkId(), 1.0 / (60 + index + 1), Double::sum);
        }
        var rankedIds = rows.keySet().stream()
                .sorted((a, b) -> Double.compare(scores.getOrDefault(b, 0.0), scores.getOrDefault(a, 0.0)))
                .toList();
        var hits = rankedIds.stream()
                .limit(safeLimit)
                .map(id -> toSearchHit(organizationId, rows.get(id), retrievalScores.getOrDefault(id, 0.0),
                        scores.getOrDefault(id, 0.0)))
                .toList();
        if (vector.isEmpty()) fallbacks.add("EMBEDDING_UNAVAILABLE");
        return new KnowledgeSearchFacade.SearchResult(hits,
                new KnowledgeSearchFacade.RetrievalTrace("BM25_VECTOR_RRF", bm25.size(), vectorRows.size(), rows.size(), fallbacks));
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
        var projection = documents.project(review.reviewRevision().confirmedDocument(),
                review.reviewRevision().excludedReviewNodeIds());
        var chunks = blockAwareChunker.chunk(review.title(), projection.nodes(), review.sourceNodes(),
                governance.largeTableRows(organizationId, reviewRevisionId));
        if (chunks.stream().noneMatch(chunk -> "CHILD".equals(chunk.role()))) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "确认文本没有可检索内容");
        }
        var textHash = sha256(chunks.stream().map(BlockAwareChunker.ChunkDraft::content)
                .reduce("", (left, right) -> left + "\n" + right));
        var aiApproved = repository.isAiApproved(organizationId, documentId);
        var embedding = aiApproved ? embeddings.getIfAvailable() : null;
        if (aiApproved && embedding == null) throw new IllegalStateException("向量服务暂不可用");

        repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId,
                "CHUNK", "block-aware-chunker", lexicalAnalyzer.version(), textHash);
        var writes = new ArrayList<KnowledgeRepository.ChunkWrite>();
        for (int index = 0; index < chunks.size(); index++) {
            var block = chunks.get(index);
            var child = "CHILD".equals(block.role());
            var analysis = child ? lexicalAnalyzer.analyzeDocument(block.content())
                    : new LexicalAnalyzer.Analysis(Map.of(), 0);
            var terms = analysis.frequencies().entrySet().stream()
                    .map(item -> new KnowledgeRepository.TermFrequency(item.getKey(), item.getValue())).toList();
            String vector = null;
            if (aiApproved && child) {
                vector = embedding.embedVector(block.content())
                        .orElseThrow(() -> new IllegalStateException("向量服务未返回结果"));
            }
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
        repository.replaceChunks(documentId, versionId, reviewRevisionId, writes);
        repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, "CHUNK", "SUCCEEDED", textHash, null);
        repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId,
                "BM25_INDEX", "postgresql-bm25", lexicalAnalyzer.version(), textHash);
        repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, "BM25_INDEX", "SUCCEEDED", textHash, null);
        if (aiApproved) {
            repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId,
                    "VECTOR_INDEX", "pgvector", embeddingModel, textHash);
            repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, "VECTOR_INDEX", "SUCCEEDED", textHash, null);
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
                                    version.allowAgentFallback()));
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
                row.lifecycleStatus(), row.reviewStatus(), row.reviewRevision(), row.currentPublicationId(), row.currentPublicationNo(),
                List.of());
    }

    private List<DocumentView> withProjects(com.jsd.aird.shared.security.Actor actor, List<DocumentView> items) {
        if (projectResources == null || items.isEmpty()) return items;
        var links = projectResources.links(actor, ResourceType.KNOWLEDGE_DOCUMENT,
                items.stream().map(DocumentView::id).toList());
        return items.stream().map(item -> new DocumentView(item.id(), item.title(), item.status(), item.scanStatus(),
                item.aiStatus(), item.currentVersionNo(), item.currentVersionId(), item.originalName(), item.contentType(),
                item.size(), item.sha256(), item.parseError(), item.createdAt(), item.updatedAt(), item.libraryScope(),
                item.categoryId(), item.categoryName(), item.lifecycleStatus(), item.reviewStatus(), item.reviewRevision(),
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
                row.reviewStatus(), row.reviewRevision(), row.ocrMode(), row.allowAgentFallback(),
                row.effectiveOcr(), row.parserMode(), readJsonObject(row.parserMetadataJson()));
    }

    private JsonNode readJsonObject(String value) {
        if (!StringUtils.hasText(value)) return objectMapper.createObjectNode();
        try { return objectMapper.readTree(value); }
        catch (Exception ignored) { return objectMapper.createObjectNode(); }
    }

    private ParsePolicy parsingPolicy(String fileName, String contentType, String requestedMode,
                                      Boolean requestedFallback) {
        var pdf = (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                || "application/pdf".equalsIgnoreCase(contentType);
        if (!pdf) return new ParsePolicy(OcrMode.AUTO, false);
        try {
            return new ParsePolicy(OcrMode.fromNullable(requestedMode), Boolean.TRUE.equals(requestedFallback));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, exception.getMessage());
        }
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
        var provenance = repository.findChunkAnchor(organizationId, row.chunkId()).orElse(null);
        return new KnowledgeSearchFacade.SearchHit(row.chunkId(), row.documentId(), row.versionId(), row.title(),
                row.originalName(), row.pageNo(), row.section(), row.content(), rrf, retrieval, rrf, rrf,
                "KNOWLEDGE_CHUNK", null, null, null,
                provenance == null ? null : provenance.primaryAnchorJson(), row.chunkNo(),
                provenance == null ? null : readJsonNullable(provenance.primaryAnchorJson()),
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

    public record CreateCommand(UUID fileId, String title, String libraryScope, UUID categoryId,
                                String ocrMode, Boolean allowAgentFallback) { }
    public record GrantCommand(String action, String reason) { }
    public record CreateVersionCommand(UUID fileId, String ocrMode, Boolean allowAgentFallback) { }
    public record DocumentView(UUID id, String title, String status, String scanStatus,
                               String aiStatus, int currentVersionNo, UUID currentVersionId, String originalName,
                               String contentType, long size, String sha256, String parseError,
                               java.time.Instant createdAt, java.time.Instant updatedAt, String libraryScope,
                               UUID categoryId, String categoryName, String lifecycleStatus, String reviewStatus,
                               int reviewRevision, UUID currentPublicationId, Integer currentPublicationNo,
                               List<ProjectResourceFacade.RelatedProjectView> relatedProjects) { }
    public record ProcessingView(UUID documentId, UUID versionId, String documentStatus, String documentError,
                                 UUID parseRunId, String parseRunStatus, String parseRunError,
                                 java.time.Instant lastAttemptAt, UUID jobId, String jobStatus, int progress,
                                 String currentStage, int attemptCount, int maxAttempts,
                                 java.time.Instant nextAttemptAt, boolean terminal, String lastError) { }
    public record VersionView(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                               String contentType, long size, String sha256, String status,
                               String errorMessage, String reviewStatus, int reviewRevision, String ocrMode,
                               boolean allowAgentFallback, Boolean effectiveOcr, String parserMode,
                               JsonNode parserMetadata) { }
    private record ParsePolicy(OcrMode ocrMode, boolean allowAgentFallback) { }
}
