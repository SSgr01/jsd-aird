package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

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
                {"pdf_info":[{"page_idx":0,"page_size":[400,800],"rotation":0}]}
                """;
        var parsed = new MineruDocumentAdapter(mapper).parsePrecise(zip(
                "x_content_list.json", content, "layout.json", layout), "test.pdf");

        assertThat(parsed.blocks()).extracting(value -> value.section())
                .contains("heading-2", "paragraph", "mineru-table-row");
        assertThat(parsed.blocks().getFirst().bbox()).containsExactly(0.025, 0.025, 0.275, 0.025,
                0.275, 0.05, 0.025, 0.05);
        var structured = new StructuredDocumentCodec(mapper).initialize(parsed.blocks()).confirmedDocument();
        var table = structured.path("content").get(structured.path("content").size() - 1);
        assertThat(table.path("type").asText()).isEqualTo("table");
        assertThat(table.path("content").get(0).path("content").get(0).path("type").asText())
                .isEqualTo("tableHeader");
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
}
