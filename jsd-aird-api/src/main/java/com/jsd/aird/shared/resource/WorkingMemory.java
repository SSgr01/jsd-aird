package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.*;
import java.util.concurrent.atomic.*;

/** Aggregate admission estimate, independent of file/upload limits; not a measurement of live heap. */
public final class WorkingMemory {
    private static final long LIMIT=Runtime.getRuntime().maxMemory()/2;
    private static final AtomicLong RESERVED=new AtomicLong();
    private WorkingMemory(){ }
    public static AutoCloseable reserve(long estimate){
        if(estimate<0 || estimate>LIMIT)throw new ApiException(ApiErrorCode.SERVER_BUSY);
        while(true){long value=RESERVED.get();if(value>LIMIT-estimate)throw new ApiException(ApiErrorCode.SERVER_BUSY);if(RESERVED.compareAndSet(value,value+estimate))break;}
        var released=new AtomicBoolean();return ()->{if(released.compareAndSet(false,true))RESERVED.addAndGet(-estimate);};
    }
    public static long reserved(){return RESERVED.get();}
    public static long limit(){return LIMIT;}
}
