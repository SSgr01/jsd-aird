package com.jsd.aird.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.ExperimentAssemblyRepository.PlanRow;
import com.jsd.aird.data.infrastructure.JdbcExperimentAssemblyRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST",matches="true")
class ExperimentAssemblyPostgresTest {
    static IsolatedPostgres db;
    static JdbcExperimentAssemblyRepository repository;
    final ObjectMapper json=new ObjectMapper();
    final UUID org=UUID.randomUUID(),job=UUID.randomUUID(),actor=UUID.randomUUID();
    final String key="FILE:record";
    @BeforeAll static void setup(){
        db=new IsolatedPostgres(Map.of("data.import_experiment_link","link"));
        db.table("link","""
                id uuid PRIMARY KEY,organization_id uuid,import_job_id uuid,recognition_job_id uuid,
                experiment_boundary_id text,assembly_key text,parent_assembly_key text,source_identity text,source_identity_type text,
                source_record_keys_jsonb jsonb,shared_context_record_keys_jsonb jsonb,plan_hash text,content_hash text,status text,
                conflicts_jsonb jsonb,warnings_jsonb jsonb,sample_boundary_ids_jsonb jsonb,logical_sample_keys_jsonb jsonb,
                source_group_keys_jsonb jsonb,created_by uuid,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),
                error_message text,resolution_action text,resolution_reason text,experiment_id uuid,experiment_version_id uuid,experiment_no text,
                UNIQUE(organization_id,import_job_id,assembly_key)
                """);
        repository=new JdbcExperimentAssemblyRepository(db.jdbc,new ObjectMapper());
    }
    @AfterAll static void cleanup(){if(db!=null)db.close();}
    @BeforeEach void clear(){db.clear();}
    void plan(String hash){repository.upsertPlan(org,job,new PlanRow(key,null,null,"FILE",json.createArrayNode(),
            json.createArrayNode(),hash,"content","READY",json.createArrayNode(),json.createArrayNode()),actor);}

    @Test void refreshKeepsFailedStatusAndItsReason(){
        plan("first");repository.markFailed(org,job,key,"first","原文件暂不可读取");
        plan("first");plan("first");
        var link=repository.find(org,job,key).orElseThrow();
        assertThat(link.status()).isEqualTo("FAILED");assertThat(link.errorMessage()).isEqualTo("原文件暂不可读取");
    }
    @Test void changedPlanClearsObsoleteFailureAndLateCallbackCannotRestoreIt(){
        plan("first");repository.markFailed(org,job,key,"first","old");plan("second");
        repository.markFailed(org,job,key,"first","late");
        var link=repository.find(org,job,key).orElseThrow();
        assertThat(link.status()).isEqualTo("READY");assertThat(link.errorMessage()).isNull();
    }
    @Test void retryAndLateFailureCannotCreateOrOverwriteAnExistingDraft(){
        plan("first");repository.markFailed(org,job,key,"first","old");
        assertThat(repository.prepareRetry(org,job,key,"stale")).isFalse();
        assertThat(repository.prepareRetry(org,job,key,"first")).isTrue();
        assertThat(repository.markRunning(org,job,key)).isTrue();
        assertThat(repository.markRunning(org,job,key)).isFalse();
        var experiment=UUID.randomUUID();repository.markSynced(org,job,key,experiment,UUID.randomUUID(),"RD261101");
        assertThat(repository.prepareRetry(org,job,key,"first")).isFalse();
        repository.markFailed(org,job,key,"first","late");plan("first");
        var link=repository.find(org,job,key).orElseThrow();
        assertThat(link.status()).isEqualTo("SYNCED");assertThat(link.experimentId()).isEqualTo(experiment);assertThat(link.errorMessage()).isNull();
    }
    @Test void changedPlanDoesNotKeepThePreviousExecutionRunning(){
        plan("first");assertThat(repository.markRunning(org,job,key)).isTrue();plan("second");
        repository.markFailed(org,job,key,"first","late");
        assertThat(repository.find(org,job,key).orElseThrow().status()).isEqualTo("READY");
    }
    @Test void explicitRetryCanRecoverADomainStateWhoseTerminalCallbackWasMissed(){
        plan("first");assertThat(repository.markRunning(org,job,key)).isTrue();
        assertThat(repository.prepareRetry(org,job,key,"first")).isTrue();
        assertThat(repository.find(org,job,key).orElseThrow().status()).isEqualTo("READY");
    }
}
