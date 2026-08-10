package com.jsd.aird.kb.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.FileSafetyScanner;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeService implements KnowledgeSearchFacade {

    private static final int CHUNK_SIZE = 1800;
    private static final int CHUNK_OVERLAP = 180;

    private final KnowledgeRepository repository;
    private final FileStorageFacade storage;
    private final OpsAsyncFacade async;
    private final AuditLogFacade audit;
    private final ObjectMapper objectMapper;
    private final List<DocumentParser> parsers;
    private final FileSafetyScanner scanner;
    private final ObjectProvider<KnowledgeEmbeddingFacade> embeddings;
    private final List<MediaExtractionProvider> mediaProviders;
    private final String embeddingModel;

    public KnowledgeService(
            KnowledgeRepository repository,
            FileStorageFacade storage,
            OpsAsyncFacade async,
            AuditLogFacade audit,
            ObjectMapper objectMapper,
            List<DocumentParser> parsers,
            FileSafetyScanner scanner,
            ObjectProvider<KnowledgeEmbeddingFacade> embeddings,
            List<MediaExtractionProvider> mediaProviders,
            @org.springframework.beans.factory.annotation.Value("${app.ai.embedding.model:}") String embeddingModel
    ) {
        this.repository = repository;
        this.storage = storage;
        this.async = async;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.parsers = List.copyOf(parsers);
        this.scanner = scanner;
        this.embeddings = embeddings;
        this.mediaProviders = List.copyOf(mediaProviders);
        this.embeddingModel = embeddingModel;
    }

    @Transactional
    public DocumentView create(CreateCommand command) {
        var actor = ActorContext.required();
        var file = storage.open(actor.organizationId(), command.fileId());
        try {
            var title = StringUtils.hasText(command.title()) ? command.title().trim() : file.originalName();
            var documentId = UUID.randomUUID();
            var versionId = UUID.randomUUID();
            repository.insertDocument(new KnowledgeRepository.NewDocument(
                    documentId, actor.organizationId(), title, normalizeType(command.documentType(), file.originalName()), actor.userId()
            ));
            repository.insertVersion(new KnowledgeRepository.NewVersion(
                    versionId, documentId, 1, command.fileId(), file.originalName(), file.contentType(), file.size(), file.sha256()
            ));
            var payload = objectMapper.createObjectNode()
                    .put("organizationId", actor.organizationId().toString())
                    .put("documentId", documentId.toString())
                    .put("versionId", versionId.toString())
                    .put("fileId", command.fileId().toString());
            async.enqueue(actor.organizationId(), "KB_INGEST_DOCUMENT", payload,
                    "kb-ingest:" + versionId, 40);
            async.appendOutbox("KB_DOCUMENT", documentId, "FILE_ACTIVATION_REQUESTED", payload);
            audit.append(actor.organizationId(), actor.userId(), "KB_DOCUMENT_CREATED", "KB_DOCUMENT", documentId,
                    objectMapper.createObjectNode().put("fileId", command.fileId().toString()));
            return get(documentId);
        } finally {
            closeQuietly(file);
        }
    }

    public PageResponse<DocumentView> list(String keyword, String status, String aiStatus, int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        var items = repository.listDocuments(actor.organizationId(), keyword, status, aiStatus, safePage, safeSize)
                .stream().map(this::view).toList();
        var total = repository.countDocuments(actor.organizationId(), keyword, status, aiStatus);
        return new PageResponse<>(items, safePage, safeSize, total, (total + safeSize - 1) / safeSize);
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
            var versionId = UUID.randomUUID();
            var versionNo = document.currentVersionNo() + 1;
            repository.insertVersion(new KnowledgeRepository.NewVersion(
                    versionId, documentId, versionNo, command.fileId(), file.originalName(), file.contentType(),
                    file.size(), file.sha256()
            ));
            repository.updateCurrentVersion(actor.organizationId(), documentId, versionNo);
            var payload = objectMapper.createObjectNode()
                    .put("organizationId", actor.organizationId().toString())
                    .put("documentId", documentId.toString())
                    .put("versionId", versionId.toString())
                    .put("fileId", command.fileId().toString());
            async.enqueue(actor.organizationId(), "KB_INGEST_DOCUMENT", payload,
                    "kb-ingest:" + versionId, 40);
            async.appendOutbox("KB_DOCUMENT", documentId, "FILE_ACTIVATION_REQUESTED", payload);
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
        var document = repository.findDocument(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var action = command.action() == null ? "" : command.action().trim().toUpperCase(Locale.ROOT);
        var aiStatus = switch (action) {
            case "APPROVE" -> {
                if (!"READY".equals(document.status()) || !"SAFE".equals(document.scanStatus())) {
                    throw new ApiException(ApiErrorCode.AI_DOCUMENT_NOT_APPROVED, "文件解析或安全扫描未完成，不能授权 AI 使用");
                }
                yield "APPROVED";
            }
            case "REJECT" -> "REJECTED";
            case "REVOKE" -> "REVOKED";
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "AI 授权动作只能是 APPROVE、REJECT 或 REVOKE");
        };
        repository.updateAiStatus(actor.organizationId(), documentId, aiStatus);
        audit.append(actor.organizationId(), actor.userId(), "KB_AI_GRANT_" + action, "KB_DOCUMENT", documentId,
                objectMapper.createObjectNode().put("reason", command.reason() == null ? "" : command.reason()));
        return get(documentId);
    }

    @Transactional
    public DocumentView reindex(UUID documentId) {
        var actor = ActorContext.required();
        var document = repository.findDocument(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var payload = objectMapper.createObjectNode()
                .put("organizationId", actor.organizationId().toString())
                .put("documentId", document.id().toString())
                .put("versionId", document.currentVersionId().toString())
                .put("fileId", document.currentVersionId().toString());
        var version = repository.findVersion(actor.organizationId(), document.currentVersionId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在"));
        payload.put("fileId", version.fileObjectId().toString());
        async.enqueue(actor.organizationId(), "KB_INGEST_DOCUMENT", payload,
                "kb-reindex:" + version.id() + ":" + System.currentTimeMillis(), 40);
        return get(documentId);
    }

    public FileStorageFacade.StoredFile openContent(UUID documentId) {
        var actor = ActorContext.required();
        var document = repository.findDocument(actor.organizationId(), documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件不存在"));
        var version = repository.findVersion(actor.organizationId(), document.currentVersionId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在"));
        audit.append(actor.organizationId(), actor.userId(), "KB_DOCUMENT_DOWNLOAD", "KB_DOCUMENT", documentId,
                objectMapper.createObjectNode().put("versionId", version.id().toString()));
        return storage.open(actor.organizationId(), version.fileObjectId());
    }

    public List<KnowledgeSearchFacade.SearchHit> search(UUID organizationId, String query, boolean aiOnly, int limit) {
        return search(new KnowledgeSearchFacade.SearchRequest(organizationId, query, aiOnly, limit, List.of(), List.of())).hits();
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
        var bm25 = repository.bm25Search(organizationId, List.copyOf(terms), request.aiOnly(), request.scopeIds(), safeLimit * 4);
        var fullText = bm25.isEmpty()
                ? repository.fullTextSearch(organizationId, request.query().trim(), request.aiOnly(), request.scopeIds(), safeLimit * 2)
                : bm25;
        var vector = embeddings.getIfAvailable() == null ? java.util.Optional.<String>empty()
                : embeddings.getIfAvailable().embedVector(request.query().trim());
        var vectorRows = vector.map(value -> repository.vectorSearch(organizationId, value, request.aiOnly(), request.scopeIds(), safeLimit * 4))
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

    public void ingest(UUID organizationId, UUID documentId, UUID versionId, UUID fileId) {
        repository.updateProcessing(documentId, versionId);
        var version = repository.findVersion(organizationId, versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "知识文件版本不存在"));
        try {
            repository.startProcessingStep(organizationId, documentId, versionId, "SCAN", "safety-scanner", null, version.sha256());
            var scan = scan(organizationId, fileId, version);
            if (scan.status() != FileSafetyScanner.ScanResult.Status.SAFE) {
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
                    .findFirst().orElse(null);
            var parser = parsers.stream().filter(candidate -> candidate.supports(version.originalName(), version.contentType()))
                    .findFirst().orElse(null);
            if (parser == null && mediaProvider == null) {
                repository.markFailed(documentId, versionId, "REJECTED", "当前文件格式暂不支持");
                return;
            }
            DocumentParser.ParsedDocument parsed;
            repository.startProcessingStep(organizationId, documentId, versionId, "PARSE",
                    mediaProvider == null ? parser.getClass().getSimpleName() : mediaProvider.getClass().getSimpleName(), null, version.sha256());
            if (mediaProvider != null) {
                if (!mediaProvider.isConfigured()) {
                    repository.finishProcessingStep(organizationId, versionId, "PARSE", "PENDING_PROVIDER", null, "OCR/ASR 服务尚未配置");
                    repository.markFailed(documentId, versionId, "PENDING_PROVIDER", "OCR/ASR 服务尚未配置");
                    return;
                }
                try (var stored = storage.open(organizationId, fileId)) {
                    parsed = mediaProvider.extract(stored.stream(), version.originalName());
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
            for (int index = 0; index < chunks.size(); index++) {
                var block = chunks.get(index);
                var vector = embeddings.getIfAvailable() == null ? null
                        : embeddings.getIfAvailable().embedVector(block.content()).orElse(null);
                var terms = TermAnalyzer.frequencies(block.content()).entrySet().stream()
                        .map(item -> new KnowledgeRepository.TermFrequency(item.getKey(), item.getValue())).toList();
                writes.add(new KnowledgeRepository.ChunkWrite(index, block.pageNo(), block.section(), block.content(), vector,
                        terms.stream().mapToInt(KnowledgeRepository.TermFrequency::frequency).sum(), TermAnalyzer.VERSION,
                        null, embeddingModel, terms));
            }
            var textHash = sha256(writes.stream().map(KnowledgeRepository.ChunkWrite::content).reduce("", (a, b) -> a + "\n" + b));
            repository.finishProcessingStep(organizationId, versionId, "CHUNK", "SUCCEEDED", textHash, null);
            repository.startProcessingStep(organizationId, documentId, versionId, "EMBEDDING", "spring-ai-embedding", null, textHash);
            repository.replaceChunks(documentId, versionId, writes);
            var hasVector = writes.stream().anyMatch(item -> item.vector() != null && !item.vector().isBlank());
            repository.finishProcessingStep(organizationId, versionId, "EMBEDDING", hasVector ? "SUCCEEDED" : "PENDING_PROVIDER",
                    hasVector ? textHash : null, hasVector ? null : "Embedding 服务未配置或调用失败");
            repository.markReady(documentId, versionId, parsed.parserVersion(), textHash);
            repository.startProcessingStep(organizationId, documentId, versionId, "BM25_INDEX", "postgresql-bm25", TermAnalyzer.VERSION, textHash);
            repository.rebuildTermStats(organizationId);
            repository.finishProcessingStep(organizationId, versionId, "BM25_INDEX", "SUCCEEDED", textHash, null);
            repository.startProcessingStep(organizationId, documentId, versionId, "VECTOR_INDEX", "pgvector", null, textHash);
            repository.finishProcessingStep(organizationId, versionId, "VECTOR_INDEX", hasVector ? "SUCCEEDED" : "PENDING_PROVIDER",
                    hasVector ? textHash : null, hasVector ? null : "Embedding 服务未配置或调用失败");
        } catch (Exception exception) {
            repository.markFailed(documentId, versionId, "FAILED", safeError(exception));
            throw exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
        }
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
                result.add(new DocumentParser.TextBlock(block.pageNo(), block.section(), text));
                continue;
            }
            for (int start = 0; start < text.length(); start += CHUNK_SIZE - CHUNK_OVERLAP) {
                var end = Math.min(text.length(), start + CHUNK_SIZE);
                result.add(new DocumentParser.TextBlock(block.pageNo(), block.section(), text.substring(start, end)));
                if (end == text.length()) break;
            }
        }
        return result;
    }

    private DocumentView view(KnowledgeRepository.DocumentRow row) {
        return new DocumentView(row.id(), row.title(), row.documentType(), row.status(), row.scanStatus(), row.aiStatus(),
                row.currentVersionNo(), row.currentVersionId(), row.originalName(), row.contentType(), row.size(),
                row.sha256(), row.parseError(), row.createdAt(), row.updatedAt());
    }

    private VersionView versionView(KnowledgeRepository.VersionRow row) {
        return new VersionView(row.id(), row.documentId(), row.versionNo(), row.fileObjectId(), row.originalName(),
                row.contentType(), row.size(), row.sha256(), row.status(), row.parserVersion(), row.errorMessage());
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

    public record CreateCommand(UUID fileId, String title, String documentType) { }
    public record GrantCommand(String action, String reason) { }
    public record CreateVersionCommand(UUID fileId) { }
    public record DocumentView(UUID id, String title, String documentType, String status, String scanStatus,
                               String aiStatus, int currentVersionNo, UUID currentVersionId, String originalName,
                               String contentType, long size, String sha256, String parseError,
                               java.time.Instant createdAt, java.time.Instant updatedAt) { }
    public record VersionView(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                              String contentType, long size, String sha256, String status, String parserVersion,
                              String errorMessage) { }
}
