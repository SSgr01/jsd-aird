package com.jsd.aird.ai.formula.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.application.FormulaModelTaskProfileRegistry;
import com.jsd.aird.ai.formula.application.UvpuAnalysisProfile;
import com.jsd.aird.ai.formula.application.UvpuResearchProfile;
import com.jsd.aird.shared.json.JsonCanonicalizer;
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
    void productionProfilePatchDoesNotRequireActualFilmThicknessAndMaterializesContractDefaults() throws Exception {
        var profile = json.readValue(Files.readString(locateProfile("uvpu_application_formulation.v1.1.2.json")),
                FormulaModelContracts.TaskProfile.class);

        assertThat(profile.version()).isEqualTo("1.1.2");
        assertThat(profile.contextFeatures()).extracting(FormulaModelContracts.ContextFeatureSpec::code)
                .contains("applicatorSpecUm")
                .doesNotContain("actualFilmThicknessUm", "filmThicknessUm");
        assertThat(profile.targets()).hasSize(5);
        assertThat(profile.targets()).allSatisfy(target -> assertThat(target.decisionThreshold()).isEqualTo(0.50d));
        assertThat(profile.formula().sumTolerance()).isEqualTo(0.02d);

        var registry = new FormulaModelTaskProfileRegistry(json, new JsonCanonicalizer(json));
        assertThat(registry.productionJson().path("targets").get(0).path("decisionThreshold").asDouble())
                .isEqualTo(0.50d);
        assertThat(registry.productionHash()).isEqualTo(new JsonCanonicalizer(json).hash(registry.productionJson()));
    }

    @Test
    void candidateProfileAddsNewTargetsWithoutChangingTheProductionDefault() {
        var canonicalizer = new JsonCanonicalizer(json);
        var registry = new FormulaModelTaskProfileRegistry(json, canonicalizer);

        assertThat(registry.production().version()).isEqualTo("1.1.2");
        assertThat(registry.production().targets()).hasSize(5);
        assertThat(registry.byVersion("1.2").version()).isEqualTo("1.2");
        assertThat(registry.byVersion("1.2").targets()).hasSize(18)
                .extracting(FormulaModelContracts.TargetSpec::targetKey)
                .contains(
                        "APP.WARPING.CURL_ANGLE@substrate=PC_FILM_170UM;stage=UV_IMMEDIATE",
                        "APP.WARPING.CURL_ANGLE@substrate=PET_100UM;stage=UV_IMMEDIATE",
                        "APP.ELONGATION@substrate=PC_FILM_170UM;method=HOT_DRAW;unit=PCT",
                        "APP.SURFACE_DRYNESS@stage=UV_CURED",
                        "APP.ADHESION.B_GRADE@substrate=PMMA_PC_COMPOSITE_0_64MM;condition=WATER_85C_1H"
                );
        assertThat(registry.byVersion("1.2").contextFeatures())
                .extracting(FormulaModelContracts.ContextFeatureSpec::code)
                .doesNotContain("actualFilmThicknessUm", "filmThicknessUm");
        assertThat(registry.candidateHash()).isEqualTo(canonicalizer.hash(registry.candidateJson()));
    }

    @Test
    void candidateTargetsAlignWithAnalysisAndResearchProfiles() {
        var registry = new FormulaModelTaskProfileRegistry(json, new JsonCanonicalizer(json));
        var analysis = new UvpuAnalysisProfile(json);
        var research = new UvpuResearchProfile(json);

        var candidateKeys = registry.byVersion("1.2").targets().stream()
                .map(FormulaModelContracts.TargetSpec::targetKey)
                .collect(java.util.stream.Collectors.toSet());
        var analysisKeys = analysis.definition().targets().stream()
                .map(UvpuAnalysisProfile.TargetDefinition::targetKey)
                .collect(java.util.stream.Collectors.toSet());
        var researchKeys = research.definition().targets().stream()
                .map(UvpuResearchProfile.Target::targetKey)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(candidateKeys).hasSize(18).isEqualTo(analysisKeys).isEqualTo(researchKeys);
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
        return locateProfile("uvpu_application_formulation.v1.json");
    }

    private Path locateProfile(String name) {
        return List.of(
                        Path.of("..", "jsd-aird-ai", "src", "jsd_aird_ai", "task_profiles",
                                name),
                        Path.of("jsd-aird-ai", "src", "jsd_aird_ai", "task_profiles",
                                name)
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
