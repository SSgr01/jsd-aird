package com.jsd.aird.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcDataScopeResolverTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcDataScopeResolver resolver = new JdbcDataScopeResolver(jdbc);
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();

    @Test
    void allScopeAllowsWithoutResourceLookup() {
        var result = resolver.resolve(check("TEMPLATE"), new Binding("template.view", "ALLOW", "ALL", List.of()));

        assertThat(result.allowed()).isTrue();
        assertThat(result.scopeType()).isEqualTo("ALL");
    }

    @Test
    void selectedScopeDeniesListsAndAllowsOnlySelectedObjects() {
        var binding = new Binding("template.view", "ALLOW", "SELECTED", List.of(resourceId));

        var listScope = resolver.resolve(check("TEMPLATE", null), binding);
        assertThat(listScope.allowed()).isTrue();
        assertThat(listScope.targetIds()).containsExactly(resourceId);
        assertThat(resolver.resolve(check("TEMPLATE"), binding).allowed()).isTrue();
        assertThat(resolver.resolve(check("TEMPLATE", UUID.randomUUID()), binding).allowed()).isFalse();
    }

    @Test
    void selfScopeUsesDatabaseOwnershipAndDoesNotDefaultToAll() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        var result = resolver.resolve(check("KNOWLEDGE"), new Binding("knowledge.manage", "ALLOW", "SELF", List.of()));

        assertThat(result.allowed()).isTrue();
        assertThat(result.scopeType()).isEqualTo("SELF");
    }

    @Test
    void projectAndCategoryEmptyTargetsAreDenied() {
        assertThat(resolver.resolve(check("EXPERIMENT"), new Binding("experiment.manage", "ALLOW", "PROJECT", List.of()))
                .allowed()).isFalse();
        assertThat(resolver.resolve(check("KNOWLEDGE"), new Binding("knowledge.manage", "ALLOW", "CATEGORY", List.of()))
                .allowed()).isFalse();
    }

    private PermissionCheck check(String resourceType) {
        return check(resourceType, resourceId);
    }

    private PermissionCheck check(String resourceType, UUID id) {
        return new PermissionCheck(organizationId, userId, "test.permission", resourceType, id, "READ");
    }
}
