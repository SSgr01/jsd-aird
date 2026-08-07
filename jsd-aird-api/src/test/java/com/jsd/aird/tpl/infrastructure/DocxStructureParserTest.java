package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

class DocxStructureParserTest {

    private final DocxStructureParser parser = new DocxStructureParser(new ObjectMapper());

    @Test
    void exposesStableDocumentIrAndCreatesAnEditableDocumentSnapshot() throws Exception {
        byte[] source;
        try (var output = new ByteArrayOutputStream()) {
            var word = WordprocessingMLPackage.createPackage();
            word.getMainDocumentPart().addParagraphOfText("生产批号：");
            word.getMainDocumentPart().addParagraphOfText("请填写实际数据");
            word.save(output);
            source = output.toByteArray();
        }

        var result = parser.parse(new ByteArrayInputStream(source));

        assertThat(result.structureSummary().path("format").asText()).isEqualTo("DOCX");
        assertThat(result.structureSummary().path("parserVersion").asText()).isEqualTo("docx-ir-v1");
        assertThat(result.structureSummary().path("documentIR").path("structureHash").asText()).hasSize(64);
        assertThat(result.structureSummary().path("documentIR").path("blocks")).hasSize(2);
        assertThat(result.structureSummary().path("documentIR").path("anchors"))
                .anyMatch(anchor -> "PARAGRAPH".equals(anchor.path("kind").asText()));
        assertThat(result.initialEditorSnapshot().path("body").path("dataStream").asText())
                .contains("生产批号：", "请填写实际数据");
        assertThat(result.issues()).isEmpty();
        assertThat(parser.format()).isEqualTo(TemplateFormat.DOCX);
    }
}
