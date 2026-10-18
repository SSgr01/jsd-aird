package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.junit.jupiter.api.Test;

class ContextCompressionServiceTest {

    @Test
    void keepsSafeSourceMetadataAndAppliesBudget() {
        var hit = new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "材料规范", "spec.pdf", 4, "性能", "a".repeat(500), 0.8);
        var data = new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                12, "B", "检测报告.xlsx", "b".repeat(500), 0.7, "DATA_CENTER:Sheet1:B12");

        var context = new ContextCompressionService().compress(List.of(hit), List.of(data), 200);

        assertThat(context.characterCount()).isLessThanOrEqualTo(200);
        assertThat(context.characterCount()).isEqualTo(context.text().length());
        assertThat(context.text()).contains("evidenceRef=D1")
                .doesNotContain("ref=")
                .doesNotContain(hit.chunkId().toString())
                .doesNotContain(hit.documentId().toString())
                .doesNotContain(hit.versionId().toString());
    }

    @Test
    void keepsDataEvidenceAttachedToTheSameRow() {
        var data = new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                12, "B", "检测报告.xlsx", "字段=密度；值=1.05；同行数据=材料名称=TEST-TPL-丙烯酸树脂", 0.9,
                "DATA_CENTER:Sheet1:B12");

        var context = new ContextCompressionService().compress(List.of(), List.of(data), 500);

        assertThat(context.text()).contains("来源=数据中心记录")
                .contains("evidenceRef=D1")
                .doesNotContain("ref=")
                .doesNotContain(data.hitId().toString())
                .doesNotContain(data.fileObjectId().toString())
                .doesNotContain(data.importJobId().toString())
                .contains("材料名称=TEST-TPL-丙烯酸树脂");
    }

    @Test
    void showsBusinessRecordIdentityInsteadOfInternalProjectionKey() {
        var internalRecordKey = "IMPORT:STRUCT:sheet-1:COLUMN:1";
        var data = new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 12, "D", "阳离子树脂应用性能实验-003.xlsx",
                "实验编号=EXP-20260921-05361A；实验名称=UV/PU阳离子树脂应用性能；记录=G-6488；字段=UA-145；值=49.25",
                0.9, "EXPERIMENT_TEMPLATE:Sheet1:D11", internalRecordKey, "UA-145", "UA-145", "49.25",
                null, "NUMBER", "Sheet1", "D11", "EXPERIMENT", "TEMPLATE_GUIDED",
                UUID.randomUUID(), UUID.randomUUID());

        var context = new ContextCompressionService().compress(List.of(), List.of(data), 1_000);

        assertThat(context.text()).contains("; 实验编号=EXP-20260921-05361A; 业务记录=G-6488")
                .doesNotContain(internalRecordKey);
    }

    @Test
    void keepsKnowledgeAndStructuredEvidenceWhenBothSourcesArePresent() {
        var knowledge = new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "知识资料", "manual.pdf", 1, "说明", "k".repeat(1_000), 0.9);
        var data = new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                12, "B", "配方.xlsx", "原始内容", 0.9, "DATA_CENTER:Sheet1:B12",
                "APP-UV-0001", "resin", "SJ-231（配方占比）", "55.86", "%", "NUMBER", "Sheet1", "B12",
                "DATA_CENTER", "TEMPLATE_GUIDED", UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED",
                "SJ-231（配方占比）", null, null, null, "%", "%", null, "TARGET");

        var context = new ContextCompressionService().compress(List.of(knowledge), List.of(data), 600);

        assertThat(context.characterCount()).isLessThanOrEqualTo(600);
        assertThat(context.characterCount()).isEqualTo(context.text().length());
        assertThat(context.text()).contains("evidenceRef=K1", "evidenceRef=D1", "值=55.86");
    }

    @Test
    void exposesOnlyEvidenceReferencesThatFitInsideTheContextBudget() {
        var first = new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "规范", "spec.pdf", 1, "参数", "a".repeat(500), 0.9);
        var second = new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "规范", "spec.pdf", 2, "参数", "b".repeat(500), 0.8);

        var context = new ContextCompressionService().compress(List.of(first, second), List.of(), 160);

        assertThat(context.evidenceRefs()).containsExactly("K1");
        assertThat(context.knowledgeCount()).isEqualTo(1);
        assertThat(context.dataFileCount()).isZero();
    }
}
