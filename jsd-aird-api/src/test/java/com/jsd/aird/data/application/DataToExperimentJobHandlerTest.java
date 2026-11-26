package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataToExperimentJobHandlerTest {
    final ObjectMapper json=new ObjectMapper();
    final ExperimentAssemblyService service=mock(ExperimentAssemblyService.class);
    final DataToExperimentJobHandler handler=new DataToExperimentJobHandler(service,json);
    @AfterEach void clear(){ActorContext.clear();}

    @Test void preparationRunsAsTheSubmittingUserAndRestoresWorkerContext() {
        var org=UUID.randomUUID();var user=UUID.randomUUID();var job=UUID.randomUUID();
        var previous=new Actor(UUID.randomUUID(),UUID.randomUUID(),"previous");
        ActorContext.set(previous);
        var payload=json.createObjectNode().put("phase","PREPARE").put("organizationId",org.toString())
                .put("actorId",user.toString()).put("actorName","submitter").put("importJobId",job.toString()).put("reviewedRevision","revision");
        when(service.prepareConfirmed(job,"revision",null)).thenAnswer(call->{
            assertThat(ActorContext.required().organizationId()).isEqualTo(org);
            assertThat(ActorContext.required().userId()).isEqualTo(user);
            return new ExperimentAssemblyService.SyncResult(1,List.of(UUID.randomUUID()));
        });
        assertThat(handler.handle(payload).path("acceptedCount").asInt()).isEqualTo(1);
        assertThat(ActorContext.current()).isSameAs(previous);
        handler.handleTerminalFailure(payload,new IllegalStateException("unavailable"));
        verify(service,never()).fail(any(),any(),any(),any(),any(),any());
    }
}
