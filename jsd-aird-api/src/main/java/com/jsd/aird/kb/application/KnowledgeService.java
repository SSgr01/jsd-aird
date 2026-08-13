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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeService implements KnowledgeSearchFacade {

    private static final int CHUNK_SIZE = 1800;
    private static final int CHUNK_OVERLAP = 180;

    private final KnowledgeRepository repository;
    private final KnowledgeGovernanceRepository governance;
    private final KnowledgeFieldExtractor fieldExtractor;
    private final FileStorageFacade storage;
    private final OpsAsyncFacade async;
    private final AuditLogFacade audit;
    private final ObjectMapper objectMapper;
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
            KnowledgeFieldExtractor fieldExtractor,
            FileStorageFacade storage,
            OpsAsyncFacade async,
            AuditLogFacade audit,
            ObjectMapper objectMapper,
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
        this.fieldExtractor = fieldExtractor;
        this.storage = storage;
        this.async = async;
        this.audit = audit;
        this.objectMapper = objectMapper;
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
                    documentId, actor.organizationId(), title, normalizeType(command.documentType(), file.originalName()),
                    actor.userId(), scope, categoryId
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
        if (!governance.updateAiUsage(actor.organizationId(), actor.userId(), publication.id(), action, command.reason())) {
            throw new ApiException(ApiErrorCode.AI_DOCUMENT_NOT_APPROVED, "当前发布版本不可授权 AI 使用");
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
        var bm25 = repository.bm25Search(organizationId, List.copyOf(terms), request.aiOnly(), request.scopeIds(),
                request.categoryIds(), safeLimit * 4);
        var fullText = bm25.isEmpty()
                ? repository.fullTextSearch(organizationId, request.query().trim(), request.aiOnly(), request.scopeIds(),
                request.categoryIds(), safeLimit * 2)
                : bm25;
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
        var hits = rows.keySet().stream()
                .sorted((a, b) -> Double.compare(scores.getOrDefault(b, 0.0), scores.getOrDefault(a, 0.0)))
                .limit(safeLimit)
                .map(id -> toSearchHit(rows.get(id), retrievalScores.getOrDefault(id, 0.0), scores.getOrDefault(id, 0.0)))
                .toList();
        var fallbacks = new ArrayList<String>();
        if (bm25.isEmpty()) fallbacks.add("BM25_UNAVAILABLE");
        if (vector.isEmpty()) fallbacks.add("EMBEDDING_UNAVAILABLE");
        return new KnowledgeSearchFacade.SearchResult(hits,
                new KnowledgeSearchFacade.RetrievalTrace("BM25_VECTOR_RRF", bm25.size(), vectorRows.size(), rows.size(), fallbacks));
    }

    public void ingest(UUID organizationId, UUID actorId, UUID documentId, UUID versionId, UUID fileId) {
        repository.updateProcessing(documentId, versionId);
        var version = repository.findVersion(organizationId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在"));
        String attemptedProvider = null;
        UUID activeParseRunId = null;
        try {
            repository.startProcessingStep(organizationId, documentId, versionId, "SCAN", "safety-scanner", null, version.sha256());
            var scan = scan(organizationId, fileId, version);
            if (scan.status() != FileSafetyScanner.ScanResult.Status.SAFE) {
                governance.createParseRun(organizationId, documentId, versionId, "FAILED", null,
                        "safety-scanner", null, scan.reason(),
                        objectMapper.createObjectNode().put("scanStatus", scan.status().name()), List.of(), List.of());
                repository.finishProcessingStep(organizationId, versionId, "SCAN",
                        scan.status() == FileSafetyScanner.ScanResult.Status.UNAVAILABLE ? "PENDING_PROVIDER" : "FAILED",
                        null, scan.reason());
                repository.updateScanStatus(documentId, scan.status().name());
                repository.markFailed(documentId, versionId,
                        scan.status() == FileSafetyScanner.ScanResult.Status.REJECTED ? "REJECTED" : "FAILED",
                        scan.reason());
                return;
            }
            repository.updateScanStatus(documentId, "SAFE");
            repository.finishProcessingStep(organizationId, versionId, "SCAN", "SUCCEEDED", version.sha256(), null);
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
                governance.createParseRun(organizationId, documentId, versionId, "FAILED", null,
                        "unsupported-format", null, "当前文件格式暂不支持",
                        objectMapper.createObjectNode().put("supported", false), List.of(), List.of());
                repository.markFailed(documentId, versionId, "REJECTED", "当前文件格式暂不支持");
                return;
            }
            DocumentParser.ParsedDocument parsed;
            repository.startProcessingStep(organizationId, documentId, versionId, "PARSE",
                    mediaProvider == null ? parser.getClass().getSimpleName() : mediaProvider.getClass().getSimpleName(), null, version.sha256());
            attemptedProvider = mediaProvider == null ? parser.getClass().getSimpleName() : mediaProvider.getClass().getSimpleName();
            if (mediaProvider != null) {
                if (!governance.hasMediaConsent(organizationId, versionId)) {
                    governance.createParseRun(organizationId, documentId, versionId, "WAITING_MEDIA_CONSENT",
                            null, mediaProvider.getClass().getSimpleName(), null, "等待媒体解析外发确认",
                            objectMapper.createObjectNode().put("requiresConsent", true), List.of(), List.of());
                    repository.finishProcessingStep(organizationId, versionId, "PARSE", "PENDING_PROVIDER", null,
                            "等待媒体解析外发确认");
                    repository.markFailed(documentId, versionId, "PENDING_PROVIDER", "等待媒体解析外发确认");
                    appendIngestAudit(organizationId, actorId, "KB_PARSE_WAITING_MEDIA_CONSENT", documentId,
                            objectMapper.createObjectNode().put("versionId", versionId.toString()));
                    return;
                }
                if (!mediaProvider.isConfigured()) {
                    governance.createParseRun(organizationId, documentId, versionId, "FAILED",
                            null, mediaProvider.getClass().getSimpleName(), null, mediaProvider.unavailableReason(),
                            objectMapper.createObjectNode().put("configured", false), List.of(), List.of());
                    repository.finishProcessingStep(organizationId, versionId, "PARSE", "PENDING_PROVIDER", null,
                            mediaProvider.unavailableReason());
                    repository.markFailed(documentId, versionId, "PENDING_PROVIDER", mediaProvider.unavailableReason());
                    appendIngestAudit(organizationId, actorId, "KB_PARSE_PROVIDER_UNAVAILABLE", documentId,
                            objectMapper.createObjectNode().put("versionId", versionId.toString())
                                    .put("provider", mediaProvider.getClass().getSimpleName())
                                    .put("error", mediaProvider.unavailableReason()));
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
            repository.finishProcessingStep(organizationId, versionId, "PARSE", "SUCCEEDED", null, null);
            repository.startProcessingStep(organizationId, documentId, versionId, "CHUNK", "builtin-chunker", TermAnalyzer.VERSION, version.sha256());
            var chunks = chunk(parsed.blocks());
            var writes = new ArrayList<KnowledgeRepository.ChunkWrite>();
            // Every ingestion creates a new, unpublished parse run. A grant on an older publication
            // (including one for the same source version) must never authorize this draft text to leave the system.
            var aiApproved = false;
            for (int index = 0; index < chunks.size(); index++) {
                var block = chunks.get(index);
                var vector = !aiApproved || embeddings.getIfAvailable() == null ? null
                        : embeddings.getIfAvailable().embedVector(block.content()).orElse(null);
                var terms = TermAnalyzer.frequencies(block.content()).entrySet().stream()
                        .map(item -> new KnowledgeRepository.TermFrequency(item.getKey(), item.getValue())).toList();
                writes.add(new KnowledgeRepository.ChunkWrite(index, block.pageNo(), block.section(), block.content(), vector,
                        terms.stream().mapToInt(KnowledgeRepository.TermFrequency::frequency).sum(), TermAnalyzer.VERSION,
                        null, embeddingModel, terms, block.sheetName(), block.cellRange(), block.paragraphId(),
                        block.bbox(), block.startTimeMs(), block.endTimeMs()));
            }
            var textHash = sha256(writes.stream().map(KnowledgeRepository.ChunkWrite::content).reduce("", (a, b) -> a + "\n" + b));
            repository.finishProcessingStep(organizationId, versionId, "CHUNK", "SUCCEEDED", textHash, null);
            repository.startProcessingStep(organizationId, documentId, versionId, "EMBEDDING", "spring-ai-embedding", null, textHash);
            var documentType = repository.findDocument(organizationId, documentId)
                    .map(KnowledgeRepository.DocumentRow::documentType).orElse("UNKNOWN");
            activeParseRunId = governance.createParseRun(organizationId, documentId, versionId, "PROCESSING",
                    parsed.parserVersion(), mediaProvider == null ? parser.getClass().getSimpleName()
                            : mediaProvider.getClass().getSimpleName(), parsed.providerTaskId(), null,
                    parseMetadata(parsed, textHash), chunks, fieldExtractor.extract(documentType, parsed.blocks())).id();
            repository.replaceChunks(documentId, versionId, activeParseRunId, writes);
            var hasVector = writes.stream().anyMatch(item -> item.vector() != null && !item.vector().isBlank());
            repository.finishProcessingStep(organizationId, versionId, "EMBEDDING", hasVector ? "SUCCEEDED" : "PENDING_PROVIDER",
                    hasVector ? textHash : null, hasVector ? null : "Embedding 服务未配置或调用失败");
            repository.markReady(documentId, versionId, parsed.parserVersion(), textHash);
            governance.updateParseRunStatus(organizationId, activeParseRunId, "PENDING_REVIEW", null);
            appendIngestAudit(organizationId, actorId, "KB_PARSE_COMPLETED", documentId,
                    objectMapper.createObjectNode().put("versionId", versionId.toString())
                            .put("parserVersion", parsed.parserVersion()).put("blockCount", parsed.blocks().size()));
            repository.startProcessingStep(organizationId, documentId, versionId, "BM25_INDEX", "postgresql-bm25", TermAnalyzer.VERSION, textHash);
            repository.rebuildTermStats(organizationId);
            repository.finishProcessingStep(organizationId, versionId, "BM25_INDEX", "SUCCEEDED", textHash, null);
            repository.startProcessingStep(organizationId, documentId, versionId, "VECTOR_INDEX", "pgvector", null, textHash);
            repository.finishProcessingStep(organizationId, versionId, "VECTOR_INDEX", hasVector ? "SUCCEEDED" : "PENDING_PROVIDER",
                    hasVector ? textHash : null, hasVector ? null : "Embedding 服务未配置或调用失败");
        } catch (Exception exception) {
            var mediaFailure = exception instanceof MediaExtractionException failure ? failure : null;
            try {
                if (activeParseRunId != null) {
                    governance.updateParseRunStatus(organizationId, activeParseRunId, "FAILED", safeError(exception));
                } else governance.createParseRun(organizationId, documentId, versionId, "FAILED",
                        mediaFailure == null ? null : mediaFailure.model(), attemptedProvider,
                        mediaFailure == null ? null : mediaFailure.providerTaskId(), safeError(exception),
                        objectMapper.createObjectNode().put("retryable", false), List.of(), List.of());
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
                block.paragraphId(), block.bbox(), block.startTimeMs(), block.endTimeMs(), block.confidence());
    }

    private JsonNode parseMetadata(DocumentParser.ParsedDocument parsed, String textHash) {
        var result = objectMapper.createObjectNode().put("textSha256", textHash);
        parsed.metadata().forEach((key, value) -> result.set(key, objectMapper.valueToTree(value)));
        return result;
    }

    private DocumentView view(KnowledgeRepository.DocumentRow row) {
        return new DocumentView(row.id(), row.title(), row.documentType(), row.status(), row.scanStatus(), row.aiStatus(),
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
                row.contentType(), row.size(), row.sha256(), row.status(), row.parserVersion(), row.errorMessage(),
                row.reviewStatus(), row.reviewRevision(), row.mediaProcessingConsent());
    }

    private KnowledgeSearchFacade.SearchHit toSearchHit(KnowledgeRepository.SearchRow row, double retrieval, double rrf) {
        return new KnowledgeSearchFacade.SearchHit(row.chunkId(), row.documentId(), row.versionId(), row.title(),
                row.originalName(), row.pageNo(), row.section(), row.content(), rrf, retrieval, rrf, rrf,
                "KNOWLEDGE_CHUNK", null, null, null, null);
    }

    private String normalizeType(String value, String fileName) {
        if (StringUtils.hasText(value)) return value.trim().toUpperCase(Locale.ROOT);
        var dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toUpperCase(Locale.ROOT) : "UNKNOWN";
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

    public record CreateCommand(UUID fileId, String title, String documentType, String libraryScope, UUID categoryId) {
        public CreateCommand(UUID fileId, String title, String documentType) {
            this(fileId, title, documentType, "INTERNAL", null);
        }
    }
    public record GrantCommand(String action, String reason) { }
    public record CreateVersionCommand(UUID fileId) { }
    public record DocumentView(UUID id, String title, String documentType, String status, String scanStatus,
                               String aiStatus, int currentVersionNo, UUID currentVersionId, String originalName,
                               String contentType, long size, String sha256, String parseError,
                               java.time.Instant createdAt, java.time.Instant updatedAt, String libraryScope,
                               UUID categoryId, String categoryName, String lifecycleStatus, String reviewStatus,
                               int reviewRevision, UUID currentPublicationId, Integer currentPublicationNo) { }
    public record VersionView(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                              String contentType, long size, String sha256, String status, String parserVersion,
                              String errorMessage, String reviewStatus, int reviewRevision,
                              boolean mediaProcessingConsent) { }
}
