package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.security.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoricalOptimizationCompositionTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UUID datasetId=UUID.randomUUID();
    private final List<String> ids=List.of(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
    private final AtomicReference<JsonNode> submitted=new AtomicReference<>();

    @Test void automaticallyRetainsFixedZeroAndNonzeroMaterialsInTheFullComposition() {
        submit(dataset("%","MASS_PERCENT"),List.of(code(0),code(1)));
        assertThat(submitted.get().path("features")).hasSize(4);
        assertThat(submitted.get().path("features").get(2).path("fixedValue").asDouble()).isZero();
        assertThat(submitted.get().path("features").get(3).path("fixedValue").asDouble()).isEqualTo(5);
        assertThat(submitted.get().path("compositionGroups").get(0).path("codes")).hasSize(4);
        assertThat(submitted.get().path("rows")).hasSize(4);
        assertThat(submitted.get().path("rows").get(0).path(code(2)).asDouble()).isZero();
    }

    @Test void nonPercentageMaterialsCannotSilentlyBecomeIndependentUnconstrainedFactors() {
        for(var unit:List.of("份","g")) {
            var data=dataset(unit,"UNSPECIFIED");
            var originalCode=code(0);var qualified=originalCode+"|"+unit+"|unspecified";
            ((ObjectNode)data.path("columns").get(0)).put("code",qualified);
            for(var row:data.path("rows")) {
                var values=(ObjectNode)row.path("values");values.set(qualified,values.remove(originalCode));
            }
            assertThatThrownBy(()->submit(data,List.of(qualified,code(1))))
                    .hasMessageContaining("当前材料优化只支持质量百分比");
        }
        assertThat(submitted.get()).isNull();
    }

    @Test void missingVariableMaterialIsNamedRatherThanIgnored() {
        assertThatThrownBy(()->submit(dataset("%","MASS_PERCENT"),List.of(code(0))))
                .hasMessageContaining("配方还缺少材料：材料1");
    }

    private String code(int index){return "formula:"+ids.get(index);}
    private ObjectNode dataset(String unit,String basis) {
        var data=json.createObjectNode().put("id",datasetId.toString()).put("name","涂层配方");
        data.putArray("sourceDatasetIds").add(datasetId.toString());
        var columns=data.putArray("columns");
        for(int i=0;i<ids.size();i++)columns.addObject().put("code",code(i)).put("name","材料"+i)
                .put("kind","NUMERIC").put("unit",unit).put("compositionBasis",basis)
                .put("semanticType","MATERIAL_RATIO").put("mappingStatus","MAPPED").put("materialId",ids.get(i));
        columns.addObject().put("code","gloss").put("name","光泽").put("kind","NUMERIC");
        var rows=data.putArray("rows");
        for(int i=0;i<4;i++)rows.addObject().put("id","sample-"+i).put("label","样品"+i).putObject("values")
                .put(code(0),60+i).put(code(1),35-i).put(code(2),0).put(code(3),5).put("gloss",70+i);
        return data;
    }
    private void submit(ObjectNode data,List<String> selected) {
        var repository=mock(OptimizationRepository.class,call->{
            if(call.getMethod().getName().equals("insertRun")){submitted.set(call.getArgument(6));return true;}
            return RETURNS_DEFAULTS.answer(call);
        });
        var jobs=mock(OpsAsyncFacade.class,call->call.getMethod().getName().equals("enqueue")?UUID.randomUUID():RETURNS_DEFAULTS.answer(call));
        var service=new OptimizationService(repository,null,new ConfigurationHashing(json),jobs,mock(AuditLogFacade.class),mock(AuthorizationService.class),null,json,false);
        var request=json.createObjectNode().put("datasetId",datasetId.toString()).put("yColumn","gloss");
        request.set("xColumns",json.valueToTree(selected));
        var previousRequest=json.createObjectNode();previousRequest.set("datasetSnapshot",data);
        var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),"researcher");
        var previous=new OptimizationRepository.RunRow(UUID.randomUUID(),"SUCCEEDED","SUCCEEDED",100,"SUCCEEDED","request","hash",previousRequest,
                json.createObjectNode(),json.createObjectNode(),json.createObjectNode(),actor.userId(),null,1,json.createObjectNode(),"EXPERIMENT_VERSION",
                UUID.randomUUID(),UUID.randomUUID(),"hash",json.createObjectNode().put("title","配方"),json.createObjectNode(),Instant.EPOCH);
        var savedRows=new java.util.ArrayList<JsonNode>();data.path("rows").forEach(row->savedRows.add(row.path("values")));
        ReflectionTestUtils.invokeMethod(service,"submitHistorical",request,"composition",actor,savedRows,previous);
    }
}
