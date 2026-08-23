package com.jsd.aird.kb.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.kb.api.KnowledgeFileSearchFacade;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.TermAnalyzer;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeFileSearchService implements KnowledgeFileSearchFacade {

    private final KnowledgeRepository documents;
    private final KnowledgeGovernanceRepository governance;
    private final LexicalAnalyzer lexicalAnalyzer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeFileSearchService(KnowledgeRepository documents,
                                      KnowledgeGovernanceRepository governance,
                                      LexicalAnalyzer lexicalAnalyzer) {
        this.documents = documents;
        this.governance = governance;
        this.lexicalAnalyzer = lexicalAnalyzer;
    }

    @Override
    public List<FileMatch> searchFiles(UUID organizationId, String query, List<UUID> scopeIds,
                                       List<UUID> categoryIds, int limit) {
        return searchFiles(organizationId, query, scopeIds, categoryIds, null, limit);
    }

    @Override
    public List<FileMatch> searchFiles(UUID organizationId, String query, List<UUID> scopeIds,
                                       List<UUID> categoryIds, Set<UUID> allowedDocumentIds, int limit) {
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return List.of();
        var candidateLimit = Math.min(400, Math.max(40, limit * 12));
        var rows = new LinkedHashMap<UUID, KnowledgeRepository.SearchRow>();
        var terms = new java.util.LinkedHashSet<KnowledgeRepository.AnalyzedTerm>();
        TermAnalyzer.frequencies(query).keySet().forEach(term -> terms.add(
                new KnowledgeRepository.AnalyzedTerm(TermAnalyzer.VERSION, term)));
        lexicalAnalyzer.analyzeQuery(query).frequencies().keySet().forEach(term -> terms.add(
                new KnowledgeRepository.AnalyzedTerm(lexicalAnalyzer.version(), term)));
        documents.bm25Search(organizationId, List.copyOf(terms), false,
                scopeIds, categoryIds, allowedDocumentIds, candidateLimit).forEach(row -> rows.putIfAbsent(row.chunkId(), row));
        documents.fullTextSearch(organizationId, query, false, scopeIds, categoryIds, allowedDocumentIds, candidateLimit)
                .forEach(row -> rows.putIfAbsent(row.chunkId(), row));
        var grouped = new LinkedHashMap<UUID, List<KnowledgeRepository.SearchRow>>();
        for (var hit : rows.values()) grouped.computeIfAbsent(hit.versionId(), ignored -> new ArrayList<>()).add(hit);
        var result = new ArrayList<FileMatch>();
        for (var entry : grouped.entrySet()) {
            if (result.size() >= limit) break;
            var version = documents.findVersion(organizationId, entry.getKey()).orElse(null);
            if (version == null) continue;
            var first = entry.getValue().getFirst();
            var document = documents.findDocument(organizationId, first.documentId()).orElse(null);
            if (document == null) continue;
            var publication = governance.currentPublication(organizationId, document.id()).orElse(null);
            if (publication == null || !version.id().equals(publication.versionId())) continue;
            var hits = entry.getValue().stream().limit(8)
                    .map(hit -> {
                        var anchor = documents.findChunkAnchor(organizationId, hit.chunkId()).orElse(
                                new KnowledgeRepository.ChunkAnchorRow(hit.pageNo(), null, null, null, List.of(),
                                        null, null, hit.section(), null, "[]", List.of(), List.of()));
                        return new Hit(hit.chunkId(), hit.content(), hit.score(), anchor.pageNo(), anchor.sheetName(),
                                anchor.cellRange(), anchor.paragraphId(), anchor.bbox(), anchor.startTimeMs(),
                                anchor.endTimeMs(), anchor.section(), read(anchor.primaryAnchorJson()),
                                readArray(anchor.anchorsJson()), anchor.reviewNodeIds(), anchor.sourceNodeKeys());
                    }).toList();
            var matchedTerms = terms.stream().map(KnowledgeRepository.AnalyzedTerm::term)
                    .filter(term -> normalize(term).length() >= 2)
                    .filter(term -> hits.stream().anyMatch(hit -> normalize(hit.snippet()).contains(normalize(term))))
                    .distinct().limit(20).toList();
            result.add(new FileMatch(version.fileObjectId(), document.id(), version.id(), first.title(),
                    version.originalName(), version.contentType(), version.size(), version.versionNo(),
                    governance.publicationTags(organizationId, publication.id()), publication.publishedAt(), hits,
                    matchedTerms));
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9\\u4E00-\\u9FFF]", "");
    }

    private JsonNode read(String value) {
        if (value == null || value.isBlank()) return null;
        try { return objectMapper.readTree(value); } catch (Exception ignored) { return null; }
    }

    private List<JsonNode> readArray(String value) {
        var parsed = read(value);
        if (parsed == null || !parsed.isArray()) return List.of();
        var result = new ArrayList<JsonNode>();
        parsed.forEach(result::add);
        return List.copyOf(result);
    }
}
