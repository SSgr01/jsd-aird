package com.jsd.aird.rnd.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.Detail;
import com.jsd.aird.rnd.domain.ExperimentModels.Summary;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcExperimentRepositoryTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcExperimentRepository repository = new JdbcExperimentRepository(jdbc, mapper);

    @Test
    void refusesToChangeASubmittedVersionInPlace() {
        givenDetail(detail(ExperimentStatus.PENDING_REVIEW));

        assertThatThrownBy(() -> repository.saveDraft(
                ORGANIZATION_ID, UUID.randomUUID(), 0,
                new ExperimentRepository.Draft(
                        "EXP-001", "实验", null, null, null, null, null, "owner", LocalDate.now(),
                        null, null, mapper.createObjectNode(), mapper.createObjectNode()
                ), USER_ID, "editor"
        ))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("待审核");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void refusesToChangeACompletedVersionInPlace() {
        givenDetail(detail(ExperimentStatus.COMPLETED));

        assertThatThrownBy(() -> repository.saveDraft(
                ORGANIZATION_ID, UUID.randomUUID(), 0,
                new ExperimentRepository.Draft(
                        "EXP-001", "实验", null, null, null, null, null, "owner", LocalDate.now(),
                        null, null, mapper.createObjectNode(), mapper.createObjectNode()
                ), USER_ID, "editor"
        ))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("已完成");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void refusesAnIllegalLifecycleTransitionBeforeUpdatingRows() {
        givenDetail(detail(ExperimentStatus.COMPLETED));

        assertThatThrownBy(() -> repository.transition(
                ORGANIZATION_ID, UUID.randomUUID(), 0, ExperimentStatus.IN_PROGRESS,
                null, USER_ID, "editor"
        ))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("IN_PROGRESS");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void recordsTheCreatorRatherThanTheExperimentOwnerInTheCreationAudit() {
        var detail = detail(ExperimentStatus.DRAFT);
        givenDetail(detail);
        var experimentId = detail.summary().id();
        var versionId = detail.currentVersionId();

        repository.create(new ExperimentRepository.Create(
                experimentId, ORGANIZATION_ID, "EXP-001", "实验", null, null,
                "MANUAL", ExperimentStatus.DRAFT, null, null, null, USER_ID, "experiment-owner",
                LocalDate.now(), versionId, null, null, mapper.createObjectNode(),
                mapper.createObjectNode(), USER_ID, "actual-creator"
        ));

        var sql = ArgumentCaptor.forClass(String.class);
        var arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), arguments.capture());
        var statements = sql.getAllValues();
        var auditIndex = -1;
        for (var index = 0; index < statements.size(); index++) {
            if (statements.get(index).contains("INSERT INTO rnd.experiment_audit")) {
                auditIndex = index;
                break;
            }
        }
        assertThat(auditIndex).isGreaterThanOrEqualTo(0);
        assertThat(arguments.getAllValues().get(auditIndex)[8]).isEqualTo("actual-creator");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void completedFactsQueryRequiresCurrentCompletedVersionAndAppliesProjectScope() {
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        var projectId = UUID.randomUUID();
        var query = new ExperimentRepository.CompletedFactsSearch(java.util.Set.of(), null, null, 1, 20,
                new ExperimentRepository.DataScopeFilter("PROJECT", USER_ID, java.util.Set.of(projectId)));

        repository.completedFacts(ORGANIZATION_ID, query);
        repository.countCompletedFacts(ORGANIZATION_ID, query);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("e.deleted=false", "e.status='COMPLETED'", "v.status='COMPLETED'",
                "e.project_id IN");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void givenDetail(Detail detail) {
        doReturn(List.of(detail)).when(jdbc)
                .query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private Detail detail(ExperimentStatus status) {
        var experimentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var model = mapper.createObjectNode();
        model.put("purpose", "验证性能");
        model.putObject("conclusion").put("mainConclusion", "达到目标").put("resultStatus", "SUCCESS");
        return new Detail(
                new Summary(experimentId, "EXP-001", "实验", null, null, "MANUAL", status,
                        null, null, null, null, null, null, "owner", LocalDate.now(), 1, 0, Instant.now()),
                versionId, null, null, mapper.createObjectNode(), model, List.of(), List.of()
        );
    }
}
