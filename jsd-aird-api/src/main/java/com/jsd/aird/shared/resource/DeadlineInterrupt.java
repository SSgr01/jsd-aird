package com.jsd.aird.shared.resource;

import java.util.concurrent.*;

/** Cooperative cancellation. Admission stays occupied until the original work actually exits. */
public final class DeadlineInterrupt implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor CLOCK=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon().name("aird-deadline-",0).factory());
    static {CLOCK.setRemoveOnCancelPolicy(true);}
    private final Thread owner=Thread.currentThread();
    private final ScheduledFuture<?> alarm;
    private boolean expired,closed;
    public DeadlineInterrupt(long milliseconds){this(milliseconds,()->{});}
    public DeadlineInterrupt(long milliseconds,Runnable cancelInput){alarm=CLOCK.schedule(()->{synchronized(this){if(!closed){expired=true;owner.interrupt();cancelInput.run();}}},Math.max(1,milliseconds),TimeUnit.MILLISECONDS);}
    public synchronized boolean expired(){return expired;}
    @Override public synchronized void close(){closed=true;alarm.cancel(false);if(expired && Thread.currentThread()==owner)Thread.interrupted();}
}
