package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelPayloadSanitizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelPayloadSanitizer sanitizer = new ModelPayloadSanitizer(objectMapper);

    @Test
    void sanitizesSecretsAndPersonalIdentifiersBeforeModelDispatch() throws Exception {
        var source = objectMapper.readTree("""
                {
                  "authorization":"Bearer secret",
                  "api_key":"sk-1234567890abcdefghijklmnop",
                  "content":"联系人 13812345678，邮箱 user@example.com，身份证 110101199001011234"
                }
                """);
        var sanitized = sanitizer.sanitize(source);

        assertThat(sanitized.path("authorization").asText()).isEqualTo("[REDACTED_SECRET]");
        assertThat(sanitized.path("api_key").asText()).isEqualTo("[REDACTED_SECRET]");
        assertThat(sanitized.path("content").asText())
                .doesNotContain("13812345678", "user@example.com", "110101199001011234")
                .contains("[REDACTED_PHONE]", "[REDACTED_EMAIL]", "[REDACTED_ID]");
    }
}
