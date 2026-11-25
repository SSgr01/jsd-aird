package com.jsd.aird.shared.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.infrastructure.GenericTabularStructureParser;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IsoDateXlsxReaderTest {
    private static final String DATE_TIME = "yyyy-mm-dd hh:mm:ss.000";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesDateTimeStylesFormulaAndSourceBytes(boolean date1904) throws Exception {
        var source = fixture("2026-11-24T13:14:15.123", date1904, DATE_TIME, false);
        var original = source.clone();
        try (var workbook = IsoDateXlsxReader.open(new ByteArrayInputStream(source))) {
            var cell = workbook.getSheetAt(0).getRow(0).getCell(0);
            assertThat(workbook.isDate1904()).isEqualTo(date1904);
            assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cell.getNumericCellValue()).isCloseTo(
                    DateUtil.getExcelDate(LocalDateTime.of(2026, 11, 24, 13, 14, 15, 123_000_000), date1904), within(1e-9));
            assertThat(new DataFormatter().formatCellValue(cell)).isEqualTo("2026-11-24 13:14:15.123");
            assertThat(cell.getCellStyle().getFillForegroundColor()).isEqualTo((short) 42);
            assertThat(cell.getCellStyle().getDataFormatString()).isEqualTo(DATE_TIME);
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(2).getCellFormula()).isEqualTo("B1*2");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(2).getNumericCellValue()).isEqualTo(14);
        }
        assertThat(source).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void templateAndDataImportReadIsoDatesUsingTheirOwnDateBases(boolean date1904) throws Exception {
        var source = fixture("2026-11-24", date1904, "yyyy-mm-dd", false);
        var parsed = new XlsxStructureParser(new ObjectMapper()).parseInstance(new ByteArrayInputStream(source));
        var cell = parsed.snapshot().at("/sheets/sheet-1/cellData/0/0");
        assertThat(cell.path("t").asInt()).isEqualTo(2);
        assertThat(cell.path("v").asDouble()).isEqualTo(DateUtil.getExcelDate(LocalDateTime.of(2026, 11, 24, 0, 0)));
        var table = new GenericTabularStructureParser().parse(new ByteArrayInputStream(source), "dates.xlsx");
        assertThat(table.sheets().getFirst().rows().getFirst().getFirst()).isEqualTo("2026-11-24");
        var layout = table.sheets().getFirst().layoutIr().path("cells").get(0);
        assertThat(layout.path("cellType").asText()).isEqualTo("NUMERIC");
        assertThat(layout.path("rawNumericValue").asDouble()).isEqualTo(
                DateUtil.getExcelDate(LocalDateTime.of(2026, 11, 24, 0, 0), date1904));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-24T23:30:00Z", "2026-11-24T23:30:00+08:00", "2026-11-24T23:30:00-06:00"})
    void doesNotShiftExplicitWallTimeWithServerTimezone(String iso) throws Exception {
        try (var workbook = IsoDateXlsxReader.open(new ByteArrayInputStream(fixture(iso, false, DATE_TIME, false)))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getLocalDateTimeCellValue())
                    .isEqualTo(LocalDateTime.of(2026, 11, 24, 23, 30));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeOnlyCellsKeepTheirFractionalDay(boolean date1904) throws Exception {
        var source = fixture("12:30:15.250", date1904, null, false);
        var result = new XlsxStructureParser(new ObjectMapper()).parseInstance(new ByteArrayInputStream(source));
        assertThat(result.snapshot().at("/sheets/sheet-1/cellData/0/0/v").asDouble())
                .isCloseTo((12 * 3600 + 30 * 60 + 15.25) / 86400, within(1e-12));
        var table = new GenericTabularStructureParser().parse(new ByteArrayInputStream(source), "time.xlsx");
        assertThat(table.sheets().getFirst().rows().getFirst().getFirst()).isEqualTo("12:30:15.250");
    }

    @Test
    void firstDayOf1904EpochStillHasCorrectEditorDate() throws Exception {
        var source = fixture("1904-01-01T06:00:00", true, DATE_TIME, false);
        var result = new XlsxStructureParser(new ObjectMapper()).parseInstance(new ByteArrayInputStream(source));
        assertThat(result.snapshot().at("/sheets/sheet-1/cellData/0/0/v").asDouble()).isEqualTo(1462.25);
    }

    @Test
    void addsDateFormatForIsoCellsWithGeneralFormatWithoutLosingTheirStyle() throws Exception {
        try (var workbook = IsoDateXlsxReader.open(new ByteArrayInputStream(fixture("2026-11-24", false, null, false)))) {
            var cell = workbook.getSheetAt(0).getRow(0).getCell(0);
            assertThat(new DataFormatter().formatCellValue(cell)).isEqualTo("2026-11-24");
            assertThat(cell.getCellStyle().getFillForegroundColor()).isEqualTo((short) 42);
            // Changing the date style must not turn other General cells into dates.
            assertThat(new DataFormatter().formatCellValue(workbook.getSheetAt(0).getRow(0).getCell(1))).isEqualTo("7");
        }
    }

    @Test
    void preservesFormulaWithIsoDateCachedResult() throws Exception {
        var source = fixture("2026-11-24", false, "yyyy-mm-dd", true);
        var result = new XlsxStructureParser(new ObjectMapper()).parseInstance(new ByteArrayInputStream(source));
        var cell = result.snapshot().at("/sheets/sheet-1/cellData/0/0");
        assertThat(cell.path("f").asText()).isEqualTo("=DATE(2026,11,24)");
        assertThat(cell.path("v").asDouble()).isEqualTo(DateUtil.getExcelDate(LocalDateTime.of(2026, 11, 24, 0, 0)));
    }

    @Test
    void emptyIsoDateIsBlankAndMalformedDateIdentifiesTheCell() throws Exception {
        try (var workbook = IsoDateXlsxReader.open(new ByteArrayInputStream(fixture("", false, null, false)))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getCellType()).isEqualTo(CellType.BLANK);
        }
        var invalid = fixture("2026-02-30T00:00:00", false, DATE_TIME, false);
        assertThatThrownBy(() -> IsoDateXlsxReader.open(new ByteArrayInputStream(invalid)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A1").hasMessageContaining("2026-02-30");
    }

    private byte[] fixture(String iso, boolean date1904, String numberFormat, boolean formula) throws Exception {
        var original = new ByteArrayOutputStream();
        int styleIndex;
        try (var workbook = new XSSFWorkbook()) {
            workbook.getCTWorkbook().getWorkbookPr().setDate1904(date1904);
            var row = workbook.createSheet("Dates").createRow(0);
            var cell = row.createCell(0);
            cell.setCellValue("placeholder");
            var style = workbook.createCellStyle();
            style.setFillForegroundColor((short) 42);
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            if (numberFormat != null) style.setDataFormat(workbook.createDataFormat().getFormat(numberFormat));
            cell.setCellStyle(style);
            styleIndex = style.getIndex();
            row.createCell(1).setCellValue(7);
            row.getCell(1).setCellStyle(style);
            row.createCell(2).setCellFormula("B1*2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(original);
        }
        var result = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(original.toByteArray())); var output = new ZipOutputStream(result)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                var bytes = input.readAllBytes();
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    var xml = new String(bytes, StandardCharsets.UTF_8);
                    var replacement = "<c r=\"A1\" s=\"" + styleIndex + "\" t=\"d\">"
                            + (formula ? "<f>DATE(2026,11,24)</f>" : "") + "<v>" + iso + "</v></c>";
                    bytes = xml.replaceAll("(?s)<c\\b[^>]*r=\"A1\"[^>]*>.*?</c>", replacement).getBytes(StandardCharsets.UTF_8);
                }
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write(bytes);
                output.closeEntry();
            }
        }
        return result.toByteArray();
    }
}
