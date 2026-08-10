package com.jsd.aird.mfg.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecordProjectionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordProjectionService service = new RecordProjectionService(objectMapper);

    @Test
    void projectsOneRootValueAndMultipleDetailAndMatrixRecords() throws Exception {
        var schema = objectMapper.readTree("""
                {
                  "type":"object",
                  "x-jsd-field-model":{"fields":[
                    {"id":"order","name":"单号","kind":"SCALAR","fieldCode":"ORDER.NO","dataPath":"/orderNo","valueType":"string"},
                    {"id":"materials","name":"明细","kind":"ROW_TABLE","fieldCode":"MATERIALS","dataPath":"/materials"},
                    {"id":"material-code","parentFieldId":"materials","name":"物料","kind":"SCALAR","fieldCode":"MATERIAL.CODE","dataPath":"/materials/*/code","valueType":"string"},
                    {"id":"matrix","name":"质检","kind":"MATRIX","fieldCode":"QUALITY","dataPath":"/quality"},
                    {"id":"ph","parentFieldId":"matrix","name":"pH","kind":"SCALAR","fieldCode":"QUALITY.PH","dataPath":"/quality/*/ph","valueType":"number"},
                    {"id":"local","name":"临时备注","kind":"SCALAR","fieldCode":"ORDER_LOCAL.X.NOTE","fieldOrigin":"ORDER_LOCAL","dataPath":"/note","valueType":"string"}
                  ]}
                }
                """);
        var data = objectMapper.readTree("""
                {
                  "orderNo":"PO-1",
                  "note":"local",
                  "materials":[{"code":"A"},{"code":"B"}],
                  "quality":[
                    {"_member":{"slotId":"column-E","label":"样品1"},"ph":7.2},
                    {"_member":{"slotId":"column-F","label":"样品2"},"ph":7.4}
                  ]
                }
                """);

        var result = service.compile(UUID.randomUUID(), UUID.randomUUID(), schema, data);

        assertThat(result.collections()).hasSize(4);
        assertThat(result.collections()).extracting(item -> item.recordKind())
                .containsExactly("DETAIL", "DETAIL", "MATRIX", "MATRIX");
        assertThat(result.collections().get(2).recordKey()).isEqualTo("column-E");
        assertThat(result.values()).extracting(item -> item.fieldCode())
                .containsExactly("ORDER.NO", "MATERIAL.CODE", "MATERIAL.CODE", "QUALITY.PH", "QUALITY.PH");
    }
}
