package com.jsd.aird.mfg.ingest.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbookSnapshotValueWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkbookSnapshotValueWriter writer = new WorkbookSnapshotValueWriter(objectMapper);

    @Test
    void fillsScalarAndRepeatLeafIntoClonedTemplateSnapshot() throws Exception {
        var snapshot = objectMapper.readTree("""
                {"sheets":{"sheet-1":{"name":"Sheet1","cellData":{}}}}
                """);
        var mapping = objectMapper.readTree("""
                [
                  {"dataPath":"/orderNo","syncDirection":"TWO_WAY","locator":{"sheetId":"sheet-1","address":"B1"}},
                  {"dataPath":"/materials/*/code","syncDirection":"TWO_WAY","locator":{"sheetId":"sheet-1","logicalInputRange":"A3:A5","valueMode":"ARRAY_COLUMN"}}
                ]
                """);
        var data = objectMapper.readTree("""
                {"orderNo":"PO-1","materials":[{"code":"A"},{"code":"B"}]}
                """);

        var result = writer.write(snapshot, mapping, data);

        assertThat(result.at("/sheets/sheet-1/cellData/0/1/v").asText()).isEqualTo("PO-1");
        assertThat(result.at("/sheets/sheet-1/cellData/2/0/v").asText()).isEqualTo("A");
        assertThat(result.at("/sheets/sheet-1/cellData/3/0/v").asText()).isEqualTo("B");
        assertThat(snapshot.at("/sheets/sheet-1/cellData/0/1/v").isMissingNode()).isTrue();
    }
}
