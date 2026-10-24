package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepare before sending response headers, with bounded disk and no whole-export byte array. */
public final class PreparedFile implements AutoCloseable {
    @FunctionalInterface public interface Writer {void write(OutputStream output)throws IOException;}
    private final Path path;private final TemporarySpace space;private final TemporarySpace.Reservation reservation;private final AtomicBoolean closed=new AtomicBoolean();
    private PreparedFile(Path path,TemporarySpace space,TemporarySpace.Reservation reservation){this.path=path;this.space=space;this.reservation=reservation;}
    public static PreparedFile create(TemporarySpace space,long maximum,Writer writer){
        var reservation=space.reserve(maximum);Path path=null;
        try{
            path=space.create();
            try(var output=Files.newOutputStream(path)){
                writer.write(new FilterOutputStream(output){
                    long written;
                    private void count(int size){OperationBudget.check();if(size>maximum-written)throw new ApiException(ApiErrorCode.PAYLOAD_TOO_LARGE);written+=size;}
                    @Override public void write(int value)throws IOException{count(1);out.write(value);}
                    @Override public void write(byte[] bytes,int offset,int size)throws IOException{count(size);out.write(bytes,offset,size);}
                });
            }
            return new PreparedFile(path,space,reservation);
        }catch(IOException|RuntimeException error){
            if(path==null)reservation.close();else space.deleteAndRelease(path,reservation);
            if(error instanceof RuntimeException runtime)throw runtime;throw new ApiException(ApiErrorCode.SERVER_BUSY);
        }
    }
    public Path path(){return path;}
    public long size(){try{return Files.size(path);}catch(IOException error){throw new ApiException(ApiErrorCode.SERVER_BUSY);}}
    @Override public void close(){if(closed.compareAndSet(false,true))space.deleteAndRelease(path,reservation);}
}
