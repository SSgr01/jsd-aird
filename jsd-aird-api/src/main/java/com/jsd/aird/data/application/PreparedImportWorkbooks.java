package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Immutable normalized source artifacts, never user drafts or correction overlays. */
@Service
public class PreparedImportWorkbooks {
    private final ObjectStorage storage;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> building = new ConcurrentHashMap<>();
    public PreparedImportWorkbooks(ObjectStorage storage, ObjectMapper json) { this.storage=storage; this.json=json; }

    String key(UUID org, DataRepository.Job job, String parser) {
        var identity=job.sourceSha256()+"|"+job.sourceFileId()+"|"+job.templateVersionId()+"|"+job.contractHash()+"|"+parser;
        try {
            var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return "internal/prepared-workbooks/v1/"+org+"/"+hash+".json.gz";
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public void save(UUID org, DataRepository.Job job, String parser, JsonNode snapshot) {
        if (!valid(snapshot)) return;
        var data=encode(snapshot);
        storage.put(key(org,job,parser), new ByteArrayInputStream(data), data.length, "application/gzip");
    }

    public JsonNode getOrBuild(UUID org, DataRepository.Job job, Supplier<JsonNode> build) {
        String key=key(org,job,job.parserVersion());
        var promise=new CompletableFuture<byte[]>();
        var existing=building.putIfAbsent(key,promise);
        if (existing!=null) {
            try {
                long remaining=com.jsd.aird.shared.resource.OperationBudget.active()
                        ?com.jsd.aird.shared.resource.OperationBudget.remainingMillis():60000L;
                return decode(existing.get(Math.max(1,remaining),TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.OPERATION_TIMEOUT);
            } catch (TimeoutException timeout) {
                throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.OPERATION_TIMEOUT);
            } catch (ExecutionException failed) {
                if(failed.getCause() instanceof RuntimeException cause)throw cause;
                if(failed.getCause() instanceof Error cause)throw cause;
                throw new IllegalStateException("Cannot prepare workbook",failed.getCause());
            }
        }
        try {
            byte[] data=null;
            JsonNode ready=null;
            try (var stored=storage.get(key)) {
                data=stored.stream().readAllBytes();
                ready=decode(data);
                if (!valid(ready)) { data=null; ready=null; }
            } catch (Exception missingOrInvalid) {
                // No empty/failed artifact is cached; old imports are repaired on their first read.
                data=null;
            }
            if(data==null) {
                var snapshot=build.get();
                if(!valid(snapshot)) throw new com.jsd.aird.shared.error.ApiException(
                        com.jsd.aird.shared.error.ApiErrorCode.FILE_NOT_READY,"导入文件缺少可编辑工作簿快照");
                ready=snapshot;
                data=encode(snapshot);
                storage.put(key,new ByteArrayInputStream(data),data.length,"application/gzip");
            }
            promise.complete(data);
            return ready; // only this owner sees ready; followers decode independent correction overlays
        } catch(RuntimeException | Error e) { promise.completeExceptionally(e); throw e; }
        finally { building.remove(key,promise); }
    }
    private boolean valid(JsonNode snapshot) {
        return snapshot!=null && snapshot.isObject() && snapshot.path("sheets").isObject() && !snapshot.path("sheets").isEmpty();
    }
    private byte[] encode(JsonNode snapshot) {
        try {
            var output=new ByteArrayOutputStream();
            try(var gzip=new GZIPOutputStream(output)) { json.writeValue(gzip,snapshot); }
            return output.toByteArray();
        } catch(IOException e) { throw new IllegalStateException("Cannot serialize prepared workbook",e); }
    }
    private JsonNode decode(byte[] bytes) {
        try(var gzip=new GZIPInputStream(new ByteArrayInputStream(bytes))) { return json.readTree(gzip); }
        catch(IOException e) { throw new IllegalStateException("Cannot read prepared workbook",e); }
    }
}
