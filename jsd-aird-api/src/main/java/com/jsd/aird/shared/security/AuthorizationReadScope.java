package com.jsd.aird.shared.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Identity/role snapshots inside one agent HTTP request; never a cross-request ACL cache. */
public final class AuthorizationReadScope implements AutoCloseable {
    private static final ThreadLocal<Map<List<?>, Object>> CURRENT = new ThreadLocal<>();
    private final Map<List<?>, Object> previous;
    private AuthorizationReadScope() { previous = CURRENT.get(); CURRENT.set(new HashMap<>()); }
    public static AuthorizationReadScope open() { return new AuthorizationReadScope(); }
    @SuppressWarnings("unchecked")
    public static <T> T read(List<?> key, Supplier<T> load) {
        var values = CURRENT.get();
        if (values == null) return load.get();
        if (values.containsKey(key)) return (T)values.get(key);
        T value = load.get();
        if (values.size() < 512) values.put(key, value);
        return value;
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
