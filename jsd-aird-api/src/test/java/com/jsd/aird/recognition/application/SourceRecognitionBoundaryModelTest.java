package com.jsd.aird.recognition.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.facts.UnifiedFactService;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.recognition.application.port.SourceRecognitionRepository;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.rnd.application.ExperimentImportService;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SourceRecognitionBoundaryModelTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final SourceRecognitionService service = new SourceRecognitionService(
            mock(SourceRecognitionRepository.class), mock(FileObjectRepository.class),
            mock(ExperimentImportService.class), mock(ExperimentDraftFacade.class),
            new ConfigurationHashing(json), mock(AuditLogFacade.class), json,
            mock(UnifiedFactService.class), mock(AuthorizationService.class));

    @Test
    void keepsThreeTechnicalReplicatesAsObservationsOfOneLogicalSample() throws Exception {
        var boundaries = json.readTree("""
                [{
                  "experimentBoundaryId":"exp-1","title":"UV experiment","confirmed":true,
                  "sourceCoordinates":{"kind":"WHOLE_FILE"},"sharedConditions":[],
                  "samples":[{
                    "sampleBoundaryId":"sample-a","logicalSampleKey":"CUSTOMER-A:SAMPLE-A",
                    "sourceGroupKeys":["sheet1:formula","sheet1:gloss"],"title":"Sample A"
                  }]
                }]
                """);
        var candidates = json.readTree("""
                [
                  {"candidateId":"formula-1","category":"FORMULA","fieldCode":"formulaComponent",
                   "rawText":"Resin 50%","parsedValue":"Resin 50%","sourceCoordinate":{"kind":"CELL","address":"A2"},
                   "confidence":0.9,"parserVersion":"test","status":"CONFIRMED",
                   "experimentBoundaryId":"exp-1","sampleBoundaryId":"sample-a","sourceGroupKey":"sheet1:formula"},
                  {"candidateId":"obs-1","category":"TEST","fieldCode":"gloss60","rawText":"80 GU","parsedValue":"80",
                   "sourceCoordinate":{"kind":"CELL","address":"D2"},"confidence":0.9,"parserVersion":"test","status":"CONFIRMED",
                   "experimentBoundaryId":"exp-1","sampleBoundaryId":"sample-a","sourceGroupKey":"sheet1:gloss",
                   "observationId":"obs-1","replicateGroupKey":"gloss60:film-1","measurementIndex":1},
                  {"candidateId":"obs-2","category":"TEST","fieldCode":"gloss60","rawText":"81 GU","parsedValue":"81",
                   "sourceCoordinate":{"kind":"CELL","address":"E2"},"confidence":0.9,"parserVersion":"test","status":"CONFIRMED",
                   "experimentBoundaryId":"exp-1","sampleBoundaryId":"sample-a","sourceGroupKey":"sheet1:gloss",
                   "observationId":"obs-2","replicateGroupKey":"gloss60:film-1","measurementIndex":2},
                  {"candidateId":"obs-3","category":"TEST","fieldCode":"gloss60","rawText":"79 GU","parsedValue":"79",
                   "sourceCoordinate":{"kind":"CELL","address":"F2"},"confidence":0.9,"parserVersion":"test","status":"CONFIRMED",
                   "experimentBoundaryId":"exp-1","sampleBoundaryId":"sample-a","sourceGroupKey":"sheet1:gloss",
                   "observationId":"obs-3","replicateGroupKey":"gloss60:film-1","measurementIndex":3}
                ]
                """);
        service.validateBoundaries(boundaries);
        service.validateCandidateAssignments(boundaries, candidates);

        var recognized = service.recognizedExperiments(job(boundaries, candidates));

        assertThat(recognized).hasSize(1);
        assertThat(recognized.getFirst().samples()).hasSize(1);
        var sample = recognized.getFirst().samples().getFirst();
        assertThat(sample.logicalSampleKey()).isEqualTo("CUSTOMER-A:SAMPLE-A");
        assertThat(sample.sourceGroupKeys()).containsExactly("sheet1:formula", "sheet1:gloss");
        assertThat(sample.effectiveFact().path("observations")).hasSize(3);
        assertThat(sample.effectiveFact().path("observations").findValuesAsText("observationId"))
                .containsExactly("obs-1", "obs-2", "obs-3");
        assertThat(sample.effectiveFact().path("recognizedItems")).hasSize(4);
    }

    @Test
    void separatesExperimentBoundariesWithoutTurningSourceGroupsIntoSamples() throws Exception {
        var boundaries = json.readTree("""
                [
                  {"experimentBoundaryId":"exp-1","title":"E1","confirmed":true,"sourceCoordinates":{"kind":"PAGE"},"sharedConditions":[],
                   "samples":[{"sampleBoundaryId":"sample-1","logicalSampleKey":"L-1","sourceGroupKeys":["page:1","page:2"],"title":"S1"}]},
                  {"experimentBoundaryId":"exp-2","title":"E2","confirmed":true,"sourceCoordinates":{"kind":"PAGE"},"sharedConditions":[],
                   "samples":[{"sampleBoundaryId":"sample-2","logicalSampleKey":"L-2","sourceGroupKeys":["page:3"],"title":"S2"}]}
                ]
                """);
        var candidates = json.readTree("""
                [
                  {"candidateId":"c1","category":"BASIC","fieldCode":"title","rawText":"E1","parsedValue":"E1","sourceCoordinate":{"kind":"PAGE"},"confidence":1,"parserVersion":"test","status":"CONFIRMED","experimentBoundaryId":"exp-1","sampleBoundaryId":"sample-1","sourceGroupKey":"page:1"},
                  {"candidateId":"c2","category":"BASIC","fieldCode":"title","rawText":"E2","parsedValue":"E2","sourceCoordinate":{"kind":"PAGE"},"confidence":1,"parserVersion":"test","status":"CONFIRMED","experimentBoundaryId":"exp-2","sampleBoundaryId":"sample-2","sourceGroupKey":"page:3"}
                ]
                """);

        service.validateBoundaries(boundaries);
        service.validateCandidateAssignments(boundaries, candidates);
        var recognized = service.recognizedExperiments(job(boundaries, candidates));

        assertThat(recognized).extracting(SourceRecognitionRepository.RecognizedExperiment::experimentBoundaryId)
                .containsExactly("exp-1", "exp-2");
        assertThat(recognized.getFirst().samples().getFirst().sourceGroupKeys())
                .containsExactly("page:1", "page:2");
    }

    @Test
    void rejectsOnePhysicalSourceGroupAssignedToTwoLogicalSamples() throws Exception {
        var boundaries = json.readTree("""
                [{"experimentBoundaryId":"exp-1","title":"E1","confirmed":true,"sourceCoordinates":{"kind":"WHOLE_FILE"},"sharedConditions":[],
                  "samples":[
                    {"sampleBoundaryId":"sample-1","logicalSampleKey":"L-1","sourceGroupKeys":["same-region"],"title":"S1"},
                    {"sampleBoundaryId":"sample-2","logicalSampleKey":"L-2","sourceGroupKeys":["same-region"],"title":"S2"}
                  ]}]
                """);

        assertThatThrownBy(() -> service.validateBoundaries(boundaries))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能同时归属于多个逻辑样本");
    }

    @Test
    void pairsWorkbookLabelsWithValuesAndBuildsOneReplicateGroup() throws Exception {
        var snapshot = json.readTree("""
                {
                  "sheetOrder":["sheet-1"],
                  "sheets":{"sheet-1":{"cellData":{
                    "0":{"0":{"v":"实验名称"},"1":{"v":"UV 60度光泽重复测量"}},
                    "1":{"0":{"v":"样本编号"},"1":{"v":"S-001"}},
                    "2":{"0":{"v":"材料A比例"},"1":{"v":"60%"}},
                    "3":{"0":{"v":"材料B比例"},"1":{"v":"38.8%"}},
                    "4":{"0":{"v":"UV能量"},"1":{"v":"800 mJ/cm2"}},
                    "5":{"0":{"v":"60°光泽"},"1":{"v":"82.1 GU"}},
                    "6":{"0":{"v":"60°光泽"},"1":{"v":"81.9 GU"}},
                    "7":{"0":{"v":"60°光泽"},"1":{"v":"82.3 GU"}}
                  }}}
                }
                """);
        var empty = json.createArrayNode();
        var sourceJob = job(empty, empty);

        var workspace = service.initialWorkspace(sourceJob, snapshot, json.createObjectNode(), "xlsx-test");
        var candidates = workspace.path("candidates");
        var gloss = candidates.findParents("fieldCode").stream()
                .filter(item -> "gloss60".equals(item.path("fieldCode").asText())).toList();

        assertThat(candidates).hasSize(8);
        assertThat(candidates.findValuesAsText("fieldCode")).contains("title", "sampleIdentity", "processCondition");
        assertThat(candidates.findValuesAsText("parsedValue")).contains("60", "38.8", "800", "82.1", "81.9", "82.3");
        assertThat(gloss).hasSize(3);
        assertThat(gloss).extracting(item -> item.path("replicateGroupKey").asText())
                .containsOnly("test:gloss60");
        assertThat(gloss).extracting(item -> item.path("measurementIndex").asInt())
                .containsExactly(1, 2, 3);
        assertThat(gloss.getFirst().path("sourceCoordinate").path("labelAddress").asText()).isEqualTo("A6");
        assertThat(gloss.getFirst().path("sourceCoordinate").path("valueAddress").asText()).isEqualTo("B6");
    }

    private SourceRecognitionRepository.Job job(JsonNode boundaries, JsonNode candidates) {
        ObjectNode workspace = json.createObjectNode();
        workspace.set("candidates", candidates);
        workspace.set("unrecognizedFragments", json.createArrayNode());
        workspace.set("issues", json.createArrayNode());
        return new SourceRecognitionRepository.Job(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "freeform.xlsx", "a".repeat(64), "XLSX", "EXPERIMENT", "FREEFORM", null, null,
                "EXPERIMENT_DRAFT", UUID.randomUUID(), "ALL", "WAITING_MAPPING", 100,
                "WAITING_REVIEW", "test-parser", workspace, boundaries, 1, null, 1,
                null, null, Instant.now(), Instant.now());
    }
}
