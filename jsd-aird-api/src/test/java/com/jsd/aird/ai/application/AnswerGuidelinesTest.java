package com.jsd.aird.ai.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnswerGuidelinesTest {
    @Test void packagedJavaRulesAreTheSameResourceUsedByHarness() throws Exception {
        var original=Files.readString(Path.of("../jsd-aird-agent/control/src/aird_agent/capabilities/answer_guidelines.txt"),StandardCharsets.UTF_8)
            .replace("\r\n","\n").strip();
        assertThat(AnswerGuidelines.TEXT).isEqualTo(original);
        assertThat(AnswerGuidelines.FINGERPRINT).isEqualTo(com.jsd.aird.shared.evidence.EvidenceProtocol.sha(original));
    }
}
