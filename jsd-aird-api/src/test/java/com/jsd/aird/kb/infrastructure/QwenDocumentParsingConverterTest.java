package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

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
}
