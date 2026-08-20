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
        assertThat(context.text()).contains("chunkId=" + hit.chunkId())
                .contains("sourceType=KNOWLEDGE_CHUNK")
                .contains("evidenceRelation=TEXT_FRAGMENT");
    }

    @Test
    void keepsChunksFromTheSamePageTogether() {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var label = new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), documentId, versionId,
                "产品参数", "product.pdf", 2, "ocr-field-value", "固含量", 0.9);
        var value = new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), documentId, versionId,
                "产品参数", "product.pdf", 2, "ocr-field-value", "1.05%", 0.8);

        var context = new ContextCompressionService().compress(List.of(label, value), List.of(), 300);

        assertThat(context.text()).contains("固含量").contains("1.05%");
        assertThat(context.text().indexOf("固含量")).isLessThan(context.text().indexOf("1.05%"));
        assertThat(context.text()).contains("evidenceRelation=STRUCTURED_FIELD_VALUE");
    }

    @Test
    void keepsDataEvidenceAttachedToTheSameRow() {
        var data = new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                12, "B", "检测报告.xlsx", "字段=密度；值=1.05；同行数据=材料名称=TEST-TPL-丙烯酸树脂", 0.9,
                "DATA_CENTER:Sheet1:B12");

        var context = new ContextCompressionService().compress(List.of(), List.of(data), 500);

        assertThat(context.text()).contains("evidenceId=" + data.hitId())
                .contains("evidenceRelation=SAME_DATA_ROW")
                .contains("材料名称=TEST-TPL-丙烯酸树脂");
    }
}
