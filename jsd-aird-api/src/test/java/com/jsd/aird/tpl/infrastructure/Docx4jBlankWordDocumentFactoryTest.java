package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

class Docx4jBlankWordDocumentFactoryTest {

    private final Docx4jBlankWordDocumentFactory factory = new Docx4jBlankWordDocumentFactory();
    private final DocxStructureParser parser = new DocxStructureParser(new ObjectMapper());

    @Test
    void createsAValidEditableDocxWithAuthoringStyles() throws Exception {
        var bytes = factory.create("空白科研模板");

        try (var input = new ByteArrayInputStream(bytes)) {
            assertThat(WordprocessingMLPackage.load(input)).isNotNull();
        }
        var parts = parts(bytes);
        assertThat(parts).containsKeys(
                "[Content_Types].xml",
                "_rels/.rels",
                "word/document.xml",
                "word/_rels/document.xml.rels",
                "word/styles.xml"
        );
        var styles = parts.get("word/styles.xml");
        assertThat(styles).contains("w:styleId=\"Normal\"");
        for (var level = 1; level <= 5; level++) {
            assertThat(styles).contains("w:styleId=\"Heading" + level + "\"");
        }

        var parsed = parser.parse(new ByteArrayInputStream(bytes));
        assertThat(parsed.initialEditorSnapshot().path("snapshotFormatVersion").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(parsed.initialEditorSnapshot().path("editorMode").asText()).isEqualTo("UNIVER_DOCS");
        assertThat(parsed.initialEditorSnapshot().path("body").path("dataStream").asText()).isNotEmpty();
        assertThat(parsed.structureSummary().path("documentIR").path("structureHash").asText()).hasSize(64);
        assertThat(parsed.issues()).isEmpty();
    }

    private Map<String, String> parts(byte[] bytes) throws Exception {
        var result = new HashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory()) {
                    result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return Map.copyOf(result);
    }
}
