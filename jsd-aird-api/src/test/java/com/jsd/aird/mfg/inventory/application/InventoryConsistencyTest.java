package com.jsd.aird.mfg.inventory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.inventory.application.port.InventoryRepository;
import com.jsd.aird.mfg.inventory.domain.InventoryModels.*;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryConsistencyTest {
    private final UUID org=UUID.randomUUID(),user=UUID.randomUUID(),product=UUID.randomUUID(),id=UUID.randomUUID();
    private final InventoryRepository repository=mock(InventoryRepository.class);
    private final AuditLogFacade audit=mock(AuditLogFacade.class);
    private final InventoryService service=new InventoryService(repository,audit,new ObjectMapper(),mock(QualityDataStore.class));
    @BeforeEach void setup(){ActorContext.set(new Actor(org,user,"test"));}
    @AfterEach void cleanup(){ActorContext.clear();}
    private Balance balance(BigDecimal quantity,BigDecimal threshold){return new Balance(product,"P","Product",null,Scope.RND,quantity,null,threshold,AlertStatus.NORMAL,0,Instant.EPOCH);}

    @Test void backgroundStockMovementsDoNotInvalidatePolicyButChangedPolicyRejectsStaleEdit(){
        var original=balance(BigDecimal.TEN,BigDecimal.ONE);
        assertThat(balance(BigDecimal.ZERO,BigDecimal.ONE).getPolicyRevision()).isEqualTo(original.getPolicyRevision());
        when(repository.productExists(org,product,Scope.RND)).thenReturn(true);
        when(repository.balance(org,product,Scope.RND)).thenReturn(Optional.of(balance(BigDecimal.TEN,BigDecimal.TEN)));
        assertThatThrownBy(()->service.updatePolicy(product,Scope.RND,new InventoryService.PolicyInput(original.getPolicyRevision(),null,BigDecimal.ZERO)))
                .isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));
        verify(repository).lockBalance(org,product,Scope.RND,user);
        verify(repository,never()).upsertPolicy(any(),any(),any(),any(),any(),any());
        verifyNoInteractions(audit);
    }

    @Test void staleShipmentCannotOverwriteCurrentDetailsAndRevisionSerializesWithoutRecursion(){
        var current=new Shipment(id,"NEW",LocalDate.now(),product,"Product",Scope.RND,BigDecimal.ONE,null,null,"sender",null,"1",BigDecimal.ONE,BigDecimal.ONE,"recipient","123","address",null,null,"new note","PENDING",UUID.randomUUID(),"operator",Instant.EPOCH);
        when(repository.lockShipment(org,id)).thenReturn(Optional.of(current));
        var json=new ObjectMapper().findAndRegisterModules().valueToTree(current);
        assertThat(json.get("revision").asText()).isEqualTo(current.getRevision());
        assertThatThrownBy(()->service.updateShipment(id,new InventoryService.ShipmentUpdateInput("a".repeat(64),"OLD",LocalDate.now(),null,"old sender","old note",null,"recipient","123","address",null,null,null,null)))
                .isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));
        verify(repository).lockShipment(org,id);
        verify(repository,never()).updateShipment(any(),any(),any());
        verifyNoInteractions(audit);
    }
}
