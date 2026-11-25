package com.jsd.aird.shared.office;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class XlsxStyleNamespaceCompatibilityTest {
    @Test void readsArbitraryDxfPrefixesWithoutDroppingStylesOrChangingSourceBytes() throws Exception {
        byte[] source;
        try(var book=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            book.createSheet("实验").createRow(0).createCell(0).setCellValue("5h");
            book.write(output);source=output.toByteArray();
        }
        var modified=new ByteArrayOutputStream();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(source));var out=new ZipOutputStream(modified)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                byte[] bytes=zip.readAllBytes();
                if(entry.getName().equals("xl/styles.xml")) {
                    var xml=new String(bytes,StandardCharsets.UTF_8);
                    xml=xml.replace("</styleSheet>","<custom:dxfs xmlns:custom=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"1\"><custom:dxf><custom:font><custom:b/></custom:font></custom:dxf></custom:dxfs><tableStyles count=\"1\"><tableStyle name=\"OriginalStyle\" count=\"1\"><tableStyleElement type=\"wholeTable\" dxfId=\"0\"/></tableStyle></tableStyles></styleSheet>");
                    bytes=xml.getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(entry.getName()));out.write(bytes);out.closeEntry();
            }
        }
        source=modified.toByteArray();var original=source.clone();
        try(var workbook=IsoDateXlsxReader.open(new ByteArrayInputStream(source))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("5h");
            var table=workbook.getStylesSource().getTableStyle("OriginalStyle");
            assertThat(table).isNotNull();
            assertThat(table.getStyle(org.apache.poi.ss.usermodel.TableStyleType.wholeTable).getFontFormatting().isBold()).isTrue();
        }
        assertThat(source).isEqualTo(original);
    }
}
