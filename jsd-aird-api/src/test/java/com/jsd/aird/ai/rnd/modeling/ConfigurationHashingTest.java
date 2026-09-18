package com.jsd.aird.ai.rnd.modeling;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ConfigurationHashingTest {

    private final ConfigurationHashing hashing = new ConfigurationHashing(new ObjectMapper());

    @Test
    void aliasNormalizationUsesNfkcWhitespaceAndCaseFoldingWithoutRemovingCodeCharacters() {
        assertThat(ConfigurationHashing.normalizeAlias("  ＡＢＣ／12  -  X=Y  "))
                .isEqualTo("abc/12 - x=y");
    }

    @Test
    void configurationHashIsIndependentOfObjectPropertyOrder() throws Exception {
        var mapper = new ObjectMapper();
        assertThat(hashing.hash(mapper.readTree("{\"b\":2,\"a\":{\"d\":4,\"c\":3}}")))
                .isEqualTo(hashing.hash(mapper.readTree("{\"a\":{\"c\":3,\"d\":4},\"b\":2}")));
    }
}
