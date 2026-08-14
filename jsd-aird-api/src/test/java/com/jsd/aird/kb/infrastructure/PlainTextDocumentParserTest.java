package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PlainTextDocumentParserTest {

    private final PlainTextDocumentParser parser = new PlainTextDocumentParser();

    @Test
    void parsesParagraphsAndSupportsMissingContentType() {
        var content = "第一段\n第二行\n\n第二段";

        assertThat(parser.supports("研发记录.txt", null)).isTrue();
        var result = parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "研发记录.txt");

        assertThat(result.parserVersion()).isEqualTo("text-plain-v2");
        assertThat(result.blocks()).extracting(value -> value.content())
                .containsExactly("第一段\n第二行", "第二段");
    }

    @Test
    void parsesMarkdownIntoSemanticHeadingsListsTablesAndCode() {
        var content = """
                # 检测报告

                - 批号 LOT-1
                - 黏度 120

                | 项目 | 结果 |
                | --- | ---: |
                | 固含量 | 52% |

                ```sql
                select 1;
                ```
                """;

        var result = parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "报告.md");

        assertThat(result.parserVersion()).isEqualTo("text-markdown-gfm-v2");
        assertThat(result.blocks()).extracting(value -> value.section())
                .containsExactly("heading-1", "list-item", "list-item", "markdown-table-0",
                        "markdown-table-0", "code-block");
        assertThat(result.blocks().getFirst().attributes()).containsEntry("level", 1);
        assertThat(result.blocks().get(1).attributes()).containsEntry("ordered", false);
        assertThat(result.blocks().get(3).attributes()).containsEntry("header", true);
        assertThat(result.blocks().getLast().attributes()).containsEntry("language", "sql");
    }

    @Test
    void parsesQuotedCsvWithoutSplittingEmbeddedCommas() {
        var result = parser.parse(new ByteArrayInputStream("名称,说明\nA,\"一,二\"".getBytes(StandardCharsets.UTF_8)),
                "数据.csv");

        assertThat(result.blocks()).extracting(value -> value.content())
                .containsExactly("名称 | 说明", "A | 一,二");
    }
}
