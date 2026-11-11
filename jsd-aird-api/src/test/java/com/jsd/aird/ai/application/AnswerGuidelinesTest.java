package com.jsd.aird.ai.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class AnswerGuidelinesTest {
    @Test void packagedJavaRulesAreTheSameResourceUsedByHarness() throws Exception {
        var original=Files.readString(Path.of("../jsd-aird-agent/control/src/aird_agent/capabilities/answer_guidelines.txt"),StandardCharsets.UTF_8)
            .replace("\r\n","\n").strip();
        try (var input = new ClassPathResource("agent-prompts/answer_guidelines.txt").getInputStream()) {
            var packaged = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
            assertThat(packaged).isEqualTo(original);
        }
    }
}
