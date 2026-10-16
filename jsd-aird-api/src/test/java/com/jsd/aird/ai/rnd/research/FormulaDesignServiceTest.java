package com.jsd.aird.ai.rnd.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.SyntheticTestAuthorization;
import com.jsd.aird.ai.rnd.formula.FormulaNormalizationService;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.prediction.PredictionRepository;
import com.jsd.aird.ai.rnd.prediction.PredictionService;
import com.jsd.aird.ai.rnd.training.FormulaModelV2Client;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FormulaDesignServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final FormulaDesignRepository repository=mock(FormulaDesignRepository.class);
    private final PredictionRepository predictions=mock(PredictionRepository.class);
    private final PredictionService predictionContext=mock(PredictionService.class);
    private final FormulaModelArtifactStore artifacts=mock(FormulaModelArtifactStore.class);
    private final FormulaModelV2Client compute=mock(FormulaModelV2Client.class);
    private final ConfigurationHashing hashing=new ConfigurationHashing(json);
    private final FormulaNormalizationService normalization=new FormulaNormalizationService(json);
    private final FormulaDesignService service=new FormulaDesignService(repository,predictions,predictionContext,artifacts,compute,hashing,
            mock(OpsAsyncFacade.class),mock(AuditLogFacade.class),json,normalization,new SyntheticTestAuthorization("production",false),false);

    @AfterEach void clearActor(){ActorContext.clear();}
    private JsonNode node(String text)throws Exception{return json.readTree(text);}
    private PredictionRepository.TargetBinding binding(UUID id,boolean composition)throws Exception{
        var b=mock(PredictionRepository.TargetBinding.class);
        when(b.targetId()).thenReturn(id);when(b.modelId()).thenReturn(UUID.randomUUID());when(b.targetVersionId()).thenReturn(UUID.randomUUID());
        when(b.targetName()).thenReturn("涂层光泽");when(b.valueType()).thenReturn("CONTINUOUS");when(b.unit()).thenReturn("GU");
        when(b.classes()).thenReturn(json.createArrayNode());when(b.definition()).thenReturn(json.createObjectNode());
        when(b.qualityPolicy()).thenReturn(json.createObjectNode());when(b.observationSemantics()).thenReturn(json.createObjectNode());
        when(b.frozenInputScheme()).thenReturn(node(composition?"{\"id\":\"scheme\",\"fields\":[{\"code\":\"FORMULA\",\"valueType\":\"COMPOSITION\",\"required\":true}]}":"{\"fields\":[{\"code\":\"temperature\",\"valueType\":\"NUMBER\"}]}"));
        when(b.frozenDictionary()).thenReturn(node("{\"materials\":[{\"materialId\":\"resin\",\"name\":\"丙烯酸树脂\",\"encoderIndex\":0}]}"));
        when(b.modelDomain()).thenReturn(node(composition?"{\"features\":[\"material:resin\"],\"minimum\":[60],\"maximum\":[80]}":"{\"features\":[\"input:temperature\"],\"minimum\":[20],\"maximum\":[30]}"));
        when(predictionContext.gateAvailability(b)).thenReturn(List.of());return b;
    }
    private FormulaDesignRepository.Row row(UUID id,JsonNode request,JsonNode bindings,String hash){
        return new FormulaDesignRepository.Row(id,"FORMULA_PREDICTION","RUNNING",null,35,"SEARCHING","request",hash,request,bindings,json.createObjectNode(),null,ActorContext.developmentDefault().userId(),UUID.randomUUID(),1,json.createObjectNode(),Instant.now());
    }

    @Test void excludesModelsThatDidNotLearnMaterialProportions()throws Exception{
        ActorContext.set(ActorContext.developmentDefault());var id=UUID.randomUUID();var b=binding(id,false);
        var base=json.createObjectNode();base.putArray("targets").addObject().put("targetId",id.toString()).put("name","涂层光泽").put("predictionAvailable",true);
        base.putArray("selected");base.putArray("requiredInputs");base.putArray("materials");base.putArray("configurationConflicts");
        when(predictionContext.context(anyList())).thenReturn(base);when(predictions.targetBindings(any(),anyList())).thenReturn(List.of(b));
        var context=service.context(List.of());
        assertThat(context.path("targets").get(0).path("available").asBoolean()).isFalse();
        assertThat(context.path("targets").get(0).path("unavailableReasons").toString()).contains("MODEL_NOT_FORMULA_ENABLED");
    }
    @Test void intersectsCategoriesAndRejectsContradictoryMultiTargetInputDefinitions()throws Exception{
        ActorContext.set(ActorContext.developmentDefault());var first=binding(UUID.randomUUID(),true);var second=binding(UUID.randomUUID(),true);
        when(first.frozenInputScheme()).thenReturn(node("{\"fields\":[{\"code\":\"SUBSTRATE\",\"valueType\":\"CATEGORY\",\"required\":false,\"encoding\":{\"allowedValues\":[\"PET\"]}}]}"));
        when(second.frozenInputScheme()).thenReturn(node("{\"fields\":[{\"code\":\"SUBSTRATE\",\"valueType\":\"CATEGORY\",\"required\":true,\"encoding\":{\"allowedValues\":[\"PC\"]}}]}"));
        var base=json.createObjectNode();base.putArray("targets");var selected=base.putArray("selected");
        for(var b:List.of(first,second)){var target=json.createObjectNode().put("targetId",b.targetId().toString()).put("name",b.targetName());selected.add(target);base.withArray("targets").add(target.deepCopy());}
        base.set("requiredInputs",node("[{\"code\":\"SUBSTRATE\",\"valueType\":\"CATEGORY\",\"required\":false,\"allowedValues\":[\"PET\"]}]"));base.putArray("configurationConflicts");
        when(predictionContext.context(anyList())).thenReturn(base);when(predictions.targetBindings(any(),anyList())).thenReturn(List.of(first,second));
        var context=service.context(List.of(first.targetId(),second.targetId()));
        assertThat(context.path("requiredInputs").get(0).path("required").asBoolean()).isTrue();
        assertThat(context.path("requiredInputs").get(0).path("allowedValues").isEmpty()).isTrue();
        assertThat(context.path("configurationConflicts").get(0).path("fieldCode").asText()).isEqualTo("SUBSTRATE");
        assertThat(context.path("materialIntersection").get(0).path("supportedRanges").size()).isEqualTo(2);
    }
    @Test void rejectsAModelWhoseLearnedMaterialsDoNotMatchItsFrozenDictionary()throws Exception{
        ActorContext.set(ActorContext.developmentDefault());var id=UUID.randomUUID();var b=binding(id,true);
        when(b.sourceDataNature()).thenReturn("SYNTHETIC");
        when(b.modelDomain()).thenReturn(node("{\"features\":[\"material:another-material\"],\"minimum\":[10],\"maximum\":[20]}"));
        var base=json.createObjectNode();base.putArray("targets").addObject().put("targetId",id.toString());
        base.putArray("selected");base.putArray("requiredInputs");base.putArray("configurationConflicts");
        when(predictionContext.context(anyList())).thenReturn(base);when(predictions.targetBindings(any(),anyList())).thenReturn(List.of(b));
        var target=service.context(List.of()).path("targets").get(0);
        assertThat(target.path("available").asBoolean()).isFalse();
        assertThat(target.path("unavailableReasons").toString()).contains("MODEL_MATERIALS_INCONSISTENT");
        assertThat(target.path("simulation").asBoolean()).isTrue();
    }
    @Test void preservesActualCandidateConditionsAndDistinguishesIdenticalRecipesWithDifferentConditions()throws Exception{
        var org=ActorContext.developmentDefault().organizationId();var run=UUID.randomUUID();var b=binding(UUID.randomUUID(),true);
        when(b.artifactKey()).thenReturn("model.zip");when(b.artifactSha()).thenReturn("0".repeat(64));
        when(artifacts.readRef(anyString(),anyString(),anyString())).thenReturn(new FormulaModelArtifactStore.ArtifactReadRef("model","http://localhost/model.zip","0".repeat(64)));
        when(predictions.targetBindings(any(),anyList())).thenReturn(List.of(b));
        when(predictions.jdbc()).thenReturn(mock(org.springframework.jdbc.core.JdbcTemplate.class));
        var request=json.createObjectNode();request.put("candidateCount",2);request.putArray("typedGoals").addObject().put("targetId",b.targetId().toString()).put("operator","MAXIMIZE").put("weight",1);
        request.set("baselineFormula",node("{\"basis\":\"MASS_PERCENT\",\"compositionComplete\":true,\"recordedTotal\":100,\"components\":[{\"materialId\":\"resin\",\"ratio\":100,\"unit\":\"%\",\"amountKnown\":true}]}"));request.putObject("fixedInputs");request.putObject("searchSpace");
        request.put("simulation",true);request.putObject("displayContext");
        var bindings=json.createObjectNode().put(b.targetId().toString(),b.modelId().toString());
        when(repository.any(org,run)).thenReturn(Optional.of(row(run,request,bindings,"hash")));when(repository.markRunning(org,run)).thenReturn(true);
        var response=json.createObjectNode().put("executionStatus","SUCCEEDED");var candidates=response.putArray("candidates");
        for(var temperature:List.of(25,30)){var candidate=candidates.addObject();candidate.set("formula",request.path("baselineFormula"));candidate.putObject("inputs").put("temperature",temperature);candidate.putArray("results");}
        when(compute.recommend(any())).thenReturn(response);service.execute(org,run);
        var payload=ArgumentCaptor.forClass(JsonNode.class);verify(compute).recommend(payload.capture());
        assertThat(payload.getValue().has("simulation")).isFalse();assertThat(payload.getValue().has("displayContext")).isFalse();
        var inputs=ArgumentCaptor.forClass(JsonNode.class);var hashes=ArgumentCaptor.forClass(String.class);
        verify(repository,times(2)).saveCandidate(eq(org),eq(run),anyInt(),any(),inputs.capture(),any(),any(),any(),any(),any(),anyDouble(),hashes.capture(),any(),any(),any(),anyDouble(),anyDouble(),anyDouble());
        assertThat(inputs.getAllValues().stream().map(value->value.path("temperature").asInt()).toList()).containsExactly(25,30);
        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
    }
    @Test void returnsTheExistingTaskForARetriedRequestBeforeRecheckingModelAvailability()throws Exception{
        ActorContext.set(ActorContext.developmentDefault());var actor=ActorContext.required();var id=UUID.randomUUID();
        var body=json.createObjectNode().put("targetTotal",100).put("formulaBasis","MASS_PERCENT");
        body.set("baselineFormula",node("{\"basis\":\"MASS_PERCENT\",\"compositionComplete\":true,\"recordedTotal\":100,\"components\":[{\"materialId\":\"resin\",\"ratio\":100,\"unit\":\"%\",\"amountKnown\":true}]}"));
        var normalized=body.deepCopy();normalized.set("baselineFormula",normalization.requireValid(body.path("baselineFormula")).formula());
        when(repository.byKey(actor.organizationId(),actor.userId(),"request-key")).thenReturn(Optional.of(row(id,normalized,json.createObjectNode(),hashing.hash(normalized))));
        assertThat(service.submit(body,"request-key").runId()).isEqualTo(id);
        verify(predictions,never()).targetBindings(any(),anyList());
    }
}
