package com.jsd.aird.ai.rnd.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.SyntheticTestAuthorization;
import com.jsd.aird.ai.rnd.formula.FormulaNormalizationService;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.prediction.PredictionRepository;
import com.jsd.aird.ai.rnd.prediction.PredictionService;
import com.jsd.aird.ai.rnd.training.FormulaModelV2Client;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CATALOG_DB_TEST",matches="true")
class FormulaDesignPostgresTest {
    @Test void restoresHistoricalConditionsAndGeneratedDataProvenanceWithoutTheSimulationSwitch(){
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("JSD_AIRD_DATASOURCE_URL"),System.getenv("JSD_AIRD_DATASOURCE_USERNAME"),System.getenv("JSD_AIRD_DATASOURCE_PASSWORD")));
        var actor=jdbc.queryForObject("SELECT organization_id,id FROM iam.app_user WHERE username='admin' LIMIT 1",(r,n)->new Actor(r.getObject(1,UUID.class),r.getObject(2,UUID.class),"admin"));
        var id=jdbc.queryForObject("""
            SELECT r.id FROM ai.research_run_v2 r WHERE r.organization_id=? AND r.created_by=? AND r.run_type='FORMULA_PREDICTION'
              AND r.request_jsonb->'fixedInputs'->>'COATING_SOLIDS'='40' AND EXISTS(SELECT 1 FROM ai.research_candidate_v2 c WHERE c.research_run_id=r.id)
            ORDER BY r.created_at LIMIT 1
            """,UUID.class,actor.organizationId(),actor.userId());
        var json=new ObjectMapper();var repository=new FormulaDesignRepository(jdbc,json);
        var service=new FormulaDesignService(repository,new PredictionRepository(jdbc,json),mock(PredictionService.class),mock(FormulaModelArtifactStore.class),mock(FormulaModelV2Client.class),new ConfigurationHashing(json),mock(OpsAsyncFacade.class),mock(AuditLogFacade.class),json,new FormulaNormalizationService(json),new SyntheticTestAuthorization("production",false),false);
        ActorContext.set(actor);
        try{
            var run=service.get(id);
            assertThat(run.simulation()).isTrue();
            assertThat(run.candidates().getFirst().inputs().path("COATING_SOLIDS").asInt()).isEqualTo(40);
            assertThat(run.frozenEvidence().path("fixedInputs").path("COATING_SOLIDS").asInt()).isEqualTo(40);
            assertThat(run.frozenEvidence().path("displayContext").path("requiredInputs").toString()).contains("COATING_SOLIDS");
            assertThat(run.frozenEvidence().path("displayContext").path("materialIntersection").isEmpty()).isFalse();
        }finally{ActorContext.clear();}
    }
}
