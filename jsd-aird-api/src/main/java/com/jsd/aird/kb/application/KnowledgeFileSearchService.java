package com.jsd.aird.kb.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.jsd.aird.kb.api.KnowledgeFileSearchFacade;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.TermAnalyzer;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeFileSearchService implements KnowledgeFileSearchFacade {

    private final KnowledgeRepository documents;
    private final KnowledgeGovernanceRepository governance;

    public KnowledgeFileSearchService(KnowledgeRepository documents,
                                      KnowledgeGovernanceRepository governance) {
        this.documents = documents;
        this.governance = governance;
    }

    @Override
    public List<FileMatch> searchFiles(UUID organizationId, String query, List<UUID> scopeIds,
                                       List<UUID> categoryIds, int limit) {
        var candidateLimit = Math.min(400, Math.max(40, limit * 12));
        var rows = new LinkedHashMap<UUID, KnowledgeRepository.SearchRow>();
        documents.bm25Search(organizationId, TermAnalyzer.frequencies(query).keySet().stream().toList(), false,
                scopeIds, categoryIds, candidateLimit).forEach(row -> rows.putIfAbsent(row.chunkId(), row));
        documents.fullTextSearch(organizationId, query, false, scopeIds, categoryIds, candidateLimit)
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
            var related = governance.publicationRelations(organizationId, publication.id()).stream()
                    .map(item -> new RelatedObject(item.id(), item.objectType(), item.externalId(), item.name())).toList();
            var hits = entry.getValue().stream().limit(8)
                    .map(hit -> {
                        var anchor = documents.findChunkAnchor(organizationId, hit.chunkId()).orElse(
                                new KnowledgeRepository.ChunkAnchorRow(hit.pageNo(), null, null, null, List.of(), null, null, hit.section()));
                        return new Hit(hit.chunkId(), hit.content(), hit.score(), anchor.pageNo(), anchor.sheetName(),
                                anchor.cellRange(), anchor.paragraphId(), anchor.bbox(), anchor.startTimeMs(),
                                anchor.endTimeMs(), anchor.section());
                    }).toList();
            result.add(new FileMatch(version.fileObjectId(), document.id(), version.id(), first.title(),
                    version.originalName(), version.contentType(), version.size(), version.versionNo(),
                    governance.publicationTags(organizationId, publication.id()), related, publication.publishedAt(), hits));
        }
        return result;
    }
}
