package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
