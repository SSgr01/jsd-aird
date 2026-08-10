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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.application.port.WordOoxmlPatcher;
import org.springframework.stereotype.Component;
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

@Component
public class WordOoxmlPatchService implements WordOoxmlPatcher {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final WordDocumentSnapshotExporter snapshotExporter = new WordDocumentSnapshotExporter();

    public byte[] apply(byte[] source, ArrayNode operations) {
        if (operations == null || operations.isEmpty()) return source;
        try {
            var parts = new ArrayList<Part>();
            byte[] documentXml = null;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(source))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    var bytes = zip.readAllBytes();
                    parts.add(new Part(entry.getName(), bytes, entry.getMethod()));
                    if ("word/document.xml".equals(entry.getName())) documentXml = bytes;
                }
            }
            if (documentXml == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "DOCX 缺少正文 XML");
            var document = parse(documentXml);
            // Resolve positional node IDs once against the base document.  An
            // insertion wraps/clones runs and therefore changes later NodeList
            // indexes; using the initial node references keeps a batch of
            // INSERT_CONTENT_CONTROL operations deterministic.
            var stableTargets = stableTargetNodes(document);
            for (var operation : operations) applyOne(document, operation, stableTargets);
            var patched = write(document);
            var result = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(result)) {
                for (var part : parts) {
                    zip.putNextEntry(new ZipEntry(part.name()));
                    zip.write("word/document.xml".equals(part.name()) ? patched : part.bytes());
                    zip.closeEntry();
                }
            }
            return result.toByteArray();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word Patch 失败：" + exception.getMessage());
        }
    }

    @Override
    public byte[] applySnapshot(byte[] source, JsonNode snapshot) {
        return snapshotExporter.export(source, snapshot);
    }

    private ArrayNode objectMapperArray(List<JsonNode> nodes) {
        var result = JsonNodeFactory.instance.arrayNode();
        nodes.forEach(result::add);
        return result;
    }

    private void applyOne(Document document, JsonNode operation, Map<String, Node> stableTargets) {
        var type = operation.path("type").asText("");
        var target = operation.path("targetId").asText("");
        if (target.isBlank()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word Patch 缺少 targetId");
        switch (type) {
            case "INSERT_CONTENT_CONTROL" -> insertContentControl(document, target, operation, stableTargets);
            case "REPLACE_CONTENT_CONTROL" -> replaceControl(document, target, operation);
            case "REPLACE_TEXT", "REPLACE_RUN_TEXT" -> replaceText(document, target, operation);
            case "SET_PARAGRAPH_ALIGNMENT" -> setParagraphAlignment(document, target, operation.path("alignment").asText(""));
            case "SET_PARAGRAPH_STYLE" -> setParagraphStyle(document, target, operation);
            case "SET_RUN_STYLE" -> setRunStyle(document, target, operation);
            case "REPLACE_TABLE_CELL" -> replaceTableCell(document, target, operation);
            case "ADD_TABLE_ROW" -> addTableRow(document, target, operation);
            case "DELETE_TABLE_ROW" -> deleteTableRow(document, target, operation);
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的 Word Patch 操作：" + type);
        }
    }

    private void insertContentControl(
            Document document, String target, JsonNode operation, Map<String, Node> stableTargets
    ) {
        var markerId = operation.path("markerId").asText("").strip();
        if (markerId.isBlank()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word 内容控件缺少 markerId");
        var node = findTargetNode(document, target, stableTargets);
        if (node == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "找不到 Word 插入位置：" + target);
        if (hasAncestor(node, "sdt")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不能在已有内容控件内部重复插入字段");
        }
        var baseText = operation.path("baseText").asText("");
        if (operation.has("baseText") && !baseText.equals(node.getTextContent())) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Word 选区内容已变化");
        }
        var parent = node.getParentNode();
        if (parent == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word 插入位置没有父节点");
        var control = document.createElementNS(W, "w:sdt");
        var properties = document.createElementNS(W, "w:sdtPr");
        var id = document.createElementNS(W, "w:id");
        id.setAttributeNS(W, "w:val", Integer.toUnsignedString(markerId.hashCode()));
        properties.appendChild(id);
        var tag = operation.path("tag").asText("");
        if (!tag.isBlank()) {
            var tagNode = document.createElementNS(W, "w:tag");
            tagNode.setAttributeNS(W, "w:val", tag);
            properties.appendChild(tagNode);
        }
        var dataBinding = document.createElementNS(W, "w:dataBinding");
        dataBinding.setAttributeNS(W, "w:storeItemID", markerId);
        properties.appendChild(dataBinding);
        var alias = operation.path("alias").asText("");
        if (!alias.isBlank()) {
            var aliasNode = document.createElementNS(W, "w:alias");
            aliasNode.setAttributeNS(W, "w:val", alias);
            properties.appendChild(aliasNode);
        }
        control.appendChild(properties);
        var content = document.createElementNS(W, "w:sdtContent");
        if ("tc".equals(node.getLocalName())) {
            // A table-cell target is a block-level location. Preserve tcPr and
            // move the existing paragraphs into the content control.
            var children = new ArrayList<Node>();
            for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element && "tcPr".equals(element.getLocalName())) continue;
                children.add(child);
            }
            for (var child : children) content.appendChild(child);
            control.appendChild(content);
            node.appendChild(control);
            return;
        }
        Node replacement = node;
        if ("text".equals(node.getLocalName())) {
            var run = node.getParentNode();
            if (run instanceof Element element && "r".equals(element.getLocalName())) replacement = run;
        }
        content.appendChild(replacement.cloneNode(true));
        control.appendChild(content);
        parent.replaceChild(control, replacement);
    }

    private Node findTargetNode(Document document, String target) {
        return findTargetNode(document, target, Map.of());
    }

    private Node findTargetNode(Document document, String target, Map<String, Node> stableTargets) {
        var stable = stableTargets.get(target);
        if (stable != null && stable.getParentNode() != null) return stable;
        if (target.startsWith("text-")) return indexed(document.getElementsByTagNameNS("*", "t"), target, "text-");
        if (target.startsWith("run-")) return indexed(document.getElementsByTagNameNS("*", "r"), target, "run-");
        if (target.startsWith("cell-")) return indexed(document.getElementsByTagNameNS("*", "tc"), target, "cell-");
        if (target.startsWith("paragraph-")) return findParagraph(document, target);
        return null;
    }

    private Map<String, Node> stableTargetNodes(Document document) {
        var targets = new HashMap<String, Node>();
        putIndexed(targets, document.getElementsByTagNameNS("*", "t"), "text-");
        putIndexed(targets, document.getElementsByTagNameNS("*", "r"), "run-");
        putIndexed(targets, document.getElementsByTagNameNS("*", "tc"), "cell-");
        var paragraphs = document.getElementsByTagNameNS("*", "p");
        for (var index = 0; index < paragraphs.getLength(); index++) {
            var paragraph = paragraphs.item(index);
            targets.putIfAbsent("paragraph-" + (index + 1), paragraph);
            if (paragraph instanceof Element element) {
                var paraId = attribute(element, "paraId");
                if (!paraId.isBlank()) targets.putIfAbsent("paragraph-" + paraId, paragraph);
            }
        }
        return targets;
    }

    private void putIndexed(Map<String, Node> targets, NodeList nodes, String prefix) {
        for (var index = 0; index < nodes.getLength(); index++) {
            targets.put(prefix + (index + 1), nodes.item(index));
        }
    }

    private boolean hasAncestor(Node node, String localName) {
        var current = node.getParentNode();
        while (current != null) {
            if (current instanceof Element element && localName.equals(element.getLocalName())) return true;
            current = current.getParentNode();
        }
        return false;
    }

    private void replaceText(Document document, String target, JsonNode operation) {
        if (target.startsWith("paragraph-")) {
            var paragraph = findParagraph(document, target);
            replaceDescendantText(paragraph, operation);
            return;
        }
        var texts = document.getElementsByTagNameNS("*", "t");
        if (target.startsWith("text-")) {
            var index = parseIndex(target, "text-");
            if (index >= 0 && index < texts.getLength()) {
                var node = texts.item(index);
                assertBaseText(node.getTextContent(), operation);
                node.setTextContent(operation.path("text").asText(""));
                return;
            }
        }
        if (target.startsWith("run-")) {
            var run = indexed(document.getElementsByTagNameNS("*", "r"), target, "run-");
            if (run != null) {
                replaceDescendantText(run, operation);
                return;
            }
        }
        throw new ApiException(ApiErrorCode.BAD_REQUEST, "找不到 Word 文本锚点：" + target);
    }

    private void replaceDescendantText(Element parent, JsonNode operation) {
        var texts = parent.getElementsByTagNameNS("*", "t");
        if (texts.getLength() == 0) throw new ApiException(ApiErrorCode.BAD_REQUEST, "文本锚点没有可编辑文本");
        assertBaseText(parent.getTextContent(), operation);
        for (var index = texts.getLength() - 1; index > 0; index--) {
            texts.item(index).getParentNode().removeChild(texts.item(index));
        }
        texts.item(0).setTextContent(operation.path("text").asText(""));
    }

    private void assertBaseText(String actual, JsonNode operation) {
        if (operation.has("baseText") && !operation.path("baseText").asText().equals(actual)) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Word 文本基线已变化");
        }
    }

    private void replaceControl(Document document, String target, JsonNode operation) {
        var controls = document.getElementsByTagNameNS("*", "sdt");
        for (var i = 0; i < controls.getLength(); i++) {
            if (!(controls.item(i) instanceof Element control)) continue;
            var props = first(control, "sdtPr");
            var id = props == null ? "" : attribute(first(props, "id"), "val");
            var tag = props == null ? "" : attribute(first(props, "tag"), "val");
            var storedMarker = props == null ? "" : attribute(first(props, "dataBinding"), "storeItemID");
            if (!target.equals(id) && !target.equals(tag) && !target.equals(storedMarker)
                    && !target.equals("content-control-" + (i + 1))) continue;
            var texts = control.getElementsByTagNameNS("*", "t");
            if (texts.getLength() == 0) {
                // Empty controls are valid Word placeholders.  Materialize a
                // simple run on first write instead of rejecting the field;
                // complex controls still retain their original OOXML content.
                var content = first(control, "sdtContent");
                if (content == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "内容控件缺少 sdtContent：" + target);
                var run = document.createElementNS(W, "w:r");
                var text = document.createElementNS(W, "w:t");
                text.setTextContent(operation.path("text").asText(""));
                run.appendChild(text);
                content.appendChild(run);
                return;
            }
            assertBaseText(control.getTextContent(), operation);
            for (var j = texts.getLength() - 1; j > 0; j--) texts.item(j).getParentNode().removeChild(texts.item(j));
            texts.item(0).setTextContent(operation.path("text").asText(""));
            return;
        }
        throw new ApiException(ApiErrorCode.BAD_REQUEST, "找不到 Word 内容控件：" + target);
    }

    private void setParagraphAlignment(Document document, String target, String alignment) {
        var paragraph = findParagraph(document, target);
        var pPr = paragraphProperties(document, paragraph);
        var jc = first(pPr, "jc");
        if (jc == null) { jc = document.createElementNS(W, "w:jc"); pPr.appendChild(jc); }
        jc.setAttributeNS(W, "w:val", alignment);
    }

    private void setParagraphStyle(Document document, String target, JsonNode operation) {
        var paragraph = findParagraph(document, target);
        var pPr = paragraphProperties(document, paragraph);
        if (operation.has("alignment")) {
            var jc = first(pPr, "jc");
            if (jc == null) { jc = document.createElementNS(W, "w:jc"); pPr.appendChild(jc); }
            jc.setAttributeNS(W, "w:val", operation.path("alignment").asText());
        }
        if (operation.has("indentLeft") || operation.has("indentRight")
                || operation.has("firstLine") || operation.has("hanging")) {
            var ind = first(pPr, "ind");
            if (ind == null) { ind = document.createElementNS(W, "w:ind"); pPr.appendChild(ind); }
            setAttribute(ind, "left", operation, "indentLeft");
            setAttribute(ind, "right", operation, "indentRight");
            setAttribute(ind, "firstLine", operation, "firstLine");
            setAttribute(ind, "hanging", operation, "hanging");
        }
        if (operation.has("numberingId")) {
            var numPr = first(pPr, "numPr");
            if (numPr == null) { numPr = document.createElementNS(W, "w:numPr"); pPr.appendChild(numPr); }
            var numId = first(numPr, "numId");
            if (numId == null) { numId = document.createElementNS(W, "w:numId"); numPr.appendChild(numId); }
            numId.setAttributeNS(W, "w:val", operation.path("numberingId").asText());
            if (operation.has("numberingLevel")) {
                var ilvl = first(numPr, "ilvl");
                if (ilvl == null) { ilvl = document.createElementNS(W, "w:ilvl"); numPr.insertBefore(ilvl, numPr.getFirstChild()); }
                ilvl.setAttributeNS(W, "w:val", operation.path("numberingLevel").asText());
            }
        }
    }

    private Element findParagraph(Document document, String target) {
        var paragraphs = document.getElementsByTagNameNS("*", "p");
        for (var i = 0; i < paragraphs.getLength(); i++) {
            if (!(paragraphs.item(i) instanceof Element paragraph)) continue;
            var paraId = attribute(paragraph, "paraId");
            if (target.equals("paragraph-" + (i + 1)) || (!paraId.isBlank() && target.equals("paragraph-" + paraId))) {
                return paragraph;
            }
        }
        throw new ApiException(ApiErrorCode.BAD_REQUEST, "找不到 Word 段落：" + target);
    }

    private Element paragraphProperties(Document document, Element paragraph) {
        var pPr = first(paragraph, "pPr");
        if (pPr == null) { pPr = document.createElementNS(W, "w:pPr"); paragraph.insertBefore(pPr, paragraph.getFirstChild()); }
        return pPr;
    }

    private void setAttribute(Element element, String name, JsonNode operation, String key) {
        if (operation.has(key)) element.setAttributeNS(W, "w:" + name, operation.path(key).asText());
    }

    private void setRunStyle(Document document, String target, JsonNode operation) {
        if (target.startsWith("run-")) {
            var run = indexed(document.getElementsByTagNameNS("*", "r"), target, "run-");
            if (run != null) { applyRunStyle(document, run, operation); return; }
        }
        if (target.startsWith("text-")) {
            var text = indexed(document.getElementsByTagNameNS("*", "t"), target, "text-");
            if (text != null && text.getParentNode().getParentNode() instanceof Element run
                    && "r".equals(run.getLocalName())) {
                applyRunStyle(document, run, operation); return;
            }
        }
        var controls = document.getElementsByTagNameNS("*", "sdt");
        for (var i = 0; i < controls.getLength(); i++) {
            if (!target.equals("content-control-" + (i + 1))) continue;
            var runs = ((Element) controls.item(i)).getElementsByTagNameNS("*", "r");
            for (var j = 0; j < runs.getLength(); j++) applyRunStyle(document, (Element) runs.item(j), operation);
            return;
        }
        throw new ApiException(ApiErrorCode.BAD_REQUEST, "找不到 Word 字符范围：" + target);
    }

    private void applyRunStyle(Document document, Element run, JsonNode operation) {
        var rPr = first(run, "rPr");
        if (rPr == null) { rPr = document.createElementNS(W, "w:rPr"); run.insertBefore(rPr, run.getFirstChild()); }
        setToggle(document, rPr, "b", operation, "bold");
        setToggle(document, rPr, "i", operation, "italic");
        setToggle(document, rPr, "u", operation, "underline");
        if (operation.has("color")) {
            var color = first(rPr, "color");
            if (color == null) { color = document.createElementNS(W, "w:color"); rPr.appendChild(color); }
            color.setAttributeNS(W, "w:val", operation.path("color").asText().replace("#", ""));
        }
        if (operation.has("fontSize")) {
            var size = first(rPr, "sz");
            if (size == null) { size = document.createElementNS(W, "w:sz"); rPr.appendChild(size); }
            size.setAttributeNS(W, "w:val", Integer.toString(Math.max(1, operation.path("fontSize").asInt() * 2)));
        }
        if (operation.has("fontFamily")) {
            var fonts = first(rPr, "rFonts");
            if (fonts == null) { fonts = document.createElementNS(W, "w:rFonts"); rPr.appendChild(fonts); }
            var family = operation.path("fontFamily").asText();
            for (var name : List.of("ascii", "hAnsi", "eastAsia", "cs")) {
                fonts.setAttributeNS(W, "w:" + name, family);
            }
        }
    }

    private void replaceTableCell(Document document, String target, JsonNode operation) {
        var cell = findCell(document, target);
        if (hasMergedCell(cell)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "复杂合并表格单元格不可编辑");
        replaceDescendantText(cell, operation);
    }

    private void addTableRow(Document document, String target, JsonNode operation) {
        var table = findTable(document, target);
        if (hasComplexTable(table)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "复杂合并表格不支持新增行");
        var rows = directChildren(table, "tr");
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "表格没有普通行");
        var source = rows.get(Math.min(Math.max(operation.path("rowIndex").asInt(rows.size() - 1), 0), rows.size() - 1));
        var clone = source.cloneNode(true);
        table.insertBefore(clone, operation.path("rowIndex").asInt(-1) >= rows.size() ? null : rows.get(Math.max(operation.path("rowIndex").asInt(-1), 0)));
    }

    private void deleteTableRow(Document document, String target, JsonNode operation) {
        var table = findTable(document, target);
        if (hasComplexTable(table)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "复杂合并表格不支持删除行");
        var rows = directChildren(table, "tr");
        var index = operation.path("rowIndex").asInt(-1);
        if (index < 0 || index >= rows.size() || rows.size() == 1) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "普通表格行索引无效");
        }
        table.removeChild(rows.get(index));
    }

    private Element findTable(Document document, String target) {
        var tables = document.getElementsByTagNameNS("*", "tbl");
        var index = parseIndex(target, "table-");
        if (index < 0 || index >= tables.getLength()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "找不到 Word 表格：" + target);
        }
        return (Element) tables.item(index);
    }

    private Element findCell(Document document, String target) {
        if (target.startsWith("cell-")) {
            var cell = indexed(document.getElementsByTagNameNS("*", "tc"), target, "cell-");
            if (cell != null) return cell;
        }
        if (target.startsWith("table-") && target.contains("-cell-")) {
            var parts = target.substring("table-".length()).split("-cell-");
            if (parts.length == 2) {
                var table = findTable(document, "table-" + parts[0]);
                var rows = directChildren(table, "tr");
                var rowParts = parts[1].split("-");
                if (rowParts.length == 2) {
                    var row = Integer.parseInt(rowParts[0]) - 1;
                    var col = Integer.parseInt(rowParts[1]) - 1;
                    if (row >= 0 && row < rows.size()) {
                        var cells = directChildren(rows.get(row), "tc");
                        if (col >= 0 && col < cells.size()) return cells.get(col);
                    }
                }
            }
        }
        throw new ApiException(ApiErrorCode.BAD_REQUEST, "找不到 Word 表格单元格：" + target);
    }

    private boolean hasComplexTable(Element table) {
        return table.getElementsByTagNameNS("*", "gridSpan").getLength() > 0
                || table.getElementsByTagNameNS("*", "vMerge").getLength() > 0;
    }

    private boolean hasMergedCell(Element cell) {
        return cell.getElementsByTagNameNS("*", "gridSpan").getLength() > 0
                || cell.getElementsByTagNameNS("*", "vMerge").getLength() > 0;
    }

    private Element indexed(NodeList nodes, String target, String prefix) {
        var index = parseIndex(target, prefix);
        return index >= 0 && index < nodes.getLength() && nodes.item(index) instanceof Element element ? element : null;
    }

    private int parseIndex(String target, String prefix) {
        if (!target.startsWith(prefix)) return -1;
        try { return Integer.parseInt(target.substring(prefix.length())) - 1; }
        catch (NumberFormatException ignored) { return -1; }
    }

    private void setToggle(Document document, Element rPr, String name, JsonNode operation, String key) {
        if (!operation.has(key)) return;
        var node = first(rPr, name);
        if (operation.path(key).asBoolean()) {
            if (node == null) { node = document.createElementNS(W, "w:" + name); rPr.appendChild(node); }
        } else if (node != null) rPr.removeChild(node);
    }

    private Element first(Element parent, String localName) {
        if (parent == null) return null;
        var children = parent.getChildNodes();
        for (var i = 0; i < children.getLength(); i++)
            if (children.item(i) instanceof Element e && localName.equals(e.getLocalName())) return e;
        return null;
    }

    private String attribute(Element element, String name) {
        if (element == null) return "";
        var value = element.getAttributeNS(W, name);
        if (value != null && !value.isBlank()) return value;
        for (var i = 0; i < element.getAttributes().getLength(); i++) {
            var attr = element.getAttributes().item(i);
            if (name.equals(attr.getLocalName()) || name.equals(attr.getNodeName())) return attr.getNodeValue();
        }
        return "";
    }

    private List<Element> directChildren(Element parent, String localName) {
        var result = new ArrayList<Element>();
        var children = parent.getChildNodes();
        for (var i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && localName.equals(element.getLocalName())) result.add(element);
        }
        return result;
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

    private record Part(String name, byte[] bytes, int method) {}
}
