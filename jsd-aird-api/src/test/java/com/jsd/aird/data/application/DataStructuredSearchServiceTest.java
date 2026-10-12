package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.DataScopeResolver;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataStructuredSearchServiceTest {

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void searchesWithinAuthorizedDataScopeAndCanRestrictToUncategorizedFiles() {
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "tester");
        ActorContext.set(actor);
        var targetJob = UUID.randomUUID();
        var authorization = mock(AuthorizationService.class);
        when(authorization.resolveScope(any())).thenReturn(DataScopeResolver.ResolvedDataScope.allow(
                "SELECTED", Set.of(targetJob)));
        var search = mock(DataSourceFileSearchFacade.class);
        var service = new DataStructuredSearchService(search, authorization);

        service.search(new DataStructuredSearchService.SearchCommand(" ", List.of(" R-100 "),
                List.of(" 粘度 "), null, true));

        var categoryIds = ArgumentCaptor.forClass(List.class);
        var accessScope = ArgumentCaptor.forClass(DataSourceFileSearchFacade.RetrievalAccessScope.class);
        var query = ArgumentCaptor.forClass(DataSourceFileSearchFacade.DataDetailQuery.class);
        verify(search).queryDetails(eq(actor.organizationId()), query.capture(), categoryIds.capture(), accessScope.capture());
        assertThat(query.getValue().mode()).isEqualTo(DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP);
        assertThat(query.getValue().recordTerms()).containsExactly("R-100");
        assertThat(query.getValue().fieldTerms()).containsExactly("粘度");
        assertThat(categoryIds.getValue()).containsExactly(DataSourceFileSearchFacade.UNCATEGORIZED_SCOPE_ID);
        assertThat(accessScope.getValue().dataScope().type()).isEqualTo("SELECTED");
        assertThat(accessScope.getValue().dataScope().targetIds()).containsExactly(targetJob);
        assertThat(accessScope.getValue().experimentScope().deniesAll()).isTrue();
    }

    @Test
    void rejectsSearchWhenUserDoesNotHaveDataViewPermission() {
        ActorContext.set(new Actor(UUID.randomUUID(), UUID.randomUUID(), "tester"));
        var authorization = mock(AuthorizationService.class);
        when(authorization.resolveScope(any())).thenReturn(DataScopeResolver.ResolvedDataScope.deny("DENIED", "TEST"));
        var search = mock(DataSourceFileSearchFacade.class);

        assertThatThrownBy(() -> new DataStructuredSearchService(search, authorization).search(
                new DataStructuredSearchService.SearchCommand(null, List.of("R-1"), List.of(), null, false)))
                .isInstanceOf(ApiException.class);
        verify(search, org.mockito.Mockito.never()).queryDetails(any(UUID.class),
                any(DataSourceFileSearchFacade.DataDetailQuery.class), any(List.class),
                any(DataSourceFileSearchFacade.RetrievalAccessScope.class));
    }
}
