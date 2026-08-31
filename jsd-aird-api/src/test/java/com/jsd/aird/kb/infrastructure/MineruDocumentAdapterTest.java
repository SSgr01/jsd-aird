package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.StructuredDocumentCodec;
import org.junit.jupiter.api.Test;

class MineruDocumentAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void convertsMineruHeadingsTablesAndNormalizedCoordinates() {
        var content = """
                [{"type":"text","text":"产 品 说 明 书","text_level":2,"bbox":[10,20,110,40],"page_idx":0},
                 {"type":"text","text":"UA-1117 是一支树脂","bbox":[10,50,220,70],"page_idx":0},
                 {"type":"table","table_body":"<table><tr><td>项目</td><td>结果</td></tr><tr><td>外观</td><td>乳白液体</td></tr></table>","bbox":[10,80,210,180],"page_idx":0}]
                """;
        var layout = """
                {"pdf_info":[{"page_idx":0,"page_size":[400,800],"rotation":90}]}
                """;
        var parsed = new MineruDocumentAdapter(mapper).parsePrecise(zip(
                "x_content_list.json", content, "layout.json", layout), "test.pdf");

        assertThat(parsed.blocks()).extracting(value -> value.section())
                .contains("heading-2", "paragraph", "mineru-table-row");
        assertThat(parsed.blocks().getFirst().bbox()).containsExactly(0.01, 0.02, 0.11, 0.02,
                0.11, 0.04, 0.01, 0.04);
        assertThat(parsed.blocks().getFirst().attributes()).containsEntry("pageWidth", 400.0)
                .containsEntry("pageHeight", 800.0).containsEntry("rotation", 90);
        assertThat(parsed.blocks().stream().filter(value -> value.section().equals("mineru-table-row")))
                .allSatisfy(row -> {
                    assertThat(row.bbox()).containsExactly(0.01, 0.08, 0.21, 0.08,
                            0.21, 0.18, 0.01, 0.18);
                    assertThat(row.attributes()).containsEntry("locatorAccuracy", "APPROXIMATE");
                });
        var structured = new StructuredDocumentCodec(mapper).initialize(parsed.blocks()).confirmedDocument();
        var table = structured.path("content").get(structured.path("content").size() - 1);
        assertThat(table.path("type").asText()).isEqualTo("table");
        assertThat(table.path("content").get(0).path("content").get(0).path("type").asText())
                .isEqualTo("tableHeader");
    }

    @Test
    void fallsBackToV2AndKeepsReviewOnlyNoise() {
        var content = """
                [{"page_idx":0,"content":[
                  {"type":"page_header","text":"产品说明书","bbox":[0,0,1000,40]},
                  {"type":"code","code_body":"UA-1117 = 400-700cps","bbox":[20,60,800,120]},
                  {"type":"chart","chart_caption":["粘度曲线"],"chart_footnote":["25℃"],"ocr_text":"400-700cps","img_path":"images/chart.png","bbox":[20,150,800,700]}
                ]}]
                """;
        var parsed = new MineruDocumentAdapter(mapper).parsePrecise(zip(
                "content_list_v2.json", content, "layout.json", "{\"pdf_info\":[]}"), "test.pdf");

        assertThat(parsed.metadata()).containsEntry("contentListVersion", 2);
        assertThat(parsed.blocks()).extracting(value -> value.section()).contains("header", "code", "chart");
        assertThat(parsed.blocks().getFirst().attributes()).containsEntry("searchable", false);
        assertThat(parsed.blocks().getLast().attributes()).containsEntry("resultEntryPath", "images/chart.png")
                .containsEntry("caption", "粘度曲线").containsEntry("footnote", "25℃")
                .containsEntry("ocrText", "400-700cps");
    }

    @Test
    void mapsOfficialV2PageArraysAndProjectsChartsAsReviewImages() throws Exception {
        var content = """
                [
                  [
                    {"type":"title","content":{"title_content":[{"type":"text","content":"研究结果"}],"level":2},"bbox":[20,20,600,70]},
                    {"type":"paragraph","content":{"paragraph_content":[{"type":"text","content":"第一"},{"type":"text","content":"页正文"}]},"bbox":[20,80,900,150]},
                    {"type":"chart","content":{"image_source":{"path":"images/chart.jpg"},"content":"PEGDA charge density","chart_caption":[{"type":"text","content":"Figure 3 电荷密度"}],"chart_footnote":[{"type":"text","content":"与 PVC 摩擦 40 s"}]},"bbox":[100,180,900,760]}
                  ],
                  [
                    {"type":"image","content":{"image_source":{"path":"images/photo.jpg"},"image_caption":[{"type":"text","content":"实验装置"}],"image_footnote":[]},"bbox":[50,40,450,300]},
                    {"type":"table","content":{"html":"<table><tr><td>项目</td><td>值</td></tr><tr><td>时间</td><td>40 s</td></tr></table>","table_caption":[{"type":"text","content":"测试条件"}],"table_footnote":[]},"bbox":[50,320,900,600]},
                    {"type":"equation_interline","content":{"math_content":"q = 7.4 μC m^{-2}"},"bbox":[50,620,700,670]},
                    {"type":"algorithm","content":{"algorithm_content":[{"type":"text","content":"Step 1: initialize"}]},"bbox":[50,680,800,730]},
                    {"type":"index","content":{"list_items":[{"item_content":[{"type":"text","content":"Figure 3"}]},{"item_content":[{"type":"text","content":"Table 1"}]}]},"bbox":[50,740,800,820]},
                    {"type":"page_footer","content":{"page_footer_content":[{"type":"text","content":"期刊页脚"}]},"bbox":[0,950,1000,1000]},
                    {"type":"future_semantic_block","content":{"future_semantic_block_content":[{"type":"text","content":"保留供审核"}]},"bbox":[0,900,1000,940]}
                  ]
                ]
                """;
        var layout = """
                {"pdf_info":[
                  {"page_idx":0,"page_size":[600,800],"rotation":0},
                  {"page_idx":1,"page_size":[600,800],"rotation":0}
                ]}
                """;
        var archive = Path.of(System.getProperty("java.io.tmpdir"), "mineru-v2-" + UUID.randomUUID() + ".zip");
        var resultFileId = UUID.randomUUID();
        var chartAssetId = UUID.randomUUID();
        var photoAssetId = UUID.randomUUID();
        Files.write(archive, zip("content_list_v2.json", content, "layout.json", layout));
        try {
            var parsed = new MineruDocumentAdapter(mapper).parsePrecise(archive, "paper.pdf", resultFileId,
                    Map.of("images/chart.jpg", chartAssetId, "images/photo.jpg", photoAssetId));

            var chart = parsed.blocks().stream().filter(block -> "chart".equals(block.section())).findFirst().orElseThrow();
            assertThat(chart.pageNo()).isEqualTo(1);
            assertThat(chart.bbox()).containsExactly(0.1, 0.18, 0.9, 0.18, 0.9, 0.76, 0.1, 0.76);
            assertThat(chart.attributes()).containsEntry("visualType", "CHART")
                    .containsEntry("resultEntryPath", "images/chart.jpg")
                    .containsEntry("assetFileId", chartAssetId.toString())
                    .containsEntry("resultFileId", resultFileId.toString())
                    .containsEntry("caption", "Figure 3 电荷密度")
                    .containsEntry("footnote", "与 PVC 摩擦 40 s")
                    .containsEntry("ocrText", "PEGDA charge density");
            assertThat(parsed.blocks()).anySatisfy(block -> {
                assertThat(block.pageNo()).isEqualTo(2);
                assertThat(block.section()).isEqualTo("image");
                assertThat(block.attributes()).containsEntry("visualType", "IMAGE")
                        .containsEntry("assetFileId", photoAssetId.toString());
            });
            assertThat(parsed.blocks()).extracting(block -> block.section())
                    .contains("heading-2", "paragraph", "mineru-table-row", "formula", "code", "listItem", "footer");
            assertThat(parsed.blocks().stream().filter(block -> "footer".equals(block.section())).findFirst().orElseThrow()
                    .attributes()).containsEntry("searchable", false);
            assertThat(parsed.blocks().stream()
                    .filter(block -> "future_semantic_block".equals(block.attributes().get("mineruType")))
                    .findFirst().orElseThrow().attributes()).containsEntry("searchable", false);

            var initialized = new StructuredDocumentCodec(mapper).initialize(parsed.blocks());
            assertThat(initialized.sourceNodes()).anySatisfy(node -> assertThat(node.nodeType()).isEqualTo("chart"));
            var reviewChart = java.util.stream.StreamSupport.stream(
                            initialized.confirmedDocument().path("content").spliterator(), false)
                    .filter(node -> node.path("attrs").path("visualType").asText().equals("CHART"))
                    .findFirst().orElseThrow();
            assertThat(reviewChart.path("type").asText()).isEqualTo("image");
            assertThat(reviewChart.path("attrs").path("assetFileId").asText()).isEqualTo(chartAssetId.toString());
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void rejectsZipPathTraversalBeforeReadingContent() {
        assertThatThrownBy(() -> new MineruDocumentAdapter(mapper).parsePrecise(
                zip("../content_list.json", "[]", "layout.json", "{}"), "test.pdf"))
                .isInstanceOf(MineruException.class).hasMessageContaining("路径穿越");
    }

    @Test
    void rejectsDuplicateEntriesAndSuspiciousCompressionRatios() {
        assertThatThrownBy(() -> new MineruDocumentAdapter(mapper).parsePrecise(
                zip("content_list.json", "[]", "CONTENT_LIST.JSON", "[]"), "test.pdf"))
                .isInstanceOf(MineruException.class).hasMessageContaining("重复 entry");

        var repetitive = "[{\"type\":\"text\",\"text\":\"" + "树".repeat(20_000) + "\"}]";
        assertThatThrownBy(() -> new MineruDocumentAdapter(mapper).parsePrecise(
                zip("content_list.json", repetitive, "layout.json", "{}"), "test.pdf"))
                .isInstanceOf(MineruException.class).hasMessageContaining("压缩比");
    }

    @Test
    void rejectsMoreThanTheConfiguredEntryLimit() {
        assertThatThrownBy(() -> new MineruDocumentAdapter(mapper).parsePrecise(tooManyEntries(), "test.pdf"))
                .isInstanceOf(MineruException.class).hasMessageContaining("entry 数超过 4096");
    }

    @Test
    void keepsAgentMarkdownParagraphsStructuredSoInlineMathIsNotPromotedToABlockFormula() {
        var parsed = new MineruDocumentAdapter(mapper).parseAgent(
                "Conductivity is $3.5 \\times 10^{-12} \\mathrm{S}$ in this sample.", "paper.pdf");

        assertThat(parsed.blocks()).singleElement().satisfies(block -> assertThat(block.section()).isEqualTo("paragraph"));
        var document = new StructuredDocumentCodec(mapper).initialize(parsed.blocks()).confirmedDocument();
        assertThat(document.path("content").get(0).path("type").asText()).isEqualTo("paragraph");
        assertThat(document.path("content").get(0).toString()).contains("inlineMath", "latexRaw");
    }

    private byte[] zip(String firstName, String first, String secondName, String second) {
        try {
            var output = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(output)) {
                put(zip, firstName, first);
                put(zip, secondName, second);
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private byte[] tooManyEntries() {
        try {
            var output = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(output)) {
                put(zip, "content_list.json", "[{\"type\":\"text\",\"text\":\"有效内容\"}]");
                for (var index = 0; index < MineruDocumentAdapter.MAX_ENTRIES; index++) {
                    put(zip, "assets/" + index + ".txt", "x");
                }
            }
            return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
