package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.List;

import com.jsd.aird.data.api.DataAssetSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.springframework.stereotype.Service;

/**
 * Keeps retrieval metadata intact while shrinking untrusted source text to a bounded context window.
 * It is deterministic and therefore remains available when a second model call is unavailable.
 */
@Service
public class ContextCompressionService {

    public Context compress(List<KnowledgeSearchFacade.SearchHit> knowledge,
                            List<DataAssetSearchFacade.DataHit> dataAssets, int maxChars) {
        var chunks = new ArrayList<String>();
        var used = 0;
        for (var hit : knowledge == null ? List.<KnowledgeSearchFacade.SearchHit>of() : knowledge) {
            var text = hit.content() == null ? "" : hit.content().replaceAll("[\\r\\n\\t]+", " ").strip();
            var remaining = Math.max(0, maxChars - used);
            if (remaining < 80) break;
            var excerpt = text.length() <= remaining ? text : text.substring(0, Math.max(0, remaining - 1)) + "…";
            chunks.add("[chunkId=" + hit.chunkId() + ",sourceType=KNOWLEDGE_CHUNK,file=" + hit.title()
                    + ",page=" + hit.pageNo() + ",section=" + hit.section() + "] " + excerpt);
            used += excerpt.length();
        }
        for (var hit : dataAssets == null ? List.<DataAssetSearchFacade.DataHit>of() : dataAssets) {
            var remaining = Math.max(0, maxChars - used);
            if (remaining < 80) break;
            var text = hit.content() == null ? "" : hit.content().replaceAll("[\\r\\n\\t]+", " ").strip();
            var excerpt = text.length() <= remaining ? text : text.substring(0, Math.max(0, remaining - 1)) + "…";
            chunks.add("[dataEntryId=" + hit.entryId() + ",assetId=" + hit.assetId() + ",revisionId=" + hit.revisionId()
                    + ",field=" + hit.fieldCode() + ",sourceType=DATA_ASSET_FIELD] " + excerpt);
            used += excerpt.length();
        }
        return new Context(String.join("\n\n", chunks), used, knowledge == null ? 0 : knowledge.size(),
                dataAssets == null ? 0 : dataAssets.size());
    }

    public record Context(String text, int characterCount, int knowledgeCount, int dataAssetCount) {
    }
}
