package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.api.ExperimentConversionFacade;
import com.jsd.aird.shared.error.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** AI boundary accepts durable owned artifacts, never model-supplied workbook bytes. */
@Service
public class ExperimentConversionTools {
    private final ExperimentConversionFacade queue;
    private final AgentArtifactStore records;
    private final AgentArtifactService artifacts;
    private final ObjectMapper json;
    public ExperimentConversionTools(ExperimentConversionFacade queue,AgentArtifactStore records,AgentArtifactService artifacts,ObjectMapper json){
        this.queue=queue;this.records=records;this.artifacts=artifacts;this.json=json;
    }
    public ObjectNode invoke(ObjectNode args) {
        var action=args.path("action").asText("list");
        if(action.equals("list")) return json.createObjectNode().set("tasks",json.valueToTree(queue.list(uuid(args,"batch_id"))));
        var id=uuid(args,"conversion_id");
        if(action.equals("source")) {
            var task=queue.get(id,false);
            try(var file=queue.original(id)) {
                var bytes=com.jsd.aird.shared.resource.BoundedStreams.readBytes(file.stream(),8*1024*1024);
                if(!hash(bytes).equals(task.path("sourceSha256").asText())) throw invalid("原件指纹不一致");
                var result=json.createObjectNode(); result.set("task",task);
                result.put("conversion_resources",true);
                var text="原始实验文件："+task.path("sourceName").asText()+"\nSHA256："+task.path("sourceSha256").asText();
                var candidate=result.putArray("candidates").addObject().put("text",text);
                var source=candidate.putObject("source").put("kind","BUSINESS_RECORD").put("resource_type","EXPERIMENT_CONVERSION")
                        .put("resource_id",id.toString()).put("document_id",id.toString()).put("version_id",task.path("sourceSha256").asText())
                        .put("source_node_key","conversion:"+id).put("content_sha256",hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .put("title",task.path("sourceName").asText());
                source.putObject("anchor").put("conversion_adapter","v1");
                result.putObject("business_file").put("name",file.originalName()).put("content_type",file.contentType())
                        .put("bytes",bytes.length).put("sha256",hash(bytes)).put("content_base64",Base64.getEncoder().encodeToString(bytes));
                return result;
            } catch(ApiException error){throw error;} catch(Exception error){throw invalid("原件读取失败");}
        }
        if(action.equals("fail")) return queue.fail(id,args.path("reason").asText());
        if(!action.equals("stage")) throw invalid("未知转换操作");
        // Resolve ownership and current export permissions on every artifact read.
        var resultId=uuid(args,"result_artifact_id");var planId=uuid(args,"plan_artifact_id");var reportId=uuid(args,"report_artifact_id");
        var result=records.get(resultId);
        if(!result.path("name").asText().toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw invalid("转换结果必须为 XLSX");
        var content=read(resultId,10*1024*1024);
        var planBytes=read(planId,1024*1024);var reportBytes=read(reportId,1024*1024);
        try {
            var plan=json.readTree(planBytes);var report=json.readTree(reportBytes);
            if(!hash(planBytes).equals(report.path("plan_sha256").asText()) ||
                    !plan.path("template_sha256").equals(report.path("template_sha256"))) throw invalid("计划或模板指纹与校验报告不一致");
            return queue.stage(id,resultId,planId,reportId,uuid(result,"file_id"),plan,report,hash(content));
        } catch(ApiException error){throw error;} catch(Exception error){throw invalid("映射计划或校验报告不是有效 JSON");}
    }
    public ObjectNode updateLaunchStatus(JsonNode args) {
        return queue.updateLaunchStatus(uuid(args,"conversion_id"),args.path("status").asText(),
                args.path("expected_updated_at").asText(),args.path("reason").asText(),args.path("request_id").asText());
    }
    public void validateSource(JsonNode source,String excerpt) {
        var task=queue.get(uuid(source,"resource_id"),false);
        var text="原始实验文件："+task.path("sourceName").asText()+"\nSHA256："+task.path("sourceSha256").asText();
        if(!task.path("sourceSha256").asText().equals(source.path("version_id").asText()) ||
                !hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(source.path("content_sha256").asText()) ||
                excerpt!=null&&!text.contains(excerpt))throw invalid("转换原件来源不匹配");
    }
    private byte[] read(UUID id,int limit) {
        var record=records.get(id);
        try(var file=artifacts.open(id)) {
            var bytes=com.jsd.aird.shared.resource.BoundedStreams.readBytes(file.stream(),limit);
            if(!hash(bytes).equals(record.path("sha256").asText())) throw invalid("成果存储指纹不一致");
            return bytes;
        } catch(ApiException error){throw error;}catch(Exception error){throw invalid("成果读取失败");}
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static UUID uuid(JsonNode node,String key){try{return UUID.fromString(node.path(key).asText());}catch(IllegalArgumentException e){throw invalid("转换参数缺失或不是有效身份："+key);}}
    private static ApiException invalid(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
}
