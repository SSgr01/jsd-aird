package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        assertThat(result.structureSummary().path("parserVersion").asText()).isEqualTo("docx-ir-v2");
        assertThat(result.structureSummary().path("documentIR").path("structureHash").asText()).hasSize(64);
        assertThat(result.structureSummary().path("documentIR").path("blocks")).hasSize(2);
        assertThat(result.structureSummary().path("documentIR").path("anchors"))
                .anyMatch(anchor -> "PARAGRAPH".equals(anchor.path("kind").asText()));
        assertThat(result.initialEditorSnapshot().path("body").path("dataStream").asText())
                .contains("生产批号：", "请填写实际数据");
        assertThat(result.initialEditorSnapshot().path("body").path("sourceParagraphs")).hasSize(2);
        assertThat(result.structureSummary().path("documentIR").path("nodes"))
                .allMatch(node -> node.has("nodeId") && node.has("sourceLocator"));
        assertThat(result.issues()).isEmpty();
        assertThat(parser.format()).isEqualTo(TemplateFormat.DOCX);
    }

    @Test
    void recognizesCustomOutlineStylesAndGenericTableSectionHeadings() throws Exception {
        var source = minimalDocx(
                """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="customSection"/></w:pPr>
                      <w:r><w:t>Overview of the experiment</w:t></w:r>
                    </w:p>
                    <w:tbl>
                      <w:tblPr><w:tblBorders><w:top w:val="single"/><w:left w:val="single"/><w:bottom w:val="single"/><w:right w:val="single"/></w:tblBorders></w:tblPr>
                      <w:tblGrid><w:gridCol w:w="9000"/></w:tblGrid>
                      <w:tr><w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Experimental design</w:t></w:r></w:p></w:tc></w:tr>
                    </w:tbl>
                    <w:sectPr/>
                  </w:body>
                </w:document>
                """,
                """
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:style w:type="paragraph" w:styleId="customSection">
                    <w:name w:val="Section Level 2"/>
                    <w:pPr><w:outlineLvl w:val="1"/></w:pPr>
                  </w:style>
                </w:styles>
                """
        );

        var nodes = parser.parse(new ByteArrayInputStream(source))
                .structureSummary().path("documentIR").path("nodes");

        assertThat(nodes).anyMatch(node -> "HEADING".equals(node.path("type").asText())
                && "Overview of the experiment".equals(node.path("text").asText())
                && node.path("level").asInt() == 2);
        assertThat(nodes).anyMatch(node -> "HEADING".equals(node.path("type").asText())
                && "Experimental design".equals(node.path("text").asText()));
    }

    @Test
    void exposesListPageBreakAndTableNodes() throws Exception {
        var source = minimalDocx(
                """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>列表项</w:t></w:r></w:p>
                    <w:p><w:r><w:br w:type="page"/></w:r></w:p>
                    <w:tbl><w:tblGrid><w:gridCol w:w="2000"/></w:tblGrid><w:tr><w:tc><w:p><w:r><w:t>单元格</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                    <w:sectPr/>
                  </w:body>
                </w:document>
                """,
                """
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"/>
                """
        );

        var nodes = parser.parse(new ByteArrayInputStream(source))
                .structureSummary().path("documentIR").path("nodes");

        assertThat(nodes).anyMatch(node -> "LIST_ITEM".equals(node.path("type").asText()));
        assertThat(nodes).anyMatch(node -> "PAGE_BREAK".equals(node.path("type").asText()));
        assertThat(nodes).anyMatch(node -> "TABLE".equals(node.path("type").asText())
                && node.path("sourceLocator").path("rowCount").asInt() == 1);
    }

    private byte[] minimalDocx(String documentXml, String stylesXml) throws Exception {
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            for (var part : Map.of("word/document.xml", documentXml, "word/styles.xml", stylesXml).entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }
}
