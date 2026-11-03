package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.api.EvidenceProtocol;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvidenceProtocolTest {
    @Test void sharedVectorsMatchPythonCanonicalSourceIdentity() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(Path.of("../jsd-aird-agent/contracts/evidence.json").toFile());
        for (var item : fixture.path("cases")) {
            assertThat(EvidenceProtocol.evidenceId(item.path("source"))).isEqualTo(item.path("evidence_id").asText());
            var projected = EvidenceProtocol.candidate(item.path("label").asText(), item.path("text").asText(), item.path("source"));
            assertThat(projected.path("evidence_id")).isEqualTo(item.path("evidence_id"));
            assertThatThrownBy(() -> EvidenceProtocol.candidate("K1", "forged", item.path("source")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SOURCE_HASH_MISMATCH");
        }
    }
}
