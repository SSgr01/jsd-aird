package com.jsd.aird.platform.web;

import java.util.Optional;

import org.slf4j.MDC;

public final class RequestIdHolder {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private RequestIdHolder() {
    }

    public static void set(String requestId) {
        MDC.put(MDC_KEY, requestId);
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    public static String currentOrUnknown() {
        return current().orElse("unknown");
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}

