package com.jsd.aird.shared.resource;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jsd.aird.shared.resource.*;
import com.jsd.aird.shared.error.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** File protocol only; the child has no Spring context, credentials or database access. */
@Component
public class IsolatedDocumentTasks {
    private final ObjectMapper json;private final TemporarySpace space;private final IsolatedProcessRunner runner;
    public IsolatedDocumentTasks(ObjectMapper json,TemporarySpace space,IsolatedProcessRunner runner){this.json=json;this.space=space;this.runner=runner;}
    public <T>T execute(String mode,InputStream input,JsonNode argument,Class<T> resultType){
        return executeVerified(mode,input,argument,resultType,null);
    }
    public <T>T executeVerified(String mode,InputStream input,JsonNode argument,Class<T> resultType,String expectedSha){
        Path directory=null;
        var reservation=space.reserve(3L*512*1024*1024);
        try{
            directory=space.createDirectory();var source=directory.resolve("input.docx");var arg=directory.resolve("argument.json");var output=directory.resolve("output");
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(input;var stream=Files.newOutputStream(source)){BoundedStreams.copy(input,stream,512L*1024*1024,digest);}
            if(expectedSha!=null && !java.util.HexFormat.of().formatHex(digest.digest()).equals(expectedSha))throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,"源文件摘要已变化");
            json.writeValue(arg.toFile(),argument==null?json.nullNode():argument);
            String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
            classpath=Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))).map(entry->Path.of(entry).toAbsolutePath().normalize().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
            var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-Xmx512m","-XX:MaxDirectMemorySize=64m","-XX:MaxMetaspaceSize=128m","-XX:ActiveProcessorCount=1","-Djava.io.tmpdir="+directory,"-cp",classpath));
            // PropertiesLauncher resolves BOOT-INF classes/dependencies in a packaged executable JAR.
            // A development classpath also ends in a dependency JAR, but uses the normal main class.
            if(!classpath.contains(File.pathSeparator) && classpath.endsWith(".jar") && !System.getProperties().containsKey("surefire.test.class.path")){
                command.add(1,"-Dloader.main="+"com.jsd.aird.bootstrap.document.OfficeProcessMain");command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
            }else command.add("com.jsd.aird.bootstrap.document.OfficeProcessMain");
            command.addAll(List.of(mode,source.toString(),arg.toString(),output.toString()));
            runner.run(command,directory,90000);
            long limit=resultType==byte[].class?512L*1024*1024:64L*1024*1024;
            if(Files.size(output)>limit)throw new ApiException(ApiErrorCode.PAYLOAD_TOO_LARGE);
            long estimate=Math.multiplyExact(Files.size(output),resultType==byte[].class?2:4);
            if(estimate>WorkingMemory.limit())throw new ApiException(ApiErrorCode.SERVER_BUSY);
            if(OperationBudget.active())OperationBudget.hold(WorkingMemory.reserve(estimate));
            if(resultType==byte[].class)return resultType.cast(Files.readAllBytes(output));
            return json.readValue(output.toFile(),resultType);
        }catch(IOException|java.security.NoSuchAlgorithmException error){throw new ApiException(ApiErrorCode.BAD_REQUEST,"文档处理产物不可用");}
        finally{if(directory==null || !runner.owns(directory)){
            if(directory!=null)space.deleteAndRelease(directory,reservation);else reservation.close();
        }}
    }
}
