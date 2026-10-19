package com.jsd.aird.ai.rnd.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.formula.FormulaNormalizationService;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.modeling.WorkbenchFormulaSupport;
import com.jsd.aird.ai.rnd.prediction.PredictionRepository;
import com.jsd.aird.ai.rnd.prediction.PredictionService;
import com.jsd.aird.ai.rnd.compute.ModelComputeClient;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FormulaDesignServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final FormulaDesignRepository repository=mock(FormulaDesignRepository.class);
    private final PredictionRepository predictions=mock(PredictionRepository.class);
    private final PredictionService predictionContext=mock(PredictionService.class);
    private final FormulaModelArtifactStore artifacts=mock(FormulaModelArtifactStore.class);
    private final ModelComputeClient compute=mock(ModelComputeClient.class);
    private final ConfigurationHashing hashing=new ConfigurationHashing(json);
    private final FormulaNormalizationService normalization=new FormulaNormalizationService(json);
    private final FormulaDesignService service=new FormulaDesignService(repository,predictions,predictionContext,artifacts,compute,hashing,
            mock(OpsAsyncFacade.class),mock(AuditLogFacade.class),json,normalization,false);

    @AfterEach void clearActor(){ActorContext.clear();}
    private JsonNode node(String text)throws Exception{return json.readTree(text);}

    private FormulaDesignRepository.Row row(UUID id,JsonNode request,JsonNode bindings,String hash){
        return new FormulaDesignRepository.Row(id,"FORMULA_PREDICTION","RUNNING",null,35,"SEARCHING","request",hash,request,bindings,json.createObjectNode(),null,ActorContext.developmentDefault().userId(),UUID.randomUUID(),1,json.createObjectNode(),Instant.now());
    }



    private PredictionRepository.TargetBinding workbenchBinding()throws Exception {
        var b=mock(PredictionRepository.TargetBinding.class);
        when(b.targetId()).thenReturn(UUID.randomUUID());when(b.modelId()).thenReturn(UUID.randomUUID());
        when(b.targetVersionId()).thenReturn(UUID.randomUUID());when(b.workbenchRunId()).thenReturn(UUID.randomUUID());
        when(b.targetName()).thenReturn("耐磨次数");when(b.targetCode()).thenReturn("WB_WEAR");when(b.targetHash()).thenReturn("0".repeat(64));
        when(b.targetVersion()).thenReturn(1);when(b.valueType()).thenReturn("CONTINUOUS");
        when(b.classes()).thenReturn(json.createArrayNode());when(b.definition()).thenReturn(json.createObjectNode());
        when(b.observationSemantics()).thenReturn(json.createObjectNode());
        when(predictionContext.gateAvailability(b)).thenReturn(List.of());
        var a="f44a3459-05c1-4958-a246-b5736c0aa682";var c="fa09039b-f0a2-48bd-9acc-71ed839ce94d";
        var scheme=json.createObjectNode().put("id","workbench").put("version",1).put("sha256","0".repeat(64));var fields=scheme.putArray("fields");
        for(var id:List.of(a,c))fields.addObject().put("code","formula:"+id).put("name",id.equals(a)?"树脂 A比例":"树脂 B比例")
                .put("valueType","NUMBER").put("unit","%").put("required",true).put("semanticType","MATERIAL_RATIO")
                .put("mappingStatus","MAPPED").put("materialId",id).put("materialCode",id.equals(a)?"R-1":"A-1")
                .put("compositionBasis","MASS_PERCENT").putObject("encoding");
        fields.addObject().put("code","temperature").put("name","温度").put("valueType","NUMBER").put("unit","°C").put("required",true);
        var rows=json.createArrayNode();rows.addObject().putObject("values").put("formula:"+a,40).put("formula:"+c,60);
        when(b.contractVersion()).thenReturn("modeling-workbench.v1");when(b.unit()).thenReturn(null);
        when(b.frozenInputScheme()).thenReturn(scheme);when(b.frozenDictionary()).thenReturn(WorkbenchFormulaSupport.dictionary(json,scheme,rows));
        var domain=json.createObjectNode();domain.putArray("features").add("formula:"+a).add("formula:"+c).add("temperature");
        domain.putArray("minimum").add(20).add(20).add(20);domain.putArray("maximum").add(80).add(80).add(30);when(b.modelDomain()).thenReturn(domain);
        when(b.artifactKey()).thenReturn("model.zip");when(b.artifactSha()).thenReturn("0".repeat(64));
        when(artifacts.readRef(anyString(),anyString(),anyString())).thenReturn(new FormulaModelArtifactStore.ArtifactReadRef("model","http://localhost/model.zip","0".repeat(64)));
        return b;
    }

    @Test void exposesWorkbenchRatiosOnceAndMapsCandidatesWithoutMutatingFrozenFields()throws Exception {
        ActorContext.set(ActorContext.developmentDefault());var b=workbenchBinding();
        var base=json.createObjectNode();base.putArray("targets").addObject().put("targetId",b.targetId().toString());
        base.putArray("selected").addObject().put("targetId",b.targetId().toString());
        base.set("requiredInputs",b.frozenInputScheme().path("fields").deepCopy());base.putArray("configurationConflicts");
        when(predictionContext.context(anyList())).thenReturn(base);when(predictions.targetBindings(any(),anyList())).thenReturn(List.of(b));
        var context=service.context(List.of(b.targetId()));
        assertThat(context.path("selected").get(0).path("available").asBoolean()).isTrue();
        assertThat(context.path("materialIntersection").size()).isEqualTo(2);
        assertThat(context.path("materialIntersection").get(0).path("name").asText()).isEqualTo("树脂 A");
        assertThat(context.path("materialIntersection").get(0).path("sourceColumnName").asText()).isEqualTo("树脂 A比例");
        assertThat(context.path("materialIntersection").get(0).path("supportedRanges").get(0).path("minimum").asInt()).isEqualTo(20);
        assertThat(context.path("requiredInputs").size()).isEqualTo(1);
        assertThat(context.path("requiredInputs").get(0).path("code").asText()).isEqualTo("temperature");
        var payload=service.frozenModelBinding(b);
        assertThat(payload.path("inputScheme").path("fields").get(0).path("encoding").path("formulaMaterialId").asText()).isEqualTo("f44a3459-05c1-4958-a246-b5736c0aa682");
        assertThat(payload.path("target").path("unit").isNull()).isTrue();
        assertThat(b.frozenInputScheme().path("fields").get(0).path("encoding").has("formulaMaterialId")).isFalse();
    }

    @Test void explainsIncompleteProportionsAndSingleRatioTargets()throws Exception {
        ActorContext.set(ActorContext.developmentDefault());var b=workbenchBinding();
        ((com.fasterxml.jackson.databind.node.ObjectNode)b.frozenDictionary()).put("compositionStatus","INCOMPLETE");
        var source=json.createObjectNode();source.putObject("modelSource").putObject("yColumn")
                .put("code","formula:f44a3459-05c1-4958-a246-b5736c0aa682").put("kind","NUMERIC").put("unit","%")
                .put("semanticType","MATERIAL_RATIO").put("mappingStatus","MAPPED")
                .put("materialId","f44a3459-05c1-4958-a246-b5736c0aa682").put("compositionBasis","MASS_PERCENT");when(b.definition()).thenReturn(source);
        var base=json.createObjectNode();base.putArray("targets").addObject().put("targetId",b.targetId().toString());base.putArray("selected");base.putArray("requiredInputs");base.putArray("configurationConflicts");
        when(predictionContext.context(anyList())).thenReturn(base);when(predictions.targetBindings(any(),anyList())).thenReturn(List.of(b));
        var target=service.context(List.of()).path("targets").get(0);
        assertThat(target.path("available").asBoolean()).isFalse();
        assertThat(target.path("unavailableReasons").toString()).contains("FORMULA_TARGET_IS_RATIO","MODEL_FORMULA_INPUTS_INCOMPLETE");
    }

    @Test void validatesMappedRecipeTotalsBoundsAndFixedConditionsBeforeSaving()throws Exception {
        var b=workbenchBinding();var payload=json.createObjectNode();payload.putArray("modelBindings").add(service.frozenModelBinding(b));
        var request=json.createObjectNode();var formula=request.putObject("baselineFormula").put("basis","MASS_PERCENT").put("compositionComplete",true).put("recordedTotal",100);
        var components=formula.putArray("components");
        var a="f44a3459-05c1-4958-a246-b5736c0aa682";var c="fa09039b-f0a2-48bd-9acc-71ed839ce94d";
        components.addObject().put("materialId",a).put("ratio",40).put("unit","PERCENT").put("amountKnown",true);
        components.addObject().put("materialId",c).put("ratio",60).put("unit","PERCENT").put("amountKnown",true);
        request.put("targetTotal",100).put("formulaBasis","MASS_PERCENT");request.putObject("fixedInputs").put("temperature",25);
        var space=request.putObject("searchSpace");var variables=space.putArray("variableMaterials");
        for(var id:List.of(a,c))variables.addObject().put("materialId",id).put("minimum",20).put("maximum",80);
        ReflectionTestUtils.invokeMethod(service,"validateFormulaRequest",request,List.of(b));
        var candidate=json.createObjectNode();candidate.set("formula",formula.deepCopy());candidate.putObject("inputs").put("temperature",25);
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(service,"candidateMatchesRequest",candidate,request,payload)).isTrue();
        candidate.withObject("inputs").put("temperature",30);
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(service,"candidateMatchesRequest",candidate,request,payload)).isFalse();
        candidate.withObject("inputs").put("temperature",25);((ObjectNode)candidate.path("formula").path("components").get(0)).put("ratio",50);
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(service,"candidateMatchesRequest",candidate,request,payload)).isFalse();
        ((ObjectNode)candidate.path("formula").path("components").get(1)).put("ratio",50);
        ((ObjectNode)variables.get(0)).put("maximum",45);
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(service,"candidateMatchesRequest",candidate,request,payload)).isFalse();
        variables.remove(0); // A is fixed at 40%; a complete but changed formula must also be rejected.
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(service,"candidateMatchesRequest",candidate,request,payload)).isFalse();
        candidate.set("formula",formula.deepCopy());
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(service,"candidateMatchesRequest",candidate,request,payload)).isTrue();
        request.withObject("fixedInputs").put("formula:"+a,40);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"validateFormulaRequest",request,List.of(b))).hasMessageContaining("材料比例请在配方材料中设置");
    }




}
