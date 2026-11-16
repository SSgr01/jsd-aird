package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.api.ExperimentConversionFacade;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.shared.error.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperimentConversionToolsTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ExperimentConversionFacade queue=mock(ExperimentConversionFacade.class);
    private final AgentArtifactStore records=mock(AgentArtifactStore.class);
    private final AgentArtifactService artifacts=mock(AgentArtifactService.class);
    private final ExperimentConversionTools tools=new ExperimentConversionTools(queue,records,artifacts,json);
    private String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
    private UUID artifact(String name,byte[] bytes)throws Exception{
        var id=UUID.randomUUID();var file=UUID.randomUUID();
        when(records.get(id)).thenReturn(json.createObjectNode().put("name",name).put("sha256",hash(bytes)).put("file_id",file.toString()));
        when(artifacts.open(id)).thenReturn(new FileStorageFacade.StoredFile(file,name,"application/json",bytes.length,hash(bytes),new ByteArrayInputStream(bytes)));
        return id;
    }
    @Test void foreignOrRevokedArtifactCannotCreateAnImport(){
        var id=UUID.randomUUID();
        when(records.get(id)).thenThrow(new ApiException(ApiErrorCode.PERMISSION_DENIED));
        var args=json.createObjectNode().put("action","stage").put("conversion_id",UUID.randomUUID().toString())
                .put("result_artifact_id",id.toString()).put("plan_artifact_id",UUID.randomUUID().toString()).put("report_artifact_id",UUID.randomUUID().toString());
        assertThrows(ApiException.class,()->tools.invoke(args));verifyNoInteractions(queue);
    }
    @Test void modifiedPlanCannotCreateAnImport()throws Exception{
        var result=artifact("result.xlsx",new byte[]{1,2});var plan=artifact("plan.json","{}".getBytes());
        var report=artifact("report.json","{\"plan_sha256\":\"wrong\"}".getBytes());
        var args=json.createObjectNode().put("action","stage").put("conversion_id",UUID.randomUUID().toString())
                .put("result_artifact_id",result.toString()).put("plan_artifact_id",plan.toString()).put("report_artifact_id",report.toString());
        assertThrows(ApiException.class,()->tools.invoke(args));verifyNoInteractions(queue);
    }
    @Test void storedBytesMustMatchRegisteredArtifactHash()throws Exception{
        var result=artifact("result.xlsx",new byte[]{1,2});
        when(artifacts.open(result)).thenReturn(new FileStorageFacade.StoredFile(UUID.randomUUID(),"result.xlsx","application/zip",3,"irrelevant",new ByteArrayInputStream(new byte[]{3,4,5})));
        var args=json.createObjectNode().put("action","stage").put("conversion_id",UUID.randomUUID().toString())
                .put("result_artifact_id",result.toString()).put("plan_artifact_id",UUID.randomUUID().toString()).put("report_artifact_id",UUID.randomUUID().toString());
        assertThrows(ApiException.class,()->tools.invoke(args));verifyNoInteractions(queue);
    }
}
