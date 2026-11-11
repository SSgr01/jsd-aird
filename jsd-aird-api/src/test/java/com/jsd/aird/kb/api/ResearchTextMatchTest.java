package com.jsd.aird.kb.api;

import com.jsd.aird.shared.error.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResearchTextMatchTest {
    @Test void fullTitlesKeepAllTermsAndExistingMatchingModes() {
        String title = "Optimization of UV-Curable Polyurethane Acrylate Coatings with Hexagonal Boron Nitride hBN";
        var terms = ResearchTextMatch.terms(title);
        assertThat(terms).hasSizeGreaterThan(8).contains("optimization", "nitride", "hbn");
        assertThat(ResearchTextMatch.score(title, title, "phrase")).isGreaterThan(0);
        assertThat(ResearchTextMatch.score(title, title, "all")).isGreaterThan(0);
        assertThat(ResearchTextMatch.score("Hexagonal boron nitride", title, "all")).isNegative();
        assertThat(ResearchTextMatch.score("Hexagonal boron nitride", title, "any")).isGreaterThan(0);
    }

    @Test void naturalLanguageWhitespaceAndRepeatedTermsNormalizeWithoutTruncating() {
        assertThat(ResearchTextMatch.terms(" PET  PET\nSiO2 adhesion flexibility haze coating nano resin film hardness "))
            .containsExactly("pet", "sio2", "adhesion", "flexibility", "haze", "coating", "nano", "resin", "film", "hardness");
        assertThat(ResearchTextMatch.score("UV curable coatings on PET film", "  UV  curable\ncoatings on PET film ", "phrase"))
            .isGreaterThan(0);
    }

    @Test void emptyUnsearchableAndOversizedQueriesReturnFieldRules() {
        for (String query : new String[]{"", "   ", "---", "x".repeat(257)}) {
            assertThatThrownBy(() -> ResearchTextMatch.terms(query)).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.detail()).isInstanceOf(Map.class);
                assertThat(((Map<?, ?>) error.detail()).get("max_length")).isEqualTo(256);
                assertThat(error.getMessage()).contains("256").doesNotContain("eight");
            });
        }
        assertThat(ResearchTextMatch.terms("x".repeat(256))).hasSize(1);
    }
}
