package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PredictionResultMappingTest {
    @Test
    void distinguishesExistingSimulationModelsFromTargetsWithoutAnEnabledModel() {
        assertThat(PredictionService.unavailableModelReason(null,"SYNTHETIC",false)).isEqualTo("NO_ACTIVE_MODEL");
        assertThat(PredictionService.unavailableModelReason(UUID.randomUUID(),"SYNTHETIC",false)).isEqualTo("SIMULATION_NOT_ENABLED");
        assertThat(PredictionService.unavailableModelReason(UUID.randomUUID(),"REAL",true)).isEqualTo("SIMULATION_NOT_ENABLED");
        assertThat(PredictionService.unavailableModelReason(UUID.randomUUID(),"REAL",false)).isEqualTo("MODEL_NOT_PRODUCTION_ELIGIBLE");
    }
    @Test
    void exposesComputeFailureCodeUnderThePublicApiFieldAndPreservesDetails() throws Exception {
        var json=new ObjectMapper();
        var source=json.readTree("""
                {"status":"BLOCKED","code":"OUT_OF_DOMAIN","targetId":"internal-version",
                 "detail":{"violations":[{"fieldCode":"input:TEMPERATURE","value":80,"minimum":20,"maximum":40}]}}
                """);
        var target=UUID.randomUUID();
        var result=PredictionService.rewriteTarget(source,target);
        assertThat(result.path("errorCode").asText()).isEqualTo("OUT_OF_DOMAIN");
        assertThat(result.path("targetId").asText()).isEqualTo(target.toString());
        assertThat(result.path("detail")).isEqualTo(source.path("detail"));
        assertThat(source.has("errorCode")).isFalse();
    }
}
