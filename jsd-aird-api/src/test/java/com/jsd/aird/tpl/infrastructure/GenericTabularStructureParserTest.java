package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class GenericTabularStructureParserTest {

    private final GenericTabularStructureParser parser = new GenericTabularStructureParser();

    @Test
    void parsesUtf8BomCsvAndSuggestsHeaderAndDataRows() {
        var csv = "\uFEFF说明,,,\n物料编码,物料名称,粘度,单位\nM-001,树脂 A,12,cps\n";

        var result = parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "materials.csv");
        var sheet = result.sheets().getFirst();

        assertThat(result.format()).isEqualTo("CSV");
        assertThat(sheet.sheetName()).isEqualTo("CSV");
        assertThat(sheet.headerCandidates()).contains(2);
        assertThat(sheet.suggestedHeaderRow()).isEqualTo(2);
        assertThat(sheet.suggestedDataStartRow()).isEqualTo(3);
        assertThat(sheet.rows().get(2)).containsExactly("M-001", "树脂 A", "12", "cps");
    }

    @Test
    void preservesQuotedCsvValues() {
        var csv = "名称,备注\n树脂 A,\"含逗号,需复核\"\n";

        var result = parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "materials.csv");

        assertThat(result.sheets().getFirst().rows().get(1)).containsExactly("树脂 A", "含逗号,需复核");
    }
}
