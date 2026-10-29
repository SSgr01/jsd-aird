package com.jsd.aird.mfg.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.mfg.application.ProductionOrderService;
import com.jsd.aird.mfg.application.RecordProjectionService;
import com.jsd.aird.mfg.infrastructure.JdbcProductionOrderRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductionConsistencyTest {
    private final UUID org=UUID.randomUUID(),id=UUID.randomUUID(),actor=UUID.randomUUID();
    private final ProductionOrderRepository repository=mock(ProductionOrderRepository.class);
    private final ObjectMapper json=new ObjectMapper();
    private final ProductionOrderService service=new ProductionOrderService(repository,new JsonCanonicalizer(json),json,mock(RecordProjectionService.class),mock(AuditLogFacade.class));
    @BeforeEach void setup(){
        ActorContext.set(new Actor(org,actor,"editor"));
        var workspace=mock(ProductionOrderRepository.ProductionWorkspace.class);
        when(workspace.lockVersion()).thenReturn(13L);when(workspace.status()).thenReturn("DRAFT");
        when(repository.findWorkspace(org,id)).thenReturn(Optional.of(workspace));
    }
    @AfterEach void cleanup(){ActorContext.clear();}
    @Test void staleSubmitCancelAndDeleteAreRejectedBeforeAnyMutation(){
        conflict(()->service.submit(id,12));conflict(()->service.cancel(id,12));conflict(()->service.delete(id,12));
        verify(repository,never()).submit(any());verify(repository,never()).cancel(any(),any(),anyLong(),any());
        verify(repository,never()).delete(any(),any(),anyLong());verify(repository,never()).appendOutbox(any(),any(),any(),any());
    }
    @Test void aCancelLostAtTheDatabaseIsAConflict(){
        when(repository.cancel(org,id,13,actor)).thenReturn(0);
        conflict(()->service.cancel(id,13));verify(repository,never()).appendOutbox(any(),any(),any(),any());
    }
    @Test void losingSubmitCasDoesNotAllocateAPublicationOrInsertASnapshot(){
        var jdbc=mock(JdbcTemplate.class);var actual=new JdbcProductionOrderRepository(jdbc,json);
        var command=mock(ProductionOrderRepository.SubmitRevision.class);
        conflict(()->actual.submit(command));
        verify(jdbc).update(contains("AND lock_version=?"),any(Object[].class));
        verify(jdbc,never()).queryForObject(anyString(),eq(Integer.class),any(Object[].class));
        verify(jdbc,never()).update(contains("INSERT INTO tpl.record_revision"),any(Object[].class));
    }
    private void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));}
}
