package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaterialLexicalAnalyzerTest {

    private final MaterialLexicalAnalyzer analyzer = new MaterialLexicalAnalyzer();

    @Test
    void protectsCrossDomainCodesFormulaeRangesAndCompoundUnits() {
        var analysis = analyzer.analyzeDocument(
                "UA-1117 的参数为 400-700cps 和 25 mJ/cm²；CaCl<sub>2</sub> 为 1 mol L−1，"
                        + "样品浓度 2 mg mL−1，过程约 10 s。");

        assertThat(analysis.frequencies()).containsKeys(
                "ua-1117", "400-700cps", "25mj/cm2", "cacl2", "1mol/l", "2mg/ml", "10s");
        assertThat(analysis.frequencies()).doesNotContainKeys("的", "为");
        assertThat(analysis.documentLength()).isEqualTo(
                analysis.frequencies().values().stream().mapToInt(Integer::intValue).sum());
        assertThat(analyzer.version()).isEqualTo("material-smartcn-v2");
    }

    @Test
    void doesNotContainAHandWrittenDomainSynonymDictionary() {
        assertThat(analyzer.analyzeQuery("粘度").frequencies())
                .isEqualTo(analyzer.analyzeDocument("粘度").frequencies())
                .containsKey("粘度").doesNotContainKey("黏度");
    }

    @Test
    void normalizesLatexChemicalSubscriptsWithoutKnowingTheCompound() {
        assertThat(analyzer.analyzeDocument("$\\mathrm { C a C l } _ { 2 }$").frequencies())
                .containsKey("cacl2");
    }
}
