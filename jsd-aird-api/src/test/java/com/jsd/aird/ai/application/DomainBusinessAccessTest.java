package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.shared.security.Actor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DomainBusinessAccessTest {
    final AuthorizationService auth=mock(AuthorizationService.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final DomainBusinessAccess policy=new DomainBusinessAccess(auth,jdbc);
    final Actor actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),"lin.chen","PROJECT_READER");
    final UUID record=UUID.randomUUID(),project=UUID.randomUUID();
    final com.fasterxml.jackson.databind.node.ObjectNode context=JsonNodeFactory.instance.objectNode();

    void grants(java.util.function.Predicate<PermissionCheck> predicate) {
        doAnswer(call->{PermissionCheck c=call.getArgument(0);return new PermissionDecision(predicate.test(c),c.permissionCode(),"ALLOW","SELECTED","DOMAIN");}).when(auth).check(any());
    }

    @ParameterizedTest
    @CsvSource({"QUALITY_RECORD,quality.view.view,QUALITY","QUALITY_COA,quality.view.view,QUALITY",
        "PRODUCTION_ORDER,production.view.view,PRODUCTION","PRODUCTION_UPLOAD,production.upload.view,PRODUCTION_UPLOAD",
        "DATA_IMPORT,data.view.view,DATA","SPECTRUM,spectrum.view.view,SPECTRUM","CUSTOMER,customer.list.view,CUSTOMER"})
    void storedRecordUsesItsPagePermission(String type,String code,String resource) {
        when(jdbc.queryForList(anyString(),eq(actor.organizationId()),eq(record))).thenReturn(List.of(Map.of("id",record)));
        grants(c->code.equals(c.permissionCode())&&resource.equals(c.resourceType())&&record.equals(c.resourceId()));
        assertThat(policy.allowed(actor,type,record,"READ",context)).isTrue();
        assertThat(policy.allowed(actor,type,record,"DOWNLOAD",context)).isFalse();
        assertThat(policy.allowed(actor,type,UUID.randomUUID(),"READ",context)).isFalse();
    }

    @Test void projectParentComesFromTheDatabaseRatherThanRequestContext() {
        UUID forged=UUID.randomUUID();context.put("project_id",forged.toString());
        when(jdbc.queryForList(contains("mdm.meeting_minutes"),eq(actor.organizationId()),eq(record)))
            .thenReturn(List.of(Map.of("project_id",project)));
        grants(c->forged.equals(c.resourceId()));
        assertThat(policy.allowed(actor,"MEETING_MINUTES",record,"READ",context)).isFalse();
        verify(auth).check(argThat(c->project.equals(c.resourceId())&&"project.meeting.view".equals(c.permissionCode())));
    }

    @Test void uploadedStandardUsesTheStoredKindAndUploadScope() {
        when(jdbc.queryForList(anyString(),eq(actor.organizationId()),eq(record))).thenReturn(List.of(Map.of("record_type","STANDARD")));
        grants(c->"research-test.standard.upload.download".equals(c.permissionCode())&&"RESEARCH_TEST_UPLOAD".equals(c.resourceType()));
        assertThat(policy.allowed(actor,"RESEARCH_TEST_UPLOAD",record,"DOWNLOAD",context.put("record_type","REPORT"))).isTrue();
        assertThat(policy.allowed(actor,"RESEARCH_TEST_UPLOAD",record,"READ",context)).isFalse();
    }

    @Test void referenceDownloadAlsoRequiresTheActualSourcePermission() {
        UUID source=UUID.randomUUID();
        when(jdbc.queryForList(contains("SELECT project_id"),eq(actor.organizationId()),eq(record)))
            .thenReturn(List.of(Map.of("project_id",project)));
        when(jdbc.queryForList(contains("SELECT resource_type,resource_id"),eq(actor.organizationId()),eq(record)))
            .thenReturn(List.of(Map.of("resource_type","DATA_IMPORT_JOB","resource_id",source)));
        when(jdbc.queryForList(contains("FROM data.import_job"),eq(actor.organizationId()),eq(source)))
            .thenReturn(List.of(Map.of("id",source)));
        grants(c->!"data.view.download".equals(c.permissionCode()));
        assertThat(policy.allowed(actor,"PROJECT_REFERENCE",record,"READ",context)).isTrue();
        assertThat(policy.allowed(actor,"PROJECT_REFERENCE",record,"DOWNLOAD",context)).isFalse();
        verify(auth).check(argThat(c->source.equals(c.resourceId())&&"data.view.download".equals(c.permissionCode())));
    }

    @Test void selectedActivityMetadataDoesNotGrantDeletedContentOrUnknownOwnership() {
        when(auth.resolveScope(any())).thenReturn(DataScopeResolver.ResolvedDataScope.allow("SELECTED",Set.of(project)));
        assertThat(policy.removedActivity(actor,"QUALITY_RECORD",record,project)).isTrue();
        assertThat(policy.removedActivity(actor,"QUALITY_RECORD",record,UUID.randomUUID())).isFalse();
        assertThat(policy.allowed(actor,"QUALITY_RECORD",record,"READ",context)).isFalse();
        when(auth.resolveScope(any())).thenReturn(DataScopeResolver.ResolvedDataScope.allow("SELF"));
        assertThat(policy.removedActivity(actor,"QUALITY_RECORD",record,project)).isFalse();
    }

    @Test void inventoryUsesStoredScopeRatherThanAnAllowedScopeSuppliedByTheCaller() {
        when(jdbc.queryForList(anyString(),eq(actor.organizationId()),eq(record))).thenReturn(List.of(Map.of("inventory_scope","PRODUCTION")));
        grants(c->c.permissionCode().startsWith("inventory.rnd."));
        assertThat(policy.allowed(actor,"INVENTORY_TRANSACTION",record,"READ",context.put("scope","RND"))).isFalse();
        verify(auth,never()).check(argThat(c->c.permissionCode().startsWith("inventory.rnd.")));
    }

    @Test void unrecognizedActionsNeverGrantAccess() {
        assertThat(policy.allowed(actor,"QUALITY_RECORD",record,"EXECUTE",context)).isFalse();
        assertThat(policy.allowed(actor,"UNREGISTERED",record,"READ",context)).isFalse();
        verifyNoInteractions(auth,jdbc);
    }

    @Test void inventoryTransferInheritsTheViewScopeOnlyAfterTransferWasAuthorized() {
        when(jdbc.queryForList(anyString(),eq(actor.organizationId()),eq(record)))
            .thenReturn(List.of(Map.of("inventory_scope","RND")));
        grants(c->true);
        when(auth.resolveScope(argThat(c->c!=null&&"inventory.sample.download".equals(c.permissionCode()))))
            .thenReturn(DataScopeResolver.ResolvedDataScope.deny("NONE","NO_BINDING"));
        when(auth.resolveScope(argThat(c->c!=null&&"inventory.sample.view".equals(c.permissionCode()))))
            .thenReturn(DataScopeResolver.ResolvedDataScope.allow("SELECTED",Set.of(record)));
        when(jdbc.queryForObject(contains("t.id IN (?)"),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        assertThat(policy.allowed(actor,"SAMPLE_DISPATCH",record,"DOWNLOAD",context)).isTrue();
        verify(jdbc).queryForObject(contains("t.id IN (?)"),eq(Boolean.class),eq(actor.organizationId()),eq(record),eq(record),eq(actor.userId()));

        grants(c->!"inventory.sample.download".equals(c.permissionCode()));
        clearInvocations(auth,jdbc);
        assertThat(policy.allowed(actor,"SAMPLE_DISPATCH",record,"DOWNLOAD",context)).isFalse();
        verify(auth,never()).resolveScope(any());
    }
}
