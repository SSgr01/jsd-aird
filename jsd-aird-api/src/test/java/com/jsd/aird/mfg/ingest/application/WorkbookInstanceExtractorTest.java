package com.jsd.aird.mfg.ingest.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbookInstanceExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkbookInstanceExtractor extractor = new WorkbookInstanceExtractor(objectMapper);

    @Test
    void extractsScalarsAndAssemblesRepeatRecordsWithoutWritingLiteralWildcardKeys() throws Exception {
        var schema = objectMapper.readTree("""
                {"type":"object","x-jsd-field-model":{"fields":[]}}
                """);
        var mapping = objectMapper.readTree("""
                [
                  {"bindingId":"order","fieldCode":"ORDER.NO","dataPath":"/orderNo","mappingKind":"SCALAR","syncDirection":"TWO_WAY","locator":{"sheetId":"sheet-1","address":"B1"}},
                  {"bindingId":"parent","fieldCode":"MATERIALS","dataPath":"/materials","mappingKind":"REPEAT_REGION","syncDirection":"TWO_WAY","locator":{"sheetId":"sheet-1","dataRange":"A3:B5"}},
                  {"bindingId":"code","parentBindingId":"parent","fieldCode":"MATERIAL.CODE","dataPath":"/materials/*/code","mappingKind":"REPEAT_FIELD","syncDirection":"TWO_WAY","locator":{"sheetId":"sheet-1","logicalInputRange":"A3:A5","valueMode":"ARRAY_COLUMN"}},
                  {"bindingId":"qty","parentBindingId":"parent","fieldCode":"MATERIAL.QTY","dataPath":"/materials/*/qty","mappingKind":"REPEAT_FIELD","syncDirection":"TWO_WAY","locator":{"sheetId":"sheet-1","logicalInputRange":"B3:B5","valueMode":"ARRAY_COLUMN"}}
                ]
                """);
        var snapshot = objectMapper.readTree("""
                {"sheets":{"sheet-1":{"name":"Sheet1","cellData":{
                  "0":{"1":{"v":"PO-1"}},
                  "2":{"0":{"v":"A"},"1":{"v":10}},
                  "3":{"0":{"v":"B"},"1":{"v":20}}
                }}}}
                """);

        var result = extractor.extract(schema, mapping, snapshot);

        assertThat(result.data().toString()).isEqualTo(
                "{\"orderNo\":\"PO-1\",\"materials\":[{\"code\":\"A\",\"qty\":10},{\"code\":\"B\",\"qty\":20}]}" );
        assertThat(result.data().toString()).doesNotContain("\"*\"");
        assertThat(result.items()).hasSize(5);
    }
}
