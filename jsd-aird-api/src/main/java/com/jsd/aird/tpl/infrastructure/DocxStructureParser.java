package com.jsd.aird.tpl.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.application.port.WordDocumentParser;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.docx4j.TextUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@Component
public class DocxStructureParser implements OfficeStructureParser, WordDocumentParser {

    private static final String WORDPROCESSINGML =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private final ObjectMapper objectMapper;

    public DocxStructureParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TemplateFormat format() {
        return TemplateFormat.DOCX;
    }

    @Override
    public ParseResult parse(InputStream input) {
        try {
            var bytes = input.readAllBytes();
            var packageParts = inspectPackage(bytes);
            var wordPackage = WordprocessingMLPackage.load(new ByteArrayInputStream(bytes));
            var documentXml = readPart(bytes, "word/document.xml");
            var dom = parseXml(documentXml);
            var plainText = TextUtils.getText(wordPackage.getMainDocumentPart());
            if (plainText == null || plainText.isBlank()) plainText = plainTextFromDom(dom);

            var summary = objectMapper.createObjectNode();
            summary.put("format", "DOCX");
            summary.put("parserVersion", "docx-ir-v1");
            summary.put("structureVersion", 1);
            summary.put("paragraphCount", count(dom, "p"));
            summary.put("tableCount", count(dom, "tbl"));
            summary.put("contentControlCount", count(dom, "sdt"));
            summary.put("bookmarkCount", count(dom, "bookmarkStart"));
            summary.put("fieldCount", count(dom, "fldSimple") + count(dom, "instrText"));
            summary.put("headerCount", packageParts.headerCount);
            summary.put("footerCount", packageParts.footerCount);
            summary.put("imageCount", packageParts.imageCount);
            summary.put("hasFootnotes", packageParts.hasFootnotes);
            summary.put("hasEndnotes", packageParts.hasEndnotes);
            summary.put("hasComments", packageParts.hasComments);
            summary.put("hasExternalLinks", packageParts.hasExternalLinks);
            summary.put("hasEmbeddedObjects", packageParts.hasEmbeddedObjects);

            var issues = new ArrayList<ParseIssue>();
            addUnsupportedIssues(packageParts, issues);
            var documentIr = documentIr(dom, documentXml, plainText, packageParts, summary);
            summary.set("documentIR", documentIr);
            var snapshot = documentSnapshot(plainText, documentIr);
            return new ParseResult(summary, snapshot, List.copyOf(issues));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "DOCX 解析失败：" + exception.getMessage());
        }
    }

    private PackageFacts inspectPackage(byte[] bytes) throws Exception {
        var facts = new PackageFacts();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                var name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                facts.headerCount += name.matches("word/header\\d+\\.xml") ? 1 : 0;
                facts.footerCount += name.matches("word/footer\\d+\\.xml") ? 1 : 0;
                facts.imageCount += name.startsWith("word/media/") ? 1 : 0;
                facts.hasFootnotes |= name.equals("word/footnotes.xml");
                facts.hasEndnotes |= name.equals("word/endnotes.xml");
                facts.hasComments |= name.equals("word/comments.xml");
                facts.hasExternalLinks |= name.startsWith("word/externallinks/");
                facts.hasEmbeddedObjects |= name.startsWith("word/embeddings/");
                facts.hasMacros |= name.endsWith("vbaproject.bin");
                facts.hasActiveX |= name.contains("activex/");
                facts.hasOleObjects |= name.contains("oleobject") || name.startsWith("word/embeddings/");
                entry = zip.getNextEntry();
            }
        }
        return facts;
    }

    private void addUnsupportedIssues(PackageFacts facts, List<ParseIssue> issues) {
        if (facts.hasMacros || facts.hasActiveX || facts.hasOleObjects) {
            issues.add(issue(
                    "BLOCKER",
                    "DOCX_ACTIVE_CONTENT_BLOCKED",
                    "检测到宏、ActiveX 或 OLE 嵌入对象；Word 模板只允许无活动内容的 OOXML 文件。"
            ));
        }
        if (facts.hasFootnotes || facts.hasEndnotes) {
            issues.add(issue(
                    "BLOCKER",
                    "DOCX_NOTES_UNSUPPORTED",
                    "脚注或尾注尚不能保证 IDocumentData/DOCX 往返，发布前必须移除或人工确认替代方案。"
            ));
        }
        if (facts.hasComments) {
            issues.add(issue(
                    "WARNING",
                    "DOCX_COMMENTS_OPAQUE",
                    "批注将作为不参与编辑的 OOXML 信息处理，当前不保证在线修改后往返。"
            ));
        }
        if (facts.hasExternalLinks || facts.hasEmbeddedObjects) {
            issues.add(issue(
                    "BLOCKER",
                    "DOCX_EXTERNAL_OR_EMBEDDED",
                    "外部链接或嵌入对象不会执行；当前转换器不能保证无损往返。"
            ));
        }
        if (facts.imageCount > 0) {
            issues.add(issue(
                    "WARNING",
                    "DOCX_IMAGE_ROUNDTRIP_REVIEW",
                    "图片已识别，浮动环绕和复杂锚点需要在导出差异预览中人工检查。"
            ));
        }
    }

    /**
     * A deliberately small, stable projection of WordprocessingML used by the web
     * workbench. It is not an alternate document authority: the native DOCX remains
     * authoritative and every write must be applied back to OOXML by a controlled
     * command handler.
     */
    private com.fasterxml.jackson.databind.node.ObjectNode documentIr(
            Document document,
            byte[] documentXml,
            String plainText,
            PackageFacts facts,
            com.fasterxml.jackson.databind.node.ObjectNode packageSummary
    ) {
        var result = objectMapper.createObjectNode();
        result.put("text", plainText == null ? "" : plainText);
        result.put("structureHash", sha256(documentXml));
        result.set("packageFacts", packageSummary.deepCopy());

        var blocks = objectMapper.createArrayNode();
        var body = firstElement(document.getDocumentElement(), "body");
        if (body != null) {
            var paragraphNo = 0;
            var tableNo = 0;
            for (var child : childElements(body)) {
                if (isWord(child, "p")) {
                    blocks.add(paragraphBlock(child, ++paragraphNo));
                } else if (isWord(child, "tbl")) {
                    blocks.add(tableBlock(child, ++tableNo));
                } else if (isWord(child, "sdt")) {
                    var content = firstElement(child, "sdtContent");
                    for (var nested : content == null ? List.<Element>of() : childElements(content)) {
                        if (isWord(nested, "p")) blocks.add(paragraphBlock(nested, ++paragraphNo));
                        if (isWord(nested, "tbl")) blocks.add(tableBlock(nested, ++tableNo));
                    }
                }
            }
        }
        result.set("blocks", blocks);

        var anchors = objectMapper.createArrayNode();
        addEditableAnchors(document, anchors);
        result.set("anchors", anchors);

        var controls = objectMapper.createArrayNode();
        var controlNodes = document.getElementsByTagNameNS("*", "sdt");
        for (var index = 0; index < controlNodes.getLength(); index++) {
            if (controlNodes.item(index) instanceof Element control) {
                controls.add(contentControl(control, index + 1));
            }
        }
        result.set("contentControls", controls);

        var bookmarks = objectMapper.createArrayNode();
        var bookmarkNodes = document.getElementsByTagNameNS("*", "bookmarkStart");
        for (var index = 0; index < bookmarkNodes.getLength(); index++) {
            if (bookmarkNodes.item(index) instanceof Element bookmark) {
                bookmarks.add(objectMapper.createObjectNode()
                        .put("id", attribute(bookmark, "id"))
                        .put("name", attribute(bookmark, "name"))
                        .put("nodeId", "bookmark-" + (index + 1)));
            }
        }
        result.set("bookmarks", bookmarks);

        var fields = objectMapper.createArrayNode();
        var simpleFields = document.getElementsByTagNameNS("*", "fldSimple");
        for (var index = 0; index < simpleFields.getLength(); index++) {
            if (simpleFields.item(index) instanceof Element field) {
                fields.add(fieldNode("field-simple-" + (index + 1), attribute(field, "instr")));
            }
        }
        var instructionNodes = document.getElementsByTagNameNS("*", "instrText");
        for (var index = 0; index < instructionNodes.getLength(); index++) {
            var instruction = instructionNodes.item(index).getTextContent();
            if (instruction != null && !instruction.isBlank()) {
                fields.add(fieldNode("field-instruction-" + (index + 1), instruction));
            }
        }
        result.set("fields", fields);

        var compatibility = objectMapper.createObjectNode();
        compatibility.put("hasMacros", facts.hasMacros)
                .put("hasActiveX", facts.hasActiveX)
                .put("hasOleObjects", facts.hasOleObjects)
                .put("hasExternalLinks", facts.hasExternalLinks)
                .put("hasEmbeddedObjects", facts.hasEmbeddedObjects)
                .put("hasFootnotes", facts.hasFootnotes)
                .put("hasEndnotes", facts.hasEndnotes)
                .put("hasComments", facts.hasComments)
                .put("imageCount", facts.imageCount)
                .put("status", compatibilityStatus(facts));
        result.set("compatibility", compatibility);
        return result;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode paragraphBlock(Element paragraph, int sequence) {
        var result = objectMapper.createObjectNode();
        var paragraphId = attribute(paragraph, "paraId");
        result.put("id", paragraphId.isBlank() ? "paragraph-" + sequence : "paragraph-" + paragraphId);
        result.put("type", "PARAGRAPH");
        result.put("text", text(paragraph));
        var properties = firstElement(paragraph, "pPr");
        var style = properties == null ? null : firstElement(properties, "pStyle");
        var alignment = properties == null ? null : firstElement(properties, "jc");
        result.put("style", style == null ? "" : attribute(style, "val"));
        result.put("alignment", alignment == null ? "" : attribute(alignment, "val"));
        result.put("contentControlCount", paragraph.getElementsByTagNameNS("*", "sdt").getLength());
        return result;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode tableBlock(Element table, int sequence) {
        var result = objectMapper.createObjectNode();
        result.put("id", "table-" + sequence);
        result.put("type", "TABLE");
        var rows = directChildren(table, "tr");
        result.put("rowCount", rows.size());
        var maxColumns = 0;
        for (var row : rows) maxColumns = Math.max(maxColumns, directChildren(row, "tc").size());
        result.put("columnCount", maxColumns);
        result.put("text", text(table));
        result.put("contentControlCount", table.getElementsByTagNameNS("*", "sdt").getLength());
        var rowNodes = objectMapper.createArrayNode();
        var rowIndex = 0;
        for (var row : rows) {
            var rowNode = objectMapper.createObjectNode()
                    .put("id", "table-" + sequence + "-row-" + (++rowIndex));
            var cells = objectMapper.createArrayNode();
            var columnIndex = 0;
            for (var cell : directChildren(row, "tc")) {
                cells.add(objectMapper.createObjectNode()
                        .put("id", "table-" + sequence + "-cell-" + rowIndex + "-" + (++columnIndex))
                        .put("text", text(cell))
                        .put("editable", isSimpleTableCell(cell)));
            }
            rowNode.set("cells", cells);
            rowNodes.add(rowNode);
        }
        result.set("rows", rowNodes);
        return result;
    }

    private boolean isSimpleTableCell(Element cell) {
        return cell.getElementsByTagNameNS("*", "fldSimple").getLength() == 0
                && cell.getElementsByTagNameNS("*", "instrText").getLength() == 0
                && cell.getElementsByTagNameNS("*", "ins").getLength() == 0
                && cell.getElementsByTagNameNS("*", "del").getLength() == 0;
    }

    private void addEditableAnchors(Document document,
                                     com.fasterxml.jackson.databind.node.ArrayNode anchors) {
        var paragraphs = document.getElementsByTagNameNS("*", "p");
        var runs = document.getElementsByTagNameNS("*", "r");
        var texts = document.getElementsByTagNameNS("*", "t");
        var cells = document.getElementsByTagNameNS("*", "tc");
        for (var i = 0; i < paragraphs.getLength(); i++) {
            if (paragraphs.item(i) instanceof Element paragraph) {
                anchors.add(objectMapper.createObjectNode()
                        .put("nodeId", paragraphNodeId(paragraph, i + 1))
                        .put("kind", "PARAGRAPH")
                        .put("text", text(paragraph))
                        .put("editable", isEditable(paragraph)));
            }
        }
        for (var i = 0; i < runs.getLength(); i++) {
            if (runs.item(i) instanceof Element run) {
                anchors.add(objectMapper.createObjectNode()
                        .put("nodeId", "run-" + (i + 1))
                        .put("kind", "RUN")
                        .put("text", text(run))
                        .put("editable", isEditable(run)));
            }
        }
        for (var i = 0; i < texts.getLength(); i++) {
            if (texts.item(i) instanceof Element textNode) {
                anchors.add(objectMapper.createObjectNode()
                        .put("nodeId", "text-" + (i + 1))
                        .put("kind", "TEXT")
                        .put("text", textNode.getTextContent())
                        .put("editable", isEditable(textNode)));
            }
        }
        for (var i = 0; i < cells.getLength(); i++) {
            if (cells.item(i) instanceof Element cell) {
                anchors.add(objectMapper.createObjectNode()
                        .put("nodeId", "cell-" + (i + 1))
                        .put("kind", "TABLE_CELL")
                        .put("text", text(cell))
                        .put("editable", isSimpleTableCell(cell)));
            }
        }
    }

    private String paragraphNodeId(Element paragraph, int sequence) {
        var paragraphId = attribute(paragraph, "paraId");
        return paragraphId.isBlank() ? "paragraph-" + sequence : "paragraph-" + paragraphId;
    }

    private boolean isEditable(Element element) {
        return element.getElementsByTagNameNS("*", "fldSimple").getLength() == 0
                && element.getElementsByTagNameNS("*", "instrText").getLength() == 0
                && element.getElementsByTagNameNS("*", "del").getLength() == 0
                && element.getElementsByTagNameNS("*", "moveFrom").getLength() == 0;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode contentControl(Element control, int sequence) {
        var result = objectMapper.createObjectNode();
        var properties = firstElement(control, "sdtPr");
        var tag = properties == null ? null : firstElement(properties, "tag");
        var alias = properties == null ? null : firstElement(properties, "alias");
        var id = properties == null ? null : firstElement(properties, "id");
        var dataBinding = properties == null ? null : firstElement(properties, "dataBinding");
        result.put("nodeId", "content-control-" + sequence);
        result.put("contentControlId", id == null ? "" : attribute(id, "val"));
        result.put("tag", tag == null ? "" : attribute(tag, "val"));
        result.put("alias", alias == null ? "" : attribute(alias, "val"));
        result.put("text", text(control));
        result.put("kind", controlKind(properties));
        if (dataBinding != null) {
            result.set("dataBinding", objectMapper.createObjectNode()
                    .put("xpath", attribute(dataBinding, "xpath"))
                    .put("storeItemId", attribute(dataBinding, "storeItemID"))
                    .put("prefixMappings", attribute(dataBinding, "prefixMappings")));
        }
        return result;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode fieldNode(String id, String instruction) {
        var normalized = instruction == null ? "" : instruction.trim();
        return objectMapper.createObjectNode()
                .put("nodeId", id)
                .put("instruction", normalized)
                .put("kind", normalized.toUpperCase(Locale.ROOT).contains("MERGEFIELD") ? "MERGEFIELD" : "FIELD");
    }

    private String controlKind(Element properties) {
        if (properties == null) return "RICH_TEXT";
        for (var name : List.of("repeatingSection", "date", "dropDownList", "comboBox", "checkBox", "picture")) {
            if (firstElement(properties, name) != null) return name.toUpperCase(Locale.ROOT);
        }
        return "RICH_TEXT";
    }

    private String compatibilityStatus(PackageFacts facts) {
        if (facts.hasMacros || facts.hasActiveX || facts.hasOleObjects || facts.hasExternalLinks || facts.hasEmbeddedObjects) {
            return "BLOCKED";
        }
        if (facts.hasFootnotes || facts.hasEndnotes || facts.hasComments || facts.imageCount > 0) return "DEGRADED";
        return "SUPPORTED";
    }

    private Element firstElement(Element parent, String localName) {
        if (parent == null) return null;
        for (var child : childElements(parent)) if (isWord(child, localName)) return child;
        return null;
    }

    private List<Element> childElements(Element parent) {
        var result = new ArrayList<Element>();
        var children = parent.getChildNodes();
        for (var index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element element) result.add(element);
        }
        return result;
    }

    private List<Element> directChildren(Element parent, String localName) {
        var result = new ArrayList<Element>();
        for (var child : childElements(parent)) if (isWord(child, localName)) result.add(child);
        return result;
    }

    private boolean isWord(Element element, String localName) {
        return localName.equals(element.getLocalName())
                && (WORDPROCESSINGML.equals(element.getNamespaceURI()) || element.getNamespaceURI() == null);
    }

    private String attribute(Element element, String localName) {
        if (element == null) return "";
        var attribute = element.getAttributeNS(WORDPROCESSINGML, localName);
        if (attribute != null && !attribute.isBlank()) return attribute;
        return element.getAttribute("w:" + localName).isBlank()
                ? element.getAttribute(localName) : element.getAttribute("w:" + localName);
    }

    private String text(Element element) {
        var texts = element.getElementsByTagNameNS("*", "t");
        var result = new StringBuilder();
        for (var index = 0; index < texts.getLength(); index++) result.append(texts.item(index).getTextContent());
        return result.toString();
    }

    private String plainTextFromDom(Document document) {
        var result = new StringBuilder();
        var paragraphs = document.getElementsByTagNameNS("*", "p");
        for (var index = 0; index < paragraphs.getLength(); index++) {
            if (!(paragraphs.item(index) instanceof Element paragraph)) continue;
            var value = text(paragraph);
            if (value.isBlank()) continue;
            if (!result.isEmpty()) result.append('\n');
            result.append(value);
        }
        return result.isEmpty() ? text(document.getDocumentElement()) : result.toString();
    }

    private String sha256(byte[] value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value);
            var result = new StringBuilder(digest.length * 2);
            for (var item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 DOCX 结构哈希", exception);
        }
    }

    private ParseIssue issue(String severity, String code, String message) {
        return new ParseIssue(severity, code, message, objectMapper.createObjectNode());
    }

    private byte[] readPart(byte[] bytes, String partName) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                if (partName.equals(entry.getName())) {
                    var output = new ByteArrayOutputStream();
                    zip.transferTo(output);
                    return output.toByteArray();
                }
                entry = zip.getNextEntry();
            }
        }
        throw new ApiException(ApiErrorCode.BAD_REQUEST, "DOCX 缺少 word/document.xml");
    }

    private Document parseXml(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private int count(Document document, String localName) {
        return document.getElementsByTagNameNS("*", localName).getLength();
    }

    private com.fasterxml.jackson.databind.JsonNode documentSnapshot(
            String text,
            com.fasterxml.jackson.databind.JsonNode documentIr
    ) {
        var normalized = text == null || text.isBlank() ? "" : text.strip();
        var dataStream = normalized.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\n", "\r") + "\r\n";
        var paragraphs = objectMapper.createArrayNode();
        var index = 0;
        paragraphs.add(objectMapper.createObjectNode().put("startIndex", 0));
        while ((index = dataStream.indexOf('\r', index)) >= 0 && index < dataStream.length() - 2) {
            paragraphs.add(objectMapper.createObjectNode().put("startIndex", index + 1));
            index++;
        }
        var body = objectMapper.createObjectNode();
        body.put("dataStream", dataStream);
        body.set("paragraphs", paragraphs);
        body.set("textRuns", objectMapper.createArrayNode());
        body.set("customRanges", documentControlRanges(dataStream, documentIr));
        var snapshot = objectMapper.createObjectNode();
        snapshot.put("id", UUID.randomUUID().toString());
        snapshot.put("title", "导入的 Word 模板");
        snapshot.set("body", body);
        var documentStyle = objectMapper.createObjectNode();
        documentStyle.set("pageSize", objectMapper.createObjectNode()
                .put("width", 595)
                .put("height", 842));
        documentStyle.put("marginTop", 72)
                .put("marginRight", 72)
                .put("marginBottom", 72)
                .put("marginLeft", 72);
        snapshot.set("documentStyle", documentStyle);
        return snapshot;
    }

    /**
     * Univer's document snapshot uses offset ranges. For imported content controls
     * we create only deterministic ranges that can be located in the flattened
     * document text. Controls we cannot locate are left for explicit placement in
     * the workbench; we never guess a duplicate occurrence.
     */
    private com.fasterxml.jackson.databind.node.ArrayNode documentControlRanges(
            String dataStream,
            com.fasterxml.jackson.databind.JsonNode documentIr
    ) {
        var ranges = objectMapper.createArrayNode();
        var searchFrom = 0;
        for (var control : documentIr.path("contentControls")) {
            var value = control.path("text").asText("").strip();
            if (value.isBlank()) continue;
            var start = dataStream.indexOf(value, searchFrom);
            if (start < 0) continue;
            var markerId = control.path("contentControlId").asText("");
            if (markerId.isBlank()) markerId = control.path("nodeId").asText("");
            if (markerId.isBlank()) continue;
            var range = objectMapper.createObjectNode();
            range.put("rangeId", markerId);
            range.put("startIndex", start);
            range.put("endIndex", start + value.length() - 1);
            range.set("properties", objectMapper.createObjectNode()
                    .put("source", "DOCX_CONTENT_CONTROL")
                    .put("tag", control.path("tag").asText(""))
                    .put("alias", control.path("alias").asText(""))
                    .put("kind", control.path("kind").asText("RICH_TEXT")));
            ranges.add(range);
            searchFrom = start + value.length();
        }
        return ranges;
    }

    private static final class PackageFacts {
        private int headerCount;
        private int footerCount;
        private int imageCount;
        private boolean hasFootnotes;
        private boolean hasEndnotes;
        private boolean hasComments;
        private boolean hasExternalLinks;
        private boolean hasEmbeddedObjects;
        private boolean hasMacros;
        private boolean hasActiveX;
        private boolean hasOleObjects;
    }
}
