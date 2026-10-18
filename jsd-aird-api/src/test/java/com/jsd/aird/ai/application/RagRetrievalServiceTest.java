package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.ai.application.port.WebSearchProvider;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.junit.jupiter.api.Test;

class RagRetrievalServiceTest {

    @Test
    void infersRecordAndFieldFromChinesePossessiveLookup() {
        var inferred = RagRetrievalService.inferFieldLookup("SJ-230水洗后的粘度是多少？");

        assertThat(inferred.recordTerm()).isEqualTo("SJ-230水洗后");
        assertThat(inferred.fieldTerm()).isEqualTo("粘度");
    }

    @Test
    void removesAssistantScopePrefixBeforeInferringRecordIdentifier() {
        var inferred = RagRetrievalService.inferFieldLookup(
                "只查数据中心：实验编号 APP-UV-0001 的粘度、硬度和钢丝绒耐磨结果");

        assertThat(inferred.recordTerm()).isEqualTo("APP-UV-0001");
    }

    @Test
    void ignoresNaturalLanguageAnalysisInstructionWhenInferringTheField() {
        var inferred = RagRetrievalService.inferFieldLookup("SJ-230水洗后的粘度是多少？请用自然语言简单分析");

        assertThat(inferred.recordTerm()).isEqualTo("SJ-230水洗后");
        assertThat(inferred.fieldTerm()).isEqualTo("粘度");
    }

    @Test
    void ignoresTheSelectedFilePrefixWhenInferringRecordAndField() {
        var inferred = RagRetrievalService.inferFieldLookup(
                "在数据文件《应用测试报告.xlsx》中查询：SJ-230水洗后的粘度是多少？");

        assertThat(inferred.recordTerm()).isEqualTo("SJ-230水洗后");
        assertThat(inferred.fieldTerm()).isEqualTo("粘度");
    }

    @Test
    void recognizesAStandaloneBusinessField() {
        var inferred = RagRetrievalService.inferFieldLookup("粘度是多少？");

        assertThat(inferred.recordTerm()).isEmpty();
        assertThat(inferred.fieldTerm()).isEqualTo("粘度");
    }

    @Test
    void extractsChineseExperimentEntitiesWithoutGeneratingNgrams() {
        assertThat(RagRetrievalService.extractStructuredQueryTerms("陈琪的实验有什么").entities())
                .containsExactly("陈琪");
        assertThat(RagRetrievalService.extractStructuredQueryTerms("分析甲油胶实验的数据").entities())
                .containsExactly("甲油胶实验");
    }

    @Test
    void extractsAllIdentifiersFromAChineseRangeWithoutTreatingMeasurementsAsIds() {
        var question = "请汇总 NOHIT-20261013-XYZ 和 XZ001至XZ003 的测试数据，膜厚 10-20 μm。";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()));

        assertThat(RagRetrievalService.explicitQueryIdentifiers(question, plan))
                .contains("NOHIT-20261013-XYZ", "XZ001", "XZ002", "XZ003")
                .doesNotContain("10-20", "20261013");
    }

    @Test
    void explicitIdentifierMatchingKeepsTokenBoundariesAndIgnoresHyphenDifferences() {
        assertThat(RagRetrievalService.containsExplicitIdentifier(List.of("G-6489"), "业务记录：G6489；字段=附着力"))
                .isTrue();
        assertThat(RagRetrievalService.containsExplicitIdentifier(List.of("G-6489"), "业务记录：G-64890；字段=附着力"))
                .isFalse();
    }

    @Test
    void longestMatchKeepsCompleteModelAndCombinationNames() {
        assertThat(RagRetrievalService.extractStructuredQueryTerms(
                "样品：超耐黄变聚氨酯（UV-01A）", List.of("超耐黄变聚氨酯", "UV-01A" )).entities())
                .containsExactly("超耐黄变聚氨酯（UV-01A）");
        assertThat(RagRetrievalService.extractStructuredQueryTerms(
                "查询耐高温水性聚氨酯A-12的粘度", List.of("耐高温水性聚氨酯A-12", "A-12" )).entities())
                .containsExactly("耐高温水性聚氨酯A-12");
    }

    @Test
    void longestMatchKeepsIndependentEntitiesTogether() {
        assertThat(RagRetrievalService.extractStructuredQueryTerms(
                "陈琪的实验有什么，样品：UV-01A").entities())
                .containsExactly("UV-01A", "陈琪");
    }

    @Test
    void doesNotTurnTheRestOfAnIdentifierQuestionIntoARecordTerm() {
        assertThat(RagRetrievalService.extractStructuredQueryTerms(
                "只查数据中心：实验编号 APP-UV-0001 的粘度、硬度和钢丝绒耐磨结果").entities())
                .contains("APP-UV-0001")
                .doesNotContain("APP-UV-0001 的粘度、硬度和钢丝绒耐磨结果");
        assertThat(RagRetrievalService.extractStructuredQueryTerms(
                "比较 APP-UV-0001 和 APP-UV-0002 的粘度").entities())
                .contains("APP-UV-0001", "APP-UV-0002");
    }

    @Test
    void keepsAChinesePrefixedIdentifierWithSeparatorsIntact() {
        assertThat(RagRetrievalService.extractStructuredQueryTerms(
                "配方-001-01 里同一个指标有多个观察值").entities())
                .contains("配方-001-01")
                .doesNotContain("001-01");
    }

    @Test
    void mapsExplicitLifecycleWordsToADataEvidenceBoundary() {
        assertThat(RagRetrievalService.inferEvidenceFilter("正式数据中心中 APP-UV-0001 的粘度"))
                .isEqualTo(DataSourceFileSearchFacade.EvidenceFilter.CONFIRMED_DATA);
        assertThat(RagRetrievalService.inferEvidenceFilter("只看已完成的实验 EXP-20260918-1B9584"))
                .isEqualTo(DataSourceFileSearchFacade.EvidenceFilter.COMPLETED_EXPERIMENT);
        assertThat(RagRetrievalService.inferEvidenceFilter("实验本里 APP-UV-0001 的粘度"))
                .isEqualTo(DataSourceFileSearchFacade.EvidenceFilter.ANY);
    }

    @Test
    void doesNotTurnAnExplicitWorkbookNameIntoRecordIdentifiers() {
        var question = "正式数据中心文件《2026年09月_应用配方性能评估_数据中心导入_240条.xlsx》中 APP-UV-0001 的粘度是多少？";

        assertThat(RagRetrievalService.extractStructuredQueryTerms(
                question.replaceAll("(?i)[“\\\"《][^”\\\"》\\r\\n]{1,260}\\.(?:xlsx|xls|csv)[”\\\"》]", " "))
                .entities()).contains("APP-UV-0001")
                .doesNotContain("2026", "09", "240", "xlsx");
    }

    @Test
    void comparisonUsesIndependentIdentifiersAndCanonicalFieldNames() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var categoryId = UUID.randomUUID();
        var question = "只查数据中心：比较 APP-UV-0001 和 APP-UV-0002 的粘度、硬度和钢丝绒耐磨观察值，不要展示数据明细。";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("FIELD_LOOKUP", "",
                List.of("比较 APP-UV-0001 和 APP-UV-0002"), List.of("粘度", "硬度", "钢丝绒耐磨观察值")));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        var captured = new AtomicReference<DataSourceFileSearchFacade.DataDetailQuery>();
        when(data.queryDetails(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class)))
                .thenAnswer(invocation -> {
                    var query = invocation.getArgument(1, DataSourceFileSearchFacade.DataDetailQuery.class);
                    captured.set(query);
                    return DataSourceFileSearchFacade.DataDetailResult.empty(query.mode());
                });
        when(reranker.isConfigured()).thenReturn(false);

        new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(), List.of(categoryId), true);

        assertThat(captured.get().recordTerms()).containsExactly("APP-UV-0001", "APP-UV-0002");
        assertThat(captured.get().recordTermMatch()).isEqualTo(DataSourceFileSearchFacade.RecordTermMatch.ANY);
        assertThat(captured.get().fieldTerms()).contains("粘度", "硬度", "钢丝绒耐磨");
        assertThat(captured.get().recordTerms()).doesNotContain("比较 APP-UV-0001 和 APP-UV-0002");
    }

    @Test
    void ignoresAnswerInstructionsWhenBuildingRecordTerms() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var categoryId = UUID.randomUUID();
        var question = "正式数据中心中 APP-UV-001 的粘度是多少？我少写了一个 0，请把命中的实验编号原样写出，并给出粘度。";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("FIELD_LOOKUP", "",
                List.of("APP-UV-001", "原样写出", "把命中"), List.of("粘度")));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        var captured = new AtomicReference<DataSourceFileSearchFacade.DataDetailQuery>();
        when(data.queryDetails(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class)))
                .thenAnswer(invocation -> {
                    var query = invocation.getArgument(1, DataSourceFileSearchFacade.DataDetailQuery.class);
                    captured.set(query);
                    return DataSourceFileSearchFacade.DataDetailResult.empty(query.mode());
                });
        when(reranker.isConfigured()).thenReturn(false);

        new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(), List.of(categoryId), true);

        assertThat(captured.get().recordTerms()).containsExactly("APP-UV-001");
    }

    @Test
    void doesNotTreatTestConditionsAsExtraRecordIdsWhenQuestionNamesARecord() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var categoryId = UUID.randomUUID();
        var question = "G-6489 的初始附着力、85度水煮1小时后和再沸水煮1小时后分别是多少？";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("FIELD_LOOKUP", "",
                List.of("G-6489", "85", "1"), List.of("初始附着力", "水煮后附着力")));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        var captured = new AtomicReference<DataSourceFileSearchFacade.DataDetailQuery>();
        when(data.queryDetails(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class)))
                .thenAnswer(invocation -> {
                    var query = invocation.getArgument(1, DataSourceFileSearchFacade.DataDetailQuery.class);
                    captured.set(query);
                    return DataSourceFileSearchFacade.DataDetailResult.empty(query.mode());
                });
        when(reranker.isConfigured()).thenReturn(false);

        new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(), List.of(categoryId), true);

        assertThat(captured.get().recordTerms()).containsExactly("G-6489");
        assertThat(captured.get().fieldTerms()).contains("初始附着力", "水煮后附着力");
    }

    @Test
    void treatsMaterialAbbreviationsAsTopicsAndAllowsExplicitlyLabelledAlphabeticIds() {
        assertThat(RagRetrievalService.isStrongExplicitRecordTerm(
                "PMMA", "高硬度低收缩、对PMMA有附着的可拉伸树脂实验数据", false)).isFalse();
        assertThat(RagRetrievalService.isStrongExplicitRecordTerm(
                "ABC", "查询编号 ABC 的实验记录", false)).isTrue();
        assertThat(RagRetrievalService.isStrongExplicitRecordTerm(
                "85", "G-6489 的85度水煮结果", true)).isFalse();
    }

    @Test
    void doesNotInferMaterialAbbreviationsAsRecordIdsForBroadTopicQueries() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var question = "高硬度低收缩、对PMMA有附着的可拉伸树脂项目实验数据";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("RECORD_DETAIL", "",
                List.of("PMMA"), List.of("附着力")));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        var captured = new AtomicReference<DataSourceFileSearchFacade.DataDetailQuery>();
        when(data.queryDetails(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class)))
                .thenAnswer(invocation -> {
                    var query = invocation.getArgument(1, DataSourceFileSearchFacade.DataDetailQuery.class);
                    captured.set(query);
                    return DataSourceFileSearchFacade.DataDetailResult.empty(query.mode());
                });
        when(reranker.isConfigured()).thenReturn(false);

        new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(), List.of(UUID.randomUUID()), true);

        assertThat(captured.get().recordTerms()).doesNotContain("PMMA");
    }

    @Test
    void keepsUnitBearingNumericReverseLookupWhenQuestionHasNoRecordIdentifier() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var categoryId = UUID.randomUUID();
        var question = "查询粘度887.4mPa·s对应的记录";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("FIELD_LOOKUP", "",
                List.of("887.4"), List.of("粘度")));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        var captured = new AtomicReference<DataSourceFileSearchFacade.DataDetailQuery>();
        when(data.queryDetails(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class)))
                .thenAnswer(invocation -> {
                    var query = invocation.getArgument(1, DataSourceFileSearchFacade.DataDetailQuery.class);
                    captured.set(query);
                    return DataSourceFileSearchFacade.DataDetailResult.empty(query.mode());
                });
        when(reranker.isConfigured()).thenReturn(false);

        new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(), List.of(categoryId), true);

        assertThat(captured.get().recordTerms()).anySatisfy(term -> assertThat(term).contains("887.4"));
    }

    @Test
    void dataRequestNoneStillSearchesTheOriginalQuestionAndItsGenericTopicTerms() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var categoryId = UUID.randomUUID();
        var question = "阳离子单体、树脂的性能及应用测试的实验中，实验配方是什么？";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        when(reranker.isConfigured()).thenReturn(false);

        var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(), List.of(categoryId), true);

        verify(data).search(any(), eq("树脂"), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
        verify(data).search(any(), eq("阳离子单体"), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
        assertThat(retrieval.trace().channels()).anySatisfy(channel -> {
            assertThat(channel.channel()).isEqualTo("STRUCTURED_DATA_ROW");
            assertThat(channel.status()).isEqualTo("EMPTY");
        });
        assertThat(retrieval.trace().channels()).anySatisfy(channel ->
                assertThat(channel.channel()).isEqualTo("EXPERIMENT_ROW"));
    }

    @Test
    void experimentContextExpansionUsesOnlyTopDistinctRecords() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var categoryId = UUID.randomUUID();
        var importJobId = UUID.randomUUID();
        var question = "x";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        var hits = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(),
                        importJobId, index + 1, "D", "实验.xlsx", "evidence", 20 - index,
                        "EXPERIMENT_TEMPLATE:Sheet1:D" + (index + 1), "record-" + index, "field-" + index,
                        "字段", "值", null, "TEXT", "Sheet1", "D" + (index + 1), "EXPERIMENT",
                        "TEMPLATE_GUIDED", UUID.randomUUID(), UUID.randomUUID()))
                .toList();
        when(data.search(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt()))
                .thenReturn(hits);
        when(data.expandExperimentContexts(any(), anyList(), anyList(),
                any(DataSourceFileSearchFacade.RetrievalAccessScope.class))).thenReturn(List.of());
        when(reranker.isConfigured()).thenReturn(false);

        new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(), List.of(categoryId), true);

        var seeds = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(data).expandExperimentContexts(any(), seeds.capture(), anyList(),
                any(DataSourceFileSearchFacade.RetrievalAccessScope.class));
        assertThat(seeds.getValue()).hasSize(12)
                .extracting(hit -> ((DataSourceFileSearchFacade.SourceFileHit) hit).recordKey())
                .doesNotHaveDuplicates();
    }

    @Test
    void emptyExactLookupFallsBackToGeneralStructuredSearch() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var categoryId = UUID.randomUUID();
        var question = "高硬度低收缩、对PMMA有附着的可拉伸树脂 项目实验数据";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("FIELD_LOOKUP", "", List.of(), List.of()));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        when(data.queryDetails(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class)))
                .thenAnswer(invocation -> DataSourceFileSearchFacade.DataDetailResult.empty(
                        invocation.getArgument(1, DataSourceFileSearchFacade.DataDetailQuery.class).mode()));
        when(reranker.isConfigured()).thenReturn(false);

        new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(), List.of(categoryId), true);

        verify(data).search(any(), eq("PMMA有附着"), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
        verify(data).search(any(), eq("PMMA"), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
        verify(data).search(any(), eq("拉伸"), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
        verify(data).search(any(), eq("可拉伸树脂"), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
    }

    @Test
    void broadTopicAmbiguityFallsBackToAuthorizedSearchButCodedRecordAmbiguityIsPreserved() {
        var categoryId = UUID.randomUUID();
        var candidates = List.of(new DataSourceFileSearchFacade.DataFileCandidate("记录A.xlsx"),
                new DataSourceFileSearchFacade.DataFileCandidate("记录B.xlsx"));
        var broadQuestion = "高硬度低收缩、对PMMA有附着的可拉伸树脂项目实验数据";
        var broadKnowledge = mock(KnowledgeSearchFacade.class);
        var broadData = mock(DataSourceFileSearchFacade.class);
        var broadRewrite = mock(QueryRewriteService.class);
        var broadReranker = mock(RerankerProvider.class);
        var broadPlan = new QueryRewriteService.QueryPlan(broadQuestion, broadQuestion, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("FIELD_LOOKUP", "",
                List.of("PMMA"), List.of()));
        when(broadRewrite.rewrite(eq(broadQuestion), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(broadPlan, "MODEL", "fast", false));
        when(broadData.queryDetails(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class)))
                .thenReturn(new DataSourceFileSearchFacade.DataDetailResult(
                        DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP,
                        DataSourceFileSearchFacade.DataResolution.AMBIGUOUS,
                        null, null, "", 0, 0, List.of(), false, candidates, false));
        when(broadReranker.isConfigured()).thenReturn(false);

        var broadRetrieval = new RagRetrievalService(broadKnowledge, broadData, broadRewrite, broadReranker)
                .retrieve(UUID.randomUUID(), broadQuestion, List.of(), List.of(), List.of(categoryId), true);

        verify(broadData).search(any(), eq("PMMA有附着"), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
        verify(broadData).search(any(), eq("PMMA"), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
        verify(broadData).search(any(), eq("拉伸"), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
        assertThat(broadRetrieval.dataDetail().requiresFileSelection()).isFalse();

        var codedQuestion = "SJ-231 批次物性";
        var codedData = mock(DataSourceFileSearchFacade.class);
        var codedRewrite = mock(QueryRewriteService.class);
        var codedReranker = mock(RerankerProvider.class);
        var codedPlan = new QueryRewriteService.QueryPlan(codedQuestion, codedQuestion, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("RECORD_DETAIL", "",
                List.of("SJ-231"), List.of()));
        when(codedRewrite.rewrite(eq(codedQuestion), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(codedPlan, "MODEL", "fast", false));
        when(codedData.queryDetails(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class)))
                .thenReturn(new DataSourceFileSearchFacade.DataDetailResult(
                        DataSourceFileSearchFacade.DataQueryMode.RECORD_DETAIL,
                        DataSourceFileSearchFacade.DataResolution.AMBIGUOUS,
                        null, null, "", 0, 0, List.of(), false, candidates, false));
        when(codedReranker.isConfigured()).thenReturn(false);

        var codedRetrieval = new RagRetrievalService(mock(KnowledgeSearchFacade.class), codedData,
                codedRewrite, codedReranker)
                .retrieve(UUID.randomUUID(), codedQuestion, List.of(), List.of(), List.of(categoryId), true);

        assertThat(codedRetrieval.dataDetail().requiresFileSelection()).isTrue();
        verify(codedData, never()).search(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
    }

    @Test
    void explicitEmptyScopesDoNotSearchAnySource() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var plan = new QueryRewriteService.QueryPlan("question", "question", List.of(), List.of(),
                List.of(), Map.of(), "", List.of());
        when(rewrite.rewrite(eq("question"), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "ORIGINAL", "", false));

        var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), "question", List.of(), List.of(), List.of(), true);

        assertThat(retrieval.knowledgeHits()).isEmpty();
        assertThat(retrieval.dataHits()).isEmpty();
        verifyNoInteractions(knowledge, data);
    }

    @Test
    void explicitRecordIdentifiersDoNotFilterTheIndependentKnowledgeBaseResults() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var question = "型号 G-6489 的应用说明和测试方法是什么？";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        var knowledgeHits = List.of(hit(1), hit(2));
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class)))
                .thenReturn(new KnowledgeSearchFacade.SearchResult(knowledgeHits,
                        new KnowledgeSearchFacade.RetrievalTrace("test", 2, 2, 2, List.of())));
        when(reranker.isConfigured()).thenReturn(false);

        var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(UUID.randomUUID()), List.of(), true);

        assertThat(retrieval.knowledgeHits()).extracting(KnowledgeSearchFacade.SearchHit::chunkId)
                .containsExactlyElementsOf(knowledgeHits.stream()
                        .map(KnowledgeSearchFacade.SearchHit::chunkId).toList());
        verify(data, never()).search(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt());
    }

    @Test
    void structuredRecordLookupKeepsOnlyKnowledgeEvidenceThatNamesTheRequestedIdentifier() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var question = "只查实验记录 QZ-999999 的实验数据和性能结果";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("FIELD_LOOKUP", "",
                List.of("QZ-999999"), List.of("实验数据", "性能结果")));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        var unrelated = hit(1);
        var matching = new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "QZ-999999 产品测试", "qz.pdf", 1, "性能", "QZ-999999 的测试记录", 0.9);
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class)))
                .thenReturn(new KnowledgeSearchFacade.SearchResult(List.of(unrelated, matching),
                        new KnowledgeSearchFacade.RetrievalTrace("test", 2, 2, 2, List.of())));
        when(data.queryDetails(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class)))
                .thenAnswer(invocation -> DataSourceFileSearchFacade.DataDetailResult.empty(
                        invocation.getArgument(1, DataSourceFileSearchFacade.DataDetailQuery.class).mode()));
        when(reranker.isConfigured()).thenReturn(false);

        var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), question, List.of(), List.of(UUID.randomUUID()),
                        List.of(UUID.randomUUID()), true);

        assertThat(retrieval.knowledgeHits()).extracting(KnowledgeSearchFacade.SearchHit::chunkId)
                .containsExactly(matching.chunkId());
    }

    @Test
    void reranksThirtyCandidatesAndCoverageCanRecoverCandidateFourteen() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var organizationId = UUID.randomUUID();
        var categoryId = UUID.randomUUID();
        var plan = new QueryRewriteService.QueryPlan("original", "rewritten", List.of("fact query"),
                List.of(new QueryRewriteService.RetrievalTerm("dynamic", List.of("alias"), "PHRASE")),
                List.of(new QueryRewriteService.RequiredFact("requested fact", "fact query")), Map.of(), "", List.of());
        when(rewrite.rewrite(eq("original"), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));

        var hits = new ArrayList<KnowledgeSearchFacade.SearchHit>();
        for (var index = 0; index < 30; index++) hits.add(hit(index));
        var target = hits.get(13);
        var result = new KnowledgeSearchFacade.SearchResult(hits,
                new KnowledgeSearchFacade.RetrievalTrace("test", 30, 30, 30, List.of()),
                List.of(new KnowledgeSearchFacade.FactCandidateSet("requested fact", "fact query",
                        List.of(target.chunkId()))));
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class))).thenReturn(result);

        when(reranker.isConfigured()).thenReturn(true);
        var ranked = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> new RerankerProvider.RankedDocument(hits.get(index).chunkId().toString(),
                        1d - index / 100d, index, "test", "test-model")).toList();
        when(reranker.rerank(eq("rewritten"), anyList())).thenReturn(new RerankerProvider.RerankOutcome(
                ranked, "SUCCEEDED", "test", "test-model", 200, "", 10, 30, 12));

        var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(organizationId, "original", List.of(), List.of(categoryId), List.of(), true);

        assertThat(retrieval.knowledgeHits()).hasSize(12);
        assertThat(retrieval.knowledgeHits().getFirst().chunkId()).isEqualTo(target.chunkId());
        assertThat(retrieval.trace().factCoverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.status()).isEqualTo("COVERED");
            assertThat(coverage.chunkId()).isEqualTo(target.chunkId());
        });
    }

    @Test
    void knowledgeAndDataChannelsRunInParallelAndBothCompleteBeforeMerge() throws Exception {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var web = mock(WebSearchProvider.class);
        var knowledgeStarted = new CountDownLatch(1);
        var dataStarted = new CountDownLatch(1);
        var plan = new QueryRewriteService.QueryPlan("question", "question", List.of(), List.of(), List.of(),
                Map.of(), "", List.of());
        when(rewrite.rewrite(eq("question"), anyList(), eq(false)))
                .thenReturn(new QueryRewriteService.Result(plan, "ORIGINAL", "", false));
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class))).thenAnswer(ignored -> {
            knowledgeStarted.countDown();
            assertThat(dataStarted.await(1, TimeUnit.SECONDS)).isTrue();
            return new KnowledgeSearchFacade.SearchResult(List.of(),
                    new KnowledgeSearchFacade.RetrievalTrace("test", 0, 0, 0, List.of()));
        });
        when(data.search(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt()))
                .thenAnswer(ignored -> {
            dataStarted.countDown();
            assertThat(knowledgeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        when(reranker.isConfigured()).thenReturn(false);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker, web, Runnable::run,
                    executor, Duration.ofSeconds(1), 6).retrieve(UUID.randomUUID(), "question", List.of(),
                    List.of(UUID.randomUUID()), List.of(UUID.randomUUID()), true, false);

            assertThat(retrieval.trace().channels()).extracting(RagRetrievalService.ChannelTrace::status)
                    .contains("EMPTY");
            verify(knowledge).search(any(KnowledgeSearchFacade.SearchRequest.class));
            verify(data).search(any(), eq("question"), anyList(),
                    any(DataSourceFileSearchFacade.AccessScope.class), eq(10));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void independentStructuredSearchTermsRunConcurrently() throws Exception {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var web = mock(WebSearchProvider.class);
        var bothStarted = new CountDownLatch(2);
        var concurrent = new java.util.concurrent.atomic.AtomicBoolean();
        var question = "树脂和性能";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        when(data.search(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt()))
                .thenAnswer(ignored -> {
                    bothStarted.countDown();
                    if (bothStarted.await(1, TimeUnit.SECONDS)) concurrent.set(true);
                    return List.of();
                });
        when(reranker.isConfigured()).thenReturn(false);
        var dataSearchExecutor = Executors.newFixedThreadPool(2);
        try {
            new RagRetrievalService(knowledge, data, rewrite, reranker, web, Runnable::run, Runnable::run,
                    dataSearchExecutor, Duration.ofSeconds(1), 6, 16)
                    .retrieve(UUID.randomUUID(), question, List.of(), List.of(), List.of(UUID.randomUUID()),
                            true, false);

            assertThat(concurrent).isTrue();
        } finally {
            dataSearchExecutor.shutdownNow();
        }
    }

    @Test
    void webSearchRunsInParallelWithInternalRetrieval() throws Exception {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var web = mock(WebSearchProvider.class);
        var webStarted = new CountDownLatch(1);
        var internalFinished = new CountDownLatch(1);
        var categoryId = UUID.randomUUID();
        var plan = new QueryRewriteService.QueryPlan("A-186", "A-186", List.of(), List.of(), List.of(),
                Map.of(), "", List.of(new QueryRewriteService.WebQuery("A-186 silane", "GENERAL", "ALL")));
        when(rewrite.rewrite(eq("A-186"), anyList(), eq(true)))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        when(web.isAvailable()).thenReturn(true);
        when(web.search(any(WebSearchProvider.SearchQuery.class))).thenAnswer(invocation -> {
            webStarted.countDown();
            assertThat(internalFinished.await(1, TimeUnit.SECONDS)).isTrue();
            return new WebSearchProvider.SearchResponse("SUCCEEDED", List.of(
                    new WebSearchProvider.SearchResult("A-186", "https://example.com/a-186", "public evidence",
                            0.9, "2026-08-01")), 20, 10, 200, "");
        });
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class))).thenAnswer(invocation -> {
            assertThat(webStarted.await(1, TimeUnit.SECONDS)).isTrue();
            internalFinished.countDown();
            return new KnowledgeSearchFacade.SearchResult(List.of(),
                    new KnowledgeSearchFacade.RetrievalTrace("test", 0, 0, 0, List.of()));
        });
        when(reranker.isConfigured()).thenReturn(false);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker, web, executor,
                    Duration.ofSeconds(2), 6).retrieve(UUID.randomUUID(), "A-186", List.of(),
                    List.of(categoryId), List.of(), true, true);

            assertThat(retrieval.webHits()).singleElement().satisfies(hit -> {
                assertThat(hit.url()).isEqualTo("https://example.com/a-186");
                assertThat(hit.content()).isEqualTo("public evidence");
            });
            assertThat(retrieval.trace().web().status()).isEqualTo("SUCCEEDED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void canonicalWebUrlRejectsPrivateAndUnsupportedTargets() {
        assertThat(RagRetrievalService.canonicalWebUrl("https://Example.com:443/path#part"))
                .isEqualTo("https://example.com/path");
        assertThat(RagRetrievalService.canonicalWebUrl("http://127.0.0.1/admin")).isEmpty();
        assertThat(RagRetrievalService.canonicalWebUrl("file:///etc/passwd")).isEmpty();
    }

    @Test
    void recognizesMultiRecordComparisonQuestions() {
        assertThat(RagRetrievalService.isComparisonQuestion("比较 APP-001 和 APP-002 的粘度")).isTrue();
        assertThat(RagRetrievalService.isComparisonQuestion("APP-001 vs APP-002 哪个耐磨更高")).isTrue();
        assertThat(RagRetrievalService.isComparisonQuestion("查询 APP-001 的粘度")).isFalse();
    }

    @Test
    void compositionQuestionsKeepFormulaFieldsAndTotalsAheadOfOtherRecordValues() {
        var fileId = UUID.randomUUID();
        var importJobId = UUID.randomUUID();
        var formula = structuredHit(fileId, importJobId, "G-6488", "UA-145", "49.25", "FORMULA", 1d);
        var total = structuredHit(fileId, importJobId, "G-6488", "合计", "100.55", "OTHER", 0.5d);
        var hardness = structuredHit(fileId, importJobId, "G-6488", "硬度", "6H", "TEST", 100d);

        var recipeResults = RagRetrievalService.prioritizeFormulaEvidence("G-6488的实验配方是什么？",
                List.of(hardness, total, formula), 2);
        var hardnessResults = RagRetrievalService.prioritizeFormulaEvidence("G-6488的硬度是多少？",
                List.of(hardness, total, formula), 2);

        assertThat(recipeResults).extracting(DataSourceFileSearchFacade.SourceFileHit::fieldName)
                .containsExactly("UA-145", "合计");
        assertThat(hardnessResults).first().extracting(DataSourceFileSearchFacade.SourceFileHit::fieldName)
                .isEqualTo("硬度");
    }

    @Test
    void expandedEvidenceReplacesSparseDuplicateSearchHitsWithMappedMetadata() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var categoryId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var fileId = UUID.randomUUID();
        var importJobId = UUID.randomUUID();
        var question = "G-6489 的实验配方是什么？";
        var plan = new QueryRewriteService.QueryPlan(question, question, List.of(), List.of(), List.of(),
                Map.of(), "", List.of(), new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()));
        when(rewrite.rewrite(eq(question), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));

        var sparseUa145 = new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), fileId, importJobId,
                2, "C", "阳离子树脂应用性能实验-003.xlsx",
                "记录=G-6489；字段=UA-145；值=49", 2d, "EXPERIMENT:Sheet1:C2",
                "IMPORT:STRUCT:COLUMN:2", "UA-145", "UA-145", "49", "", "NUMBER",
                "Sheet1", "C2", "EXPERIMENT", "TEMPLATE_GUIDED", UUID.randomUUID(), UUID.randomUUID());
        var richHits = List.of(
                enrichedFormulaHit(sparseUa145, "UA-145", "49", "FORMULA"),
                enrichedFormulaHit(sparseUa145, "UA-330", "27.4", "FORMULA"),
                enrichedFormulaHit(sparseUa145, "二乙二醇二乙烯基醚", "7.5", "FORMULA"),
                enrichedFormulaHit(sparseUa145, "F-279NT", "10.30", "FORMULA"),
                enrichedFormulaHit(sparseUa145, "SS059/碳酸丙烯酯=1/1", "4.10", "FORMULA"),
                enrichedFormulaHit(sparseUa145, "合计", "101.20", "OTHER"));
        when(data.search(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt()))
                .thenReturn(List.of(sparseUa145));
        when(data.expandExperimentContexts(any(), anyList(), anyList(),
                any(DataSourceFileSearchFacade.RetrievalAccessScope.class))).thenReturn(richHits);
        when(reranker.isConfigured()).thenReturn(false);

        var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(organizationId, question, List.of(), List.of(), List.of(categoryId), true);

        assertThat(retrieval.dataHits()).extracting(DataSourceFileSearchFacade.SourceFileHit::fieldName)
                .contains("UA-145", "UA-330", "二乙二醇二乙烯基醚", "F-279NT", "SS059/碳酸丙烯酯=1/1", "合计");
        assertThat(retrieval.dataHits().stream().filter(hit -> "UA-145".equals(hit.fieldName())).findFirst())
                .get().extracting(DataSourceFileSearchFacade.SourceFileHit::semanticDomain).isEqualTo("FORMULA");
    }

    @Test
    void explicitRecordQueriesExpandTheMatchedRecordForRequestedFields() {
        var importJobId = UUID.randomUUID();
        var ordinaryMatches = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> experimentSeedHit(importJobId, "RECORD-" + index, "硬度", "6H",
                        "TEST", 100d - index)).toList();
        var formulaTotal = experimentSeedHit(importJobId, "G-6488", "合计", "100.55", "OTHER", 0.1d);
        var matched = new ArrayList<>(ordinaryMatches);
        matched.add(formulaTotal);

        var compositionSeeds = RagRetrievalService.selectExperimentSeeds("G-6488实验配方是什么？", matched);
        var ordinarySeeds = RagRetrievalService.selectExperimentSeeds("G-6488硬度是多少？", matched);

        assertThat(compositionSeeds).extracting(DataSourceFileSearchFacade.SourceFileHit::recordKey)
                .contains("G-6488");
        assertThat(ordinarySeeds).extracting(DataSourceFileSearchFacade.SourceFileHit::recordKey)
                .contains("G-6488");
    }

    @Test
    void compositionEvidenceKeepsEachRecipeGroupWholeWhenTheContextLimitIsReached() {
        var jobId = UUID.randomUUID();
        var firstFile = UUID.randomUUID();
        var secondFile = UUID.randomUUID();
        var hits = List.of(
                structuredHit(firstFile, jobId, "RECORD-A", "A", "4", "FORMULA", 10d),
                structuredHit(firstFile, jobId, "RECORD-A", "B", "6", "FORMULA", 10d),
                structuredHit(firstFile, jobId, "RECORD-A", "合计", "12", "OTHER", 10d),
                structuredHit(secondFile, jobId, "RECORD-B", "A", "8", "FORMULA", 5d),
                structuredHit(secondFile, jobId, "RECORD-B", "B", "12", "FORMULA", 5d),
                structuredHit(secondFile, jobId, "RECORD-B", "合计", "24", "OTHER", 5d));

        var result = RagRetrievalService.prioritizeFormulaEvidence("对比这两份实验配方", hits, 4);

        assertThat(result).extracting(DataSourceFileSearchFacade.SourceFileHit::recordKey)
                .containsExactly("RECORD-A", "RECORD-A", "RECORD-A");
    }

    @Test
    void explicitRecordIsExpandedBeforeHigherScoringUnrelatedExperimentRecords() {
        var importJobId = UUID.randomUUID();
        var unrelated = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> experimentSeedHit(importJobId, "G-700" + index, "UA-145", "49",
                        "FORMULA", 100d - index)).toList();
        var requested = experimentSeedHit(importJobId, "G-6489", "UA-145", "49", "FORMULA", 0.1d);
        var candidates = new ArrayList<>(unrelated);
        candidates.add(requested);

        var seeds = RagRetrievalService.selectExperimentSeeds(
                "请查询 G-6489 的完整配方以及 PMMA/PC 复合板附着力", candidates);

        assertThat(seeds).extracting(DataSourceFileSearchFacade.SourceFileHit::recordKey)
                .containsExactly("G-6489");
    }

    @Test
    void explicitRecordQueryKeepsRequestedTestFieldsWithItsCompleteFormula() {
        var fileId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var internalRecordKey = "IMPORT:STRUCT:sheet-1:COLUMN:2";
        var hits = new ArrayList<DataSourceFileSearchFacade.SourceFileHit>();
        hits.add(structuredHitWithBusinessRecord(fileId, jobId, internalRecordKey, "G-6489",
                "UA-145", "49", "FORMULA", 1d));
        hits.add(structuredHitWithBusinessRecord(fileId, jobId, internalRecordKey, "G-6489",
                "UA-330", "27.4", "FORMULA", 1d));
        hits.add(structuredHitWithBusinessRecord(fileId, jobId, internalRecordKey, "G-6489",
                "二乙二醇二乙烯基醚", "7.5", "FORMULA", 1d));
        hits.add(structuredHitWithBusinessRecord(fileId, jobId, internalRecordKey, "G-6489",
                "F-279NT", "10.30", "FORMULA", 1d));
        hits.add(structuredHitWithBusinessRecord(fileId, jobId, internalRecordKey, "G-6489",
                "SS059/碳酸丙烯酯=1/1", "4.10", "FORMULA", 1d));
        hits.add(structuredHitWithBusinessRecord(fileId, jobId, internalRecordKey, "G-6489",
                "合计", "101.20", "OTHER", 1d));
        hits.add(structuredHitWithBusinessRecordAndLabel(fileId, jobId, internalRecordKey, "G-6489",
                "PMMA/PC复合板", "测试数据记录 > 初始附着力 > PMMA/PC复合板", "3B", "TEST", 1d));
        hits.add(structuredHitWithBusinessRecordAndLabel(fileId, jobId, internalRecordKey, "G-6489",
                "PMMA/PC复合板", "测试数据记录 > 85度水煮1小时后的附着力 > PMMA/PC复合板", "4B", "TEST", 1d));
        hits.add(structuredHitWithBusinessRecordAndLabel(fileId, jobId, internalRecordKey, "G-6489",
                "PMMA/PC复合板", "测试数据记录 > 再沸水煮1小时后的附着力 > PMMA/PC复合板", "4B", "TEST", 1d));
        for (var index = 0; index < 12; index++) {
            hits.add(structuredHit(fileId, jobId, "G-700" + index, "OTHER-COMPONENT", "1", "FORMULA", 50d));
        }

        var result = RagRetrievalService.prioritizeFormulaEvidence(
                "请查询文件《阳离子树脂应用性能实验-003.xlsx》中业务记录 G-6489 的完整配方，以及它在PMMA/PC复合板上的初始和水煮后附着力。",
                hits, 9);

        assertThat(result).hasSize(9);
        assertThat(result).extracting(DataSourceFileSearchFacade.SourceFileHit::fieldName)
                .contains("UA-145", "UA-330", "二乙二醇二乙烯基醚", "F-279NT",
                        "SS059/碳酸丙烯酯=1/1", "合计");
        assertThat(result).filteredOn(hit -> "TEST".equals(hit.semanticDomain()))
                .extracting(DataSourceFileSearchFacade.SourceFileHit::fieldValue)
                .containsExactlyInAnyOrder("3B", "4B", "4B");
        assertThat(result).allSatisfy(hit -> assertThat(hit.content()).contains("G-6489"));
    }

    @Test
    void formulaContextGroupingUsesBusinessRecordWhenRawKeysDiffer() {
        var fileId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var mixedKeys = List.of(
                structuredHitWithBusinessRecord(fileId, jobId, "IMPORT:STRUCT:COLUMN:2", "G-6489",
                        "UA-145", "49", "FORMULA", 3d),
                structuredHitWithBusinessRecord(fileId, jobId, "G-6489", "G-6489",
                        "UA-330", "27.4", "FORMULA", 2d),
                structuredHitWithBusinessRecord(fileId, jobId, "IMPORT:STRUCT:COLUMN:2", "G-6489",
                        "合计", "101.2", "OTHER", 1d));

        var result = RagRetrievalService.prioritizeFormulaEvidence("G-6489 的实验配方", mixedKeys, 2);

        assertThat(result).isEmpty();
    }

    @Test
    void disabledWebSearchNeverTouchesTheProvider() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var web = mock(WebSearchProvider.class);
        var plan = new QueryRewriteService.QueryPlan("question", "question", List.of(), List.of(), List.of(),
                Map.of(), "", List.of());
        when(rewrite.rewrite(eq("question"), anyList(), eq(false)))
                .thenReturn(new QueryRewriteService.Result(plan, "ORIGINAL", "", false));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker, web, executor,
                    Duration.ofSeconds(1), 6).retrieve(UUID.randomUUID(), "question", List.of(),
                    List.of(), List.of(), true, false);

            assertThat(retrieval.webHits()).isEmpty();
            assertThat(retrieval.trace().web().status()).isEqualTo("SKIPPED");
            verifyNoInteractions(web);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void webEvidenceSurvivesAnInternalRetrievalFailure() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var web = mock(WebSearchProvider.class);
        var categoryId = UUID.randomUUID();
        var plan = new QueryRewriteService.QueryPlan("question", "question", List.of(), List.of(), List.of(),
                Map.of(), "", List.of(new QueryRewriteService.WebQuery("public question", "GENERAL", "ALL")));
        when(rewrite.rewrite(eq("question"), anyList(), eq(true)))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(web.isAvailable()).thenReturn(true);
        when(web.search(any(WebSearchProvider.SearchQuery.class))).thenReturn(new WebSearchProvider.SearchResponse(
                "SUCCEEDED", List.of(new WebSearchProvider.SearchResult("Public", "https://example.com/public",
                "external evidence", 0.8, "")), 10, 8, 200, ""));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker, web, executor,
                    Duration.ofSeconds(1), 6).retrieve(UUID.randomUUID(), "question", List.of(),
                    List.of(categoryId), List.of(), true, true);

            assertThat(retrieval.knowledgeHits()).isEmpty();
            assertThat(retrieval.webHits()).hasSize(1);
            assertThat(retrieval.trace().channels())
                    .filteredOn(channel -> "KNOWLEDGE_KEYWORD_VECTOR".equals(channel.channel()))
                    .singleElement().extracting(RagRetrievalService.ChannelTrace::status).isEqualTo("FAILED");
            verify(web).search(any(WebSearchProvider.SearchQuery.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private KnowledgeSearchFacade.SearchHit hit(int index) {
        return new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "document", "document.pdf", 3, "section", "content " + index, 1d - index / 100d);
    }

    private DataSourceFileSearchFacade.SourceFileHit structuredHit(UUID fileId, UUID importJobId, String record,
                                                                    String field, String value,
                                                                    String semanticDomain, double score) {
        return new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), fileId, importJobId,
                12, "C", "实验配方.xlsx", "记录：" + record + "；字段：" + field + "；值=" + value,
                score, "EXPERIMENT:Sheet1:C12", record, field, field, value, "", "NUMBER",
                "Sheet1", "C12", "EXPERIMENT", "TEMPLATE_GUIDED", UUID.randomUUID(), UUID.randomUUID(),
                "COMPLETED", field, semanticDomain, "FORMULA".equals(semanticDomain) ? "RATIO" : "DYNAMIC_VALUE",
                null, "", "", null, "TARGET");
    }

    private DataSourceFileSearchFacade.SourceFileHit structuredHitWithBusinessRecord(
            UUID fileId, UUID importJobId, String recordKey, String businessRecord,
            String field, String value, String semanticDomain, double score) {
        return structuredHitWithBusinessRecordAndLabel(fileId, importJobId, recordKey, businessRecord,
                field, field, value, semanticDomain, score);
    }

    private DataSourceFileSearchFacade.SourceFileHit structuredHitWithBusinessRecordAndLabel(
            UUID fileId, UUID importJobId, String recordKey, String businessRecord,
            String field, String sourceLabel, String value, String semanticDomain, double score) {
        return new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), fileId, importJobId,
                12, "C", "实验配方.xlsx", "记录=" + businessRecord + "；字段=" + sourceLabel + "；值=" + value,
                score, "EXPERIMENT:Sheet1:C12", recordKey, field, field, value, "", "NUMBER",
                "Sheet1", "C12", "EXPERIMENT", "TEMPLATE_GUIDED", UUID.randomUUID(), UUID.randomUUID(),
                "COMPLETED", sourceLabel, semanticDomain,
                "FORMULA".equals(semanticDomain) ? "RATIO" : "DYNAMIC_VALUE", null, "", "", null, "TARGET");
    }

    private DataSourceFileSearchFacade.SourceFileHit enrichedFormulaHit(
            DataSourceFileSearchFacade.SourceFileHit template, String field, String value, String semanticDomain) {
        var isTotal = "合计".equals(field);
        var hitId = "UA-145".equals(field) ? template.hitId() : UUID.randomUUID();
        return new DataSourceFileSearchFacade.SourceFileHit(hitId, template.fileObjectId(), template.importJobId(),
                template.rowNumber(), template.columnName(), template.originalName(),
                "记录=G-6489；字段=" + field + "；值=" + value, 1d, template.sourceLocator(),
                template.recordKey(), field, field, value, "", "NUMBER", template.sheetName(),
                template.cellAddress(), "EXPERIMENT", "TEMPLATE_GUIDED", template.experimentId(),
                template.experimentVersionId(), "COMPLETED", field, semanticDomain,
                isTotal ? "DYNAMIC_VALUE" : "COMPONENT", null, "", "", isTotal ? "REPEAT_FIELD" : null,
                "SAME_RECORD");
    }

    private DataSourceFileSearchFacade.SourceFileHit experimentSeedHit(UUID importJobId, String record,
                                                                       String field, String value,
                                                                       String semanticDomain, double score) {
        var sourceLabel = "合计".equals(field) ? "涂料配方 > 实验配方 > 合计" : field;
        return new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(), importJobId,
                12, "C", "实验.xlsx", "记录：" + record + "；字段：" + field + "；值=" + value,
                score, "EXPERIMENT:Sheet1:C12", record, field, field, value, "", "NUMBER",
                "Sheet1", "C12", "EXPERIMENT", "TEMPLATE_GUIDED", UUID.randomUUID(), UUID.randomUUID(),
                "COMPLETED", sourceLabel, semanticDomain, "FORMULA".equals(semanticDomain) ? "RATIO" : "DYNAMIC_VALUE",
                null, "", "", null, "TARGET");
    }
}
