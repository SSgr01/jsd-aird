package com.jsd.aird.quality.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QualityConsistencyTest {
    private final UUID org=UUID.randomUUID(),id=UUID.randomUUID(),actor=UUID.randomUUID();
    private final QualityDataStore repository=mock(QualityDataStore.class);
    private final ObjectMapper json=new ObjectMapper();
    private final AuthorizationService authorization=mock(AuthorizationService.class);
    private final QualityDataService service=new QualityDataService(repository,json,null,null,null,null,mock(AuditLogFacade.class),authorization);
    private final QualityDataStore.RecordView current=mock(QualityDataStore.RecordView.class);
    @BeforeEach void setup(){ActorContext.set(new Actor(org,actor,"editor"));when(current.lockVersion()).thenReturn(13L);when(current.data()).thenReturn(json.createObjectNode());when(current.businessType()).thenReturn("coa");when(repository.record(org,"USER",id)).thenReturn(current);when(repository.record(eq(org),anyString(),eq(id))).thenReturn(current);}
    @AfterEach void cleanup(){ActorContext.clear();}
    @Test void staleDeletionAndPublicationDoNotWrite(){
        conflict(()->service.deleteRecord(id,12));conflict(()->service.publishRecord(id,12));
        verify(repository,never()).softDeleteOne(any(),any(),anyLong());verify(repository,never()).publish(any(),any(),any(),anyLong());
    }
    @Test void finalizedRecordCannotBeDeletedEvenWithMatchingRevision(){
        when(current.data()).thenReturn(json.createObjectNode().put("status","已签发"));
        assertThatThrownBy(()->service.deleteRecord(id,13)).isInstanceOf(ApiException.class).hasMessageContaining("正式质量");
        verify(repository,never()).softDeleteOne(any(),any(),anyLong());
    }
    @Test void duplicateBatchTargetsAreRejectedBeforeWrites(){
        var item=new QualityDataService.RecordVersion(id,13L);
        assertThatThrownBy(()->service.deleteBatch(List.of(item,item))).isInstanceOf(ApiException.class);
        verify(repository,never()).softDeleteOne(any(),any(),anyLong());
    }
    @Test void anyStaleBatchTargetStopsTheWholeDelete(){
        var other=UUID.randomUUID();var second=mock(QualityDataStore.RecordView.class);when(second.lockVersion()).thenReturn(4L);when(repository.record(eq(org),anyString(),eq(other))).thenReturn(second);
        conflict(()->service.deleteBatch(List.of(new QualityDataService.RecordVersion(id,13L),new QualityDataService.RecordVersion(other,3L))));
        verify(repository,never()).softDeleteOne(any(),any(),anyLong());
    }
    private void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));}
}
