package com.jsd.aird.kb.infrastructure;

import com.jsd.aird.kb.domain.DocumentParsingFailure;

/** A deterministic Office format failure; retrying the same uploaded bytes cannot fix it. */
public final class OfficeDocumentParseException extends RuntimeException implements DocumentParsingFailure {

    public OfficeDocumentParseException(String fileName, Throwable cause) {
        super("Office 文件无法解析，文件可能已损坏、加密或扩展名不匹配: " + safeFileName(fileName), cause);
    }

    @Override
    public Integer httpStatus() {
        return null;
    }

    @Override
    public String apiCode() {
        return "INVALID_OFFICE_DOCUMENT";
    }

    @Override
    public String taskId() {
        return null;
    }

    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public boolean fallbackEligible() {
        return false;
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "文件";
        var normalized = fileName.replace('\\', '/');
        var separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }
}
