package com.jsd.aird.ai.rnd.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.stream.Collectors;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSemanticsCatalogServiceTest {

    @Test
    void expandsCustomerRowsWithoutTurningExamplesIntoTrainingFacts() throws Exception {
        try (var workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            header.createCell(1).setCellValue("测试项目");
            header.createCell(2).setCellValue("测试方法");
            header.createCell(3).setCellValue("测试结果（例）");
            header.createCell(4).setCellValue("影响因素");
            var gloss = sheet.createRow(1);
            gloss.createCell(1).setCellValue("光泽");
            gloss.createCell(2).setCellValue("光泽度计");
            gloss.createCell(3).setCellValue("20°：80-90；60°：98-100；85°：102-103");
            gloss.createCell(4).setCellValue("膜厚、基材");
            var hardness = sheet.createRow(2);
            hardness.createCell(1).setCellValue("硬度");
            hardness.createCell(2).setCellValue("铅笔硬度计");
            hardness.createCell(3).setCellValue("3B、HB、2H");
            try (var bytes = new ByteArrayOutputStream()) {
                workbook.write(bytes);
                var service = new TestSemanticsCatalogService(null, null, null, new ObjectMapper());
                Method parse = TestSemanticsCatalogService.class.getDeclaredMethod("parse", byte[].class, String.class);
                parse.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<?> rows = (List<?>) parse.invoke(service, bytes.toByteArray(), "customer.xls");
                assertThat(rows).hasSize(4);
                assertThat(accessor(rows, "atomicName"))
                        .containsExactly("20°光泽", "60°光泽", "85°光泽", "铅笔硬度");
                assertThat(accessor(rows, "unit")).containsExactly("GU", "GU", "GU", null);
                assertThat(accessor(rows, "rawExample"))
                        .containsOnly("20°：80-90；60°：98-100；85°：102-103", "3B、HB、2H");
            }
        }
    }

    private static List<Object> accessor(List<?> rows, String name) throws Exception {
        return rows.stream().map(row -> {
            try {
                var method = row.getClass().getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(row);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toList());
    }
}
