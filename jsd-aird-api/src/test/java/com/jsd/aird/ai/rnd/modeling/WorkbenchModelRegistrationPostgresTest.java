package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.prediction.PredictionRepository;
import com.jsd.aird.ai.rnd.training.TrainingRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_WORKBENCH_DB_TEST",matches="true")
class WorkbenchModelRegistrationPostgresTest {
    @Test
    void registersFrozenRunsIdempotentlyAndVersionsWithoutFabricatingTrainingJobs() {
        var ds=new DriverManagerDataSource(System.getenv("JSD_AIRD_DATASOURCE_URL"),
                System.getenv("JSD_AIRD_DATASOURCE_USERNAME"),System.getenv("JSD_AIRD_DATASOURCE_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);var json=new ObjectMapper();var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.execute(status->{
            status.setRollbackOnly();
            var actor=jdbc.query("SELECT organization_id,created_by FROM ai.modeling_workbench_run LIMIT 1",
                    (rs,n)->new Actor(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),"admin")).getFirst();
            ActorContext.set(actor);
            try {
                var store=mock(FormulaModelArtifactStore.class);
                when(store.verify(anyString(),anyString(),anyString())).thenAnswer(call->new FormulaModelArtifactStore.StoredArtifact(call.getArgument(0),call.getArgument(1),100,"application/zip"));
                var service=new WorkbenchModelRegistrationService(jdbc,json,new ConfigurationHashing(json),store,mock(AuditLogFacade.class));
                var code="field-"+UUID.randomUUID();
                var request=json.createObjectNode();var xs=request.putArray("xColumns");
                xs.addObject().put("code",code).put("name","固含").put("kind","NUMERIC").put("unit","%");
                request.putObject("yColumn").put("code",code+"-result").put("name","光泽").put("kind","NUMERIC");
                var rows=request.putArray("rows");for(int i=0;i<8;i++)rows.addObject().put("id","row-"+i).put("label","配方记录 "+i).putObject("values").put(code,i).put(code+"-result",i*2);
                var result=json.createObjectNode().put("rowCount",8);result.putObject("metrics").put("selectedAlgorithm","RANDOM_FOREST").put("mae",0.5);
                result.putObject("applicabilityDomain").put("method","ROBUST_FEATURE_RANGE").putArray("features").add(code);
                result.withObject("applicabilityDomain").putArray("minimum").add(0);result.withObject("applicabilityDomain").putArray("maximum").add(7);
                var runIds=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
                for(var id:runIds)jdbc.update("INSERT INTO ai.modeling_workbench_run(id,organization_id,created_by,dataset_id,dataset_name,idempotency_key,request_hash,request_jsonb,selected_x_jsonb,selected_y_jsonb,status,result_jsonb,model_artifact_key,model_artifact_sha256) VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,'SUCCEEDED',?::jsonb,?,?)",
                        id,actor.organizationId(),actor.userId(),UUID.randomUUID(),"涂层配方",id.toString(),"a".repeat(64),request.toString(),xs.toString(),request.path("yColumn").toString(),result.toString(),"registration/"+id,"b".repeat(64));
                var first=service.register(runIds.getFirst(),"first");var again=service.register(runIds.getFirst(),"retry");
                assertThat(again).isEqualTo(first);verify(store,times(1)).verify(anyString(),anyString(),anyString());
                var second=service.register(runIds.get(1),"second");assertThat(second.path("targetId")).isEqualTo(first.path("targetId"));assertThat(second.path("version").asInt()).isEqualTo(2);
                assertThat(service.registration(actor.organizationId(),runIds.getFirst()).path("status").asText()).isEqualTo("RETIRED");
                var modelId=UUID.fromString(second.path("modelVersionId").asText());
                var model=new TrainingRepository(jdbc,json).modelDetail(actor.organizationId(),modelId);
                assertThat(model.snapshot()).isNull();assertThat(model.model().trainingJobId()).isNull();assertThat(model.model().trainableSamples()).isEqualTo(8);
                assertThat(model.modelCard().path("trainingRows").get(0).path("label").asText()).isEqualTo("配方记录 0");
                var binding=new PredictionRepository(jdbc,json).targetBindings(actor.organizationId(),List.of(UUID.fromString(second.path("targetId").asText()))).getFirst();
                assertThat(binding.modelId()).isEqualTo(modelId);assertThat(binding.frozenInputScheme().path("fields").get(0).path("code").asText()).isEqualTo(code);
                jdbc.update("UPDATE ai.modeling_workbench_run SET status='RUNNING' WHERE id=?",runIds.get(2));
                assertThatThrownBy(()->service.register(runIds.get(2),"unfinished")).hasMessageContaining("训练成功");
                jdbc.update("UPDATE ai.modeling_workbench_run SET status='SUCCEEDED' WHERE id=?",runIds.get(2));
                when(store.verify(anyString(),anyString(),anyString())).thenThrow(new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.FILE_NOT_READY,"模型文件缺失"));
                assertThatThrownBy(()->service.register(runIds.get(2),"missing-file")).hasMessageContaining("文件缺失");
                assertThat(service.registration(actor.organizationId(),runIds.get(2))).isNull();
                ActorContext.set(new Actor(UUID.randomUUID(),actor.userId(),"other-org"));
                assertThatThrownBy(()->service.register(runIds.getFirst(),"forbidden")).hasMessageContaining("无权注册");
            } finally {ActorContext.clear();}
            return null;
        });
    }
}
