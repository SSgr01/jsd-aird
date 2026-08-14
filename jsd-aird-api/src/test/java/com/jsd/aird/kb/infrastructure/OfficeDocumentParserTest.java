package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class OfficeDocumentParserTest {

    private final OfficeDocumentParser parser = new OfficeDocumentParser();

    @Test
    void parsesDocxHierarchyAndTableWithStablePaths() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var document = new XWPFDocument()) {
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("实验报告");
            var paragraph = document.createParagraph();
            paragraph.createRun().setText("批号 LOT-1");
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(1).setText("结果");
            document.write(bytes);
        }

        var parsed = parser.parse(new ByteArrayInputStream(bytes.toByteArray()), "report.docx");

        assertThat(parsed.parserVersion()).isEqualTo("office-docx-v2");
        assertThat(parsed.blocks()).extracting(value -> value.section())
                .containsExactly("heading-1", "paragraph", "table-row");
        assertThat(parsed.blocks()).extracting(value -> value.paragraphId())
                .containsExactly("p-0", "p-1", "table-0-row-0");
    }

    @Test
    void keepsSmallWorkbookInlineAndMovesLargeSheetToVirtualTable() throws Exception {
        var small = workbookBytes(workbook -> {
            var sheet = workbook.createSheet("配方");
            sheet.createRow(0).createCell(0).setCellValue("原料");
            sheet.getRow(0).createCell(1).setCellValue("比例");
            sheet.createRow(1).createCell(0).setCellValue("A");
            sheet.getRow(1).createCell(1).setCellValue(25);
        });
        var inline = parser.parse(new ByteArrayInputStream(small), "formula.xlsx");

        assertThat(inline.sourceTables()).isEmpty();
        assertThat(inline.blocks()).extracting(value -> value.cellRange())
                .containsExactly("A1:B1", "A2:B2");

        var large = workbookBytes(workbook -> {
            var sheet = workbook.createSheet("明细");
            for (var row = 0; row < 501; row++) sheet.createRow(row).createCell(0).setCellValue("R" + row);
        });
        var virtual = parser.parse(new ByteArrayInputStream(large), "large.xlsx");

        assertThat(virtual.blocks()).singleElement().satisfies(block -> {
            assertThat(block.section()).isEqualTo("data-table-ref");
            assertThat(block.attributes()).containsEntry("rowCount", 501).containsKey("sheetKey");
        });
        assertThat(virtual.sourceTables()).singleElement().satisfies(table -> {
            assertThat(table.rowCount()).isEqualTo(501);
            assertThat(table.cells()).hasSize(501);
        });
    }

    private byte[] workbookBytes(java.util.function.Consumer<Workbook> setup) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var workbook = new XSSFWorkbook()) {
            setup.accept(workbook);
            workbook.write(bytes);
        }
        return bytes.toByteArray();
    }
}
