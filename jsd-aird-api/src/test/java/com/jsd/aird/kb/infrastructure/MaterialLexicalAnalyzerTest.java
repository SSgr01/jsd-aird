package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaterialLexicalAnalyzerTest {

    private final MaterialLexicalAnalyzer analyzer = new MaterialLexicalAnalyzer();

    @Test
    void protectsMaterialTermsCodesRangesAndCompoundUnits() {
        var analysis = analyzer.analyzeDocument(
                "UA-1117 的粘度为 400-700cps，固化能量 25 mJ/cm²，溶剂为乙酸乙酯，属于高官能度树脂");

        assertThat(analysis.frequencies()).containsKeys("ua-1117", "400-700cps", "25mj/cm2", "乙酸乙酯", "高官能度");
        assertThat(analysis.frequencies()).doesNotContainKeys("酸乙", "能度", "的", "为");
        assertThat(analysis.documentLength()).isEqualTo(
                analysis.frequencies().values().stream().mapToInt(Integer::intValue).sum());
        assertThat(analyzer.version()).isEqualTo("material-smartcn-v1");
    }

    @Test
    void expandsSynonymsOnlyForQueries() {
        assertThat(analyzer.analyzeDocument("粘度").frequencies()).containsKey("粘度").doesNotContainKey("黏度");
        assertThat(analyzer.analyzeQuery("粘度").frequencies()).containsKeys("粘度", "黏度");
    }
}
