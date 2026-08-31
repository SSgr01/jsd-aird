package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.StructuredDocumentCodec;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.infrastructure.QwenDocumentParsingConverter;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class StructuredDocumentCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructuredDocumentCodec codec = new StructuredDocumentCodec(objectMapper);

    @Test
    void buildsSemanticListsAndProjectsOnlyIncludedReviewNodes() {
        var initial = codec.initialize(List.of(
                block("heading-1", "检测报告", Map.of("level", 1)),
                block("list-item", "批号 LOT-1", Map.of("ordered", false)),
                block("list-item", "黏度 120", Map.of("ordered", false)),
                block("paragraph", "人工确认内容", Map.of())
        ));
        var content = initial.confirmedDocument().path("content");

        assertThat(content.get(0).path("type").asText()).isEqualTo("heading");
        assertThat(content.get(0).path("attrs").path("level").asInt()).isEqualTo(1);
        assertThat(content.get(1).path("type").asText()).isEqualTo("bulletList");
        assertThat(content.get(1).path("content")).hasSize(2);

        var excluded = UUID.fromString(content.get(1).path("content").get(0)
                .path("attrs").path("reviewNodeId").asText());
        var projection = codec.project(initial.confirmedDocument(), List.of(excluded));

        assertThat(projection.confirmedText()).contains("检测报告", "黏度 120", "人工确认内容")
                .doesNotContain("批号 LOT-1");
        assertThat(projection.nodes()).allSatisfy(node -> assertThat(node.sourceNodeKeys()).isNotEmpty());
    }

    @Test
    void allowsUserContentWithoutSourceAndRejectsDuplicateReviewIdentity() {
        var document = objectMapper.createObjectNode().put("type", "doc");
        var content = document.putArray("content");
        var id = UUID.randomUUID().toString();
        for (var text : List.of("A", "B")) {
            var paragraph = content.addObject().put("type", "paragraph");
            paragraph.putObject("attrs").put("reviewNodeId", id).put("origin", "user")
                    .putArray("sourceNodeKeys");
            paragraph.putArray("content").addObject().put("type", "text").put("text", text);
        }

        assertThatThrownBy(() -> codec.validate(document))
                .isInstanceOf(ApiException.class).hasMessageContaining("重复节点标识");
    }

    @Test
    void writesOcrCellSpansAndHeadersIntoTiptapTableNodes() {
        var blocks = new QwenDocumentParsingConverter().convert("""
                \\begin{tabular}{ccccc}
                 & A & B & C & D \\\\
                1 & \\multicolumn{4}{c}{材料基础信息} \\\\
                2 & 物料名称 & \\multicolumn{3}{l}{TEST-TPL-丙烯酸树脂} \\\\
                3 & 状态 & \\multicolumn{3}{l}{合格} \\\\
                \\end{tabular}
                """, 1);

        var table = codec.initialize(blocks).confirmedDocument().path("content").get(0);
        assertThat(table.path("type").asText()).isEqualTo("table");
        assertThat(table.path("content")).hasSize(3);
        var title = table.path("content").get(0).path("content").get(0);
        assertThat(title.path("type").asText()).isEqualTo("tableHeader");
        assertThat(title.path("attrs").path("colspan").asInt()).isEqualTo(4);
        var value = table.path("content").get(1).path("content").get(1);
        assertThat(value.path("type").asText()).isEqualTo("tableCell");
        assertThat(value.path("attrs").path("colspan").asInt()).isEqualTo(3);
    }

    @Test
    void keepsProviderBlocksWithoutGuessingPdfLayout() {
        var blocks = List.of(
                pdfBlock("产", 0.10, 0.20, 0.12, 0.24),
                pdfBlock("品", 0.125, 0.20, 0.145, 0.24),
                pdfBlock("说", 0.15, 0.20, 0.17, 0.24),
                pdfBlock("明", 0.175, 0.20, 0.195, 0.24),
                pdfBlock("书", 0.20, 0.20, 0.22, 0.24),
                pdfBlock("外", 0.10, 0.30, 0.12, 0.34),
                pdfBlock("观", 0.10, 0.35, 0.12, 0.39),
                new DocumentParser.TextBlock(1, "table-row", "物料 | UA-1117", null, null, null,
                        List.of(0.1, 0.45, 0.3, 0.45, 0.3, 0.49, 0.1, 0.49), null, null, null, Map.of())
        );

        var normalized = codec.normalizeBlocks(blocks);

        assertThat(normalized).hasSize(8);
        assertThat(normalized.get(0).content()).isEqualTo("产");
        assertThat(normalized.get(1).content()).isEqualTo("品");
        assertThat(normalized.get(5).content()).isEqualTo("外");
        assertThat(normalized.getLast().content()).isEqualTo("物料 | UA-1117");
        assertThat(normalized.get(0).bbox()).containsExactly(0.1, 0.20, 0.12, 0.20, 0.12, 0.24, 0.1, 0.24);
    }

    @Test
    void doesNotMergeSeparateNormalParagraphsWithoutShortPdfFragments() {
        var blocks = List.of(
                pdfBlock("产品说明书", 0.10, 0.20, 0.40, 0.24),
                pdfBlock("主要技术指标", 0.10, 0.30, 0.40, 0.34)
        );

        var normalized = codec.normalizeBlocks(blocks);
        assertThat(normalized).hasSize(2);
        assertThat(normalized.get(0).content()).isEqualTo("产品说明书");
        assertThat(normalized.get(1).content()).isEqualTo("主要技术指标");
    }

    @Test
    void truncatesHeadingStackAndLetsSiblingContentInheritTheCorrectSection() {
        var initial = codec.initialize(List.of(
                block("heading-1", "第一章", Map.of("level", 1)),
                block("heading-2", "参数", Map.of("level", 2)),
                block("paragraph", "参数正文", Map.of()),
                block("heading-2", "用途", Map.of("level", 2)),
                block("paragraph", "用途正文", Map.of()),
                block("heading-1", "第二章", Map.of("level", 1)),
                block("paragraph", "第二章正文", Map.of())
        ));

        var projection = codec.project(initial.confirmedDocument(), List.of());
        assertThat(projection.nodes().stream().filter(node -> node.text().equals("参数正文")).findFirst().orElseThrow()
                .headingPath()).containsExactly("第一章", "参数");
        assertThat(projection.nodes().stream().filter(node -> node.text().equals("用途正文")).findFirst().orElseThrow()
                .headingPath()).containsExactly("第一章", "用途");
        assertThat(projection.nodes().stream().filter(node -> node.text().equals("第二章正文")).findFirst().orElseThrow()
                .headingPath()).containsExactly("第二章");
    }

    @Test
    void parsesMineruInlineMathAndScriptsWhilePreservingRawLatex() {
        var latexRaw = "3 . 5 \\times 1 0 ^ { - 1 2 } \\mathrm { S } \\mathrm { c m } ^ { - 1 }";
        var input = "The conductivity is $" + latexRaw
                + "$ and the cited result is <sup>[</sup><sup>10</sup><sup>]</sup>.";

        var initialized = codec.initialize(List.of(block("paragraph", input, Map.of())));
        var paragraph = initialized.confirmedDocument().path("content").get(0);
        var inlineMath = java.util.stream.StreamSupport.stream(paragraph.path("content").spliterator(), false)
                .filter(node -> "inlineMath".equals(node.path("type").asText()))
                .findFirst().orElseThrow();

        assertThat(inlineMath.path("attrs").path("latexRaw").asText()).isEqualTo(latexRaw);
        assertThat(initialized.confirmedDocument().toString()).contains("superscript")
                .doesNotContain("<sup>", "</sup>");
        var evidence = codec.project(initialized.confirmedDocument(), List.of()).confirmedText();
        assertThat(evidence).contains("10⁻¹²", "cm⁻¹", "[10]").doesNotContain("\\mathrm", "<sup>");
    }

    @Test
    void givesBlockMathPriorityAndBuildsAValidTopLevelSequence() {
        var initialized = codec.initialize(List.of(block("paragraph", "Before $$x^2$$ after", Map.of())));
        var content = initialized.confirmedDocument().path("content");

        assertThat(content).extracting(node -> node.path("type").asText())
                .containsExactly("paragraph", "formula", "paragraph");
        assertThat(content.get(1).path("attrs").path("latexRaw").asText()).isEqualTo("x^2");
        assertThat(content.get(0).path("attrs").path("sourceNodeKeys").get(0).asText())
                .isEqualTo(content.get(1).path("attrs").path("sourceNodeKeys").get(0).asText())
                .isEqualTo(content.get(2).path("attrs").path("sourceNodeKeys").get(0).asText());
        assertThat(content.get(0).path("attrs").path("reviewNodeId").asText())
                .isNotEqualTo(content.get(1).path("attrs").path("reviewNodeId").asText());
        assertThat(content.get(1).path("attrs").path("reviewNodeId").asText())
                .isNotEqualTo(content.get(2).path("attrs").path("reviewNodeId").asText());
    }

    @Test
    void leavesCurrencyUnknownAnglesAndCodeUntouchedWhileRemovingOnlyKnownOrphanTokens() {
        var initialized = codec.initialize(List.of(
                block("paragraph", "The price ranges from $5 to $10; \\$5; <Fe>; abc</sup>def", Map.of()),
                block("code", "$x$ <sup>literal</sup> \\alpha", Map.of())
        ));
        var content = initialized.confirmedDocument().path("content");

        assertThat(content.get(0).toString()).doesNotContain("inlineMath", "</sup>");
        assertThat(codec.project(initialized.confirmedDocument(), List.of()).confirmedText())
                .contains("$5 to $10", "$5", "<Fe>", "abcdef", "$x$ <sup>literal</sup> \\alpha");
    }

    @Test
    void supportsBracketedMathAndNeverRepairsMalformedMarkupAsHtml() {
        var input = "\\[\\frac{a}{b}\\] then \\(H_{2}O\\) and unclosed $x; "
                + "A<sup>B<sub>C</sup>D; C:\\Users";
        var initialized = codec.initialize(List.of(block("paragraph", input, Map.of())));
        var content = initialized.confirmedDocument().path("content");

        assertThat(content).extracting(node -> node.path("type").asText())
                .containsExactly("formula", "paragraph");
        assertThat(content.get(0).path("attrs").path("latexRaw").asText()).isEqualTo("\\frac{a}{b}");
        assertThat(content.get(1).toString()).contains("inlineMath", "$x", "C:\\\\Users")
                .doesNotContain("<sup>", "<sub>");
        assertThat(codec.project(initialized.confirmedDocument(), List.of()).confirmedText())
                .contains("$\\frac{a}{b}$", "H₂O", "$x", "ABCD", "C:\\Users");
    }

    private DocumentParser.TextBlock block(String section, String text, Map<String, Object> attributes) {
        return new DocumentParser.TextBlock(null, section, text, null, null, null, List.of(), null,
                null, null, attributes);
    }

    private DocumentParser.TextBlock pdfBlock(String text, double left, double top, double right, double bottom) {
        return new DocumentParser.TextBlock(1, "paragraph", text, null, null, null,
                List.of(left, top, right, top, right, bottom, left, bottom), null, null, null, Map.of());
    }
}
