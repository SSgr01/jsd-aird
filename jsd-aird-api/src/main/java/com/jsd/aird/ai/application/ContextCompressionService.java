package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.HashMap;
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
        var includedEvidenceRefs = new ArrayList<String>();
        var used = 0;
        var safeKnowledge = knowledge == null ? List.<KnowledgeSearchFacade.SearchHit>of() : knowledge;
        var knowledgeRefs = new HashMap<java.util.UUID, String>();
        for (var index = 0; index < safeKnowledge.size(); index++) {
            knowledgeRefs.put(safeKnowledge.get(index).chunkId(), "K" + (index + 1));
        }
        var groupedKnowledge = new LinkedHashMap<String, List<KnowledgeSearchFacade.SearchHit>>();
        for (var hit : safeKnowledge) {
            var key = String.join("|", String.valueOf(hit.documentId()), String.valueOf(hit.versionId()),
                    String.valueOf(hit.pageNo()), String.valueOf(hit.section()));
            groupedKnowledge.computeIfAbsent(key, ignored -> new ArrayList<>()).add(hit);
        }
        for (var group : groupedKnowledge.values()) {
            var remaining = Math.max(0, maxChars - used);
            if (remaining < 80) break;
            var groupParts = new ArrayList<String>();
            var groupRefs = new ArrayList<String>();
            for (var hit : group) {
                var text = normalized(hit.content());
                var image = imageMarkdown(hit);
                if (text.isBlank() && image.isBlank()) continue;
                var evidenceRef = knowledgeRefs.get(hit.chunkId());
                groupRefs.add(evidenceRef);
                groupParts.add("[evidenceRef=" + evidenceRef + "] " + text
                        + (image.isBlank() ? "" : " " + image));
            }
            if (groupParts.isEmpty()) continue;
            var groupText = String.join(" ", groupParts);
            var first = group.get(0);
            var excerpt = groupText.length() <= remaining ? groupText
                    : groupText.substring(0, Math.max(0, remaining - 1)) + "…";
            chunks.add("[source=knowledge,file=" + first.title()
                    + ",page=" + first.pageNo() + ",section=" + first.section() + "] " + excerpt);
            for (var evidenceRef : groupRefs) {
                if (excerpt.contains("[evidenceRef=" + evidenceRef + "]")) includedEvidenceRefs.add(evidenceRef);
            }
            used += excerpt.length();
        }
        var safeDataFiles = dataFiles == null ? List.<DataSourceFileSearchFacade.SourceFileHit>of() : dataFiles;
        for (var index = 0; index < safeDataFiles.size(); index++) {
            var hit = safeDataFiles.get(index);
            var remaining = Math.max(0, maxChars - used);
            if (remaining < 80) break;
            var text = hit.content() == null ? "" : hit.content().replaceAll("[\\r\\n\\t]+", " ").strip();
            var evidenceRef = "D" + (index + 1);
            var evidenceText = "[evidenceRef=" + evidenceRef + "] " + text;
            var excerpt = evidenceText.length() <= remaining ? evidenceText
                    : evidenceText.substring(0, Math.max(0, remaining - 1)) + "…";
            chunks.add("[source=data,file=" + hit.originalName()
                    + ",row=" + hit.rowNumber() + ",column=" + hit.columnName() + "] " + excerpt);
            if (excerpt.contains("[evidenceRef=" + evidenceRef + "]")) includedEvidenceRefs.add(evidenceRef);
            used += excerpt.length();
        }
        var knowledgeCount = (int) includedEvidenceRefs.stream().filter(ref -> ref.startsWith("K")).count();
        var dataFileCount = includedEvidenceRefs.size() - knowledgeCount;
        return new Context(String.join("\n\n", chunks), used, knowledgeCount, dataFileCount,
                List.copyOf(includedEvidenceRefs));
    }

    private String normalized(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").strip();
    }

    private String imageMarkdown(KnowledgeSearchFacade.SearchHit hit) {
        var anchors = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        if (hit.anchor() != null && !hit.anchor().isMissingNode() && !hit.anchor().isNull()) anchors.add(hit.anchor());
        if (hit.anchors() != null) anchors.addAll(hit.anchors());
        for (var anchor : anchors) {
            var assetId = anchor.path("assetFileId").asText("");
            if (assetId.isBlank()) continue;
            var caption = anchor.path("caption").asText("").replace("]", "").replace("\n", " ").strip();
            return "![" + caption + "](/api/v1/knowledge/assets/" + assetId + "/content)";
        }
        return "";
    }

    public record Context(String text, int characterCount, int knowledgeCount, int dataFileCount,
                          List<String> evidenceRefs) {
    }
}
