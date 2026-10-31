package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ResearchKnowledgeService implements ResearchKnowledgeFacade {
    private final KnowledgeRepository repository;
    private final KnowledgeGovernanceService governance;
    private final StructuredDocumentCodec codec;
    private final LexicalAnalyzer analyzer;
    private final ObjectProvider<KnowledgeEmbeddingFacade> embeddings;
    private final AuthorizationService authorization;
    private final ObjectMapper mapper;
    private final int dimension;

    public ResearchKnowledgeService(KnowledgeRepository repository, KnowledgeGovernanceService governance,
            StructuredDocumentCodec codec, LexicalAnalyzer analyzer, ObjectProvider<KnowledgeEmbeddingFacade> embeddings,
            AuthorizationService authorization, ObjectMapper mapper,
            @Value("${app.ai.embedding.dimension:1024}") int dimension) {
        this.repository = repository; this.governance = governance; this.codec = codec;
        this.analyzer = analyzer; this.embeddings = embeddings; this.authorization = authorization;
        this.mapper = mapper; this.dimension = dimension;
    }

    @Override
    public void authorize(List<DocumentRef> documents) {
        var actor = ActorContext.required();
        authorization.require(new com.jsd.aird.iam.api.PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
        if (documents == null || documents.size() > 100) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        for (var ref : documents) {
            authorization.require(new com.jsd.aird.iam.api.PermissionCheck(actor.organizationId(), actor.userId(), "knowledge.view", "KNOWLEDGE", ref.document_id(), "READ"));
            var view = governance.publishedContent(ref.document_id(), ref.publication_id());
            if (!repository.isAiApproved(actor.organizationId(), ref.document_id()))
                throw new ApiException(ApiErrorCode.AI_DOCUMENT_NOT_APPROVED);
            if (!view.publication().documentId().equals(ref.document_id())) throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    @Override
    public Result search(List<DocumentRef> documents, String query, int offset) {
        authorize(documents);
        return searchAuthorized(documents, query, offset);
    }

    @Override
    public Result discover(String query, int offset, String cursor) {
        authorize(List.of());
        var actor = ActorContext.required();
        int after;
        try { after = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
            if (after < 0 || after > 100000) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException error) { throw new ApiException(ApiErrorCode.BAD_REQUEST, "Invalid discovery cursor"); }
        var page = repository.researchDocuments(actor.organizationId(), after, 501);
        var window = page.stream().limit(500).toList();
        // Filter before any chunk retrieval/ranking. Forbidden titles and counts never leave this service.
        var allowed = window.stream().filter(row -> authorization.check(new com.jsd.aird.iam.api.PermissionCheck(
            actor.organizationId(), actor.userId(), "knowledge.view", "KNOWLEDGE", row.id(), "READ")).allowed())
            .map(row -> new DocumentRef(row.id(), row.publicationId())).toList();
        var result = searchAuthorized(allowed, query, offset);
        var trace = new LinkedHashMap<>(result.trace());
        trace.put("scope", "AUTHORIZED_LIBRARY");
        trace.put("coverage", page.size() > 500 ? "MORE_LIBRARY_WINDOWS_AVAILABLE" : "CURRENT_WINDOW_COMPLETE");
        return new Result(result.candidates(), trace, result.next_offset(),
            page.size() > 500 ? Integer.toString(after + 500) : null);
    }

    private Result searchAuthorized(List<DocumentRef> documents, String query, int offset) {
        if (query == null || query.isBlank() || query.length() > 4000 || offset < 0 || offset > 48)
            throw new ApiException(ApiErrorCode.BAD_REQUEST);
        if (documents.isEmpty()) return new Result(List.of(), Map.of("status", "NO_SELECTED_DOCUMENTS"), null);
        var actor = ActorContext.required();
        var refs = new HashMap<UUID, DocumentRef>();
        for (var ref : documents) refs.put(ref.document_id(), ref);
        var allowed = Set.copyOf(refs.keySet());
        // Search indexes represent the current publication, not every review snapshot.
        // A pinned historical publication stays readable, but must never inherit hits
        // from a newer correction of the same file version.
        var current = new HashMap<UUID, UUID>();
        for (var ref : documents) repository.findDocument(actor.organizationId(), ref.document_id())
            .ifPresent(document -> current.put(ref.document_id(), document.currentPublicationId()));
        var terms = analyzer.analyzeCompatibleQuery(query).stream().flatMap(family -> family.alternatives().stream()
            .map(term -> new KnowledgeRepository.AnalyzedTerm(family.ordinal(), term.analyzerVersion(), term.term()))).toList();
        var rows = new LinkedHashMap<UUID, KnowledgeRepository.SearchRow>();
        var scores = new HashMap<UUID, Double>();
        var trace = new LinkedHashMap<String, Object>();
        try {
            merge(repository.bm25Search(actor.organizationId(), terms, true, List.of(), allowed, 48), rows, scores);
            trace.put("bm25", "SUCCEEDED");
        } catch (RuntimeException error) { trace.put("bm25", "FAILED"); }
        try {
            var embedder = embeddings.getIfAvailable();
            var vector = embedder == null ? Optional.<String>empty() : embedder.embedVector(query);
            if (vector.isPresent()) {
                merge(repository.vectorSearch(actor.organizationId(), vector.get(), true, List.of(), allowed, 48, dimension), rows, scores);
                trace.put("vector", "SUCCEEDED");
            } else trace.put("vector", "UNAVAILABLE");
        } catch (RuntimeException error) { trace.put("vector", "FAILED"); }
        var metadata = repository.searchPublishedMetadata(actor.organizationId(), List.of(query), true, List.of(), allowed, 24);
        merge(metadata, rows, scores);
        if (rows.isEmpty()) merge(repository.fullTextSearch(actor.organizationId(), query, true, List.of(), allowed, 48), rows, scores);
        var ranked = rows.values().stream().filter(row -> {
            var ref = refs.get(row.documentId());
            return ref != null && ref.publication_id().equals(current.get(ref.document_id()))
                && governance.publishedContent(ref.document_id(), ref.publication_id()).publication().versionId().equals(row.versionId());
        }).sorted(Comparator.comparingDouble((KnowledgeRepository.SearchRow row) -> scores.get(row.chunkId())).reversed()).toList();
        var candidates = ranked.stream().skip(offset).limit(12).map(row -> {
            var anchor = repository.findChunkAnchor(actor.organizationId(), row.chunkId()).orElse(null);
            var locator = mapper.createObjectNode().put("chunkId", row.chunkId().toString());
            if (anchor != null) {
                if (anchor.primaryAnchorJson() != null) {
                    try { locator.setAll((com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(anchor.primaryAnchorJson())); }
                    catch (Exception ignored) { }
                }
                locator.set("sourceNodeKeys", mapper.valueToTree(anchor.sourceNodeKeys()));
            }
            var ref = refs.get(row.documentId());
            authorize(List.of(ref));
            return new Candidate(row.content(), source(ref, row.versionId(), row.chunkId().toString(), locator, row.title(), row.content()), scores.get(row.chunkId()));
        }).toList();
        trace.put("strategy", "SCOPED_BM25_VECTOR_METADATA_RRF");
        trace.put("candidate_count", ranked.size());
        trace.put("historical_documents", documents.stream().filter(ref -> !ref.publication_id().equals(current.get(ref.document_id()))).map(DocumentRef::document_id).toList());
        trace.put("historical_read", "Use document_read for pinned historical publications; no current-index substitution");
        return new Result(candidates, trace, offset + 12 < ranked.size() ? offset + 12 : null);
    }

    private void merge(List<KnowledgeRepository.SearchRow> channel, Map<UUID, KnowledgeRepository.SearchRow> rows, Map<UUID, Double> scores) {
        for (int index = 0; index < channel.size(); index++) {
            var row = channel.get(index); rows.putIfAbsent(row.chunkId(), row);
            scores.merge(row.chunkId(), 1d / (61 + index), Double::sum);
        }
    }

    @Override
    public Result read(DocumentRef ref, int offset, int limit) {
        authorize(List.of(ref));
        if (offset < 0 || limit < 1 || limit > 20) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var view = governance.publishedContent(ref.document_id(), ref.publication_id());
        var nodes = codec.project(view.confirmedDocument(), view.excludedReviewNodeIds()).nodes();
        var sourceNodes = new HashMap<UUID, KnowledgeGovernanceRepository.SourceNodeView>();
        view.sourceNodes().forEach(node -> sourceNodes.put(node.sourceNodeKey(), node));
        var result = nodes.stream().skip(offset).limit(limit).map(node -> {
            var locator = mapper.createObjectNode().put("reviewNodeId", node.reviewNodeId().toString());
            locator.set("sourceNodeKeys", mapper.valueToTree(node.sourceNodeKeys()));
            if (!node.sourceNodeKeys().isEmpty() && sourceNodes.containsKey(node.sourceNodeKeys().getFirst()))
                locator.set("original", sourceNodes.get(node.sourceNodeKeys().getFirst()).sourceAnchor());
            return new Candidate(node.text(), source(ref, view.publication().versionId(), node.reviewNodeId().toString(), locator,
                view.originalName(), node.text()), 1);
        }).toList();
        return new Result(result, Map.of("total_nodes", nodes.size(), "publication_id", ref.publication_id()),
            offset + limit < nodes.size() ? offset + limit : null);
    }

    @Override
    public Object table(DocumentRef ref, UUID tableId, int offset) {
        authorize(List.of(ref));
        return governance.publishedTable(ref.document_id(), ref.publication_id(), tableId, offset, 30, 0, 20);
    }

    @Override
    public void validate(SourceRef source, String excerpt) {
        if (!"KB_PUBLICATION".equals(source.kind()) || source.anchor() == null || source.url() != null)
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Unsupported source authority");
        var ref = new DocumentRef(UUID.fromString(source.document_id()), UUID.fromString(source.publication_id()));
        authorize(List.of(ref));
        var view = governance.publishedContent(ref.document_id(), ref.publication_id());
        if (!view.publication().versionId().toString().equals(source.version_id()))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "来源文件版本不匹配");
        String text = null;
        if (source.anchor().hasNonNull("chunkId")) {
            var document = repository.findDocument(ActorContext.required().organizationId(), ref.document_id())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (!ref.publication_id().equals(document.currentPublicationId())
                || !source.source_node_key().equals(source.anchor().get("chunkId").asText()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "检索来源已变化，请回读固定发布版本后重新提取");
            var row = repository.loadSearchRows(ActorContext.required().organizationId(), List.of(UUID.fromString(source.anchor().get("chunkId").asText())))
                .stream().filter(item -> item.documentId().equals(ref.document_id()) && item.versionId().toString().equals(source.version_id())).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            text = row.content();
            var anchor = repository.findChunkAnchor(ActorContext.required().organizationId(), row.chunkId()).orElse(null);
            var locator = mapper.createObjectNode().put("chunkId", row.chunkId().toString());
            if (anchor != null) {
                if (anchor.primaryAnchorJson() != null) try { locator.setAll((com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(anchor.primaryAnchorJson())); } catch (Exception ignored) { }
                locator.set("sourceNodeKeys", mapper.valueToTree(anchor.sourceNodeKeys()));
            }
            if (!locator.equals(source.anchor()) || !row.title().equals(source.title()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "来源位置或标题不匹配");
        } else {
            var node = codec.project(view.confirmedDocument(), view.excludedReviewNodeIds()).nodes().stream()
                .filter(item -> item.reviewNodeId().toString().equals(source.source_node_key()))
                .findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            text = node.text();
            var locator = mapper.createObjectNode().put("reviewNodeId", node.reviewNodeId().toString());
            locator.set("sourceNodeKeys", mapper.valueToTree(node.sourceNodeKeys()));
            if (!node.sourceNodeKeys().isEmpty()) view.sourceNodes().stream()
                .filter(item -> item.sourceNodeKey().equals(node.sourceNodeKeys().getFirst())).findFirst()
                .ifPresent(item -> locator.set("original", item.sourceAnchor()));
            if (!locator.equals(source.anchor()) || !view.originalName().equals(source.title()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "来源位置或标题不匹配");
        }
        if (!sha(text).equals(source.content_sha256()) || excerpt == null || !text.contains(excerpt))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "原文摘录或来源版本不匹配");
    }

    @Override public SourceRef indexedSource(com.jsd.aird.kb.api.KnowledgeSearchFacade.SearchHit hit) {
        var document = repository.findDocument(ActorContext.required().organizationId(), hit.documentId())
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        var ref = new DocumentRef(hit.documentId(), document.currentPublicationId());
        authorize(List.of(ref));
        if (!governance.publishedContent(ref.document_id(), ref.publication_id()).publication().versionId().equals(hit.versionId()))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "检索来源版本已变化");
        var value = source(ref, hit.versionId(), hit.chunkId().toString(), hit.anchor(), hit.title(), hit.content());
        // Use the exact authoritative anchor shape, including chunk ID.
        var anchor = repository.findChunkAnchor(ActorContext.required().organizationId(), hit.chunkId()).orElse(null);
        var locator = mapper.createObjectNode().put("chunkId", hit.chunkId().toString());
        if (anchor != null) {
            if (anchor.primaryAnchorJson() != null) try { locator.setAll((com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(anchor.primaryAnchorJson())); } catch (Exception ignored) { }
            locator.set("sourceNodeKeys", mapper.valueToTree(anchor.sourceNodeKeys()));
        }
        return source(ref, hit.versionId(), hit.chunkId().toString(), locator, value.title(), hit.content());
    }

    private SourceRef source(DocumentRef ref, UUID version, String node, JsonNode anchor, String title, String text) {
        return new SourceRef("KB_PUBLICATION", ref.document_id().toString(), version.toString(), ref.publication_id().toString(), node, sha(text), anchor, title, null);
    }

    private String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
