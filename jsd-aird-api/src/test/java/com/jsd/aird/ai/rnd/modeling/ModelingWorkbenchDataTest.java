package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelingWorkbenchDataTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelingWorkbenchService service = new ModelingWorkbenchService(null, json, null, null, null, null, null);

    @Test
    void freezesAllSelectedRecordsForChecksAndKeepsOnlyNormalizedCompleteRowsForRegistration() {
        var id = UUID.randomUUID();
        var data = dataset();
        var rows = data.withArray("rows");
        rows.addObject().put("id", "missing").put("label", "缺少固含").putObject("values").put("y", 20).put("constant", "PET");
        rows.addObject().put("id", "invalid").putObject("values").put("x", "0x10").put("y", 20).put("constant", "PET");
        var command = new ModelingWorkbenchService.TrainCommand(id, List.of(id), List.of("x", "constant"), "y", null);
        ObjectNode frozen = ReflectionTestUtils.invokeMethod(service, "freezeRequest", data, command, List.of(id));
        assertThat(frozen).isNotNull();
        assertThat(frozen.path("rowCount").asInt()).isEqualTo(6);
        assertThat(frozen.path("selectedRowCount").asInt()).isEqualTo(8);
        assertThat(frozen.path("selectedRows").size()).isEqualTo(8);
        assertThat(frozen.path("selectedRows").get(6).path("label").asText()).isEqualTo("缺少固含");
        assertThat(frozen.path("rows").size()).isEqualTo(6);
        assertThat(frozen.path("rows").get(0).path("values").path("x").isNumber()).isTrue();
        assertThat(frozen.path("rows").get(0).path("values").path("constant").asText()).isEqualTo("PET");
        assertThat(frozen.path("xColumns").size()).isEqualTo(2);
    }

    @Test
    void uncheckedRecordsDoNotEnterTrainingOrDiagnostics() {
        var id = UUID.randomUUID();
        var data = dataset();
        data.withArray("rows").addObject().put("id", "unchecked").putObject("values").put("x", "bad");
        var command = new ModelingWorkbenchService.TrainCommand(id, List.of(id), List.of("x"), "y",
                Map.of(id.toString(), List.of("row-0", "row-1", "row-2", "row-3", "row-4", "row-5")));
        ObjectNode frozen = ReflectionTestUtils.invokeMethod(service, "freezeRequest", data, command, List.of(id));
        assertThat(frozen.path("selectedRows").size()).isEqualTo(6);
        assertThat(frozen.path("rows").size()).isEqualTo(6);
    }

    @Test
    void rejectsInputsThatCannotDistinguishAnyCompleteRecords() {
        var id = UUID.randomUUID();
        var command = new ModelingWorkbenchService.TrainCommand(id, List.of(id), List.of("constant"), "y", null);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "freezeRequest", dataset(), command, List.of(id)))
                .hasMessageContaining("所选 X 在完整记录中都没有变化");
    }

    private ObjectNode dataset() {
        var data = json.createObjectNode().put("name", "涂层配方");
        var columns = data.putArray("columns");
        columns.addObject().put("code", "x").put("name", "固含").put("kind", "NUMERIC");
        columns.addObject().put("code", "constant").put("name", "基材").put("kind", "CATEGORICAL");
        columns.addObject().put("code", "y").put("name", "耐磨次数").put("kind", "NUMERIC");
        var rows = data.putArray("rows");
        for (int i = 0; i < 6; i++) rows.addObject().put("id", "row-" + i).put("label", "涂层 " + i)
                .putObject("values").put("x", " " + (30 + i) + " ").put("constant", " PET ").put("y", i * 10);
        return data;
    }
}
