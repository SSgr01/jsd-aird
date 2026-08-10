package com.jsd.aird.tpl.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
            summary.put("parserVersion", "docx-ir-v2");
            summary.put("structureVersion", 2);
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
            addEditorLocators(documentIr, snapshot.path("body").path("dataStream").asText(""));
            applySnapshotStyles(snapshot, documentIr);
            summary.set("documentIR", documentIr);
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
        result.put("schemaVersion", 2);
        result.put("documentType", "WORD");
        result.put("text", plainText == null ? "" : plainText);
        result.put("structureHash", sha256(documentXml));
        result.put("documentId", "docx-" + sha256(documentXml).substring(0, 16));
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

        var paragraphTexts = objectMapper.createArrayNode();
        var allParagraphs = document.getElementsByTagNameNS("*", "p");
        for (var index = 0; index < allParagraphs.getLength(); index++) {
            if (allParagraphs.item(index) instanceof Element paragraph) {
                paragraphTexts.add(objectMapper.createObjectNode()
                        .put("paragraphIndex", index + 1)
                        .put("text", text(paragraph)));
            }
        }
        result.set("paragraphTexts", paragraphTexts);

        var nodes = documentNodes(document);
        result.set("nodes", nodes);
        result.put("nodeCount", nodes.size());
        var headingCount = 0;
        for (var node : nodes) {
            if (Set.of("DOCUMENT_TITLE", "HEADING").contains(node.path("type").asText())) headingCount++;
        }
        result.put("headingCount", headingCount);

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
        result.put("insideTable", ancestor(paragraph, "tc") != null);
        return result;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode documentNodes(Document document) {
        var nodes = objectMapper.createArrayNode();
        var paragraphs = document.getElementsByTagNameNS("*", "p");
        var stack = new ArrayList<NodeFrame>();
        var sequence = 0;
        for (var index = 0; index < paragraphs.getLength(); index++) {
            if (!(paragraphs.item(index) instanceof Element paragraph)) continue;
            var value = text(paragraph).strip();
            if (value.isBlank()) continue;
            sequence++;
            var properties = firstElement(paragraph, "pPr");
            var style = properties == null ? null : firstElement(properties, "pStyle");
            var outline = properties == null ? null : firstElement(properties, "outlineLvl");
            var numId = properties == null ? null : firstElement(firstElement(properties, "numPr"), "numId");
            var ilvl = properties == null ? null : firstElement(firstElement(properties, "numPr"), "ilvl");
            var alignment = properties == null ? "" : attribute(firstElement(properties, "jc"), "val");
            var maxFontSize = maxFontSize(paragraph);
            var bold = paragraph.getElementsByTagNameNS("*", "b").getLength() > 0;
            var insideTable = ancestor(paragraph, "tc") != null;
            var title = isDocumentTitle(value, style, alignment, bold, maxFontSize);
            var heading = title || isHeading(value, style, outline, numId, insideTable, bold, maxFontSize);
            var level = title ? 0 : headingLevel(value, style, outline, numId, ilvl, insideTable, stack);
            while (!stack.isEmpty() && stack.get(stack.size() - 1).level >= level) stack.remove(stack.size() - 1);
            var nodeId = paragraphNodeId(paragraph, index + 1);
            var node = objectMapper.createObjectNode()
                    .put("nodeId", nodeId)
                    .put("type", title ? "DOCUMENT_TITLE" : heading ? "HEADING" : "PARAGRAPH")
                    .put("level", level)
                    .put("text", value)
                    .put("sortOrder", sequence);
            if (!stack.isEmpty()) node.put("parentId", stack.get(stack.size() - 1).nodeId);
            if (heading) node.put("title", value);
            var source = node.putObject("sourceLocator")
                    .put("part", "word/document.xml")
                    .put("paragraphIndex", index + 1)
                    .put("insideTable", insideTable);
            if (style != null) source.put("style", attribute(style, "val"));
            if (outline != null) source.put("outlineLevel", attribute(outline, "val"));
            if (numId != null) source.put("numId", attribute(numId, "val"));
            if (ilvl != null) source.put("listLevel", attribute(ilvl, "val"));
            var propertiesNode = node.putObject("properties")
                    .put("alignment", alignment)
                    .put("bold", bold)
                    .put("fontSize", maxFontSize);
            var family = firstFontFamily(paragraph);
            if (!family.isBlank()) propertiesNode.put("fontFamily", family);
            nodes.add(node);
            if (heading) stack.add(new NodeFrame(nodeId, level));
        }
        return nodes;
    }

    private boolean isDocumentTitle(String value, Element style, String alignment, boolean bold, int fontSize) {
        var styleValue = style == null ? "" : attribute(style, "val").toLowerCase(Locale.ROOT);
        return styleValue.contains("title")
                || ("center".equalsIgnoreCase(alignment)
                && ((bold && fontSize >= 14) || fontSize >= 20)
                && value.length() <= 80);
    }

    private boolean isHeading(String value, Element style, Element outline, Element numId,
                              boolean insideTable, boolean bold, int fontSize) {
        var styleValue = style == null ? "" : attribute(style, "val").toLowerCase(Locale.ROOT);
        if (styleValue.contains("heading") || styleValue.contains("title") || outline != null) return true;
        if (value.matches("^(第\\s*[一二三四五六七八九十百]+章|[一二三四五六七八九十百]+[、.．]|\\d+(?:[.．]\\d+)*[、.．]).*")) return true;
        if (insideTable && value.length() <= 36 && !value.contains("____")
                && !value.contains("________________") && !value.matches(".*[。！？；].*")) {
            return isTableSectionHeading(value);
        }
        return bold && fontSize >= 18 && value.length() <= 48 && !value.contains("____");
    }

    private boolean isTableSectionHeading(String value) {
        return Set.of(
                "立项背景", "组织实施方式：", "立项目的：", "主要研究内容及关键技术：",
                "研究目标", "研究方案", "风险分析", "结论"
        ).contains(value.strip());
    }

    private int headingLevel(String value, Element style, Element outline, Element numId, Element ilvl,
                             boolean insideTable, List<NodeFrame> stack) {
        var styleValue = style == null ? "" : attribute(style, "val").toLowerCase(Locale.ROOT);
        if (styleValue.contains("heading1") || styleValue.equals("heading") || outline != null && "0".equals(attribute(outline, "val"))) return 1;
        if (styleValue.contains("heading2") || styleValue.contains("heading3")) return 2;
        if (value.matches("^[一二三四五六七八九十百]+[、.．].*")) return 1;
        if (value.matches("^\\d+(?:[.．]\\d+)*[、.．].*")) return 2;
        if (numId != null && ilvl != null) return Math.min(5, 1 + parseInt(attribute(ilvl, "val"), 0));
        if (insideTable) return 2;
        return stack.isEmpty() ? 1 : Math.min(5, stack.get(stack.size() - 1).level + 1);
    }

    private int maxFontSize(Element paragraph) {
        var sizes = paragraph.getElementsByTagNameNS("*", "sz");
        var max = 0;
        for (var index = 0; index < sizes.getLength(); index++) max = Math.max(max, parseInt(attribute((Element) sizes.item(index), "val"), 0));
        return max / 2;
    }

    private String firstFontFamily(Element paragraph) {
        var fonts = paragraph.getElementsByTagNameNS("*", "rFonts");
        return fonts.getLength() == 0 ? "" : attribute((Element) fonts.item(0), "eastAsia");
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private Element ancestor(Element element, String localName) {
        var current = element.getParentNode();
        while (current instanceof Element parent) {
            if (isWord(parent, localName)) return parent;
            current = parent.getParentNode();
        }
        return null;
    }

    private void addEditorLocators(ObjectNode documentIr, String dataStream) {
        var searchFrom = 0;
        for (var node : documentIr.withArray("nodes")) {
            if (!(node instanceof ObjectNode objectNode)) continue;
            var value = node.path("text").asText("");
            if (value.isBlank()) continue;
            var start = dataStream.indexOf(value, searchFrom);
            if (start < 0) start = dataStream.indexOf(value);
            if (start < 0) continue;
            objectNode.putObject("editorLocator")
                    .put("snapshotRevision", 1)
                    .put("startOffset", start)
                    .put("endOffset", start + value.length() - 1)
                    .put("textHash", sha256(value.getBytes(StandardCharsets.UTF_8)));
            searchFrom = start + value.length();
        }
    }

    private void applySnapshotStyles(com.fasterxml.jackson.databind.JsonNode snapshot,
                                     com.fasterxml.jackson.databind.JsonNode documentIr) {
        if (!(snapshot.path("body") instanceof com.fasterxml.jackson.databind.node.ObjectNode body)) return;
        var paragraphs = objectMapper.createArrayNode();
        var textRuns = objectMapper.createArrayNode();
        var paragraphStarts = new HashSet<Integer>();
        paragraphStarts.add(0);
        for (var node : documentIr.path("nodes")) {
            var locator = node.path("editorLocator");
            if (!locator.has("startOffset")) continue;
            var start = locator.path("startOffset").asInt();
            if (paragraphStarts.add(start)) {
                var paragraph = objectMapper.createObjectNode().put("startIndex", start);
                paragraph.put("paragraphIndex", node.path("sourceLocator").path("paragraphIndex").asInt(0));
                paragraph.set("paragraphStyle", paragraphStyle(node));
                paragraphs.add(paragraph);
            }
            var value = node.path("text").asText("");
            if (!value.isBlank()) {
                var run = objectMapper.createObjectNode()
                        .put("st", start)
                        .put("ed", locator.path("endOffset").asInt(start + value.length() - 1));
                run.set("ts", textStyle(node));
                textRuns.add(run);
            }
        }
        body.set("paragraphs", paragraphs);
        body.set("textRuns", textRuns);
        body.set("sourceParagraphs", documentIr.path("paragraphTexts").deepCopy());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode paragraphStyle(com.fasterxml.jackson.databind.JsonNode node) {
        var style = objectMapper.createObjectNode();
        var properties = node.path("properties");
        var alignment = properties.path("alignment").asText("").toLowerCase(Locale.ROOT);
        if (!alignment.isBlank()) style.put("horizontalAlign", switch (alignment) {
            case "center" -> 2;
            case "right" -> 3;
            case "both", "justify" -> 4;
            default -> 1;
        });
        var type = node.path("type").asText("");
        if ("DOCUMENT_TITLE".equals(type)) style.put("namedStyleType", 2);
        else if ("HEADING".equals(type)) style.put("namedStyleType", Math.min(8, 3 + node.path("level").asInt(1)));
        style.set("textStyle", textStyle(node));
        return style;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode textStyle(com.fasterxml.jackson.databind.JsonNode node) {
        var style = objectMapper.createObjectNode();
        var properties = node.path("properties");
        var fontSize = properties.path("fontSize").asInt(0);
        if (fontSize > 0) style.put("fs", fontSize);
        if (properties.path("bold").asBoolean(false)) style.put("bl", 1);
        if (properties.has("fontFamily") && !properties.path("fontFamily").asText().isBlank()) {
            style.put("ff", properties.path("fontFamily").asText());
        }
        return style;
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
        result.put("markerId", dataBinding == null ? "" : attribute(dataBinding, "storeItemID"));
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
        snapshot.put("snapshotFormatVersion", 4);
        snapshot.put("editorMode", "UNIVER_DOCS");
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
            var markerId = control.path("markerId").asText("");
            if (markerId.isBlank()) markerId = control.path("contentControlId").asText("");
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

    private record NodeFrame(String nodeId, int level) {
    }
}
