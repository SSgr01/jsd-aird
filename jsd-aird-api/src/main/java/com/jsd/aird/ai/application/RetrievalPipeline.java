package com.jsd.aird.ai.application;

import com.jsd.aird.ai.application.port.AssistantRepository;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Shared fixed-QA retrieval/selection, not an answer-generating Agent. */
@Service
public class RetrievalPipeline {
    private final RagRetrievalService rag;
    private final ContextCompressionService compression;

    public RetrievalPipeline(RagRetrievalService rag, ContextCompressionService compression) {
        this.rag = rag; this.compression = compression;
    }

    public RagRetrievalService.Retrieval retrieve(UUID organizationId, String question,
            List<AssistantRepository.MessageRow> history, List<UUID> knowledgeCategories, List<UUID> dataCategories,
            boolean web, DataSourceFileSearchFacade.RetrievalAccessScope scope) {
        return rag.retrieve(organizationId, question, history, knowledgeCategories, dataCategories, true, web, scope);
    }

    public ContextCompressionService.Context compress(List<KnowledgeSearchFacade.SearchHit> knowledge,
            List<DataSourceFileSearchFacade.SourceFileHit> data, int budget) {
        return compression.compress(knowledge, data, budget);
    }
}
