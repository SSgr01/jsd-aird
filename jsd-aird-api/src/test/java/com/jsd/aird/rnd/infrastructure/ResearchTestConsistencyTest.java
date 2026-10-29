package com.jsd.aird.rnd.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.rnd.domain.ResearchTestModels.*;
import com.jsd.aird.shared.error.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResearchTestConsistencyTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final JdbcResearchTestRepository repository=new JdbcResearchTestRepository(jdbc,new ObjectMapper());
    private final Summary summary=mock(Summary.class);
    private final UUID org=UUID.randomUUID(), id=UUID.randomUUID(), actor=UUID.randomUUID();
    @BeforeEach @SuppressWarnings({"rawtypes","unchecked"}) void setup(){
        when(summary.lockVersion()).thenReturn(12L);when(summary.status()).thenReturn(Status.PENDING_REVIEW);when(summary.recordType()).thenReturn(Type.REPORT);
        var detail=new Detail(summary,UUID.randomUUID(),null,null,null,null,null,null,null,List.of());
        doReturn(List.of(detail)).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
    }
    @Test void staleApprovalCannotCreatePublicationOrAuditRows(){
        assertConflict(()->repository.transition(org,id,11,Status.PUBLISHED,null,actor,"reviewer"),ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void currentVersionWithInvalidStatusCannotBeApproved(){
        when(summary.status()).thenReturn(Status.DRAFT);
        assertConflict(()->repository.transition(org,id,12,Status.PUBLISHED,null,actor,"reviewer"),ApiErrorCode.RESOURCE_CONFLICT);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void publicationCannotBeEditedThroughTheRenameEndpoint(){
        when(summary.status()).thenReturn(Status.PUBLISHED);
        assertConflict(()->repository.rename(org,id,"rename","R-1","owner",null,null,null,null,null,null,null,null,12,actor,"editor"),ApiErrorCode.RESOURCE_CONFLICT);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,ApiErrorCode code){
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.errorCode()).isEqualTo(code));
    }
}
