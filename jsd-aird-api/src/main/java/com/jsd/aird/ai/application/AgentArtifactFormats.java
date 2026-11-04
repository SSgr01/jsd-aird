package com.jsd.aird.ai.application;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.util.Locale;
import java.util.Map;

/** Registration support only: this does not promise that a generator is available. */
final class AgentArtifactFormats {
    record Format(String contentType, String storageKind) { }
    private static final Map<String, Format> FORMATS = Map.of(
        "json", new Format("application/json", "RESEARCH_ARTIFACT"),
        "csv", new Format("text/csv", "RESEARCH_ARTIFACT"),
        "txt", new Format("text/plain", "RESEARCH_ARTIFACT"),
        "md", new Format("text/markdown", "RESEARCH_ARTIFACT"),
        "xlsx", new Format("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "OFFICE"),
        "docx", new Format("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "OFFICE"),
        "png", new Format("image/png", "RESEARCH_ARTIFACT"),
        "pdf", new Format("application/pdf", "DATA_SOURCE")
    );

    static Format resolve(String name) {
        var dot = name.lastIndexOf('.');
        var format = dot < 1 ? null : FORMATS.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (format == null || name.length() > 180 || name.chars().anyMatch(Character::isISOControl)
                || name.chars().anyMatch(c -> c == '/' || c == '\\' || c == ':'))
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的成果文件名");
        return format;
    }
}
