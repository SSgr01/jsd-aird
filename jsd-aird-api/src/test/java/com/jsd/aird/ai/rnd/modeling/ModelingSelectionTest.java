package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.rnd.optimization.*;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.rnd.api.ResearchExperimentFacade;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.shared.error.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelingSelectionTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UUID org=UUID.randomUUID(),dataset=UUID.randomUUID(),experiment=UUID.randomUUID(),version=UUID.randomUUID();
    private final OptimizationRepository repository=mock(OptimizationRepository.class);
    private final AuthorizationService authorization=mock(AuthorizationService.class);
    private final ResearchExperimentFacade access=mock(ResearchExperimentFacade.class);
    private final OptimizationService service=new OptimizationService(repository,null,null,null,null,authorization,null,json,false,access);
    @BeforeEach void setup(){ActorContext.set(new Actor(org,UUID.randomUUID(),"test"));}
    @AfterEach void clear(){ActorContext.clear();}

    @Test void onlyProjectsExplicitExperimentsAndRetainsTheirEvidence() {
        var model=json.createObjectNode();
        model.putArray("testResults").addObject().put("fieldCode","viscosity").put("value",1220).put("parseStatus","EXACT").putArray("sourceRefs").addObject().put("address","C46");
        when(repository.selectedHistoryRecords(org,dataset,List.of(experiment))).thenReturn(List.of(new OptimizationRepository.HistoryRecordRow(experiment,version,"B-3616","PU773",model,Instant.now())));
        when(repository.historyDatasetName(org,dataset)).thenReturn(Optional.of("合成实验"));
        when(repository.historyFieldNames(org)).thenReturn(Map.of());
        when(repository.materialCatalog(org)).thenReturn(MaterialCatalogSnapshot.empty());
        var result=service.selectedModelingDataset(dataset,List.of(experiment),false);
        assertThat(result.path("sourceDocumentCount").asInt()).isEqualTo(1);
        assertThat(result.path("rows").get(0).path("sourceExperimentId").asText()).isEqualTo(experiment.toString());
        var order=inOrder(access,repository);
        order.verify(access).requireReadableVersion(org,experiment,null);
        order.verify(repository).selectedHistoryRecords(org,dataset,List.of(experiment));
        verify(repository,never()).historyRecords(any(),any());
        verify(repository,never()).historyDatasets(any(),any());
    }
    @Test void rejectsForgedOrUnauthorizedIdsBeforeReadingPayload() {
        doThrow(new ApiException(ApiErrorCode.PERMISSION_DENIED)).when(access).requireReadableVersion(org,experiment,null);
        assertThatThrownBy(()->service.selectedModelingDataset(dataset,List.of(experiment),false)).isInstanceOf(ApiException.class);
        verifyNoInteractions(repository);
    }
    @Test void rejectsDuplicateEmptyAndCrossDatasetSelections() {
        assertThatThrownBy(()->service.selectedModelingDataset(dataset,List.of(),false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.selectedModelingDataset(dataset,List.of(experiment,experiment),false)).isInstanceOf(ApiException.class);
        when(repository.selectedHistoryRecords(org,dataset,List.of(experiment))).thenReturn(List.of());
        assertThatThrownBy(()->service.selectedModelingDataset(dataset,List.of(experiment),false)).hasMessageContaining("已变化");
    }
    @Test void rejectsUnboundedPagesBeforeQuerying() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var selector=new ModelingSelectionService(jdbc,authorization,json);
        assertThatThrownBy(()->selector.experiments(dataset,null,null,0,20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->selector.experiments(dataset,null,null,1,5000)).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void metadataCannotBypassTheExistingDataScope() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var selector=new ModelingSelectionService(jdbc,authorization,json);
        when(authorization.resolveScope(any())).thenReturn(DataScopeResolver.ResolvedDataScope.deny("SELF","DENIED"));
        assertThatThrownBy(()->selector.batches(dataset)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->selector.experiments(dataset,null,null,1,20)).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc);
    }
}
