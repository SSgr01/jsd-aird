package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.jsd.aird.kb.domain.DocumentParser;
import org.junit.jupiter.api.Test;

class QwenDocumentParsingConverterTest {

    private final QwenDocumentParsingConverter converter = new QwenDocumentParsingConverter();

    @Test
    void convertsWhitelistedLatexAndDegradesUnsupportedCommandsToText() {
        var blocks = converter.convert("""
                \\section{检测报告}
                \\begin{enumerate}
                \\item 第一项
                \\item 第二项
                \\end{enumerate}
                \\begin{tabular}{ll}
                项目 & 结果 \\\\
                黏度 & 120 \\\\
                \\end{tabular}
                \\customcommand{保留文字}
                \\include{evil.tex}
                """, 3);

        assertThat(blocks).extracting(value -> value.section())
                .containsExactly("heading-1", "list-item", "list-item", "table-row", "table-row", "paragraph");
        assertThat(blocks.getFirst().attributes()).containsEntry("level", 1);
        assertThat(blocks.get(1).attributes()).containsEntry("ordered", true);
        assertThat(blocks.getLast().content()).contains("customcommand", "保留文字").doesNotContain("evil.tex");
        assertThat(blocks).allSatisfy(block -> {
            assertThat(block.pageNo()).isEqualTo(3);
            assertThat(block.bbox()).isEmpty();
        });
    }

    @Test
    void restoresMaterialSpreadsheetTableAndRemovesInferredExcelAxes() {
        var converted = converter.convertDetailed("""
                \\begin{longtable}{ccccc}
                 & A & B & C & D \\\\
                1 & \\multicolumn{4}{c}{材料基础信息} \\\\
                2 & 物料名称 & \\multicolumn{3}{l}{TEST-TPL-丙烯酸树脂} \\\\
                3 & 牌号 & \\multicolumn{3}{l}{A-2026} \\\\
                4 & 供应商 & \\multicolumn{3}{l}{杰事达供应商} \\\\
                5 & 密度 & \\multicolumn{3}{l}{1.05} \\\\
                6 & 检验日期 & \\multicolumn{3}{l}{2026-08-11} \\\\
                7 & \\multicolumn{4}{l}{} \\\\
                8 & \\multicolumn{4}{l}{备注} \\\\
                9 & 适用温度 & \\multicolumn{3}{l}{25 \\textdegree C} \\\\
                10 & 状态 & \\multicolumn{3}{l}{合格} \\\\
                \\end{longtable}
                """, 1);

        assertThat(converted.reliableTable()).isTrue();
        assertThat(converted.blocks()).hasSize(10);
        assertThat(converted.blocks()).extracting(DocumentParser.TextBlock::content)
                .startsWith("材料基础信息", "物料名称 | TEST-TPL-丙烯酸树脂")
                .doesNotContain("A | B | C | D", "1 | 材料基础信息");
        assertThat(converted.blocks()).allSatisfy(block ->
                assertThat(block.attributes()).containsEntry("spreadsheetAxesRemoved", true));

        var titleCells = cells(converted.blocks().getFirst());
        assertThat(titleCells).hasSize(1);
        assertThat(titleCells.getFirst()).containsEntry("columnSpan", 4).containsEntry("header", true);
        var materialCells = cells(converted.blocks().get(1));
        assertThat(materialCells).hasSize(2);
        assertThat(materialCells.getFirst()).containsEntry("header", true);
        assertThat(materialCells.get(1)).containsEntry("columnSpan", 3);
    }

    @Test
    void preservesMultirowInsideTabularx() {
        var converted = converter.convertDetailed("""
                \\begin{tabularx}{\\textwidth}{lX}
                \\multirow{2}{*}{状态} & 合格 \\\\
                 & 已复核 \\\\
                \\end{tabularx}
                """, 2);

        assertThat(converted.reliableTable()).isTrue();
        assertThat(cells(converted.blocks().getFirst()).getFirst())
                .containsEntry("text", "状态").containsEntry("rowSpan", 2);
    }

    @Test
    void parsesOnlyWhitelistedHtmlTableStructure() {
        var converted = converter.convertTableHtml("""
                ```html
                <script>fetch('https://evil.example')</script>
                <table onclick="alert(1)">
                  <tr><th rowspan="2">项目</th><th>结果</th></tr>
                  <tr><td><a href="https://evil.example">合格</a></td></tr>
                </table>
                ```
                """, 1);

        assertThat(converted.reliableTable()).isTrue();
        assertThat(cells(converted.blocks().getFirst()).getFirst())
                .containsEntry("text", "项目").containsEntry("rowSpan", 2).containsEntry("header", true);
        assertThat(converted.blocks()).extracting(DocumentParser.TextBlock::content)
                .allSatisfy(text -> assertThat(text).doesNotContain("fetch", "onclick", "evil.example"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cells(DocumentParser.TextBlock block) {
        return (List<Map<String, Object>>) block.attributes().get("cells");
    }
}
