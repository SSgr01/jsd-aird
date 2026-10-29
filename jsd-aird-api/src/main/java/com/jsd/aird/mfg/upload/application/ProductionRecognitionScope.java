package com.jsd.aird.mfg.upload.application;

import com.jsd.aird.ops.application.port.AsyncJobHandler.ExecutionContext;
import java.util.function.Supplier;

/** Worker identity, cleared even when parsing or persistence fails. */
public final class ProductionRecognitionScope {
    public record Identity(ExecutionContext execution, boolean terminal) {}
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
    private ProductionRecognitionScope() {}
    public static Identity current() { return CURRENT.get(); }
    public static <T> T run(ExecutionContext context, boolean terminal, Supplier<T> work) {
        var previous=CURRENT.get();
        CURRENT.set(new Identity(context,terminal));
        try { return work.get(); }
        finally { if(previous==null)CURRENT.remove();else CURRENT.set(previous); }
    }
}
