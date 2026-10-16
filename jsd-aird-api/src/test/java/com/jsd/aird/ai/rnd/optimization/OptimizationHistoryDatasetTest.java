package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptimizationHistoryDatasetTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UUID org = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();
    private final OptimizationRepository repository = mock(OptimizationRepository.class);
    private final OptimizationService service = new OptimizationService(repository, null, null, null, null, null, null,
            mock(AuthorizationService.class), null, null, json, null, false);

    @AfterEach void clearActor() { ActorContext.clear(); }

    @Test void importedMeasurementsKeepTheirLabelsValuesAndSampleIdentitiesWithoutMetadataColumns() throws Exception {
        setup("""
                {"dynamicValues":{"COATING_SOLIDS":"42 %","IMPORT:STRUCT:sheet-1:table-id:COLUMN:2:TABLE.COLUMN.树脂编号":"R2",
                  "IMPORT:STRUCT:sheet-1:block-id:FORM:1:备注":"透明","IMPORT:UNKNOWN":"internal"},
                 "processSteps":[
                  {"field":"APPLICATION_CONDITION","labelPath":"涂料固含","unit":"%","value":"39.7",
                   "sourceRecordKey":"IMPORT:STRUCT:sheet-1:table-id:COLUMN:1","sourceIdentity":"EX-01","itemId":"item-1","sampleKey":"group-1","sourceRefs":[{"sheetName":"Sheet1"}]},
                  {"field":"APPLICATION_CONDITION","labelPath":"涂料固含","unit":"%","value":"41.5",
                   "sourceRecordKey":"IMPORT:STRUCT:sheet-2:table-id:COLUMN:1","sourceIdentity":"EX-01","itemId":"item-2","sampleKey":"group-2","sourceRefs":[{"sheetName":"Sheet2"}]}]
                }
                """);
        var result = service.modelingHistoryDataset(datasetId);
        var names = result.path("columns").findValuesAsText("name");
        assertThat(names).containsExactly("涂料固含", "树脂编号 · 第1页第2列", "备注", "涂料固含 · Sheet1 · EX-01", "涂料固含 · Sheet2 · EX-01");
        assertThat(names).noneMatch(name -> name.contains("IMPORT:") || name.contains("itemId") || name.contains("sampleKey"));
        var values = result.path("rows").get(0).path("values");
        assertThat(values.path("input:COATING_SOLIDS").asDouble()).isEqualTo(42);
        assertThat(values.path("process:IMPORT:STRUCT:sheet-1:table-id:COLUMN:1:APPLICATION_CONDITION").asDouble()).isEqualTo(39.7);
        assertThat(values.path("process:IMPORT:STRUCT:sheet-2:table-id:COLUMN:1:APPLICATION_CONDITION").asDouble()).isEqualTo(41.5);
    }

    @Test void blankCellsAndSourceMetadataDoNotCountAsMeasuredValues() throws Exception {
        setup("""
                {"dynamicValues":{"空白":"  ","缺失":"—","未测":"N/A","溢出":"1e309"},
                 "processSteps":[{"name":"固化","duration":30,"sourceIdentity":"EX-01","sourceRecordKey":"internal","itemId":"item-1"}],
                 "testResults":[{"name":"光泽","unit":"GU","value":"78"}]}
                """);
        var result = service.modelingHistoryDataset(datasetId);
        assertThat(result.path("columns").findValuesAsText("name")).containsExactly("固化·duration", "光泽");
    }

    @Test void repeatedResultsInDifferentSheetsDoNotOverwriteEachOther() throws Exception {
        setup("""
                {"testResults":[
                  {"testItem":"光泽","unit":"GU","value":"78","sourceRecordKey":"IMPORT:STRUCT:sheet-1:table:COLUMN:1",
                   "sourceIdentity":"EX-01","sourceRefs":[{"sheetName":"Sheet1"}]},
                  {"testItem":"光泽","unit":"GU","value":"80","sourceRecordKey":"IMPORT:STRUCT:sheet-2:table:COLUMN:1",
                   "sourceIdentity":"EX-01","sourceRefs":[{"sheetName":"Sheet2"}]}]}
                """);
        var result = service.modelingHistoryDataset(datasetId);
        assertThat(result.path("columns").findValuesAsText("name")).containsExactly("光泽 · Sheet1 · EX-01", "光泽 · Sheet2 · EX-01");
        var values = result.path("rows").get(0).path("values");
        assertThat(values.path("result:IMPORT:STRUCT:sheet-1:table:COLUMN:1:光泽").asDouble()).isEqualTo(78);
        assertThat(values.path("result:IMPORT:STRUCT:sheet-2:table:COLUMN:1:光泽").asDouble()).isEqualTo(80);
    }

    @Test void combinesCompatibleTablesAndKeepsEachRowLinkedToItsSource() throws Exception {
        ActorContext.set(new Actor(org, UUID.randomUUID(), "researcher"));
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var firstExperiment = UUID.randomUUID();
        var secondExperiment = UUID.randomUUID();
        when(repository.historyDatasets(org, "")).thenReturn(List.of(
                new OptimizationRepository.HistoryDatasetRow(firstId, "涂层实验 A", 1, Instant.EPOCH),
                new OptimizationRepository.HistoryDatasetRow(secondId, "涂层实验 B", 1, Instant.EPOCH)));
        when(repository.historyFieldNames(org)).thenReturn(Map.of("TEMPERATURE_A", "温度", "TEMPERATURE_B", "温度"));
        when(repository.historyRecords(org, firstId)).thenReturn(List.of(new OptimizationRepository.HistoryRecordRow(
                firstExperiment, UUID.randomUUID(), "EXP-A", "涂层 A",
                json.readTree("""
                        {"dynamicValues":{"TEMPERATURE_A":25,"ONLY_A":1},"testResults":[{"targetCode":"GLOSS_A","targetName":"60°光泽","unit":"GU","value":72}]}
                        """), Instant.EPOCH)));
        when(repository.historyRecords(org, secondId)).thenReturn(List.of(new OptimizationRepository.HistoryRecordRow(
                secondExperiment, UUID.randomUUID(), "EXP-B", "涂层 B",
                json.readTree("""
                        {"dynamicValues":{"TEMPERATURE_B":30,"ONLY_B":2},"testResults":[{"targetCode":"GLOSS_B","targetName":"60°光泽","unit":"GU","value":80}]}
                        """), Instant.EPOCH)));

        var merged = service.modelingHistoryDatasetSetForTraining(List.of(firstId, secondId));

        assertThat(merged.path("sourceDatasetIds").toString()).contains(firstId.toString(), secondId.toString());
        assertThat(merged.path("columns").findValuesAsText("code")).containsExactly("input:TEMPERATURE_A", "result:GLOSS_A");
        assertThat(merged.path("rows")).hasSize(2);
        assertThat(merged.path("rows").get(0).path("sourceDatasetId").asText()).isEqualTo(firstId.toString());
        assertThat(merged.path("rows").get(1).path("sourceDatasetName").asText()).isEqualTo("涂层实验 B");
        assertThat(merged.path("rows").get(0).path("values").has("input:ONLY_A")).isFalse();
        assertThat(merged.path("rows").get(1).path("values").path("result:GLOSS_A").asDouble()).isEqualTo(80);
    }

    private void setup(String editModel) throws Exception {
        ActorContext.set(new Actor(org, UUID.randomUUID(), "researcher"));
        when(repository.historyDatasets(org, "")).thenReturn(List.of(new OptimizationRepository.HistoryDatasetRow(datasetId, "涂层实验", 1, Instant.EPOCH)));
        when(repository.historyFieldNames(org)).thenReturn(Map.of("COATING_SOLIDS", "涂料固含"));
        when(repository.historyRecords(org, datasetId)).thenReturn(List.of(new OptimizationRepository.HistoryRecordRow(UUID.randomUUID(), UUID.randomUUID(), "EX-01", "涂层实验", json.readTree(editModel), Instant.EPOCH)));
    }
}
