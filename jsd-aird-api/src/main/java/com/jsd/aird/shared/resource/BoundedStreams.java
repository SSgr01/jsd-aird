package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.*;
import java.io.*;
import java.security.*;

public final class BoundedStreams {
    public static InputStream limit(InputStream input,long maximum){
        return new java.io.FilterInputStream(input){
            private long total;
            private void count(int value){OperationBudget.check();if(value>0 && (total+=value)>maximum)throw new ApiException(ApiErrorCode.PAYLOAD_TOO_LARGE);}
            @Override public int read()throws IOException{int value=in.read();count(value<0?0:1);return value;}
            @Override public int read(byte[] bytes,int offset,int length)throws IOException{int count=in.read(bytes,offset,(int)Math.min(length,Math.max(1,maximum-total+1)));count(count);return count;}
            @Override public long skip(long n)throws IOException{byte[] buffer=new byte[(int)Math.min(n,65536)];long skipped=0;while(skipped<n){int count=read(buffer,0,(int)Math.min(buffer.length,n-skipped));if(count<0)break;skipped+=count;}return skipped;}
            @Override public boolean markSupported(){return false;}
            @Override public synchronized void reset()throws IOException{throw new IOException("Bounded stream does not support resetting byte counts");}
        };
    }
    public static byte[] readBytes(InputStream input,long limit)throws IOException{
        var output=new java.io.ByteArrayOutputStream();copy(input,output,limit,null);return output.toByteArray();
    }
    private BoundedStreams(){ }
    public static long copy(InputStream input,OutputStream output,long limit,MessageDigest digest)throws IOException{
        byte[] buffer=new byte[65536];long total=0;int count;
        while((count=input.read(buffer))!=-1){OperationBudget.check();if(count>limit-total)throw new ApiException(ApiErrorCode.PAYLOAD_TOO_LARGE);if(digest!=null)digest.update(buffer,0,count);output.write(buffer,0,count);total+=count;}
        return total;
    }
}
