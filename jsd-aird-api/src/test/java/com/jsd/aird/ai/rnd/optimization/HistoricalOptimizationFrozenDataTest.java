package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoricalOptimizationFrozenDataTest {
    @Test void nextRoundUsesTheSavedSamplesAndObservationsWithoutReloadingOriginalReports() throws Exception {
        var json=new ObjectMapper();var captured=new AtomicReference<JsonNode>();
        var repository=mock(OptimizationRepository.class,call->{
            if(call.getMethod().getName().equals("insertRun")){captured.set(call.getArgument(6));return true;}
            return RETURNS_DEFAULTS.answer(call);
        });
        var jdbc=mock(JdbcTemplate.class);when(repository.jdbc()).thenReturn(jdbc);
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
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<JsonNode>>any(),eq(org),eq(run),eq("gloss")))
                .thenReturn(List.of(json.createObjectNode().put("temperature",29).put("gloss",85)));
        var service=new OptimizationService(repository,null,new ConfigurationHashing(json),jobs,mock(AuditLogFacade.class),mock(AuthorizationService.class),null,json,false);
        ActorContext.set(new Actor(org,user,"researcher"));
        try {
            service.continueHistorical(run,"next-round");
            assertThat(captured.get().path("rows")).hasSize(5);
            assertThat(captured.get().path("rows").get(4).path("gloss").asInt()).isEqualTo(85);
            assertThat(captured.get().path("rows").get(0).path("temperature").asInt()).isEqualTo(20);
            assertThat(captured.get().path("datasetSnapshot")).isEqualTo(snapshot);
            verify(repository,never()).historyRecords(any(),any());
            verify(repository,never()).historyDatasets(any(),any());
        } finally { ActorContext.clear(); }
    }
}
