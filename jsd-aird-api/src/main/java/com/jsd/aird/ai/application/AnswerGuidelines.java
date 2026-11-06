package com.jsd.aird.ai.application;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** The same packaged resource is consumed by fixed QA and the Harness. */
final class AnswerGuidelines {
    static final String TEXT = load();
    static final String FINGERPRINT = com.jsd.aird.shared.evidence.EvidenceProtocol.sha(TEXT);
    private AnswerGuidelines() { }
    private static String load() {
        try (var input = new ClassPathResource("agent-prompts/answer_guidelines.txt").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        } catch (java.io.IOException error) { throw new IllegalStateException("Missing shared answer guidelines", error); }
    }
}
