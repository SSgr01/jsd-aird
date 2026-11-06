package com.jsd.aird.mdm.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.mdm.domain.model.CustomerRequirement;
import com.jsd.aird.mdm.domain.model.ProjectTask;
import com.jsd.aird.mdm.domain.model.ProjectPriority;
import com.jsd.aird.mdm.application.query.ProjectTaskSummary;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentProjectRecordsTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ProjectService projects = mock(ProjectService.class);
    final CustomerRequirementService requirements = mock(CustomerRequirementService.class);
    final MaterialService materials = mock(MaterialService.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final AuthorizationService authorization = mock(AuthorizationService.class, CALLS_REAL_METHODS);
    final ProjectWorkService work = mock(ProjectWorkService.class);
    final AgentProjectService service = new AgentProjectService(projects, mock(ProjectStageService.class),
        work, authorization, jdbc, json, requirements, materials);
    final UUID project = UUID.randomUUID(), requirement = UUID.randomUUID();

    @BeforeEach void setup() {
        ActorContext.set(new Actor(UUID.randomUUID(), UUID.randomUUID(), "synthetic"));
        when(authorization.check(any(PermissionCheck.class))).thenReturn(new PermissionDecision(true, "project.view", "ALLOW", "ALL", "test"));
        when(jdbc.queryForList(contains("mdm.customer_requirement"), eq(UUID.class), eq(project), eq(project.toString())))
            .thenReturn(List.of(requirement));
        when(requirements.requirement(requirement)).thenReturn(record(7));
    }
    @AfterEach void clear() { ActorContext.clear(); }

    CustomerRequirement record(long version) {
        return new CustomerRequirement(requirement, "TEST-REQ", null, "隔离验收需求", "25 °C 下测试；目标值保留单位", "HIGH",
            null, null, CustomerRequirement.RequirementStatus.CONFIRMED, null, project, List.of(project),
            json.createObjectNode(), version, Instant.EPOCH, Instant.EPOCH);
    }

    @Test void readsOnlyExplicitLinksAndRevalidatesVersionAndRelationship() throws Exception {
        var args = json.createObjectNode().put("project_id", project.toString()).put("record_type", "REQUIREMENT");
        var result = service.read("get_project_records", args);
        assertThat(result.path("relation").asText()).isEqualTo("EXPLICIT_PROJECT_LINK");
        var candidate = result.path("candidates").get(0);
        var source = candidate.path("source");
        assertThat(candidate.path("text").asText()).contains("25 °C", "目标值");
        assertThat(source.path("version_id").asText()).isEqualTo("7");
        assertThat(source.path("project_id").asText()).isEqualTo(project.toString());
        service.validateSource(source, "25 °C");
        var transported = json.readTree(source.toString());
        service.validateSource(transported, "25 °C");
        ((com.fasterxml.jackson.databind.node.ObjectNode) transported.path("anchor")).put("recordVersion", 8);
        assertThatThrownBy(() -> service.validateSource(transported, null)).isInstanceOf(ApiException.class);
        when(requirements.requirement(requirement)).thenReturn(record(8));
        assertThatThrownBy(() -> service.validateSource(source, null)).isInstanceOf(ApiException.class);
        when(jdbc.queryForList(contains("mdm.customer_requirement"), eq(UUID.class), eq(project), eq(project.toString())))
            .thenReturn(List.of());
        assertThatThrownBy(() -> service.authorizeSource(source)).isInstanceOf(ApiException.class);
    }

    @Test void unlinkedIdAndRevokedProjectNeverLoadRecordContents() {
        var other = UUID.randomUUID();
        var args = json.createObjectNode().put("project_id", project.toString()).put("record_type", "REQUIREMENT").put("record_id", other.toString());
        assertThatThrownBy(() -> service.read("get_project_records", args)).isInstanceOf(ApiException.class);
        verifyNoInteractions(requirements, materials);
        when(authorization.check(any(PermissionCheck.class))).thenReturn(PermissionDecision.denied("project.view"));
        clearInvocations(jdbc);
        assertThatThrownBy(() -> service.read("get_project_records", args)).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc, requirements, materials);
    }

    @Test void taskSearchEvidenceSurvivesDetailReadButRejectsActualTaskChanges() throws Exception {
        var id = UUID.randomUUID(); var stage = UUID.randomUUID();
        var task = new ProjectTask(id, "TASK-TEST", project, stage, "同条件复测", "验收", ProjectPriority.MEDIUM,
            null, "PENDING", 0, 1, Instant.EPOCH, Instant.EPOCH);
        when(work.searchTasks(any())).thenReturn(List.of(new ProjectTaskSummary(id, "TASK-TEST", project,
            "关联项目名称", stage, "关联阶段名称", task.name(), task.owner(), "MEDIUM", null, "PENDING",
            0, 1, Instant.EPOCH, Instant.EPOCH)));
        when(work.getTask(id)).thenReturn(task);
        var args = json.createObjectNode().put("project_id", project.toString());
        var listed = service.read("list_project_tasks", args).path("candidates").get(0);
        var detailed = service.read("get_task_detail", args.deepCopy().put("task_id", id.toString())).path("candidates").get(0);
        assertThat(listed).isEqualTo(detailed);
        var source = json.readTree(listed.path("source").toString());
        service.validateSource(source, "同条件复测");
        when(work.getTask(id)).thenReturn(new ProjectTask(id, "TASK-TEST", project, stage, task.name(),
            task.owner(), task.priority(), null, "PENDING", 1, 1, Instant.EPOCH, Instant.EPOCH));
        assertThatCode(() -> service.authorizeSource(source)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateSource(source, null)).isInstanceOf(ApiException.class);
        when(work.getTask(id)).thenReturn(new ProjectTask(id, "TASK-TEST", project, stage, "已变更",
            task.owner(), task.priority(), null, "PENDING", 0, 2, Instant.EPOCH, Instant.EPOCH));
        assertThatThrownBy(() -> service.authorizeSource(source)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.validateSource(source, null)).isInstanceOf(ApiException.class);
    }
}
