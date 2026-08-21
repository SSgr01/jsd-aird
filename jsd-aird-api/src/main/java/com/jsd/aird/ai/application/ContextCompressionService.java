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
            var orderedGroup = group.stream()
                    .sorted(java.util.Comparator.comparingInt(this::chunkOrder))
                    .toList();
            var groupParts = new ArrayList<String>();
            for (var index = 0; index < orderedGroup.size(); index++) {
                var hit = orderedGroup.get(index);
                var text = normalized(hit.content());
                if (text.isBlank()) continue;
                var relation = hit.section() != null && hit.section().toLowerCase().contains("ocr-field-value")
                        ? "STRUCTURED_FIELD_VALUE" : "TEXT_FRAGMENT";
                if (index + 1 < orderedGroup.size() && isAdjacentFieldValue(hit, orderedGroup.get(index + 1))) {
                    var value = orderedGroup.get(++index);
                    groupParts.add("[chunkId=" + hit.chunkId() + ";relatedChunkId=" + value.chunkId()
                            + ";evidenceRelation=STRUCTURED_FIELD_VALUE] " + text + " = " + normalized(value.content()));
                } else {
                    groupParts.add("[chunkId=" + hit.chunkId() + ";evidenceRelation=" + relation + "] " + text);
                }
            }
            var groupText = String.join(" ", groupParts);
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

    private int chunkOrder(KnowledgeSearchFacade.SearchHit hit) {
        return hit.chunkNo() == null || hit.chunkNo() < 0 ? Integer.MAX_VALUE : hit.chunkNo();
    }

    private String normalized(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").strip();
    }

    /**
     * MinerU may return a visually aligned two-column label/value pair as two
     * paragraph blocks. Only combine consecutive chunks that look like a
     * material parameter row; ordinary prose and headings remain independent
     * evidence so the model cannot infer facts from arbitrary adjacency.
     */
    private boolean isAdjacentFieldValue(KnowledgeSearchFacade.SearchHit label,
                                          KnowledgeSearchFacade.SearchHit value) {
        if (label.chunkNo() == null || value.chunkNo() == null
                || label.chunkNo() < 0 || value.chunkNo() != label.chunkNo() + 1
                || label.pageNo() == null || !label.pageNo().equals(value.pageNo())) return false;
        var left = normalized(label.content());
        var right = normalized(value.content());
        if (left.isBlank() || right.isBlank() || left.length() > 40 || right.length() > 120) return false;
        if (left.matches(".*[。！？；;，,]$")) return false;
        if (left.contains("说明书") || left.contains("产品特性") || left.contains("技术指标")
                || left.contains("注意事项") || left.startsWith("[") || left.startsWith("·")) return false;
        return left.matches(".*(型号|名称|外观|粘度|固含|含固|密度|溶剂|官能|颜色|包装|含量|硬度|耐磨|闪点|沸点|分子量|状态|温度|时间|能量|配方).*" );
    }

    public record Context(String text, int characterCount, int knowledgeCount, int dataFileCount) {
    }
}
