package com.jsd.aird.ai.formula.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FormulaModelContractsTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void readsTheSharedUvpuTaskProfile() throws Exception {
        var profilePath = locateProfile();
        var profile = json.readValue(
                Files.readString(profilePath),
                FormulaModelContracts.TaskProfile.class
        );

        assertThat(profile.contractVersion()).isEqualTo(FormulaModelContracts.CONTRACT_VERSION);
        assertThat(profile.code()).isEqualTo("UVPU_APPLICATION_FORMULATION");
        assertThat(profile.formula().balanceMaterialCode()).isEqualTo("S-48");
        assertThat(profile.formula().mainResinCodes()).hasSize(6);
        assertThat(profile.targets()).allSatisfy(target -> {
            assertThat(target.testMethod()).isNotBlank();
            assertThat(target.substrate()).isEqualTo("PET_100UM_OPTICAL");
        });
        assertThat(profile.targets())
                .filteredOn(target -> target.valueType() == FormulaModelContracts.ValueType.CONTINUOUS)
                .hasSize(4);
        assertThat(profile.targets())
                .filteredOn(target -> target.valueType() == FormulaModelContracts.ValueType.ORDINAL)
                .singleElement()
                .satisfies(target -> {
                    assertThat(target.code()).isEqualTo("Y__HARDNESS_PET_1KG_ORD");
                    assertThat(target.ordinalLabels()).containsExactly("H", "2H", "3H", "4H");
                });
        assertThat(profile.modelEligibility().get("LIGHTGBM").minSamples()).isEqualTo(150);
        assertThat(profile.readinessThresholds().productionContinuous().minSamples()).isEqualTo(80);
        assertThat(profile.readinessThresholds().productionBinary().minMinorityClassSamples()).isEqualTo(20);
        assertThat(profile.readinessThresholds().productionCategorical().minSamplesPerObservedClass()).isEqualTo(10);
        assertThat(profile.classificationModelEligibility().get("CATBOOST").minSamples()).isEqualTo(80);
        assertThat(profile.probabilityCalibration().method()).isEqualTo("PLATT");
        assertThat(profile.applicabilityDomain().outOfDomainRequiresFallback()).isTrue();
    }

    @Test
    void readsTheSharedScoreGoldenRequestAndResponse() throws Exception {
        var request = json.readValue(
                Files.readString(locateContractExample("score-request.uvpu-synthetic.golden.json")),
                FormulaModelContracts.ScoreRequest.class
        );
        var response = json.readValue(
                Files.readString(locateContractExample("score-response.uvpu-synthetic.golden.json")),
                FormulaModelContracts.ScoreResponse.class
        );

        assertThat(request.requestId()).isEqualTo(response.requestId());
        assertThat(request.modelBundleHash()).isEqualTo(response.modelBundleSha256());
        assertThat(request.rows()).singleElement().satisfies(row ->
                assertThat(row.formula()).containsEntry("S-48", 39.3)
        );
        assertThat(response.rows()).singleElement().satisfies(row ->
                assertThat(row.predictions()).hasSize(4)
        );
        assertThat(response.rows().getFirst().ordinalPredictions()).singleElement().satisfies(prediction ->
                assertThat(prediction.predictedClass()).isIn("H", "2H", "3H", "4H")
        );
        assertThat(response.rows().getFirst().classificationPredictions()).isEmpty();
        assertThat(response.unsupportedTargets()).isEmpty();
    }

    private Path locateProfile() {
        return List.of(
                        Path.of("..", "jsd-aird-ai", "src", "jsd_aird_ai", "task_profiles",
                                "uvpu_application_formulation.v1.json"),
                        Path.of("jsd-aird-ai", "src", "jsd_aird_ai", "task_profiles",
                                "uvpu_application_formulation.v1.json")
                ).stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Shared formula model profile not found"));
    }

    private Path locateContractExample(String name) {
        return List.of(
                        Path.of("..", "jsd-aird-ai", "contracts", "formula-model.v1", "examples", name),
                        Path.of("jsd-aird-ai", "contracts", "formula-model.v1", "examples", name)
                ).stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Shared contract example not found: " + name));
    }
}
