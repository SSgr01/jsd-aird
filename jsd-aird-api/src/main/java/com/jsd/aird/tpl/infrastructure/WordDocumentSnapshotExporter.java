package com.jsd.aird.tpl.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Rebuilds the main Word document from the IDocumentData snapshot.
 *
 * The original package is still used as the archive and as the source for
 * relationships, styles, headers, footers and embedded media. The editable
 * body itself is generated from the same snapshot that Univer rendered, so the
 * saved DOCX cannot silently diverge from the preview/editing surface.
 */
final class WordDocumentSnapshotExporter {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String XML = "http://www.w3.org/XML/1998/namespace";
    private static final char TABLE_START = '\u001A';
    private static final char TABLE_ROW_START = '\u001B';
    private static final char TABLE_CELL_START = '\u001C';
    private static final char TABLE_CELL_END = '\u001D';
    private static final char TABLE_ROW_END = '\u000E';
    private static final char TABLE_END = '\u000F';
    private static final char IMAGE_BLOCK = '\b';

    byte[] export(byte[] source, JsonNode snapshot) {
        if (snapshot == null || snapshot.path("snapshotFormatVersion").asInt(0) < 5) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "当前 Word 编辑快照版本过旧，请重新导入原始 DOCX 后再编辑");
        }
        try {
            var parts = readParts(source);
            var originalXml = parts.get("word/document.xml");
            if (originalXml == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "DOCX 缺少正文 XML");
            var original = parse(originalXml);
            var document = newDocument();
            var root = document.createElementNS(W, "w:document");
            root.setAttribute("xmlns:w", W);
            root.setAttribute("xmlns:r", "http://schemas.openxmlformats.org/officeDocument/2006/relationships");
            document.appendChild(root);
            var body = document.createElementNS(W, "w:body");
            root.appendChild(body);

            var renderer = new SnapshotRenderer(document, original, snapshot);
            renderer.render(body);
            appendSectionProperties(document, body, original, snapshot.path("documentStyle"));
            var generatedXml = write(document);
            if (containsList(snapshot)) ensureNumberingParts(parts);

            var output = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(output)) {
                for (var part : parts.entrySet()) {
                    zip.putNextEntry(new ZipEntry(part.getKey()));
                    zip.write("word/document.xml".equals(part.getKey()) ? generatedXml : part.getValue());
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word 编辑快照导出失败：" + exception.getMessage());
        }
    }

    private final class SnapshotRenderer {
        private final Document target;
        private final Document original;
        private final JsonNode snapshot;
        private final String stream;
        private final JsonNode body;
        private final List<Element> originalDrawings = new ArrayList<>();
        private int nextDrawing;

        private SnapshotRenderer(Document target, Document original, JsonNode snapshot) {
            this.target = target;
            this.original = original;
            this.snapshot = snapshot;
            this.body = snapshot.path("body");
            this.stream = body.path("dataStream").asText("\r\n");
            var drawings = original.getElementsByTagNameNS("*", "drawing");
            for (var index = 0; index < drawings.getLength(); index++) {
                if (drawings.item(index) instanceof Element drawing) originalDrawings.add(drawing);
            }
        }

        private void render(Element targetBody) {
            var start = 0;
            var position = 0;
            while (position < stream.length()) {
                var value = stream.charAt(position);
                if (value == TABLE_START) {
                    if (position > start) renderParagraph(targetBody, start, position);
                    position = renderTable(targetBody, position);
                    start = position;
                } else if (value == '\r') {
                    renderParagraph(targetBody, start, position);
                    position++;
                    start = position;
                } else if (value == '\n' && position == stream.length() - 1) {
                    position++;
                    start = position;
                } else {
                    position++;
                }
            }
            if (start < stream.length() && !stream.substring(start).isBlank()) renderParagraph(targetBody, start, stream.length());
            if (targetBody.getChildNodes().getLength() == 0) renderParagraph(targetBody, 0, 0);
        }

        private int renderTable(Element parent, int position) {
            var tableId = tableIdAt(position);
            var table = target.createElementNS(W, "w:tbl");
            applyTableProperties(table, snapshot.path("tableSource").path(tableId));
            parent.appendChild(table);
            position++;
            var sourceTable = snapshot.path("tableSource").path(tableId);
            var rowIndex = 0;
            while (position < stream.length() && stream.charAt(position) == TABLE_ROW_START) {
                position++;
                var row = target.createElementNS(W, "w:tr");
                var sourceRow = sourceTable.path("tableRows").path(rowIndex++);
                applyRowProperties(row, sourceRow);
                table.appendChild(row);
                var cellIndex = 0;
                while (position < stream.length() && stream.charAt(position) == TABLE_CELL_START) {
                    position++;
                    var cell = target.createElementNS(W, "w:tc");
                    var sourceCell = sourceRow.path("tableCells").path(cellIndex++);
                    applyCellProperties(cell, sourceCell);
                    row.appendChild(cell);
                    var contentStart = position;
                    while (position < stream.length() && stream.charAt(position) != TABLE_CELL_END) {
                        if (stream.charAt(position) == TABLE_START) {
                            if (position > contentStart) renderParagraph(cell, contentStart, position);
                            position = renderTable(cell, position);
                            contentStart = position;
                        } else if (stream.charAt(position) == '\r') {
                            renderParagraph(cell, contentStart, position);
                            position++;
                            contentStart = position;
                        } else {
                            position++;
                        }
                    }
                    if (contentStart < position || cell.getChildNodes().getLength() == 0) renderParagraph(cell, contentStart, position);
                    if (position < stream.length() && stream.charAt(position) == TABLE_CELL_END) position++;
                }
                if (position < stream.length() && stream.charAt(position) == TABLE_ROW_END) position++;
            }
            if (position < stream.length() && stream.charAt(position) == TABLE_END) position++;
            return position;
        }

        private String tableIdAt(int position) {
            for (var table : body.path("tables")) {
                if (table.path("startIndex").asInt(-1) == position) return table.path("tableId").asText("");
            }
            return "";
        }

        private void renderParagraph(Element parent, int start, int end) {
            var paragraph = target.createElementNS(W, "w:p");
            applyParagraphStyle(paragraph, paragraphStyle(start));
            parent.appendChild(paragraph);
            var cursor = start;
            while (cursor < end) {
                if (stream.charAt(cursor) == IMAGE_BLOCK) {
                    appendOriginalDrawing(paragraph);
                    cursor++;
                    continue;
                }
                var runStart = cursor;
                while (cursor < end && stream.charAt(cursor) != IMAGE_BLOCK) cursor++;
                appendStyledText(paragraph, runStart, cursor);
            }
        }

        private void appendStyledText(Element paragraph, int start, int end) {
            var cursor = start;
            while (cursor < end) {
                var style = styleAt(cursor);
                var next = cursor + 1;
                while (next < end && styleEquals(style, styleAt(next)) && stream.charAt(next) != IMAGE_BLOCK) next++;
                var run = target.createElementNS(W, "w:r");
                appendRunProperties(run, style);
                var index = cursor;
                while (index < next) {
                    var value = stream.charAt(index);
                    if (value == '\t') {
                        run.appendChild(target.createElementNS(W, "w:tab"));
                        index++;
                    } else if (value == '\f' || value == '\n') {
                        var br = target.createElementNS(W, "w:br");
                        if (value == '\f') br.setAttributeNS(W, "w:type", "page");
                        run.appendChild(br);
                        index++;
                    } else if (value != '\r') {
                        var textStart = index;
                        while (index < next && "\t\f\n\r".indexOf(stream.charAt(index)) < 0) index++;
                        var text = target.createElementNS(W, "w:t");
                        if (textStart == cursor || stream.charAt(textStart) == ' ' || stream.charAt(textStart) == '\u00a0') {
                            text.setAttributeNS(XML, "xml:space", "preserve");
                        }
                        text.setTextContent(stream.substring(textStart, index));
                        run.appendChild(text);
                    } else index++;
                }
                if (run.getChildNodes().getLength() > 0) paragraph.appendChild(run);
                cursor = next;
            }
        }

        private JsonNode styleAt(int index) {
            var paragraphStyle = paragraphStyleAt(index).path("textStyle");
            for (var run : body.path("textRuns")) {
                if (index >= run.path("st").asInt(Integer.MAX_VALUE)
                        && index <= run.path("ed").asInt(Integer.MIN_VALUE)) {
                    return mergeTextStyle(paragraphStyle, run.path("ts"));
                }
            }
            return mergeTextStyle(paragraphStyle, null);
        }

        private JsonNode mergeTextStyle(JsonNode paragraphStyle, JsonNode runStyle) {
            var result = JsonNodeFactory.instance.objectNode();
            if (paragraphStyle != null && paragraphStyle.isObject()) {
                result.setAll((ObjectNode) paragraphStyle);
            }
            if (runStyle != null && runStyle.isObject()) {
                result.setAll((ObjectNode) runStyle);
            }
            if (!result.has("fs")) result.put("fs", 14);
            if (!result.has("ff")) result.put("ff", "宋体");
            return result;
        }

        private boolean styleEquals(JsonNode left, JsonNode right) {
            return left == right || left.equals(right);
        }

        private JsonNode paragraphStyle(int start) {
            for (var paragraph : body.path("paragraphs")) {
                if (paragraph.path("startIndex").asInt(-1) == start) return paragraph.path("paragraphStyle");
            }
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }

        private JsonNode paragraphStyleAt(int index) {
            JsonNode result = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            for (var paragraph : body.path("paragraphs")) {
                if (paragraph.path("startIndex").asInt(-1) <= index) result = paragraph.path("paragraphStyle");
                else break;
            }
            return result;
        }

        private void applyParagraphStyle(Element paragraph, JsonNode style) {
            if (style == null || !style.isObject()) return;
            var pPr = target.createElementNS(W, "w:pPr");
            var namedStyle = style.path("namedStyleType").asInt(0);
            if (namedStyle >= 4 && namedStyle <= 8) {
                var pStyle = target.createElementNS(W, "w:pStyle");
                pStyle.setAttributeNS(W, "w:val", "Heading" + (namedStyle - 3));
                pPr.appendChild(pStyle);
            }
            var bullet = style.path("bullet");
            if (bullet.isObject()) {
                var numPr = target.createElementNS(W, "w:numPr");
                var ilvl = target.createElementNS(W, "w:ilvl");
                ilvl.setAttributeNS(W, "w:val", Integer.toString(Math.max(0, bullet.path("nestingLevel").asInt(0))));
                numPr.appendChild(ilvl);
                var numId = target.createElementNS(W, "w:numId");
                var listType = bullet.path("listType").asText("");
                numId.setAttributeNS(W, "w:val", listType.contains("BULLET") ? "2" : "1");
                numPr.appendChild(numId);
                pPr.appendChild(numPr);
            }
            var align = style.path("horizontalAlign").asInt(1);
            if (align > 1) {
                var jc = target.createElementNS(W, "w:jc");
                jc.setAttributeNS(W, "w:val", switch (align) {
                    case 2 -> "center";
                    case 3 -> "right";
                    case 4 -> "both";
                    default -> "left";
                });
                pPr.appendChild(jc);
            }
            appendSpacing(pPr, style, "spaceAbove", "before");
            appendSpacing(pPr, style, "spaceBelow", "after");
            if (pPr.getChildNodes().getLength() > 0) paragraph.insertBefore(pPr, paragraph.getFirstChild());
        }

        private void appendSpacing(Element pPr, JsonNode style, String source, String targetName) {
            var value = style.path(source).path("v").asInt(-1);
            if (value < 0) return;
            var spacing = firstChild(pPr, "spacing");
            if (spacing == null) {
                spacing = target.createElementNS(W, "w:spacing");
                pPr.appendChild(spacing);
            }
            spacing.setAttributeNS(W, "w:" + targetName, Integer.toString(Math.round(value * 20f)));
        }

        private void appendRunProperties(Element run, JsonNode style) {
            if (style == null || !style.isObject()) return;
            var rPr = target.createElementNS(W, "w:rPr");
            if (style.path("bl").asInt(0) != 0) rPr.appendChild(target.createElementNS(W, "w:b"));
            if (style.path("it").asInt(0) != 0) rPr.appendChild(target.createElementNS(W, "w:i"));
            if (style.path("ul").isObject()) rPr.appendChild(target.createElementNS(W, "w:u"));
            var fontSize = style.path("fs").asDouble(14);
            if (fontSize > 0) {
                var size = target.createElementNS(W, "w:sz");
                size.setAttributeNS(W, "w:val", Integer.toString(Math.max(1, (int) Math.round(fontSize * 2))));
                rPr.appendChild(size);
            }
            var fontFamily = style.path("ff").asText("宋体");
            if (!fontFamily.isBlank()) {
                var fonts = target.createElementNS(W, "w:rFonts");
                for (var name : List.of("ascii", "hAnsi", "eastAsia", "cs")) fonts.setAttributeNS(W, "w:" + name, fontFamily);
                rPr.appendChild(fonts);
            }
            if (style.path("cl").isObject()) {
                var color = style.path("cl").path("rgb").asText("").replace("#", "");
                if (!color.isBlank()) {
                    var colorNode = target.createElementNS(W, "w:color");
                    colorNode.setAttributeNS(W, "w:val", color);
                    rPr.appendChild(colorNode);
                }
            }
            if (rPr.getChildNodes().getLength() > 0) run.appendChild(rPr);
        }

        private void appendOriginalDrawing(Element paragraph) {
            if (nextDrawing >= originalDrawings.size()) return;
            paragraph.appendChild(target.importNode(originalDrawings.get(nextDrawing++), true));
        }

        private void applyTableProperties(Element table, JsonNode source) {
            if (source == null || !source.isObject()) return;
            var properties = target.createElementNS(W, "w:tblPr");
            var width = source.path("size").path("width").path("v").asInt(0);
            if (width > 0) {
                var tblW = target.createElementNS(W, "w:tblW");
                tblW.setAttributeNS(W, "w:w", Integer.toString(Math.round(width * 20f)));
                tblW.setAttributeNS(W, "w:type", "dxa");
                properties.appendChild(tblW);
            }
            var align = source.path("align").asInt(0);
            if (align > 0) {
                var jc = target.createElementNS(W, "w:jc");
                jc.setAttributeNS(W, "w:val", align == 1 ? "center" : "right");
                properties.appendChild(jc);
            }
            if (source.path("layout").asInt(0) == 1) {
                var layout = target.createElementNS(W, "w:tblLayout");
                layout.setAttributeNS(W, "w:type", "fixed");
                properties.appendChild(layout);
            }
            table.appendChild(properties);
            var grid = target.createElementNS(W, "w:tblGrid");
            for (var column : source.path("tableColumns")) {
                var gridColumn = target.createElementNS(W, "w:gridCol");
                gridColumn.setAttributeNS(W, "w:w", Integer.toString((int) Math.round(column.path("size").path("width").path("v").asDouble(72) * 20)));
                grid.appendChild(gridColumn);
            }
            table.appendChild(grid);
        }

        private void applyRowProperties(Element row, JsonNode source) {
            var value = source.path("trHeight").path("val").path("v").asInt(0);
            if (value <= 0) return;
            var trPr = target.createElementNS(W, "w:trPr");
            var height = target.createElementNS(W, "w:trHeight");
            height.setAttributeNS(W, "w:val", Integer.toString(Math.round(value * 20f)));
            trPr.appendChild(height);
            row.appendChild(trPr);
        }

        private void applyCellProperties(Element cell, JsonNode source) {
            if (source == null || !source.isObject()) return;
            var columnSpan = source.path("columnSpan").asInt(1);
            var rowSpan = source.path("rowSpan").asInt(1);
            var tcPr = target.createElementNS(W, "w:tcPr");
            if (columnSpan > 1) {
                var span = target.createElementNS(W, "w:gridSpan");
                span.setAttributeNS(W, "w:val", Integer.toString(columnSpan));
                tcPr.appendChild(span);
            }
            if (rowSpan == 0) {
                var merge = target.createElementNS(W, "w:vMerge");
                tcPr.appendChild(merge);
            } else if (rowSpan > 1) {
                var merge = target.createElementNS(W, "w:vMerge");
                merge.setAttributeNS(W, "w:val", "restart");
                tcPr.appendChild(merge);
            }
            appendCellBorders(tcPr, source);
            if (tcPr.getChildNodes().getLength() > 0) cell.appendChild(tcPr);
        }

        private void appendCellBorders(Element tcPr, JsonNode source) {
            var borderElements = new ArrayList<Element>();
            for (var side : List.of("top", "right", "bottom", "left")) {
                var border = source.path("border" + Character.toUpperCase(side.charAt(0)) + side.substring(1));
                var element = target.createElementNS(W, "w:" + side);
                element.setAttributeNS(W, "w:val", borderValue(border));
                var width = Math.max(1, (int) Math.round(border.path("width").path("v").asDouble(1) * 8));
                element.setAttributeNS(W, "w:sz", Integer.toString(width));
                element.setAttributeNS(W, "w:space", "0");
                var color = border.path("color").path("rgb").asText("").replace("#", "");
                element.setAttributeNS(W, "w:color", color.isBlank() ? "000000" : color);
                borderElements.add(element);
            }
            if (borderElements.isEmpty()) return;
            var borders = target.createElementNS(W, "w:tcBorders");
            borderElements.forEach(borders::appendChild);
            tcPr.appendChild(borders);
        }

        private String borderValue(JsonNode border) {
            if (border == null || !border.isObject()) return "single";
            return switch (border.path("dashStyle").asInt(1)) {
                case 2 -> "dotted";
                case 3 -> "dashed";
                default -> "single";
            };
        }
    }

    private void appendSectionProperties(Document document, Element body, Document original, JsonNode style) {
        var sectPr = document.createElementNS(W, "w:sectPr");
        var pageSize = style.path("pageSize");
        var pgSz = document.createElementNS(W, "w:pgSz");
        pgSz.setAttributeNS(W, "w:w", Integer.toString((int) Math.round(pageSize.path("width").asDouble(595) * 20)));
        pgSz.setAttributeNS(W, "w:h", Integer.toString((int) Math.round(pageSize.path("height").asDouble(842) * 20)));
        if (style.path("pageOrient").asInt(0) == 1) pgSz.setAttributeNS(W, "w:orient", "landscape");
        sectPr.appendChild(pgSz);
        var pgMar = document.createElementNS(W, "w:pgMar");
        for (var item : List.of(
                new String[]{"top", "marginTop"}, new String[]{"right", "marginRight"},
                new String[]{"bottom", "marginBottom"}, new String[]{"left", "marginLeft"},
                new String[]{"header", "marginHeader"}, new String[]{"footer", "marginFooter"})) {
            pgMar.setAttributeNS(W, "w:" + item[0], Integer.toString((int) Math.round(style.path(item[1]).asDouble(item[1].contains("Header") || item[1].contains("Footer") ? 36 : 72) * 20)));
        }
        sectPr.appendChild(pgMar);
        var originalSectPr = lastElement(original.getDocumentElement(), "sectPr");
        if (originalSectPr != null) {
            for (var child : directChildren(originalSectPr)) {
                if ("headerReference".equals(child.getLocalName()) || "footerReference".equals(child.getLocalName())
                        || "titlePg".equals(child.getLocalName()) || "cols".equals(child.getLocalName())
                        || "docGrid".equals(child.getLocalName())) {
                    sectPr.appendChild(document.importNode(child, true));
                }
            }
        }
        body.appendChild(sectPr);
    }

    private Element firstChild(Element parent, String localName) {
        for (var index = 0; index < parent.getChildNodes().getLength(); index++) {
            var child = parent.getChildNodes().item(index);
            if (child instanceof Element element && localName.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private Element lastElement(Element parent, String localName) {
        Element result = null;
        if (parent == null) return null;
        for (var index = 0; index < parent.getChildNodes().getLength(); index++) {
            var child = parent.getChildNodes().item(index);
            if (child instanceof Element element && localName.equals(element.getLocalName())) result = element;
        }
        return result;
    }

    private List<Element> directChildren(Element parent) {
        var result = new ArrayList<Element>();
        for (var index = 0; index < parent.getChildNodes().getLength(); index++) {
            if (parent.getChildNodes().item(index) instanceof Element element) result.add(element);
        }
        return result;
    }

    private Map<String, byte[]> readParts(byte[] source) throws Exception {
        var parts = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(source))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                parts.put(entry.getName(), zip.readAllBytes());
                entry = zip.getNextEntry();
            }
        }
        return parts;
    }

    private Document newDocument() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().newDocument();
    }

    private Document parse(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private byte[] write(Document document) throws Exception {
        var output = new ByteArrayOutputStream();
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private boolean containsList(JsonNode snapshot) {
        for (var paragraph : snapshot.path("body").path("paragraphs")) {
            if (paragraph.path("paragraphStyle").path("bullet").isObject()) return true;
        }
        return false;
    }

    private void ensureNumberingParts(Map<String, byte[]> parts) {
        parts.putIfAbsent("word/numbering.xml", defaultNumberingXml().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var contentTypes = new String(parts.getOrDefault("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes()), java.nio.charset.StandardCharsets.UTF_8);
        if (!contentTypes.contains("/word/numbering.xml")) {
            contentTypes = contentTypes.replace("</Types>", "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/></Types>");
            parts.put("[Content_Types].xml", contentTypes.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var relationshipsName = "word/_rels/document.xml.rels";
        var relationships = new String(parts.getOrDefault(relationshipsName, "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>".getBytes()), java.nio.charset.StandardCharsets.UTF_8);
        if (!relationships.contains("numbering.xml")) {
            relationships = relationships.replace("</Relationships>", "<Relationship Id=\"rIdJsdNumbering\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/></Relationships>");
            parts.put(relationshipsName, relationships.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private String defaultNumberingXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:start w:val=\"1\"/></w:lvl></w:abstractNum>"
                + "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/><w:rPr><w:rFonts w:ascii=\"Symbol\" w:hAnsi=\"Symbol\"/></w:rPr></w:lvl></w:abstractNum>"
                + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>"
                + "</w:numbering>";
    }
}
