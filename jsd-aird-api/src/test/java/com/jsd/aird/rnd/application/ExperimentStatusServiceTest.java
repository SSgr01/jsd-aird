package com.jsd.aird.rnd.application;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.DataScopeResolver.ResolvedDataScope;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class ExperimentStatusServiceTest {
    final UUID org=UUID.randomUUID(), user=UUID.randomUUID();
    final ExperimentRepository repository=mock(ExperimentRepository.class);
    final AuthorizationService authorization=mock(AuthorizationService.class);
    final ExperimentStatusService service=new ExperimentStatusService(repository,authorization);
    @BeforeEach void actor() {ActorContext.set(new Actor(org,user,"ordinary"));}
    @AfterEach void clear() {ActorContext.clear();}

    @Test void deniedViewDoesNotReadAnyExperiment() {
        when(authorization.resolveScope(any())).thenReturn(ResolvedDataScope.deny("SELF","DENIED"));
        assertThat(service.current(List.of(UUID.randomUUID()))).isEmpty();
        verifyNoInteractions(repository);
    }
    @Test void usesOrganizationAndViewScopeInBatchesWithoutLoadingWorkbooks() {
        var target=UUID.randomUUID();
        when(authorization.resolveScope(any())).thenReturn(ResolvedDataScope.allow("CUSTOM",Set.of(target)));
        var ids=new ArrayList<UUID>();for(int i=0;i<501;i++)ids.add(UUID.randomUUID());
        ids.add(ids.getFirst());ids.add(null);
        when(repository.statuses(eq(org),any(),any())).thenReturn(List.of());
        assertThat(service.current(ids)).isEmpty();
        var batches=ArgumentCaptor.forClass(Collection.class);
        verify(repository,times(2)).statuses(eq(org),batches.capture(),eq(new ExperimentRepository.DataScopeFilter("CUSTOM",user,Set.of(target))));
        assertThat(batches.getAllValues().stream().map(Collection::size).toList()).containsExactly(500,1);
        verifyNoMoreInteractions(repository);
    }
    @Test void failedReadDoesNotInventAnUnpublishedStatus() {
        when(authorization.resolveScope(any())).thenReturn(ResolvedDataScope.allow("ALL"));
        when(repository.statuses(eq(org),any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThat(service.current(List.of(UUID.randomUUID()))).isEmpty();
    }
}
