package com.jsd.aird.core.application;

import com.jsd.aird.core.api.ProjectResourceFacade.*;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.Actor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProjectRelationConsistencyTest {
    @Test void anotherEditorsNewLinkCannotBeDeletedByAnOldRelationDialog() {
        var jdbc=mock(JdbcTemplate.class);var authorization=mock(AuthorizationService.class);
        var service=new ProjectResourceService(jdbc,authorization);
        var org=UUID.randomUUID();var id=UUID.randomUUID();var actor=new Actor(org,UUID.randomUUID(),"test");
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<ProjectRelationTarget>>any(),
                eq(org),eq("KNOWLEDGE_DOCUMENT"),eq(id)))
                .thenReturn(List.of(new ProjectRelationTarget(UUID.randomUUID(),null,null)));
        assertThatThrownBy(()->service.replaceLinksChecked(actor,ResourceType.KNOWLEDGE_DOCUMENT,id,List.of(),List.of()))
                .isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(authorization);
    }
}
