package com.jsd.aird.kb.infrastructure;

import com.jsd.aird.kb.domain.DocumentParsingFailure;

/** Structured provider failure used to make retry and fallback decisions explicit. */
public final class MineruException extends RuntimeException implements DocumentParsingFailure {

    private final Integer httpStatus;
    private final String apiCode;
    private final String taskId;
    private final boolean retryable;
    private final boolean fallbackEligible;

    public MineruException(String message, Integer httpStatus, String apiCode, String taskId,
                           boolean retryable, boolean fallbackEligible, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.apiCode = apiCode;
        this.taskId = taskId;
        this.retryable = retryable;
        this.fallbackEligible = fallbackEligible;
    }

    public static MineruException contract(String message, String taskId) {
        return new MineruException(message, null, "CONTRACT", taskId, false, false, null);
    }

    public static MineruException adapter(String message, Throwable cause) {
        return new MineruException(message, null, "ADAPTER", null, false, false, cause);
    }

    public Integer httpStatus() { return httpStatus; }
    public String apiCode() { return apiCode; }
    public String taskId() { return taskId; }
    public boolean retryable() { return retryable; }
    public boolean fallbackEligible() { return fallbackEligible; }
}
