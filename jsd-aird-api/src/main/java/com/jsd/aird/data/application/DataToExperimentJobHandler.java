package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DataToExperimentJobHandler implements AsyncJobHandler {
    private final ExperimentAssemblyService service;private final ObjectMapper json;
    public DataToExperimentJobHandler(ExperimentAssemblyService service,ObjectMapper json){this.service=service;this.json=json;}
    @Override public boolean supports(String type){return "DATA_TO_EXPERIMENT_SYNC".equals(type);}
    @Override public JsonNode handle(JsonNode p){
        var created=service.execute(uuid(p,"organizationId"),uuid(p,"actorId"),p.path("actorName").asText(),
                uuid(p,"importJobId"),p.path("assemblyKey").asText(),p.path("planHash").asText(),uuid(p,"categoryId"),
                optional(p,"projectId"),optional(p,"stageId"),optional(p,"taskId"));
        return json.createObjectNode().put("status","SYNCED").put("experimentId",created.experimentId().toString())
                .put("experimentVersionId",created.experimentVersionId().toString()).put("experimentNo",created.experimentNo());
    }
    @Override public boolean isRetryable(Exception e){return !(e instanceof ApiException);}
    @Override public void handleTerminalFailure(JsonNode p,Exception e){service.fail(uuid(p,"organizationId"),uuid(p,"importJobId"),p.path("assemblyKey").asText(),e);}
    private UUID uuid(JsonNode p,String field){return UUID.fromString(p.path(field).asText());}
    private UUID optional(JsonNode p,String field){return p.hasNonNull(field)&&!p.path(field).asText().isBlank()?uuid(p,field):null;}
}
