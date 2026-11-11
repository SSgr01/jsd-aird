package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.platform.web.IsolatedPostgres;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises real project SQL in disposable schemas; stage/task ownership lives on the project. */
@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST", matches="true")
class AgentExperimentHierarchyPostgresTest {
    static IsolatedPostgres db;
    final UUID org=UUID.randomUUID(), otherOrg=UUID.randomUUID(), project=UUID.randomUUID(),
        otherProject=UUID.randomUUID(), stage=UUID.randomUUID(), otherStage=UUID.randomUUID(), task=UUID.randomUUID();
    AgentExperimentService service;

    @BeforeAll static void database() {
        db=new IsolatedPostgres(Map.of("mdm.project","project", "mdm.project_stage","project_stage", "mdm.project_task","project_task",
            "rnd.experiment_audit","experiment_audit", "ops.audit_log","audit_log"));
        db.table("project","id uuid PRIMARY KEY, organization_id uuid NOT NULL, deleted boolean NOT NULL DEFAULT false");
        db.table("project_stage","id uuid PRIMARY KEY, project_id uuid NOT NULL, deleted boolean NOT NULL DEFAULT false");
        db.table("project_task","id uuid PRIMARY KEY, project_id uuid NOT NULL, stage_id uuid NOT NULL, deleted boolean NOT NULL DEFAULT false");
        db.table("experiment_audit","organization_id uuid,operator_id uuid,action text,after_jsonb jsonb");
        db.table("audit_log","id uuid PRIMARY KEY,organization_id uuid,actor_id uuid,action text,aggregate_type text,aggregate_id uuid,detail_jsonb jsonb");
    }
    @AfterAll static void close() { if(db!=null)db.close(); }
    @BeforeEach void setup() {
        db.clear();
        ActorContext.set(new Actor(org,UUID.randomUUID(),"isolated-acceptance"));
        db.jdbc.update("INSERT INTO mdm.project(id,organization_id) VALUES(?,?),(?,?)", project,org,otherProject,otherOrg);
        db.jdbc.update("INSERT INTO mdm.project_stage(id,project_id) VALUES(?,?),(?,?)", stage,project,otherStage,otherProject);
        db.jdbc.update("INSERT INTO mdm.project_task(id,project_id,stage_id) VALUES(?,?,?)", task,project,stage);
        var json=new ObjectMapper();
        service=new AgentExperimentService(mock(ExperimentRepository.class),mock(ExperimentDraftFacade.class),
            mock(AuthorizationService.class),db.jdbc,json,new JsonCanonicalizer(json),mock(TemplateDataImportFacade.class));
    }
    @AfterEach void actor() { ActorContext.clear(); }

    @Test void acceptsOwnedHierarchyAndUnassociatedExperiments() {
        assertThatCode(()->service.hierarchy(project,stage,task)).doesNotThrowAnyException();
        assertThatCode(()->service.hierarchy(project,null,task)).doesNotThrowAnyException();
        assertThatCode(()->service.hierarchy(null,null,null)).doesNotThrowAnyException();
    }
    @Test void rejectsOtherOrganizationsAndUnrelatedOrDeletedRecords() {
        assertThatThrownBy(()->service.hierarchy(otherProject,otherStage,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.hierarchy(project,otherStage,task)).isInstanceOf(ApiException.class);
        db.jdbc.update("UPDATE mdm.project_task SET deleted=true WHERE id=?",task);
        assertThatThrownBy(()->service.hierarchy(project,stage,task)).isInstanceOf(ApiException.class);
        db.jdbc.update("UPDATE mdm.project_task SET deleted=false WHERE id=?",task);
        db.jdbc.update("UPDATE mdm.project_stage SET deleted=true WHERE id=?",stage);
        assertThatThrownBy(()->service.hierarchy(project,null,task)).isInstanceOf(ApiException.class);
    }
    @Test void taskMustBelongToItsStageAndProject() {
        var another=UUID.randomUUID();
        db.jdbc.update("INSERT INTO mdm.project_stage(id,project_id) VALUES(?,?)",another,project);
        assertThatThrownBy(()->service.hierarchy(project,another,task)).isInstanceOf(ApiException.class);
        db.jdbc.update("UPDATE mdm.project_task SET stage_id=? WHERE id=?",otherStage,task);
        assertThatThrownBy(()->service.hierarchy(project,null,task)).isInstanceOf(ApiException.class);
    }
    @Test void reconciliationClosesAnUncommittedWriteAndFencesDelayedRequests() {
        var key="a".repeat(64);
        var args=new ObjectMapper().createObjectNode().put("operation_key",key).put("title","isolated acceptance")
            .put("category_id",UUID.randomUUID().toString()).put("planned_date","2026-11-06")
            .put("template_version_id",UUID.randomUUID().toString());
        args.putObject("edit_model").put("plan","synthetic acceptance plan");
        assertThat(service.receipt(key,args).path("status").asText()).isEqualTo("UNKNOWN");
        var closed=db.tx.execute(status->service.reconcileReceipt(key,args));
        assertThat(closed.path("status").asText()).isEqualTo("FAILED");
        assertThat(closed.path("result").path("error").asText()).isEqualTo("EXPERIMENT_DRAFT_NOT_COMMITTED");
        assertThat(service.receipt(key).path("status").asText()).isEqualTo("FAILED");
        assertThatThrownBy(()->db.tx.execute(status->service.createDraft(args))).isInstanceOf(ApiException.class)
            .hasMessageContaining("不能重放");
        assertThatThrownBy(()->db.tx.execute(status->service.createDraft(args.deepCopy().put("title","different"))))
            .isInstanceOf(ApiException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM ops.audit_log",Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM rnd.experiment_audit",Integer.class)).isZero();
    }
}
