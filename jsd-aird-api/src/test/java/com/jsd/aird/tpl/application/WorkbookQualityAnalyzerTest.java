package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WorkbookQualityAnalyzerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkbookQualityAnalyzer analyzer = new WorkbookQualityAnalyzer(objectMapper);

    @Test
    void detectsAndReversiblySplitsGenericTitleAndBodyWithoutKeywordRules() throws Exception {
        var structure = objectMapper.readTree("""
                {
                  "candidateCells": [
                    {"sheetId":"sheet-1","sheetName":"记录","address":"A1","empty":false,
                     "value":"阶段结论：本批样品在当前条件下表现稳定","style":{"bd":{"b":{"s":1}}}},
                    {"sheetId":"sheet-1","sheetName":"记录","address":"B1","empty":true,
                     "style":{"bd":{"b":{"s":1}}}}
                  ],
                  "regions":[], "dataValidations":[], "namedRanges":[]
                }
                """);
        var snapshot = objectMapper.readTree("""
                {"sheets":{"sheet-1":{"cellData":{"0":{"0":{"v":"阶段结论：本批样品在当前条件下表现稳定"},"1":{}}}}}}
                """);

        var result = analyzer.analyze(structure, snapshot, Set.of(), true);

        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.issueType()).isEqualTo("MIXED_CELL_ROLES");
            assertThat(issue.status()).isEqualTo("AUTO_APPLIED");
            assertThat(issue.inversePatch().path("operations")).hasSize(2);
        });
        assertThat(result.snapshot().path("sheets").path("sheet-1").path("cellData")
                .path("0").path("0").path("v").asText()).isEqualTo("阶段结论");
        assertThat(result.snapshot().path("sheets").path("sheet-1").path("cellData")
                .path("0").path("1").path("v").asText()).isEqualTo("本批样品在当前条件下表现稳定");
        assertThat(analyzer.apply(result.snapshot(), result.issues().getFirst().inversePatch())).isTrue();
        assertThat(result.snapshot().path("sheets").path("sheet-1").path("cellData")
                .path("0").path("0").path("v").asText())
                .isEqualTo("阶段结论：本批样品在当前条件下表现稳定");
    }

    @Test
    void doesNotAutoFixMappedOrProtectedContentAndDoesNotTreatUrlAsTitle() throws Exception {
        var structure = objectMapper.readTree("""
                {
                  "candidateCells": [
                    {"sheetId":"sheet-1","sheetName":"记录","address":"A1","empty":false,
                     "value":"观察说明：这里是需要客户确认的正文","style":{"bd":{"b":{"s":1}}}},
                    {"sheetId":"sheet-1","sheetName":"记录","address":"B1","empty":true,
                     "style":{"bd":{"b":{"s":1}}}},
                    {"sheetId":"sheet-1","sheetName":"记录","address":"A2","empty":false,
                     "value":"https://example.com:8443/path","style":{"bd":{"b":{"s":1}}}}
                  ],
                  "regions":[], "dataValidations":[], "namedRanges":[]
                }
                """);
        var snapshot = objectMapper.readTree("""
                {"sheets":{"sheet-1":{"cellData":{"0":{"0":{"v":"观察说明：这里是需要客户确认的正文"},"1":{}},
                "1":{"0":{"v":"https://example.com:8443/path"}}}}}}
                """);

        var result = analyzer.analyze(structure, snapshot, Set.of("sheet-1|A1"), true);

        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.address()).isEqualTo("A1");
            assertThat(issue.autoFixable()).isFalse();
            assertThat(issue.status()).isEqualTo("DETECTED");
        });
        assertThat(result.changed()).isFalse();
    }

    @Test
    void keepsLongNoteSentenceAsStaticContentInsteadOfTemplateQualityIssue() throws Exception {
        var structure = objectMapper.readTree("""
                {"candidateCells":[
                  {"sheetId":"sheet-1","address":"A25","empty":false,
                   "value":"注：原料投料偏差不能大于1%,补损溶剂除外.","hasBorder":true}
                ],"regions":[],"dataValidations":[],"namedRanges":[]}
                """);
        var snapshot = objectMapper.readTree("""
                {"sheets":{"sheet-1":{"cellData":{"24":{"0":{"v":"注：原料投料偏差不能大于1%,补损溶剂除外."}}}}}}
                """);

        var result = analyzer.analyze(structure, snapshot, Set.of(), true);

        assertThat(result.issues()).isEmpty();
        assertThat(result.changed()).isFalse();
    }
}
