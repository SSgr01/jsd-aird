package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.atomic.*;

/** Reserve before receiving bytes, including servlet spool and derived staging copies. */
public final class TemporarySpace {
    private static final java.util.concurrent.ScheduledThreadPoolExecutor CLEANER=new java.util.concurrent.ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon().name("aird-temp-cleanup-",0).factory());
    static {CLEANER.setRemoveOnCancelPolicy(true);}
    private final AtomicLong reserved=new AtomicLong();
    private final long limit;
    private final Path root;
    public TemporarySpace(long limit,Path root){this.limit=limit;this.root=root.toAbsolutePath().normalize();try{
        Files.createDirectories(this.root);
        // Crash leftovers still consume the budget. Never delete an unverified live process directory.
        try(var owned=Files.newDirectoryStream(this.root,"aird-*")){
            long existing=0;for(var item:owned)try(var paths=Files.walk(item)){for(var path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())existing=Math.addExact(existing,Files.size(path));}
            reserved.set(existing);
        }
    }catch(IOException e){throw new IllegalStateException("Cannot create application temporary directory",e);}}
    public Reservation reserve(long bytes){
        if(bytes<0 || bytes>limit)throw new ApiException(ApiErrorCode.SERVER_BUSY);
        try{if(Files.getFileStore(root).getUsableSpace()<bytes+1_073_741_824L)throw new ApiException(ApiErrorCode.SERVER_BUSY);}catch(IOException e){throw new ApiException(ApiErrorCode.SERVER_BUSY);}
        while(true){long current=reserved.get();if(current>limit-bytes)throw new ApiException(ApiErrorCode.SERVER_BUSY);if(reserved.compareAndSet(current,current+bytes))return new Reservation(bytes);}
    }
    public long reserved(){return reserved.get();}
    public Path create()throws IOException{return Files.createTempFile(root,"aird-",".staged");}
    public Path createDirectory()throws IOException{return Files.createTempDirectory(root,"aird-process-");}
    public void deleteAndRelease(Path artifact,Reservation reservation){
        var owned=artifact.toAbsolutePath().normalize();
        if(owned.equals(root) || !owned.startsWith(root) || !owned.getFileName().toString().startsWith("aird-"))throw new IllegalArgumentException("Not an owned temporary artifact");
        try{deleteOwned(owned);reservation.close();}
        catch(IOException error){
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>();
            future.set(CLEANER.scheduleWithFixedDelay(()->{try{deleteOwned(owned);reservation.close();var task=future.get();if(task!=null)task.cancel(false);}catch(IOException ignored){ }},5,5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    private void deleteOwned(Path artifact)throws IOException{
        if(!Files.exists(artifact,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(artifact)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    public final class Reservation implements AutoCloseable{
        private final long bytes;private final AtomicBoolean closed=new AtomicBoolean();
        private Reservation(long bytes){this.bytes=bytes;}
        public void close(){if(closed.compareAndSet(false,true))reserved.addAndGet(-bytes);}
    }
}
