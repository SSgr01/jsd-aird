package com.jsd.aird.shared.office;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class XlsxSnapshotReadTest {
    final XlsxStructureParser parser=new XlsxStructureParser(new ObjectMapper());
    @ParameterizedTest
    @ValueSource(strings={"/templates/unified/test.xlsx","/templates/unified/synthesis.xlsx","/unified-lab/test-example.xlsx"})
    void snapshotReadKeepsTheCompleteEditableWorkbookAndBindingMetadata(String resource) throws Exception {
        byte[] bytes;try(var input=getClass().getResourceAsStream(resource)){bytes=input.readAllBytes();}
        var full=(ObjectNode)parser.parseInstance(new ByteArrayInputStream(bytes)).snapshot();
        var compact=(ObjectNode)parser.parseSnapshot(new ByteArrayInputStream(bytes));
        full.remove("id");compact.remove("id");
        assertThat(compact).isEqualTo(full);
    }
    @Test void snapshotReadPreservesLaterSheetsFormulaCachesStylesAndPrintSettings() throws Exception {
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            for(int i=0;i<3;i++) {
                var sheet=book.createSheet("实验"+i);var row=sheet.createRow(0);
                row.createCell(0).setCellValue(i+1);row.createCell(1).setCellValue("5h");
                row.createCell(2).setCellFormula("A1*2");
                sheet.addMergedRegion(new CellRangeAddress(2,2,0,2));sheet.createFreezePane(1,1);
                sheet.setColumnWidth(1,5000);sheet.getPrintSetup().setLandscape(true);
            }
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();book.write(out);bytes=out.toByteArray();
        }
        var full=(ObjectNode)parser.parseInstance(new ByteArrayInputStream(bytes)).snapshot();
        var compact=(ObjectNode)parser.parseSnapshot(new ByteArrayInputStream(bytes));
        full.remove("id");compact.remove("id");
        assertThat(compact).isEqualTo(full);
        assertThat(compact.at("/sheets/sheet-3/cellData/0/2/f").asText()).isEqualTo("=A1*2");
        assertThat(compact.at("/sheets/sheet-3/cellData/0/2/v").asInt()).isEqualTo(6);
    }
}
