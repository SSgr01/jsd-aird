package com.jsd.aird.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** Request-local timing values captured by infrastructure filters. */
public final class RequestTimingHolder {

    private static final ThreadLocal<Map<String, Long>> VALUES =
            ThreadLocal.withInitial(LinkedHashMap::new);

    private RequestTimingHolder() {
    }

    public static void put(String name, long value) {
        if (name == null || name.isBlank()) return;
        VALUES.get().put(name, Math.max(0, value));
    }

    public static Map<String, Long> snapshot() {
        return Map.copyOf(VALUES.get());
    }

    public static void set(Map<String, Long> values) {
        VALUES.get().clear();
        if (values != null) VALUES.get().putAll(values);
    }

    public static void clear() {
        VALUES.remove();
    }
}
