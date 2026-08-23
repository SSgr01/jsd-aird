package com.jsd.aird.kb.domain;

/** Provider-neutral diagnostic contract for document parsing failures. */
public interface DocumentParsingFailure {

    Integer httpStatus();

    String apiCode();

    String taskId();

    boolean retryable();

    boolean fallbackEligible();
}
