package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Both executing and waiting counts are bounded; no unbounded executor queue. */
public final class BoundedGate {
    private final Semaphore running,waiting;
    private final int capacity;
    private final int queueCapacity;
    private final AtomicLong rejected=new AtomicLong();
    public BoundedGate(int capacity,int queue){this.capacity=capacity;queueCapacity=queue;running=new Semaphore(capacity,true);waiting=new Semaphore(queue,true);}
    public Lease enter(long millis){
        if(running.tryAcquire())return new Lease();
        if(!waiting.tryAcquire()){rejected.incrementAndGet();throw new ApiException(ApiErrorCode.SERVER_BUSY);}
        try {
            if(running.tryAcquire(millis,TimeUnit.MILLISECONDS))return new Lease();
            rejected.incrementAndGet();throw new ApiException(ApiErrorCode.SERVER_BUSY);
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new ApiException(ApiErrorCode.OPERATION_TIMEOUT);}
        finally{waiting.release();}
    }
    public int active(){return capacity-running.availablePermits();}
    public int queued(){return queueCapacity-waiting.availablePermits();}
    public long rejections(){return rejected.get();}
    public final class Lease implements AutoCloseable {
        private final AtomicBoolean closed=new AtomicBoolean();
        @Override public void close(){if(closed.compareAndSet(false,true))running.release();}
    }
}
