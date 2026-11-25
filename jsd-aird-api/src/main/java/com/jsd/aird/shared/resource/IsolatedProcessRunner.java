package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Native/uncooperative computation cannot retain a database connection or spawn unbounded children. */
@Component
public final class IsolatedProcessRunner {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(IsolatedProcessRunner.class);
    private BoundedGate slot=new BoundedGate(1,0);
    @org.springframework.beans.factory.annotation.Autowired
    void configureCapacity(@org.springframework.beans.factory.annotation.Value("${app.worker.document-processes:1}") int processes) {
        if (processes < 1 || processes > 32) throw new IllegalArgumentException("document-processes must be 1..32");
        slot = new BoundedGate(processes, 0);
    }
    private final Set<Path> activeDirectories=ConcurrentHashMap.newKeySet();
    public void run(List<String> command,Path directory,long timeoutMillis){
        var lease=slot.enter(0);Process process=null;List<ProcessHandle> children=List.of();
        var diagnostic=new OutputTail();Thread outputReader=null;
        try{
            OperationBudget.check();
            var builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
            var environment=new HashMap<>(builder.environment());builder.environment().clear();
            for(var name:List.of("SystemRoot","WINDIR","PATH","Path","LANG","LC_ALL","TEMP","TMP","FONTCONFIG_PATH"))if(environment.containsKey(name))builder.environment().put(name,environment.get(name));
            process=builder.start();activeDirectories.add(directory.toAbsolutePath().normalize());
            var started=process;
            outputReader=Thread.ofVirtual().name("aird-document-output").start(()->{
                try(var input=started.getInputStream()) {
                    var buffer=new byte[4096];int count;
                    while((count=input.read(buffer))!=-1)diagnostic.append(buffer,count);
                }catch(java.io.IOException ignored){ }
            });
            long timeout=Math.max(1,Math.min(timeoutMillis,OperationBudget.remainingMillis()));
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
            while(!process.waitFor(Math.min(500,timeout),TimeUnit.MILLISECONDS)){
                OperationBudget.check();
                if(System.nanoTime()>=end)throw new ApiException(ApiErrorCode.OPERATION_TIMEOUT);
                if(Files.getFileStore(directory).getUsableSpace()<1_073_741_824L)throw new ApiException(ApiErrorCode.SERVER_BUSY);
                long bytes=0;try(var paths=Files.walk(directory)){
                    var iterator=paths.iterator();int visited=0;
                    while(iterator.hasNext()){
                        var path=iterator.next();if(++visited>100000)throw new ApiException(ApiErrorCode.PAYLOAD_TOO_LARGE);
                        if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))bytes=Math.addExact(bytes,Files.size(path));
                        if(bytes>3L*512*1024*1024)throw new ApiException(ApiErrorCode.PAYLOAD_TOO_LARGE);
                    }
                }
            }
            if(process.exitValue()!=0) {
                outputReader.join(1000);
                // Never log the command or environment (they may contain credentials).
                // A bounded tail also prevents a noisy child from retaining unbounded output.
                log.warn("Document process exited with code {}. Diagnostic tail: {}",process.exitValue(),diagnostic.text());
            }
            if(process.exitValue()==75)throw new ApiException(ApiErrorCode.SERVER_BUSY,"文档处理资源不足，原文件及编辑内容仍保留");
            if(process.exitValue()!=0)throw new ApiException(ApiErrorCode.BAD_REQUEST,"文档处理失败，原文件及编辑内容仍保留");
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new ApiException(ApiErrorCode.OPERATION_TIMEOUT);}
        catch(java.io.IOException error){throw new ApiException(ApiErrorCode.SERVER_BUSY,"文档处理进程暂不可用");}
        finally{
            if(process!=null){
                children=process.descendants().toList();children.forEach(ProcessHandle::destroyForcibly);
                if(process.isAlive())process.destroyForcibly();
                boolean interrupted=Thread.interrupted();
                try{process.waitFor(3,TimeUnit.SECONDS);for(var child:children)try{child.onExit().get(1,TimeUnit.SECONDS);}catch(ExecutionException|TimeoutException ignored){ }}catch(InterruptedException error){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
            }
            if((process==null || !process.isAlive()) && children.stream().noneMatch(ProcessHandle::isAlive)){activeDirectories.remove(directory.toAbsolutePath().normalize());lease.close();}
            // If the OS cannot terminate a child, deliberately retain the slot.
        }
    }
    static final class OutputTail {
        private final byte[] bytes=new byte[8192];private int cursor,size;
        synchronized void append(byte[] source,int count) {
            int start=Math.max(0,count-bytes.length);
            for(int i=start;i<count;i++){bytes[cursor]=source[i];cursor=(cursor+1)%bytes.length;}
            size=Math.min(bytes.length,size+count);
        }
        synchronized String text() {
            var result=new byte[size];int start=(cursor-size+bytes.length)%bytes.length;
            for(int i=0;i<size;i++)result[i]=bytes[(start+i)%bytes.length];
            return new String(result,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    public int active(){return slot.active();}
    @org.springframework.beans.factory.annotation.Autowired
    void metrics(io.micrometer.core.instrument.MeterRegistry registry){registry.gauge("aird.capacity.processes.active",this,IsolatedProcessRunner::active);}
    public boolean owns(Path directory){return activeDirectories.contains(directory.toAbsolutePath().normalize());}
}
