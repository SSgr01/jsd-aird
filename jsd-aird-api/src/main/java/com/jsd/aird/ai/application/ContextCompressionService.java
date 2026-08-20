package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.springframework.stereotype.Service;

/**
 * Keeps retrieval metadata intact while shrinking untrusted source text to a bounded context window.
 * It is deterministic and therefore remains available when a second model call is unavailable.
 */
@Service
public class ContextCompressionService {

    public Context compress(List<KnowledgeSearchFacade.SearchHit> knowledge,
                            List<DataSourceFileSearchFacade.SourceFileHit> dataFiles, int maxChars) {
        var chunks = new ArrayList<String>();
        var used = 0;
        var groupedKnowledge = new LinkedHashMap<String, List<KnowledgeSearchFacade.SearchHit>>();
        for (var hit : knowledge == null ? List.<KnowledgeSearchFacade.SearchHit>of() : knowledge) {
            var key = String.join("|", String.valueOf(hit.documentId()), String.valueOf(hit.versionId()),
                    String.valueOf(hit.pageNo()), String.valueOf(hit.section()));
            groupedKnowledge.computeIfAbsent(key, ignored -> new ArrayList<>()).add(hit);
        }
        for (var group : groupedKnowledge.values()) {
            var remaining = Math.max(0, maxChars - used);
            if (remaining < 80) break;
            var groupText = group.stream().map(hit -> {
                var text = hit.content() == null ? "" : hit.content().replaceAll("[\\r\\n\\t]+", " ").strip();
                var relation = hit.section() != null && hit.section().toLowerCase().contains("ocr-field-value")
                        ? "STRUCTURED_FIELD_VALUE" : "TEXT_FRAGMENT";
                return "[chunkId=" + hit.chunkId() + ";evidenceRelation=" + relation + "] " + text;
            }).reduce((left, right) -> left + " " + right).orElse("");
            var first = group.get(0);
            var excerpt = groupText.length() <= remaining ? groupText
                    : groupText.substring(0, Math.max(0, remaining - 1)) + "…";
            chunks.add("[sourceType=KNOWLEDGE_CHUNK,file=" + first.title()
                    + ",page=" + first.pageNo() + ",section=" + first.section() + "] " + excerpt);
            used += excerpt.length();
        }
        for (var hit : dataFiles == null ? List.<DataSourceFileSearchFacade.SourceFileHit>of() : dataFiles) {
            var remaining = Math.max(0, maxChars - used);
            if (remaining < 80) break;
            var text = hit.content() == null ? "" : hit.content().replaceAll("[\\r\\n\\t]+", " ").strip();
            var excerpt = text.length() <= remaining ? text : text.substring(0, Math.max(0, remaining - 1)) + "…";
            chunks.add("[evidenceId=" + hit.hitId() + ",fileObjectId=" + hit.fileObjectId() + ",importJobId=" + hit.importJobId()
                    + ",row=" + hit.rowNumber() + ",column=" + hit.columnName()
                    + ",evidenceRelation=SAME_DATA_ROW,sourceType=DATA_SOURCE_FILE] " + excerpt);
            used += excerpt.length();
        }
        return new Context(String.join("\n\n", chunks), used, knowledge == null ? 0 : knowledge.size(),
                dataFiles == null ? 0 : dataFiles.size());
    }

    public record Context(String text, int characterCount, int knowledgeCount, int dataFileCount) {
    }
}
