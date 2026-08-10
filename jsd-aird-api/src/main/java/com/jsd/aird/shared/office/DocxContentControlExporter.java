package com.jsd.aird.shared.office;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;

/** Writes scalar values into native DOCX content controls using stable marker ids. */
@Component
public final class DocxContentControlExporter {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private final ObjectMapper objectMapper;

    public DocxContentControlExporter(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public Result export(byte[] source, JsonNode mapping, JsonNode data) {
        var warnings = new ArrayList<SnapshotWorkbookExporter.Warning>();
        try {
            var parts = new ArrayList<Part>();
            byte[] documentXml = null;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(source))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    var bytes = zip.readAllBytes(); parts.add(new Part(entry.getName(), bytes));
                    if ("word/document.xml".equals(entry.getName())) documentXml = bytes;
                }
            }
            if (documentXml == null) throw new IllegalArgumentException("DOCX 缺少正文 XML");
            var document = parse(documentXml);
            if (mapping != null && mapping.isArray()) {
                for (var binding : mapping) writeBinding(document, binding, data, warnings);
            }
            var patched = write(document);
            var output = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(output)) {
                for (var part : parts) {
                    zip.putNextEntry(new ZipEntry(part.name()));
                    zip.write("word/document.xml".equals(part.name()) ? patched : part.bytes());
                    zip.closeEntry();
                }
            }
            return new Result(output.toByteArray(), List.copyOf(warnings));
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("DOCX 导出失败：" + exception.getMessage(), exception); }
    }

    private void writeBinding(Document document, JsonNode binding, JsonNode data,
                              List<SnapshotWorkbookExporter.Warning> warnings) {
        var marker = first(binding.path("markerId"), binding.path("locator").path("markerId"));
        var path = binding.path("dataPath").asText("");
        if (marker.isBlank()) {
            warnings.add(new SnapshotWorkbookExporter.Warning("BINDING_MISSING", binding.path("bindingId").asText(""), path, "Word 字段没有 markerId，已留空"));
            return;
        }
        var values = new ArrayList<>(readValues(data, path));
        if (values.isEmpty()) values.add(null);
        var controls = document.getElementsByTagNameNS("*", "sdt");
        var matched = new ArrayList<Element>();
        for (var index = 0; index < controls.getLength(); index++) {
            if (!(controls.item(index) instanceof Element control)) continue;
            var props = firstElement(control, "sdtPr");
            var dataBinding = props == null ? null : firstElement(props, "dataBinding");
            var stored = dataBinding == null ? "" : attr(dataBinding, "storeItemID");
            var tag = props == null ? "" : attr(firstElement(props, "tag"), "val");
            if (!marker.equals(stored) && !marker.equals(tag) && !marker.equals(attr(firstElement(props, "id"), "val"))) continue;
            matched.add(control);
        }
        for (var index = 0; index < values.size(); index++) {
            Element control = index < matched.size() ? matched.get(index) : cloneControl(matched, index);
            if (control != null) {
                var value = values.get(index);
                replaceText(control, value == null || value.isNull() || value.isMissingNode() ? "" : value.isValueNode() ? value.asText() : value.toString());
            }
        }
        if (matched.isEmpty()) warnings.add(new SnapshotWorkbookExporter.Warning("BINDING_MISSING", binding.path("bindingId").asText(""), path, "Word 内容控件不存在，已留空"));
    }

    private Element cloneControl(List<Element> matched, int index) {
        if (matched.isEmpty()) return null;
        var source = matched.get(Math.min(index, matched.size() - 1));
        var parent = source.getParentNode();
        if (parent == null) return null;
        var clone = (Element) source.cloneNode(true);
        parent.insertBefore(clone, source.getNextSibling());
        matched.add(clone);
        return clone;
    }

    private void replaceText(Element control, String text) {
        var texts = control.getElementsByTagNameNS("*", "t");
        if (texts.getLength() == 0) {
            var content = firstElement(control, "sdtContent");
            if (content == null) return;
            var run = content.getOwnerDocument().createElementNS(W, "w:r");
            var node = content.getOwnerDocument().createElementNS(W, "w:t"); node.setTextContent(text);
            run.appendChild(node); content.appendChild(run); return;
        }
        for (var index = texts.getLength() - 1; index > 0; index--) texts.item(index).getParentNode().removeChild(texts.item(index));
        texts.item(0).setTextContent(text);
    }

    private Document parse(byte[] content) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
    }

    private byte[] write(Document document) throws Exception {
        var factory = TransformerFactory.newInstance(); factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var transformer = factory.newTransformer(); transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        var output = new ByteArrayOutputStream(); transformer.transform(new DOMSource(document), new StreamResult(output)); return output.toByteArray();
    }

    private List<JsonNode> readValues(JsonNode current, String pointer) {
        if (pointer == null || pointer.isBlank()) return List.of();
        var parts = new ArrayList<String>();
        for (var part : pointer.split("/")) if (!part.isBlank()) parts.add(part.replace("~1", "/").replace("~0", "~"));
        return readValues(current, parts, 0);
    }

    private List<JsonNode> readValues(JsonNode current, List<String> parts, int index) {
        if (index >= parts.size()) return current == null ? List.of() : List.of(current);
        if (current == null || current.isMissingNode() || current.isNull()) return List.of();
        var part = parts.get(index);
        if ("*".equals(part)) {
            if (!current.isArray()) return List.of();
            var result = new ArrayList<JsonNode>();
            current.forEach(item -> result.addAll(readValues(item, parts, index + 1)));
            return result;
        }
        return readValues(current.path(part), parts, index + 1);
    }
    private String first(JsonNode one, JsonNode two) { return one != null && !one.asText("").isBlank() ? one.asText() : two == null ? "" : two.asText(""); }
    private Element firstElement(Node node, String local) { if (node == null) return null; for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element element && local.equals(element.getLocalName())) return element; return null; }
    private String attr(Element node, String name) { return node == null ? "" : node.getAttributeNS(W, name); }

    public record Result(byte[] content, List<SnapshotWorkbookExporter.Warning> warnings) {}
    private record Part(String name, byte[] bytes) {}
}
