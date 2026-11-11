package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.error.ApiErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoricalOptimizationFrozenDataTest {
    @Test void reopeningAnOptimizationRechecksCurrentExperimentAccess() {
        var json=new ObjectMapper();var repository=mock(OptimizationRepository.class);
        var org=UUID.randomUUID();var user=UUID.randomUUID();var run=UUID.randomUUID();var experiment=UUID.randomUUID();var version=UUID.randomUUID();
        var request=json.createObjectNode();request.putObject("datasetSnapshot").putArray("rows").addObject()
            .put("sourceExperimentId",experiment.toString()).put("versionId",version.toString());
        when(repository.byId(org,user,run)).thenReturn(Optional.of(new OptimizationRepository.RunRow(run,"SUCCEEDED","SUCCEEDED",100,"SUCCEEDED","request","hash",request,
            json.createObjectNode(),json.createObjectNode(),json.createObjectNode(),user,null,1,json.createObjectNode(),"EXPERIMENT_VERSION",
            experiment,version,"hash",json.createObjectNode(),json.createObjectNode(),Instant.EPOCH)));
        var source=mock(com.jsd.aird.rnd.api.ResearchExperimentFacade.class);
        doThrow(new ApiException(ApiErrorCode.DATA_SCOPE_DENIED)).when(source).requireReadableVersion(org,experiment,version);
        var service=new OptimizationService(repository,null,new ConfigurationHashing(json),mock(OpsAsyncFacade.class),mock(AuditLogFacade.class),mock(AuthorizationService.class),null,json,false,source);
        ActorContext.set(new Actor(org,user,"researcher"));
        try {
            assertThatThrownBy(()->service.get(run)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.DATA_SCOPE_DENIED));
        } finally {ActorContext.clear();}
    }
    @Test void dataChangeBetweenAuthorizedReadAndSubmissionRejectsBeforeAnyRunIsCreated() {
        var json=new ObjectMapper();var repo=mock(OptimizationRepository.class);var jdbc=mock(JdbcTemplate.class);
        when(repo.jdbc()).thenReturn(jdbc);var datasetId=UUID.randomUUID();
        var service=spy(new OptimizationService(repo,null,new ConfigurationHashing(json),mock(OpsAsyncFacade.class),mock(AuditLogFacade.class),mock(AuthorizationService.class),null,json,false,null));
        var changed=json.createObjectNode().put("id",datasetId.toString()).put("name","隔离数据的新版本");
        changed.putArray("rows").addObject().put("sourceExperimentId",UUID.randomUUID().toString()).put("versionId",UUID.randomUUID().toString());
        doReturn(changed).when(service).historyDataset(datasetId);
        var request=json.createObjectNode().put("mode","HISTORICAL_GP_BO").put("datasetId",datasetId.toString());
        request.putObject("expectedDatasetHashes").put(datasetId.toString(),"0".repeat(64));
        ActorContext.set(ActorContext.developmentDefault());
        try {
            assertThatThrownBy(()->service.submit(request,"isolated-snapshot-key"))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
            verify(repo,never()).materialCatalog(any());
        } finally { ActorContext.clear(); }
    }
    @Test void nextRoundUsesTheSavedSamplesAndObservationsWithoutReloadingOriginalReports() throws Exception {
        var json=new ObjectMapper();var captured=new AtomicReference<JsonNode>();
        var repository=mock(OptimizationRepository.class,call->{
            if(call.getMethod().getName().equals("insertRun")){captured.set(call.getArgument(6));return true;}
            return RETURNS_DEFAULTS.answer(call);
        });
        var jdbc=mock(JdbcTemplate.class);when(repository.jdbc()).thenReturn(jdbc);
        when(repository.materialCatalog(any())).thenReturn(MaterialCatalogSnapshot.empty());
        var jobs=mock(OpsAsyncFacade.class,call->call.getMethod().getName().equals("enqueue")?UUID.randomUUID():RETURNS_DEFAULTS.answer(call));
        var org=UUID.randomUUID();var user=UUID.randomUUID();var run=UUID.randomUUID();var dataset=UUID.randomUUID();
        var request=json.createObjectNode().put("mode","HISTORICAL_GP_BO").put("datasetId",dataset.toString())
                .put("datasetName","涂层配方").put("target","gloss").put("direction","MAXIMIZE").put("candidateCount",4);
        request.putArray("datasetIds").add(dataset.toString());
        request.putArray("features").addObject().put("code","temperature").put("kind","NUMERIC").put("minimum",20).put("maximum",32);
        var snapshot=request.putObject("datasetSnapshot").put("id",dataset.toString()).put("name","涂层配方");
        snapshot.putArray("sourceDatasetIds").add(dataset.toString());
        var columns=snapshot.putArray("columns");
        columns.addObject().put("code","temperature").put("name","温度").put("kind","NUMERIC");
        columns.addObject().put("code","gloss").put("name","光泽").put("kind","NUMERIC");
        var selected=request.putObject("selectedRowIdsByDataset").putArray(dataset.toString());
        var rows=request.putArray("rows");var samples=snapshot.putArray("rows");
        for(int i=0;i<4;i++){
            var values=json.createObjectNode().put("temperature",20+i).put("gloss",70+i);
            rows.add(values);selected.add("sample-"+i);
            samples.addObject().put("id","sample-"+i).put("label","样品"+i).put("sourceDatasetId",dataset.toString()).set("values",values);
        }
        var previous=new OptimizationRepository.RunRow(run,"SUCCEEDED","SUCCEEDED",100,"SUCCEEDED","request","hash",request,
                json.createObjectNode(),json.createObjectNode(),json.createObjectNode(),user,null,1,json.createObjectNode(),"EXPERIMENT_VERSION",
                UUID.randomUUID(),UUID.randomUUID(),"hash",json.createObjectNode().put("title","涂层配方"),json.createObjectNode(),Instant.EPOCH);
        when(repository.byId(org,user,run)).thenReturn(Optional.of(previous));
        var service=new OptimizationService(repository,null,new ConfigurationHashing(json),jobs,mock(AuditLogFacade.class),mock(AuthorizationService.class),null,json,false,null);
        request.set("baseRows",samples.deepCopy());
        var observations=mock(ResearchObservationService.class);
        when(observations.currentSource(eq(org),any())).thenAnswer(call->!"experiment-2".equals(((JsonNode)call.getArgument(1)).path("sourceExperimentId").asText()));
        var measured=json.createObjectNode().put("experimentId",UUID.randomUUID().toString()).put("experimentVersionId",UUID.randomUUID().toString())
            .put("sampleKey","new-sample").put("fieldCode","gloss").put("value",85);
        measured.putObject("actualInputs").put("temperature",29);
        when(observations.trainingManifest(org,run)).thenReturn(List.of(measured,measured.deepCopy()));
        // The same physical sample must enter the next training matrix once.
        for(int i=0;i<4;i++)((com.fasterxml.jackson.databind.node.ObjectNode)request.path("baseRows").get(i))
            .put("sourceExperimentId","experiment-"+i).put("sampleKey","sample-"+i);
        ((com.fasterxml.jackson.databind.node.ArrayNode)request.path("baseRows")).add(request.path("baseRows").get(0).deepCopy());
        service.loopServices(observations,null);
        ActorContext.set(new Actor(org,user,"researcher"));
        try {
            service.continueHistorical(run,"next-round");
            assertThat(captured.get().path("rows")).hasSize(4);
            assertThat(captured.get().path("rows").get(3).path("gloss").asInt()).isEqualTo(85);
            assertThat(captured.get().path("rows").get(3).path("temperature").asInt()).isEqualTo(29);
            assertThat(captured.get().path("rows")).noneSatisfy(row->assertThat(row.path("temperature").asInt()).isEqualTo(22));
            assertThat(captured.get().path("rows").get(0).path("temperature").asInt()).isEqualTo(20);
            assertThat(captured.get().path("datasetSnapshot")).isEqualTo(snapshot);
            verify(repository,never()).historyRecords(any(),any());
            verify(repository,never()).historyDatasets(any(),any());
        } finally { ActorContext.clear(); }
    }
}
