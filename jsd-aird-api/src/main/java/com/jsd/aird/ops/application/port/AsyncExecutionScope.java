package com.jsd.aird.ops.application.port;

import java.util.function.Supplier;

/** Carries the immutable lease identity through synchronous preparation and final submission. */
public final class AsyncExecutionScope {
    private static final ThreadLocal<AsyncJobHandler.ExecutionContext> CURRENT=new ThreadLocal<>();
    private AsyncExecutionScope(){ }
    public static AsyncJobHandler.ExecutionContext current(){return CURRENT.get();}
    public static <T>T run(AsyncJobHandler.ExecutionContext context,Supplier<T> work){var previous=CURRENT.get();CURRENT.set(context);try{return work.get();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}}
}
