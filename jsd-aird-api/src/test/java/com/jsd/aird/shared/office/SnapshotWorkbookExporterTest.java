package com.jsd.aird.shared.office;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SnapshotWorkbookExporterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SnapshotWorkbookExporter exporter = new SnapshotWorkbookExporter(objectMapper);

    @Test
    void preservesSnapshotCellsAndWritesScalarAndArrayValues() throws Exception {
        var snapshot = objectMapper.readTree("""
                {"sheetOrder":["sheet-1"],"sheets":{"sheet-1":{"name":"Sheet1","cellData":{"0":{"0":{"v":"订单号"}},"2":{"0":{"v":"旧"}}},"mergeData":[],"rowData":{},"columnData":{}}},"styles":{}}
                """);
        var mapping = objectMapper.readTree("""
                [{"bindingId":"order","dataPath":"/orderNo","syncDirection":"TWO_WAY","locator":{"sheetId":"sheet-1","address":"B1"}},
                 {"bindingId":"items","dataPath":"/items/*/code","syncDirection":"TWO_WAY","locator":{"sheetId":"sheet-1","logicalInputRange":"A3:A4","valueMode":"ARRAY_COLUMN"}}]
                """);
        var data = objectMapper.readTree("{\"orderNo\":\"PO-1\",\"items\":[{\"code\":\"A\"},{\"code\":\"B\"}]}");

        var result = exporter.export(snapshot, mapping, data,
                new SnapshotWorkbookExporter.Manifest("v1", "s", "m", "DRAFT", null));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result.content()))) {
            assertThat(workbook.getSheet("Sheet1").getRow(0).getCell(0).getStringCellValue()).isEqualTo("订单号");
            assertThat(workbook.getSheet("Sheet1").getRow(0).getCell(1).getStringCellValue()).isEqualTo("PO-1");
            assertThat(workbook.getSheet("Sheet1").getRow(2).getCell(0).getStringCellValue()).isEqualTo("A");
            assertThat(workbook.getSheet("Sheet1").getRow(3).getCell(0).getStringCellValue()).isEqualTo("B");
            assertThat(workbook.getSheet(SnapshotWorkbookExporter.MANIFEST_SHEET)).isNotNull();
        }
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void reportsMissingLocatorWithoutFailingExport() throws Exception {
        var snapshot = objectMapper.readTree("{\"sheetOrder\":[],\"sheets\":{},\"styles\":{}}");
        var mapping = objectMapper.readTree("[{\"bindingId\":\"missing\",\"dataPath\":\"/name\",\"locator\":{}}]");
        var result = exporter.export(snapshot, mapping, objectMapper.createObjectNode(), null);
        assertThat(result.warnings()).extracting(SnapshotWorkbookExporter.Warning::code).contains("BINDING_MISSING");
    }
}
