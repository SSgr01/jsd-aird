package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UniverSnapshotStructureParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UniverSnapshotStructureParser parser = new UniverSnapshotStructureParser(objectMapper);

    @Test
    void convertsWorkbookCellsIntoRecognitionCandidates() {
        var source = """
                {
                  "id":"workbook-1",
                  "snapshotFormatVersion":3,
                  "sheets":{
                    "sheet-1":{
                      "id":"sheet-1",
                      "name":"生产单",
                      "rowCount":100,
                      "columnCount":20,
                      "cellData":{
                        "1":{"0":{"v":"产品名称"},"1":{"v":"示例产品"}},
                        "2":{"2":{"v":12.5}}
                      }
                    }
                  }
                }
                """;

        var result = parser.parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.initialEditorSnapshot().path("id").asText()).isEqualTo("workbook-1");
        assertThat(result.structureSummary().path("candidateCellCount").asInt()).isEqualTo(3);
        assertThat(result.structureSummary().path("structureVersion").asInt()).isEqualTo(5);
        assertThat(result.structureSummary().path("candidateCells").get(0).path("address").asText())
                .isEqualTo("A2");
        assertThat(result.structureSummary().path("candidateCells").get(2).path("value").asDouble())
                .isEqualTo(12.5);
    }

    @Test
    void rejectsLegacySnapshotsInsteadOfRunningLegacyRecognitionRules() {
        var source = """
                {"id":"legacy","snapshotFormatVersion":2,"sheets":{}}
                """;
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("版本必须为 3");
    }
}
