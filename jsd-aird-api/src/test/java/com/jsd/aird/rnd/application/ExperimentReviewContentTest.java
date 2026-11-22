package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExperimentReviewContentTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void ignoresCanvasChangesButRetainsDataAndFieldDefinitions() throws Exception {
        var snapshot = json.readTree("""
                {"sheets":{"s":{"name":"实验记录","cellData":{"0":{"0":{"v":"750g","s":"bold"}}},
                  "custom":{"templateInstance":{"model":{"fields":[{"name":"硬度","unit":"g","reviewStatus":"CONFIRMED"}]},
                    "issues":[],"revision":1}}}}}
                """);
        var expected = ExperimentReviewContent.workbook(json, snapshot);
        var changed = (ObjectNode) snapshot.deepCopy();
        changed.withObject("/sheets/s").put("zoomRatio", 1.5).put("scrollTop", 300);
        changed.withObject("/sheets/s/freeze").put("ySplit", 0);
        changed.withObject("/sheets/s/cellData/0/0").put("s", "normal");
        changed.withObject("/sheets/s/custom/templateInstance").put("revision", 12).putArray("issues").add("stale");
        assertThat(ExperimentReviewContent.workbook(json, changed)).isEqualTo(expected);
        changed.withObject("/sheets/s/cellData/0/0").put("v", "800g");
        assertThat(ExperimentReviewContent.workbook(json, changed)).isNotEqualTo(expected);
        changed.withObject("/sheets/s/cellData/0/0").put("v", "750g");
        ((ObjectNode) changed.at("/sheets/s/custom/templateInstance/model/fields/0")).put("unit", "kg");
        assertThat(ExperimentReviewContent.workbook(json, changed)).isNotEqualTo(expected);
    }

    @Test void ignoresFormulaResultCachesButNotChangesToTheFormula() throws Exception {
        var snapshot = (ObjectNode) json.readTree("""
                {"sheets":{"s":{"cellData":{"0":{"0":{"f":"=SUM(B1:C1)","v":10,"t":2}}}}}}
                """);
        var reviewed = ExperimentReviewContent.workbook(json, snapshot);
        snapshot.withObject("/sheets/s/cellData/0/0").put("v", 11).put("t", 1).put("s", "recalculated");
        assertThat(ExperimentReviewContent.workbook(json, snapshot)).isEqualTo(reviewed);
        snapshot.withObject("/sheets/s/cellData/0/0").put("f", "=AVERAGE(B1:C1)");
        assertThat(ExperimentReviewContent.workbook(json, snapshot)).isNotEqualTo(reviewed);
    }

    @Test void ignoresInternalMetadataAndEmptyFormattedCellsButKeepsZeroAndFalse() throws Exception {
        var snapshot = (ObjectNode) json.readTree("""
                {"sheets":{"s":{"cellData":{"0":{"0":{"v":0},"1":{"v":false}}}}}}
                """);
        var reviewed = ExperimentReviewContent.workbook(json, snapshot);
        snapshot.withObject("/sheets/internal").put("name", "_JSD_METADATA").put("revision", 12);
        snapshot.withObject("/sheets/s/cellData/3/3").put("s", "border").putNull("v");
        assertThat(ExperimentReviewContent.workbook(json, snapshot)).isEqualTo(reviewed);
        snapshot.withObject("/sheets/s/cellData/0/0").remove("v");
        assertThat(ExperimentReviewContent.workbook(json, snapshot)).isNotEqualTo(reviewed);
    }
}
