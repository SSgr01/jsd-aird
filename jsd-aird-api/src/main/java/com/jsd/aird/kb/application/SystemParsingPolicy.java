package com.jsd.aird.kb.application;

import java.util.Locale;

import com.jsd.aird.kb.domain.OcrMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** System-owned parsing policy. Public knowledge APIs cannot override these values. */
@Component
public final class SystemParsingPolicy {

    private final OcrMode defaultOcrMode;
    private final boolean agentFallbackEnabled;

    public SystemParsingPolicy(
            @Value("${app.ai.mineru.default-ocr-mode:AUTO}") String defaultOcrMode,
            @Value("${app.ai.mineru.agent-fallback-enabled:false}") boolean agentFallbackEnabled
    ) {
        this.defaultOcrMode = OcrMode.fromNullable(defaultOcrMode);
        this.agentFallbackEnabled = agentFallbackEnabled;
    }

    public Policy forFile(String fileName, String contentType) {
        var pdf = (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                || "application/pdf".equalsIgnoreCase(contentType);
        return pdf ? new Policy(defaultOcrMode, agentFallbackEnabled) : new Policy(OcrMode.AUTO, false);
    }

    public OcrMode defaultOcrMode() {
        return defaultOcrMode;
    }

    public boolean agentFallbackEnabled() {
        return agentFallbackEnabled;
    }

    public record Policy(OcrMode ocrMode, boolean allowAgentFallback) { }
}
