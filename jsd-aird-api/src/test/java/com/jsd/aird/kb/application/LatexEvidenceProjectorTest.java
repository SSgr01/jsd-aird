package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LatexEvidenceProjectorTest {

    private final LatexEvidenceProjector projector = new LatexEvidenceProjector();

    @Test
    void usesTheLatexAstForStandardSymbolsAndSimpleScripts() {
        assertThat(projector.project("H_{2}O + \\alpha \\times \\Omega + 10^{-12}"))
                .isEqualTo("H₂O+α×Ω+10⁻¹²");
    }

    @Test
    void keepsComplexStructuresAsCompleteLatexInsteadOfGuessingAPlainTextMeaning() {
        var latex = "\\frac{\\partial^2 C}{\\partial x^2}";

        assertThat(projector.project(latex)).isEqualTo("$" + latex + "$");
    }

    @Test
    void onlyNormalizesNarrowMineruSpacingArtifactsForRendering() {
        assertThat(MineruLatexNormalizer.normalizeForRender(
                "\\mathrm { P V A c } = 1 0 ^ { - 1 2 }"))
                .isEqualTo("\\mathrm{PVAc} = 10 ^ { - 12 }");
    }
}
