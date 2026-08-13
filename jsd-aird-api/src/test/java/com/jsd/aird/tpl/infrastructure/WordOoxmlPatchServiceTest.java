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

    @Test
    void insertsStableContentControlAroundSelectedRun() throws Exception {
        var operations = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("type", "INSERT_CONTENT_CONTROL")
                        .put("targetId", "text-1").put("markerId", "marker-123")
                        .put("tag", "FIELD.ORDER_NO").put("alias", "订单号")
                        .put("text", "订单号").put("baseText", "订单号"));

        var patched = service.apply(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>订单号</w:t></w:r></w:p></w:body>
                </w:document>
                """), operations);
        var xml = documentXml(patched);
        assertThat(xml).contains("w:sdt", "FIELD.ORDER_NO", "订单号", "w:b");
    }

    @Test
    void keepsMultiplePositionalInsertionsBoundToTheirOriginalTextNodes() throws Exception {
        var operations = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("type", "INSERT_CONTENT_CONTROL")
                        .put("targetId", "text-1").put("markerId", "marker-1").put("alias", "订单号")
                        .put("baseText", "订单号"))
                .add(objectMapper.createObjectNode().put("type", "INSERT_CONTENT_CONTROL")
                        .put("targetId", "text-2").put("markerId", "marker-2").put("alias", "批号")
                        .put("baseText", "批号"));
        var patched = service.apply(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>订单号</w:t></w:r><w:r><w:t>批号</w:t></w:r></w:p></w:body>
                </w:document>
                """), operations);
        var xml = documentXml(patched);
        assertThat(xml).contains("marker-1", "marker-2", "订单号", "批号");
    }

    @Test
    void rejectsInsertionInsideExistingControl() throws Exception {
        var operations = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("type", "INSERT_CONTENT_CONTROL")
                        .put("targetId", "text-1").put("markerId", "marker-123"));
        assertThatThrownBy(() -> service.apply(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:sdt><w:sdtPr><w:id w:val="7"/></w:sdtPr><w:sdtContent><w:r><w:t>已有</w:t></w:r></w:sdtContent></w:sdt></w:p></w:body>
                </w:document>
                """), operations)).isInstanceOf(ApiException.class)
                .hasMessageContaining("已有内容控件");
    }

    @Test
    void writesIntoAnEmptyContentControlPlaceholder() throws Exception {
        var operations = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("type", "REPLACE_CONTENT_CONTROL")
                        .put("targetId", "marker-empty").put("text", "2026-08-09"));
        var patched = service.apply(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:sdt><w:sdtPr><w:dataBinding w:storeItemID="marker-empty"/></w:sdtPr><w:sdtContent/></w:sdt></w:p></w:body>
                </w:document>
                """), operations);
        assertThat(documentXml(patched)).contains("marker-empty", "2026-08-09");
    }

    @Test
    void rebuildsTheNativeDocumentFromTheUniverDocumentSnapshot() throws Exception {
        var snapshot = objectMapper.readTree("""
                {
                  "id": "doc-1",
                  "snapshotFormatVersion": 5,
                  "editorMode": "UNIVER_DOCS",
                  "documentStyle": {"pageSize": {"width": 595, "height": 842}, "marginTop": 72,
                    "marginRight": 72, "marginBottom": 72, "marginLeft": 72},
                  "body": {
                    "dataStream": "标题修改\\r正文\\r\\n",
                    "paragraphs": [
                      {"startIndex": 0,
                       "paragraphStyle": {"horizontalAlign": 2}}
                    ],
                    "textRuns": [],
                    "customRanges": []
                  }
                }
                """);
        var patched = service.applySnapshot(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>标题</w:t></w:r></w:p>
                    <w:p><w:r><w:t>正文</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """), snapshot);
        var xml = documentXml(patched);
        assertThat(xml).contains("标题修改", "w:val=\"center\"");
    }

    @Test
    void exportsSnapshotTableCellBordersAsSolidWordBorders() throws Exception {
        var border = objectMapper.createObjectNode()
                .put("dashStyle", 1);
        border.set("width", objectMapper.createObjectNode().put("v", 1));
        border.set("color", objectMapper.createObjectNode().put("rgb", "#000000"));
        var cell = objectMapper.createObjectNode();
        cell.set("borderTop", border);
        cell.set("borderRight", border);
        cell.set("borderBottom", border);
        cell.set("borderLeft", border);
        var snapshot = objectMapper.createObjectNode().put("snapshotFormatVersion", 5);
        snapshot.putObject("documentStyle").putObject("pageSize").put("width", 595).put("height", 842);
        var body = snapshot.putObject("body");
        body.put("dataStream", "\u001A\u001B\u001C表格\u001D\u000E\u000F\r\n")
                .putArray("tables").addObject().put("startIndex", 0).put("endIndex", 8).put("tableId", "table-1");
        body.putArray("paragraphs");
        body.putArray("textRuns");
        var table = snapshot.putObject("tableSource").putObject("table-1");
        table.putArray("tableColumns").addObject().putObject("size").putObject("width").put("v", 72);
        table.putArray("tableRows").addObject().putArray("tableCells").add(cell);

        var patched = service.applySnapshot(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>原始内容</w:t></w:r></w:p></w:body>
                </w:document>
                """), snapshot);
        var xml = documentXml(patched);

        assertThat(xml).contains("w:tcBorders", "w:val=\"single\"", "w:sz=\"8\"");
        assertThat(xml).doesNotContain("w:val=\"dotted\"", "w:val=\"dashed\"");
    }

    @Test
    void exportsSnapshotHeadingListAndPageBreak() throws Exception {
        var snapshot = objectMapper.createObjectNode().put("snapshotFormatVersion", 5);
        snapshot.putObject("documentStyle").putObject("pageSize").put("width", 595).put("height", 842);
        var body = snapshot.putObject("body");
        body.put("dataStream", "标题\r列表项\r\f\r\n");
        body.putArray("textRuns");
        body.putArray("customRanges");
        var paragraphs = body.putArray("paragraphs");
        paragraphs.addObject().put("startIndex", 0).putObject("paragraphStyle").put("namedStyleType", 4);
        var listParagraph = paragraphs.addObject().put("startIndex", 3);
        listParagraph.putObject("paragraphStyle").putObject("bullet").put("listType", "BULLET_LIST");
        paragraphs.addObject().put("startIndex", 6).putObject("paragraphStyle");

        var patched = service.applySnapshot(docx("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>原始内容</w:t></w:r></w:p></w:body>
                </w:document>
                """), snapshot);
        var xml = documentXml(patched);

        assertThat(xml).contains("w:pStyle", "w:val=\"Heading1\"", "w:numPr", "w:val=\"2\"", "w:type=\"page\"");
        assertThat(part(patched, "word/numbering.xml")).contains("w:numbering", "w:num w:numId=\"2\"");
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
        return part(docx, "word/document.xml");
    }

    private String part(byte[] docx, String name) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }
}
