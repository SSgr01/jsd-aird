package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import org.junit.jupiter.api.Test;

class AssistantDataDetailTest {

    @Test
    void sendsDataMissesToTheSharedAnswerContextWithARestrictedClaim() {
        var detail = DataSourceFileSearchFacade.DataDetailResult.empty(
                DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP);

        assertThat(AssistantService.dataLookupContext(detail))
                .contains("未找到匹配文件或记录")
                .doesNotContain("可能原因", "数据不存在");
    }

    @Test
    void doesNotReportADataMissWhenTheDataSourceWasNotSelected() {
        var detail = DataSourceFileSearchFacade.DataDetailResult.empty(
                DataSourceFileSearchFacade.DataQueryMode.RECORD_DETAIL);

        assertThat(AssistantService.dataLookupContext(detail, true))
                .contains("本轮没有检索结构化数据来源", "不包含数据中心或实验记录")
                .doesNotContain("未找到匹配文件或记录");
    }

    @Test
    void addsRecordContextToTheSameEvidenceSetUsedByTheSharedAnswerModel() {
        var target = hit("APP-UV-0001", "树脂编号", "SJ-231", "CONFIRMED", "TARGET");
        var sameRecord = hit("APP-UV-0001", "SJ-231（配方占比）", "55.86", "CONFIRMED", "SAME_RECORD");
        var sharedSheet = hit("APP-UV-0001", "测试基材", "100μm PET 光学膜", "CONFIRMED", "SHARED_SHEET");
        var detail = new DataSourceFileSearchFacade.DataDetailResult(
                DataSourceFileSearchFacade.DataQueryMode.RECORD_DETAIL,
                DataSourceFileSearchFacade.DataResolution.RESOLVED,
                UUID.randomUUID(), UUID.randomUUID(), "应用配方.xlsx", 1, 3,
                List.of(target), false, List.of(), false,
                List.of(new DataSourceFileSearchFacade.RecordContext("APP-UV-0001", "Sheet1",
                        List.of(target), List.of(sameRecord, sharedSheet), false, false, "")), "VALUE");
        var retrieval = new RagRetrievalService.Retrieval(null, List.of(), List.of(target), detail, List.of(), null);

        var answerHits = AssistantService.answerDataHits(retrieval);
        var compressed = new ContextCompressionService().compress(List.of(), answerHits, 2_000, true);

        assertThat(answerHits).containsExactly(target, sameRecord, sharedSheet);
        assertThat(compressed.evidenceRefs()).containsExactly("D1", "D2", "D3");
        assertThat(compressed.text()).contains("来源=数据中心记录", "业务记录=APP-UV-0001")
                .contains("来源状态=已确认")
                .contains("字段=树脂编号；值=SJ-231")
                .contains("字段=SJ-231（配方占比）；值=55.86")
                .contains("证据关系=同一工作表共享条件；原始内容=记录=APP-UV-0001；字段=测试基材；值=100μm PET 光学膜");
    }

    @Test
    void preservesMixedSourceStatusesAsAConstraintOnSynthesis() {
        var confirmed = hit("APP-UV-0001", "树脂编号", "SJ-231", "CONFIRMED", "TARGET");
        var draft = hit("APP-UV-0241", "树脂编号", "SJ-231", "DRAFT", "TARGET");
        var result = new DataSourceFileSearchFacade.DataDetailResult(
                DataSourceFileSearchFacade.DataQueryMode.RECORD_DETAIL,
                DataSourceFileSearchFacade.DataResolution.RESOLVED,
                UUID.randomUUID(), UUID.randomUUID(), "多来源数据.xlsx", 2, 2,
                List.of(confirmed, draft), false, List.of(), false, List.of(), "VALUE");

        var boundary = AssistantService.dataEvidenceBoundary(result);

        assertThat(boundary.observationOnly()).isTrue();
        assertThat(boundary.description()).contains("已确认记录，也有草稿").contains("逐条注明状态");
        assertThat(AssistantService.dataLookupContext(result)).contains("找到匹配记录");
    }

    @Test
    void asksForClarificationWhenARequestedDisplayNameHasDifferentSemantics() {
        var massDensity = hit("REC-001", "密度", "1.08", "CONFIRMED", "TARGET", "mass_density", "g/cm³");
        var opticalDensity = hit("REC-002", "密度", "0.42", "CONFIRMED", "TARGET", "optical_density", "");

        var ambiguity = AssistantService.detectFieldAmbiguity("请问密度是什么？", List.of(massDensity, opticalDensity));

        assertThat(ambiguity).isNotNull();
        assertThat(ambiguity.fieldName()).isEqualTo("密度");
        assertThat(ambiguity.candidates()).extracting(AssistantService.FieldCandidate::code)
                .containsExactly("mass_density", "optical_density");
    }

    @Test
    void sameFieldAcrossRecordsIsNotAmbiguous() {
        var first = hit("REC-001", "密度", "1.08", "CONFIRMED", "TARGET");
        var second = hit("REC-002", "密度", "1.12", "CONFIRMED", "TARGET");

        assertThat(AssistantService.detectFieldAmbiguity("查询密度", List.of(first, second))).isNull();
    }

    private DataSourceFileSearchFacade.SourceFileHit hit(String recordKey, String fieldName, String value,
                                                          String status, String relation) {
        return hit(recordKey, fieldName, value, status, relation, fieldName, null);
    }

    private DataSourceFileSearchFacade.SourceFileHit hit(String recordKey, String fieldName, String value,
                                                          String status, String relation, String fieldCode,
                                                          String unit) {
        return new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                12, "C", "应用配方.xlsx", "记录=" + recordKey + "；字段=" + fieldName + "；值=" + value,
                0.9, "DATA_CENTER:Sheet1:C12", recordKey, fieldCode, fieldName, value, unit,
                "TEXT", "Sheet1", "C12", "DATA_CENTER", "TEMPLATE_GUIDED", UUID.randomUUID(), UUID.randomUUID(),
                status, fieldName, null, null, null, unit, unit, null, relation);
    }
}
