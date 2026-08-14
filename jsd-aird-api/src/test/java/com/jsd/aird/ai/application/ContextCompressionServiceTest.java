package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.junit.jupiter.api.Test;

class ContextCompressionServiceTest {

    @Test
    void keepsSourceIdentityAndAppliesBudget() {
        var hit = new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "材料规范", "spec.pdf", 4, "性能", "a".repeat(500), 0.8);
        var data = new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                12, "B", "检测报告.xlsx", "b".repeat(500), 0.7, "DATA_CENTER:Sheet1:B12");

        var context = new ContextCompressionService().compress(List.of(hit), List.of(data), 200);

        assertThat(context.characterCount()).isLessThanOrEqualTo(200);
        assertThat(context.text()).contains("chunkId=" + hit.chunkId()).contains("sourceType=KNOWLEDGE_CHUNK");
    }
}
