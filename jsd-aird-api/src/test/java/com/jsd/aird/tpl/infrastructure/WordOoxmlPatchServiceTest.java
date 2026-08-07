package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class WordOoxmlPatchServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WordOoxmlPatchService service = new WordOoxmlPatchService();

    @Test
    void appliesTextCharacterParagraphAndTableCellPatchesAtomically() throws Exception {
        var operations = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("type", "REPLACE_TEXT")
                        .put("targetId", "text-1").put("baseText", "标题").put("text", "新标题"))
                .add(objectMapper.createObjectNode().put("type", "SET_RUN_STYLE")
                        .put("targetId", "run-1").put("bold", true).put("fontSize", 16))
                .add(objectMapper.createObjectNode().put("type", "SET_PARAGRAPH_STYLE")
                        .put("targetId", "paragraph-1").put("alignment", "center"))
                .add(objectMapper.createObjectNode().put("type", "REPLACE_TABLE_CELL")
                        .put("targetId", "table-1-cell-1-1").put("baseText", "旧值").put("text", "新值"))
                .add(objectMapper.createObjectNode().put("type", "ADD_TABLE_ROW")
                        .put("targetId", "table-1").put("rowIndex", 1))
                .add(objectMapper.createObjectNode().put("type", "DELETE_TABLE_ROW")
                        .put("targetId", "table-1").put("rowIndex", 1));

        var patched = service.apply(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>标题</w:t></w:r></w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>旧值</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                  </w:body>
                </w:document>
                """), operations);
        var xml = documentXml(patched);

        assertThat(xml).contains("新标题", "新值", "w:val=\"center\"", "w:val=\"32\"");
        assertThat(xml).contains("w:b");
    }

    @Test
    void rejectsBaseTextConflictWithoutReturningPartialOutput() throws Exception {
        var operations = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("type", "REPLACE_TEXT")
                        .put("targetId", "text-1").put("baseText", "错误基线").put("text", "不应写入"));

        assertThatThrownBy(() -> service.apply(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>标题</w:t></w:r></w:p></w:body>
                </w:document>
                """), operations))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("基线");
    }

    private byte[] docx(String documentXml) throws Exception {
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            return output.toByteArray();
        }
    }

    private String documentXml(byte[] docx) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }
}
