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
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.FileSafetyScanner;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import com.jsd.aird.kb.domain.MediaExtractionException;
import com.jsd.aird.kb.domain.TermAnalyzer;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeService implements KnowledgeSearchFacade {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final int CHUNK_SIZE = 1800;
    private static final int CHUNK_OVERLAP = 180;

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
            @org.springframework.beans.factory.annotation.Value("${app.storage.presign-expiry:15m}") Duration presignExpiry
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
            repository.insertDocument(new KnowledgeRepository.NewDocument(
                    documentId, actor.organizationId(), title, actor.userId(), scope, categoryId
            ));
            try {
                repository.insertVersion(new KnowledgeRepository.NewVersion(
                        versionId, documentId, 1, command.fileId(), file.originalName(), file.contentType(), file.size(), file.sha256()
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
                                           String lifecycleStatus, String reviewStatus, int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        var items = repository.listDocuments(actor.organizationId(), keyword, status, aiStatus, scope, categoryId,
                        lifecycleStatus, reviewStatus, safePage, safeSize)
                .stream().map(this::view).toList();
        var total = repository.countDocuments(actor.organizationId(), keyword, status, aiStatus, scope, categoryId,
                lifecycleStatus, reviewStatus);
        return new PageResponse<>(items, safePage, safeSize, total, (total + safeSize - 1) / safeSize);
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
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
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
            try {
                repository.insertVersion(new KnowledgeRepository.NewVersion(
                        versionId, documentId, versionNo, command.fileId(), file.originalName(), file.contentType(),
                        file.size(), file.sha256()
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
        var terms = new java.util.LinkedHashSet<String>();
        variants.forEach(value -> terms.addAll(TermAnalyzer.frequencies(value).keySet()));
        var fallbacks = new ArrayList<String>();
        List<KnowledgeRepository.SearchRow> bm25;
        try {
            bm25 = repository.bm25Search(organizationId, List.copyOf(terms), request.aiOnly(), request.scopeIds(),
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
        var orderedIds = expandNeighborEvidence(request, terms, rankedIds, rows, scores, retrievalScores, safeLimit);
        var hits = orderedIds.stream()
                .limit(safeLimit)
                .map(id -> toSearchHit(rows.get(id), retrievalScores.getOrDefault(id, 0.0), scores.getOrDefault(id, 0.0)))
                .toList();
        if (vector.isEmpty()) fallbacks.add("EMBEDDING_UNAVAILABLE");
        return new KnowledgeSearchFacade.SearchResult(hits,
                new KnowledgeSearchFacade.RetrievalTrace("BM25_VECTOR_RRF", bm25.size(), vectorRows.size(), rows.size(), fallbacks));
    }

    /**
     * OCR for scanned forms often stores a field label and its value as two
     * adjacent chunks. Keep the pair together when a query contains the
     * label, otherwise the numeric/text value can be cut off by top-k before
     * the model receives the evidence.
     */
    private List<UUID> expandNeighborEvidence(KnowledgeSearchFacade.SearchRequest request,
                                              Set<String> queryTerms,
                                              List<UUID> rankedIds,
                                              Map<UUID, KnowledgeRepository.SearchRow> rows,
                                              Map<UUID, Double> scores,
                                              Map<UUID, Double> retrievalScores,
                                              int safeLimit) {
        if (rankedIds.isEmpty() || queryTerms.isEmpty()) return rankedIds;
        var ordered = new java.util.LinkedHashSet<UUID>();
        var expandedSeeds = 0;
        for (var id : rankedIds) {
            var row = rows.get(id);
            if (row == null) continue;
            ordered.add(id);
            if (expandedSeeds >= 3 || ordered.size() > safeLimit * 2
                    || !isQueryFieldLabel(row.content(), queryTerms)
                    || row.pageNo() == null || row.chunkNo() < 0) continue;
            expandedSeeds++;
            var neighbors = repository.neighboringChunks(request.organizationId(), row.documentId(), row.versionId(),
                    row.pageNo(), row.chunkNo(), request.aiOnly(), request.scopeIds(), request.categoryIds(), 2, 5);
            for (var neighbor : neighbors) {
                if (neighbor.chunkId().equals(row.chunkId())) continue;
                rows.putIfAbsent(neighbor.chunkId(), neighbor);
                scores.putIfAbsent(neighbor.chunkId(), Math.max(0.0001, scores.getOrDefault(id, 0.0) * 0.96));
                retrievalScores.putIfAbsent(neighbor.chunkId(), row.score());
                ordered.add(neighbor.chunkId());
            }
        }
        rankedIds.stream().filter(id -> !ordered.contains(id)).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private boolean isQueryFieldLabel(String content, Set<String> queryTerms) {
        if (!StringUtils.hasText(content)) return false;
        var normalized = content.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 24 || normalized.matches("[\\p{N}\\p{Punct}\\s]+")) return false;
        return queryTerms.contains(normalized);
    }

    public void markIndexStale(UUID organizationId, UUID documentId, UUID versionId, UUID reviewRevisionId) {
        for (var step : List.of("CHUNK", "BM25_INDEX", "VECTOR_INDEX")) {
            repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId, step,
                    "knowledge-index", TermAnalyzer.VERSION, null);
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
        var sourceByKey = review.sourceNodes().stream().collect(java.util.stream.Collectors.toMap(
                KnowledgeGovernanceRepository.SourceNodeView::sourceNodeKey, value -> value));
        var projection = documents.project(review.reviewRevision().confirmedDocument(),
                review.reviewRevision().excludedReviewNodeIds());
        var projectedSource = projection.nodes().stream().map(node -> {
            var sourceNode = node.sourceNodeKeys().stream().map(sourceByKey::get)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return projectedBlock(node, sourceNode);
        }).filter(block -> block.content() != null && !block.content().isBlank()).toList();
        var source = new ArrayList<>(OcrFieldValueLinker.link(projectedSource));
        governance.largeTableRows(organizationId, reviewRevisionId).forEach(row -> source.add(
                new DocumentParser.TextBlock(null, "spreadsheet-row", row.projectedText(), row.sheetName(),
                        row.cellRange(), null, List.of(), null, null, null)));
        if (source.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "确认文本不能为空");

        var chunks = chunk(source);
        var textHash = sha256(chunks.stream().map(DocumentParser.TextBlock::content)
                .reduce("", (left, right) -> left + "\n" + right));
        var aiApproved = repository.isAiApproved(organizationId, documentId);
        var embedding = aiApproved ? embeddings.getIfAvailable() : null;
        if (aiApproved && embedding == null) throw new IllegalStateException("向量服务暂不可用");

        repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId,
                "CHUNK", "builtin-chunker", TermAnalyzer.VERSION, textHash);
        var writes = new ArrayList<KnowledgeRepository.ChunkWrite>();
        for (int index = 0; index < chunks.size(); index++) {
            var block = chunks.get(index);
            var terms = TermAnalyzer.frequencies(block.content()).entrySet().stream()
                    .map(item -> new KnowledgeRepository.TermFrequency(item.getKey(), item.getValue())).toList();
            String vector = null;
            if (aiApproved) {
                vector = embedding.embedVector(block.content())
                        .orElseThrow(() -> new IllegalStateException("向量服务未返回结果"));
            }
            writes.add(new KnowledgeRepository.ChunkWrite(index, block.pageNo(), block.section(), block.content(), vector,
                    terms.stream().mapToInt(KnowledgeRepository.TermFrequency::frequency).sum(), TermAnalyzer.VERSION,
                    null, vector == null ? null : embeddingModel, terms, block.sheetName(), block.cellRange(),
                    block.paragraphId(), block.bbox(), block.startTimeMs(), block.endTimeMs()));
        }
        repository.replaceChunks(documentId, versionId, reviewRevisionId, writes);
        repository.finishProcessingStep(organizationId, versionId, reviewRevisionId, "CHUNK", "SUCCEEDED", textHash, null);
        repository.startProcessingStep(organizationId, documentId, versionId, reviewRevisionId,
                "BM25_INDEX", "postgresql-bm25", TermAnalyzer.VERSION, textHash);
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

    private DocumentParser.TextBlock projectedBlock(StructuredDocumentCodec.ProjectedNode node,
                                                    KnowledgeGovernanceRepository.SourceNodeView source) {
        var anchor = source == null ? objectMapper.createObjectNode() : source.sourceAnchor();
        var kind = anchor.path("kind").asText();
        Integer page = anchor.has("page") ? anchor.path("page").asInt() : null;
        String sheet = "sheet_range".equals(kind) ? anchor.path("sheetName").asText(null) : null;
        String range = "sheet_range".equals(kind) ? anchor.path("range").asText(null) : null;
        String paragraph = "docx_path".equals(kind) ? anchor.path("paragraphId").asText(null) : null;
        Long start = "time_range".equals(kind) && anchor.has("startMs") ? anchor.path("startMs").asLong() : null;
        Long end = "time_range".equals(kind) && anchor.has("endMs") ? anchor.path("endMs").asLong() : null;
        var bbox = new ArrayList<Double>();
        anchor.path("polygon").forEach(value -> {
            if (value.isNumber()) bbox.add(value.asDouble());
            else if (value.isArray()) value.forEach(coordinate -> bbox.add(coordinate.asDouble()));
        });
        return new DocumentParser.TextBlock(page, node.nodeType(), node.text(), sheet, range, paragraph,
                bbox, start, end, null);
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
                try (var stored = storage.open(organizationId, fileId)) {
                    parsed = parser.parse(stored.stream(), version.originalName());
                }
            }
            var textHash = sha256(parsed.blocks().stream().map(block -> block.content() == null ? "" : block.content())
                    .reduce("", (left, right) -> left + "\n" + right));
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
            try {
                if (activeParseRunId != null) {
                    governance.updateParseRunStatus(organizationId, activeParseRunId, "FAILED", safeError(exception));
                } else {
                    activeParseRunId = governance.createParseRun(organizationId, actorId, documentId, versionId, "FAILED",
                            mediaFailure == null ? null : mediaFailure.model(), attemptedProvider,
                            mediaFailure == null ? null : mediaFailure.providerTaskId(), safeError(exception),
                            objectMapper.createObjectNode().put("retryable", false), List.of(), List.of()).id();
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

    private List<DocumentParser.TextBlock> chunk(List<DocumentParser.TextBlock> blocks) {
        var result = new ArrayList<DocumentParser.TextBlock>();
        for (var block : blocks) {
            var text = block.content() == null ? "" : block.content().replaceAll("\\s+", " ").strip();
            if (text.isBlank()) continue;
            // OCR field/value relations and spreadsheet rows are atomic evidence
            // units. Splitting them into unrelated character windows would make
            // a label and its value independently retrievable again.
            if (block.section() != null && (block.section().startsWith("ocr-field-value")
                    || block.section().startsWith("spreadsheet-row"))) {
                result.add(copyBlock(block, text));
                continue;
            }
            if (text.length() <= CHUNK_SIZE) {
                result.add(copyBlock(block, text));
                continue;
            }
            for (int start = 0; start < text.length(); start += CHUNK_SIZE - CHUNK_OVERLAP) {
                var end = Math.min(text.length(), start + CHUNK_SIZE);
                result.add(copyBlock(block, text.substring(start, end)));
                if (end == text.length()) break;
            }
        }
        return result;
    }

    private DocumentParser.TextBlock copyBlock(DocumentParser.TextBlock block, String text) {
        return new DocumentParser.TextBlock(block.pageNo(), block.section(), text, block.sheetName(), block.cellRange(),
                block.paragraphId(), block.bbox(), block.startTimeMs(), block.endTimeMs(), block.confidence(),
                block.attributes());
    }

    private JsonNode parseMetadata(DocumentParser.ParsedDocument parsed, String textHash) {
        var result = objectMapper.createObjectNode().put("textSha256", textHash);
        parsed.metadata().forEach((key, value) -> result.set(key, objectMapper.valueToTree(value)));
        return result;
    }

    private DocumentView view(KnowledgeRepository.DocumentRow row) {
        return new DocumentView(row.id(), row.title(), row.status(), row.scanStatus(), row.aiStatus(),
                row.currentVersionNo(), row.currentVersionId(), row.originalName(), row.contentType(), row.size(),
                row.sha256(), row.parseError(), row.createdAt(), row.updatedAt(), row.libraryScope(), row.categoryId(), row.categoryName(),
                row.lifecycleStatus(), row.reviewStatus(), row.reviewRevision(), row.currentPublicationId(), row.currentPublicationNo());
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
                row.contentType(), row.size(), row.sha256(), row.status(), row.errorMessage(),
                row.reviewStatus(), row.reviewRevision());
    }

    private KnowledgeSearchFacade.SearchHit toSearchHit(KnowledgeRepository.SearchRow row, double retrieval, double rrf) {
        return new KnowledgeSearchFacade.SearchHit(row.chunkId(), row.documentId(), row.versionId(), row.title(),
                row.originalName(), row.pageNo(), row.section(), row.content(), rrf, retrieval, rrf, rrf,
                "KNOWLEDGE_CHUNK", null, null, null, null);
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
                               int reviewRevision, UUID currentPublicationId, Integer currentPublicationNo) { }
    public record VersionView(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                               String contentType, long size, String sha256, String status,
                               String errorMessage, String reviewStatus, int reviewRevision) { }
}
