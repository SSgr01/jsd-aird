package com.jsd.aird.tpl.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.application.port.WordDocumentParser;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Component
public class DocxStructureParser implements OfficeStructureParser, WordDocumentParser {

    private static final String WORDPROCESSINGML =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String RELATIONSHIPS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final char TABLE_START = '\u001A';
    private static final char TABLE_ROW_START = '\u001B';
    private static final char TABLE_CELL_START = '\u001C';
    private static final char TABLE_CELL_END = '\u001D';
    // Keep these values aligned with Univer's DataStreamTreeTokenType.
    // 0x1E/0x1F are custom-range tokens, not table terminators.
    private static final char TABLE_ROW_END = '\u000E';
    private static final char TABLE_END = '\u000F';
    private static final char IMAGE_BLOCK = '\b';
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(?:第\\s*[一二三四五六七八九十百千万]+章|[一二三四五六七八九十百千万]+[、.．]|\\d+(?:[.．]\\d+)*[、.．]).*"
    );
    private static final Pattern HEADING_STYLE_LEVEL = Pattern.compile(
            "(?i)(?:heading|标题|title)\\s*([1-9])"
    );

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
            var documentXml = readPart(bytes, "word/document.xml");
            var dom = parseXml(documentXml);
            var plainText = plainTextFromDom(dom);

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
            var styleCatalog = styleCatalog(bytes);
            var documentIr = documentIr(dom, documentXml, plainText, packageParts, summary, styleCatalog);
            summary.set("documentIR", documentIr);
            var snapshot = documentSnapshot(dom, bytes, documentIr, packageParts, styleCatalog);
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
                facts.imageCount += !entry.isDirectory() && name.startsWith("word/media/") ? 1 : 0;
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
     * Word stores most of its effective run formatting in styles.xml and only
     * stores the differences on w:rPr.  Univer does not resolve that OOXML
     * inheritance for us, so doing only the direct w:rPr conversion makes
     * otherwise identical Chinese text fall back to different fonts/sizes.
     */
    private static final class WordStyleCatalog {
        private final ObjectMapper objectMapper;
        private final ObjectNode defaults;
        private final Map<String, ObjectNode> styles;
        private final Map<String, ObjectNode> paragraphStyles;
        private final Map<String, String> basedOn;
        private final Map<String, String> styleNames;

        private WordStyleCatalog(
                ObjectMapper objectMapper,
                ObjectNode defaults,
                Map<String, ObjectNode> styles,
                Map<String, ObjectNode> paragraphStyles,
                Map<String, String> basedOn,
                Map<String, String> styleNames
        ) {
            this.objectMapper = objectMapper;
            this.defaults = defaults;
            this.styles = styles;
            this.paragraphStyles = paragraphStyles;
            this.basedOn = basedOn;
            this.styleNames = styleNames;
        }

        private static WordStyleCatalog defaults(ObjectMapper objectMapper) {
            var defaults = objectMapper.createObjectNode()
                    .put("ff", "宋体")
                    .put("fs", 11);
            return new WordStyleCatalog(objectMapper, defaults, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        private static WordStyleCatalog from(ObjectMapper objectMapper, Document stylesDocument) {
            var fallback = defaults(objectMapper);
            if (stylesDocument == null || stylesDocument.getDocumentElement() == null) return fallback;

            var defaults = fallback.defaults.deepCopy();
            var docDefaults = child(stylesDocument.getDocumentElement(), "docDefaults");
            var defaultRunProperties = docDefaults == null ? null : child(child(docDefaults, "rPrDefault"), "rPr");
            merge(defaults, parseRunProperties(objectMapper, defaultRunProperties));

            var styles = new HashMap<String, ObjectNode>();
            var paragraphStyles = new HashMap<String, ObjectNode>();
            var basedOn = new HashMap<String, String>();
            var styleNames = new HashMap<String, String>();
            var styleNodes = stylesDocument.getElementsByTagNameNS("*", "style");
            for (var index = 0; index < styleNodes.getLength(); index++) {
                if (!(styleNodes.item(index) instanceof Element style)) continue;
                var styleId = attribute(style, "styleId");
                if (styleId.isBlank()) continue;
                styles.put(styleId, parseRunProperties(objectMapper, child(style, "rPr")));
                paragraphStyles.put(styleId, parseParagraphProperties(objectMapper, child(style, "pPr")));
                var name = child(style, "name");
                if (name != null && !attribute(name, "val").isBlank()) styleNames.put(styleId, attribute(name, "val"));
                var parent = child(style, "basedOn");
                if (parent != null && !attribute(parent, "val").isBlank()) basedOn.put(styleId, attribute(parent, "val"));
            }
            return new WordStyleCatalog(objectMapper, defaults, styles, paragraphStyles, basedOn, styleNames);
        }

        private ObjectNode paragraphStyle(Element paragraphProperties) {
            var result = defaults.deepCopy();
            var paragraphStyle = child(paragraphProperties, "pStyle");
            merge(result, resolve(attribute(paragraphStyle, "val"), new HashSet<>()));
            merge(result, parseRunProperties(objectMapper, child(paragraphProperties, "rPr")));
            return result;
        }

        private ObjectNode runStyle(ObjectNode inherited, Element runProperties) {
            var result = inherited == null ? defaults.deepCopy() : inherited.deepCopy();
            var runStyle = child(runProperties, "rStyle");
            merge(result, resolve(attribute(runStyle, "val"), new HashSet<>()));
            merge(result, parseRunProperties(objectMapper, runProperties));
            return result;
        }

        private boolean isHeadingStyle(String styleId) {
            if (styleId == null || styleId.isBlank()) return false;
            var identity = (styleId + " " + styleNames.getOrDefault(styleId, "")).toLowerCase(Locale.ROOT);
            return identity.contains("heading")
                    || identity.contains("标题")
                    || identity.contains("title")
                    || resolvedParagraphStyle(styleId).has("outlineLevel");
        }

        private boolean isTitleStyle(String styleId) {
            if (styleId == null || styleId.isBlank()) return false;
            var identity = (styleId + " " + styleNames.getOrDefault(styleId, "")).toLowerCase(Locale.ROOT);
            return identity.contains("title") || identity.contains("标题");
        }

        private int headingLevel(String styleId) {
            var paragraphStyle = resolvedParagraphStyle(styleId);
            if (paragraphStyle.has("outlineLevel")) {
                return Math.min(5, Math.max(1, paragraphStyle.path("outlineLevel").asInt(0) + 1));
            }
            var identity = styleId + " " + styleNames.getOrDefault(styleId, "");
            Matcher matcher = HEADING_STYLE_LEVEL.matcher(identity);
            if (matcher.find()) return Math.min(5, Math.max(1, parseInt(matcher.group(1), 1)));
            return isHeadingStyle(styleId) ? 1 : 0;
        }

        private ObjectNode resolvedParagraphStyle(String styleId) {
            var result = objectMapper.createObjectNode();
            resolveParagraphStyle(styleId, new HashSet<>(), result);
            return result;
        }

        private void resolveParagraphStyle(String styleId, Set<String> visiting, ObjectNode target) {
            if (styleId == null || styleId.isBlank() || !visiting.add(styleId)) return;
            resolveParagraphStyle(basedOn.get(styleId), visiting, target);
            merge(target, paragraphStyles.get(styleId));
        }

        private ObjectNode resolve(String styleId, Set<String> visiting) {
            var result = objectMapper.createObjectNode();
            if (styleId == null || styleId.isBlank() || !visiting.add(styleId)) return result;
            var parent = basedOn.get(styleId);
            if (parent != null) merge(result, resolve(parent, visiting));
            merge(result, styles.get(styleId));
            return result;
        }

        private static ObjectNode parseRunProperties(ObjectMapper objectMapper, Element properties) {
            var result = objectMapper.createObjectNode();
            if (properties == null) return result;
            var fonts = child(properties, "rFonts");
            var family = firstAttribute(fonts, "eastAsia", "ascii", "hAnsi", "cs");
            if (!family.isBlank()) result.put("ff", family);
            var size = child(properties, "sz");
            if (size == null) size = child(properties, "szCs");
            if (size != null) result.put("fs", Math.max(1, Math.round(parseInt(attribute(size, "val"), 21) / 2f)));
            setBoolean(result, "bl", child(properties, "b"));
            setBoolean(result, "it", child(properties, "i"));
            var underline = child(properties, "u");
            if (underline != null) {
                var value = attribute(underline, "val");
                result.set("ul", objectMapper.createObjectNode().put("s", value.isBlank() || !Set.of("none", "0", "false", "off").contains(value.toLowerCase(Locale.ROOT)) ? 1 : 0));
            }
            var color = child(properties, "color");
            var colorValue = attribute(color, "val");
            if (!colorValue.isBlank() && !"auto".equalsIgnoreCase(colorValue)) {
                result.set("cl", objectMapper.createObjectNode().put("rgb", colorValue.startsWith("#") ? colorValue : "#" + colorValue));
            }
            return result;
        }

        private static ObjectNode parseParagraphProperties(ObjectMapper objectMapper, Element properties) {
            var result = objectMapper.createObjectNode();
            if (properties == null) return result;
            var outline = child(properties, "outlineLvl");
            if (outline != null) result.put("outlineLevel", parseInt(attribute(outline, "val"), 0));
            return result;
        }

        private static void setBoolean(ObjectNode target, String key, Element element) {
            if (element == null) return;
            var value = attribute(element, "val");
            target.put(key, value.isBlank() || !Set.of("0", "false", "off", "none").contains(value.toLowerCase(Locale.ROOT)) ? 1 : 0);
        }

        private static void merge(ObjectNode target, ObjectNode source) {
            if (source == null) return;
            source.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue().deepCopy()));
        }

        private static Element child(Element parent, String localName) {
            if (parent == null) return null;
            for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element && localName.equals(element.getLocalName())) return element;
            }
            return null;
        }

        private static String attribute(Element element, String localName) {
            if (element == null) return "";
            var value = element.getAttributeNS(WORDPROCESSINGML, localName);
            if (value == null || value.isBlank()) value = element.getAttribute(localName);
            return value == null ? "" : value;
        }

        private static String firstAttribute(Element element, String... names) {
            for (var name : names) {
                var value = attribute(element, name);
                if (!value.isBlank()) return value;
            }
            return "";
        }

        private static int parseInt(String value, int fallback) {
            try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
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
            com.fasterxml.jackson.databind.node.ObjectNode packageSummary,
            WordStyleCatalog styleCatalog
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

        var nodes = documentNodes(document, styleCatalog);
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

    private com.fasterxml.jackson.databind.node.ArrayNode documentNodes(Document document, WordStyleCatalog styleCatalog) {
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
            var styleId = style == null ? "" : attribute(style, "val");
            var title = isDocumentTitle(value, styleId, styleCatalog, alignment, bold, maxFontSize);
            var heading = title || isHeading(value, styleId, styleCatalog, outline, insideTable, bold, maxFontSize, paragraph);
            var level = title ? 0 : headingLevel(value, styleId, styleCatalog, outline, numId, ilvl, insideTable, stack);
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
                    // The paragraph style is inherited by runs without an
                    // explicit w:rPr. Use the first run as the paragraph base;
                    // using the maximum size made a large title run enlarge
                    // every ordinary character in the same paragraph.
                    .put("bold", firstRunBold(paragraph))
                    .put("fontSize", firstFontSize(paragraph));
            var family = firstFontFamily(paragraph);
            if (!family.isBlank()) propertiesNode.put("fontFamily", family);
            nodes.add(node);
            if (heading) stack.add(new NodeFrame(nodeId, level));
        }
        return nodes;
    }

    private boolean isDocumentTitle(String value, String styleId, WordStyleCatalog styleCatalog,
                                    String alignment, boolean bold, int fontSize) {
        return styleCatalog.isTitleStyle(styleId)
                || ("center".equalsIgnoreCase(alignment)
                && ((bold && fontSize >= 14) || fontSize >= 20)
                && value.length() <= 80);
    }

    private boolean isHeading(String value, String styleId, WordStyleCatalog styleCatalog,
                              Element outline, boolean insideTable, boolean bold, int fontSize,
                              Element paragraph) {
        if (styleCatalog.isHeadingStyle(styleId) || outline != null) return true;
        if (NUMBERED_HEADING.matcher(value).matches()) return true;
        if (insideTable && isTableHeadingCandidate(paragraph, value, bold, fontSize)) return true;
        return bold && fontSize >= 18 && value.length() <= 48 && !value.contains("____");
    }

    private boolean isTableHeadingCandidate(Element paragraph, String value, boolean bold, int fontSize) {
        if (value.length() > 48 || value.isBlank() || value.matches(".*[。！？；].*")) return false;
        var cell = ancestor(paragraph, "tc");
        var row = ancestor(paragraph, "tr");
        if (cell == null || row == null) return false;
        var nonEmptyParagraphs = 0;
        var cellParagraphs = cell.getElementsByTagNameNS("*", "p");
        for (var index = 0; index < cellParagraphs.getLength(); index++) {
            if (cellParagraphs.item(index) instanceof Element element && !text(element).strip().isBlank()) {
                nonEmptyParagraphs++;
            }
        }
        if (nonEmptyParagraphs != 1) return false;
        var rowCells = directChildren(row, "tc");
        var cellProperties = firstElement(cell, "tcPr");
        var gridSpan = firstElement(cellProperties, "gridSpan");
        var spansMultipleColumns = gridSpan != null && parseInt(attribute(gridSpan, "val"), 1) > 1;
        // A short, punctuation-free paragraph that occupies the only cell (or
        // an explicit multi-column span) is a generic section marker. This is
        // intentionally structural; no business vocabulary is required.
        return (rowCells.size() == 1 || spansMultipleColumns) && (bold || fontSize >= 12);
    }

    private int headingLevel(String value, String styleId, WordStyleCatalog styleCatalog,
                             Element outline, Element numId, Element ilvl,
                             boolean insideTable, List<NodeFrame> stack) {
        var styleLevel = styleCatalog.headingLevel(styleId);
        if (styleLevel > 0) return styleLevel;
        if (outline != null) return Math.min(5, Math.max(1, 1 + parseInt(attribute(outline, "val"), 0)));
        if (value.matches("^第\\s*[一二三四五六七八九十百千万]+章.*|^[一二三四五六七八九十百千万]+[、.．].*")) return 1;
        if (value.matches("^\\d+(?:[.．]\\d+)*[、.．].*")) return 2;
        if (numId != null && ilvl != null) return Math.min(5, 1 + parseInt(attribute(ilvl, "val"), 0));
        if (insideTable) return 2;
        return stack.isEmpty() ? 1 : Math.min(5, stack.get(stack.size() - 1).level + 1);
    }

    private int maxFontSize(Element paragraph) {
        var sizes = paragraph.getElementsByTagNameNS("*", "sz");
        var max = 0;
        for (var index = 0; index < sizes.getLength(); index++) max = Math.max(max, parseInt(attribute((Element) sizes.item(index), "val"), 0));
        return max == 0 ? 11 : Math.max(1, Math.round(max / 2f));
    }

    private int firstFontSize(Element paragraph) {
        var runs = paragraph.getElementsByTagNameNS("*", "r");
        for (var index = 0; index < runs.getLength(); index++) {
            if (!(runs.item(index) instanceof Element run)) continue;
            var size = firstElement(firstElement(run, "rPr"), "sz");
            if (size != null) return Math.max(1, Math.round(parseInt(attribute(size, "val"), 21) / 2f));
        }
        return 11;
    }

    private boolean firstRunBold(Element paragraph) {
        var runs = paragraph.getElementsByTagNameNS("*", "r");
        for (var index = 0; index < runs.getLength(); index++) {
            if (!(runs.item(index) instanceof Element run)) continue;
            return firstElement(firstElement(run, "rPr"), "b") != null;
        }
        return false;
    }

    private String firstFontFamily(Element paragraph) {
        var fonts = paragraph.getElementsByTagNameNS("*", "rFonts");
        if (fonts.getLength() == 0) return "";
        var font = (Element) fonts.item(0);
        for (var name : List.of("eastAsia", "ascii", "hAnsi", "cs")) {
            var value = attribute(font, name);
            if (!value.isBlank()) return value;
        }
        return "";
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
        var nodesByParagraph = new HashMap<Integer, com.fasterxml.jackson.databind.JsonNode>();
        for (var node : documentIr.path("nodes")) {
            var locator = node.path("editorLocator");
            var paragraphIndex = node.path("sourceLocator").path("paragraphIndex").asInt(0);
            if (paragraphIndex > 0 && locator.has("startOffset")) nodesByParagraph.put(paragraphIndex, node);
        }
        for (var paragraph : body.withArray("paragraphs")) {
            if (!(paragraph instanceof ObjectNode paragraphNode)) continue;
            var paragraphIndex = paragraph.path("paragraphIndex").asInt(0);
            var node = nodesByParagraph.get(paragraphIndex);
            if (node != null) paragraphNode.set("paragraphStyle", paragraphStyle(node));
        }
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
        var result = new StringBuilder();
        appendInlineText(element, result);
        return result.toString();
    }

    private void appendInlineText(Node node, StringBuilder result) {
        if (node instanceof Element element) {
            var localName = element.getLocalName();
            if ("t".equals(localName) || "delText".equals(localName)) {
                result.append(element.getTextContent());
                return;
            }
            if ("tab".equals(localName)) {
                result.append('\t');
                return;
            }
            if ("br".equals(localName) || "cr".equals(localName)) {
                result.append("page".equals(attribute(element, "type")) ? '\f' : '\n');
                return;
            }
        }
        var children = node.getChildNodes();
        for (var index = 0; index < children.getLength(); index++) {
            appendInlineText(children.item(index), result);
        }
    }

    private String plainTextFromDom(Document document) {
        var result = new StringBuilder();
        var paragraphs = document.getElementsByTagNameNS("*", "p");
        for (var index = 0; index < paragraphs.getLength(); index++) {
            if (!(paragraphs.item(index) instanceof Element paragraph)) continue;
            var value = text(paragraph);
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
            Document document,
            byte[] packageBytes,
            com.fasterxml.jackson.databind.JsonNode documentIr,
            PackageFacts facts,
            WordStyleCatalog styleCatalog
    ) throws Exception {
        var packageEntries = readParts(packageBytes);
        var imageInventory = imageInventory(document, packageEntries);
        var flow = new FlowBuilder(document, firstElement(document.getDocumentElement(), "body"), imageInventory, styleCatalog).build();
        var body = flow.body;
        body.set("customRanges", documentControlRanges(flow.dataStream(), documentIr));
        body.set("sourceParagraphs", documentIr.path("paragraphTexts").deepCopy());

        var snapshot = objectMapper.createObjectNode();
        snapshot.put("id", UUID.randomUUID().toString());
        snapshot.put("title", documentTitle(documentIr));
        snapshot.put("snapshotFormatVersion", 5);
        snapshot.put("editorMode", "UNIVER_DOCS");
        snapshot.set("body", body);
        var documentStyle = documentStyle(document);
        snapshot.set("documentStyle", documentStyle);
        snapshot.set("tableSource", flow.tableSource);
        var headers = headerFooterSnapshots(packageEntries, "header", imageInventory, styleCatalog);
        var footers = headerFooterSnapshots(packageEntries, "footer", imageInventory, styleCatalog);
        snapshot.set("headers", headers);
        snapshot.set("footers", footers);
        if (headers.size() > 0) documentStyle.put("defaultHeaderId", headers.fieldNames().next());
        if (footers.size() > 0) documentStyle.put("defaultFooterId", footers.fieldNames().next());
        snapshot.set("wordImport", objectMapper.createObjectNode()
                .put("sourceFormat", "DOCX")
                .put("parserVersion", "docx-univer-v5")
                .put("sourceHash", sha256(packageBytes))
                .put("paragraphCount", count(document, "p"))
                .put("tableCount", count(document, "tbl"))
                .put("imageCount", facts.imageCount)
                .put("headerCount", facts.headerCount)
                .put("footerCount", facts.footerCount)
                .put("unsupportedFeatureCount", facts.unsupportedFeatureCount())
                .set("packageFacts", objectMapper.createObjectNode()
                        .put("hasMacros", facts.hasMacros)
                        .put("hasActiveX", facts.hasActiveX)
                        .put("hasOleObjects", facts.hasOleObjects)
                        .put("hasFootnotes", facts.hasFootnotes)
                        .put("hasEndnotes", facts.hasEndnotes)
                        .put("hasComments", facts.hasComments)
                        .put("hasExternalLinks", facts.hasExternalLinks)
                        .put("hasEmbeddedObjects", facts.hasEmbeddedObjects)));
        if (!imageInventory.resources.isEmpty()) {
            imageInventory.drawings.fields().forEachRemaining(entry -> {
                if (entry.getValue() instanceof ObjectNode drawing) {
                    drawing.put("unitId", snapshot.path("id").asText());
                    drawing.put("subUnitId", snapshot.path("id").asText());
                }
            });
            snapshot.set("resources", imageInventory.resources);
            snapshot.set("drawings", imageInventory.drawings);
        }
        return snapshot;
    }

    private String documentTitle(com.fasterxml.jackson.databind.JsonNode documentIr) {
        for (var node : documentIr.path("nodes")) {
            if ("DOCUMENT_TITLE".equals(node.path("type").asText())) return node.path("text").asText("导入的 Word 模板");
        }
        return "导入的 Word 模板";
    }

    private com.fasterxml.jackson.databind.node.ObjectNode documentStyle(Document document) {
        var style = objectMapper.createObjectNode();
        var pageSize = style.putObject("pageSize");
        pageSize.put("width", 595);
        pageSize.put("height", 842);
        style.put("documentFlavor", 2);
        style.put("marginTop", 72).put("marginRight", 72).put("marginBottom", 72).put("marginLeft", 72);
        var body = firstElement(document.getDocumentElement(), "body");
        var sectPr = lastElement(body, "sectPr");
        if (sectPr == null) return style;
        var pgSz = firstElement(sectPr, "pgSz");
        if (pgSz != null) {
            var width = twips(attribute(pgSz, "w"), 595);
            var height = twips(attribute(pgSz, "h"), 842);
            pageSize.put("width", width);
            pageSize.put("height", height);
            if ("landscape".equalsIgnoreCase(attribute(pgSz, "orient"))) style.put("pageOrient", 1);
        }
        var pgMar = firstElement(sectPr, "pgMar");
        if (pgMar != null) {
            style.put("marginTop", twips(attribute(pgMar, "top"), 72));
            style.put("marginRight", twips(attribute(pgMar, "right"), 72));
            style.put("marginBottom", twips(attribute(pgMar, "bottom"), 72));
            style.put("marginLeft", twips(attribute(pgMar, "left"), 72));
            style.put("marginHeader", twips(attribute(pgMar, "header"), 36));
            style.put("marginFooter", twips(attribute(pgMar, "footer"), 36));
        }
        return style;
    }

    private int twips(String value, int fallback) {
        var parsed = parseInt(value, -1);
        return parsed < 0 ? fallback : Math.max(1, Math.round(parsed / 20f));
    }

    private Map<String, byte[]> readParts(byte[] bytes) throws Exception {
        var result = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                result.put(entry.getName().replace('\\', '/'), zip.readAllBytes());
                entry = zip.getNextEntry();
            }
        }
        return result;
    }

    private WordStyleCatalog styleCatalog(byte[] packageBytes) {
        try {
            return WordStyleCatalog.from(objectMapper, parseXml(readPart(packageBytes, "word/styles.xml")));
        } catch (Exception ignored) {
            // styles.xml is optional in a few minimal DOCX producers. Keep a
            // deterministic Chinese-document baseline when it is absent.
            return WordStyleCatalog.defaults(objectMapper);
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode headerFooterSnapshots(
            Map<String, byte[]> parts, String type, ImageInventory images, WordStyleCatalog styleCatalog
    ) {
        var result = objectMapper.createObjectNode();
        var prefix = "word/" + type;
        for (var entry : parts.entrySet()) {
            var name = entry.getKey();
            if (!name.startsWith(prefix) || !name.endsWith(".xml")) continue;
            try {
                var document = parseXml(entry.getValue());
                var root = document.getDocumentElement();
                var flow = new FlowBuilder(document, root, images, styleCatalog).build();
                var key = type + "-" + name.substring(prefix.length(), name.length() - 4);
                var value = objectMapper.createObjectNode().put("headerId", key).put("footerId", key);
                value.set("body", flow.body);
                result.set(key, value);
            } catch (Exception ignored) {
                // A malformed optional header/footer must not make the main body unavailable.
            }
        }
        return result;
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

    private Element lastElement(Element parent, String localName) {
        if (parent == null) return null;
        Element result = null;
        for (var child : childElements(parent)) if (isWord(child, localName)) result = child;
        return result;
    }

    private String attributeAny(Element element, String namespace, String localName) {
        if (element == null) return "";
        var value = element.getAttributeNS(namespace, localName);
        if (value != null && !value.isBlank()) return value;
        for (var index = 0; index < element.getAttributes().getLength(); index++) {
            var attribute = element.getAttributes().item(index);
            if (localName.equals(attribute.getLocalName()) || localName.equals(attribute.getNodeName())) {
                return attribute.getNodeValue();
            }
        }
        return "";
    }

    private String imageMimeType(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".svg") || lower.endsWith(".svgz")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private final class ImageInventory {
        private final Map<Element, ImageInfo> byDrawing = new IdentityHashMap<>();
        private final com.fasterxml.jackson.databind.node.ArrayNode resources = objectMapper.createArrayNode();
        private final ObjectNode drawings = objectMapper.createObjectNode();

        private ImageInventory() {
        }

    }

    private ImageInventory imageInventory(Document document, Map<String, byte[]> parts) throws Exception {
        var inventory = new ImageInventory();
        registerImages(document, parts, "word/_rels/document.xml.rels", inventory);
        for (var entry : parts.entrySet()) {
            var name = entry.getKey();
            if (!name.matches("word/header\\d+\\.xml")) continue;
            var header = parseXml(entry.getValue());
            var fileName = name.substring(name.lastIndexOf('/') + 1);
            registerImages(header, parts, "word/_rels/" + fileName + ".rels", inventory);
        }
        for (var entry : parts.entrySet()) {
            var name = entry.getKey();
            if (!name.matches("word/footer\\d+\\.xml")) continue;
            var footer = parseXml(entry.getValue());
            var fileName = name.substring(name.lastIndexOf('/') + 1);
            registerImages(footer, parts, "word/_rels/" + fileName + ".rels", inventory);
        }
        return inventory;
    }

    private void registerImages(
            Document document, Map<String, byte[]> parts, String relsPath, ImageInventory inventory
    ) throws Exception {
        var relationships = new HashMap<String, String>();
        var rels = parts.get(relsPath);
        if (rels != null) {
            var relDocument = parseXml(rels);
            var nodes = relDocument.getElementsByTagNameNS("*", "Relationship");
            for (var index = 0; index < nodes.getLength(); index++) {
                if (nodes.item(index) instanceof Element relationship) {
                    var target = relationship.getAttribute("Target").replace('\\', '/');
                    if (target.startsWith("/")) target = target.substring(1);
                    if (!target.startsWith("word/")) target = "word/" + target;
                    relationships.put(relationship.getAttribute("Id"), target);
                }
            }
        }
        var drawings = document.getElementsByTagNameNS("*", "drawing");
        var imageSequence = inventory.resources.size();
        for (var index = 0; index < drawings.getLength(); index++) {
            if (!(drawings.item(index) instanceof Element drawing)) continue;
            var blips = drawing.getElementsByTagNameNS("*", "blip");
            if (blips.getLength() == 0 || !(blips.item(0) instanceof Element blip)) continue;
            var target = relationships.get(attributeAny(blip, RELATIONSHIPS, "embed"));
            if (target == null || !parts.containsKey(target)) continue;
            var imageId = "image-" + (++imageSequence);
            var bytes = parts.get(target);
            var dataUri = "data:" + imageMimeType(target) + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(bytes);
            inventory.resources.add(objectMapper.createObjectNode()
                    .put("id", imageId)
                    .put("name", target.substring(target.lastIndexOf('/') + 1))
                    .put("data", dataUri));
            var size = imageSize(drawing);
            var drawingNode = objectMapper.createObjectNode()
                    .put("drawingId", imageId)
                    .put("title", "")
                    .put("description", "")
                    .put("imageSourceType", "BASE64")
                    .put("source", dataUri)
                    .put("drawingType", 0)
                    .put("layoutType", 0)
                    .put("unitId", "")
                    .put("subUnitId", "");
            drawingNode.set("transform", objectMapper.createObjectNode()
                    .put("width", size.width)
                    .put("height", size.height)
                    .put("left", 0)
                    .put("top", 0));
            var docTransform = drawingNode.putObject("docTransform");
            docTransform.set("size", objectMapper.createObjectNode()
                    .put("width", size.width)
                    .put("height", size.height));
            docTransform.put("angle", 0);
            docTransform.set("positionH", objectMapper.createObjectNode()
                    .put("relativeFrom", 3)
                    .put("posOffset", 0));
            docTransform.set("positionV", objectMapper.createObjectNode()
                    .put("relativeFrom", 1)
                    .put("posOffset", 0));
            inventory.drawings.set(imageId, drawingNode);
            inventory.byDrawing.put(drawing, new ImageInfo(imageId));
        }
    }

    private Size imageSize(Element drawing) {
        var extents = drawing.getElementsByTagNameNS("*", "extent");
        if (extents.getLength() == 0 || !(extents.item(0) instanceof Element extent)) return new Size(100, 100);
        return new Size(
                Math.max(1, Math.round(parseLong(attributeAny(extent, "", "cx"), 1270000L) / 12700f)),
                Math.max(1, Math.round(parseLong(attributeAny(extent, "", "cy"), 1270000L) / 12700f))
        );
    }

    private long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }

    private final class FlowBuilder {
        private final Document document;
        private final Element root;
        private final ImageInventory images;
        private final WordStyleCatalog styleCatalog;
        private final IdentityHashMap<Element, Integer> paragraphIndexes = new IdentityHashMap<>();
        private final StringBuilder stream = new StringBuilder();
        private final ObjectNode body = objectMapper.createObjectNode();
        private final com.fasterxml.jackson.databind.node.ArrayNode paragraphs = objectMapper.createArrayNode();
        private final com.fasterxml.jackson.databind.node.ArrayNode textRuns = objectMapper.createArrayNode();
        private final com.fasterxml.jackson.databind.node.ArrayNode tables = objectMapper.createArrayNode();
        private final com.fasterxml.jackson.databind.node.ArrayNode sectionBreaks = objectMapper.createArrayNode();
        private final ObjectNode tableSource = objectMapper.createObjectNode();
        private final com.fasterxml.jackson.databind.node.ArrayNode customBlocks = objectMapper.createArrayNode();
        private int tableSequence;

        private FlowBuilder(Document document, Element root, ImageInventory images, WordStyleCatalog styleCatalog) {
            this.document = document;
            this.root = root;
            this.images = images == null ? new ImageInventory() : images;
            this.styleCatalog = styleCatalog == null ? WordStyleCatalog.defaults(objectMapper) : styleCatalog;
            if (root != null) {
                var nodes = root.getElementsByTagNameNS("*", "p");
                for (var index = 0; index < nodes.getLength(); index++) {
                    if (nodes.item(index) instanceof Element paragraph) paragraphIndexes.put(paragraph, index + 1);
                }
            }
        }

        private FlowBuilder build() {
            if (root != null) processContainer(root);
            if (stream.length() == 0 || stream.charAt(stream.length() - 1) != '\r') {
                paragraphs.add(objectMapper.createObjectNode().put("startIndex", stream.length()).put("paragraphIndex", 0));
                stream.append('\r');
            }
            stream.append('\n');
            body.put("dataStream", stream.toString());
            body.set("paragraphs", paragraphs);
            body.set("textRuns", textRuns);
            body.set("tables", tables);
            body.set("sectionBreaks", sectionBreaks);
            body.set("customBlocks", customBlocks);
            return this;
        }

        private String dataStream() {
            return stream.toString();
        }

        private void processContainer(Element container) {
            for (var child : childElements(container)) {
                if (isWord(child, "p")) appendParagraph(child);
                else if (isWord(child, "tbl")) appendTable(child);
                else if (isWord(child, "sdt")) {
                    var content = firstElement(child, "sdtContent");
                    if (content != null) processContainer(content);
                }
            }
        }

        private void appendParagraph(Element paragraph) {
            var start = stream.length();
            paragraphs.add(objectMapper.createObjectNode()
                    .put("startIndex", start)
                    .put("paragraphIndex", paragraphIndexes.getOrDefault(paragraph, 0)));
            var before = stream.length();
            appendParagraphChildren(paragraph);
            if (stream.length() == before) {
                // An empty Word paragraph still has a real caret position in Docs.
            }
            stream.append('\r');
        }

        private void appendParagraphChildren(Element paragraph) {
            var paragraphStyle = styleCatalog.paragraphStyle(firstElement(paragraph, "pPr"));
            for (var child : childElements(paragraph)) {
                if (isWord(child, "pPr")) continue;
                appendInline(child, paragraphStyle);
            }
        }

        private void appendInline(Element element, ObjectNode inheritedStyle) {
            if (isWord(element, "r")) {
                appendRun(element, inheritedStyle);
                return;
            }
            if (isWord(element, "drawing")) {
                var image = images.byDrawing.get(element);
                if (image != null) {
                    var start = stream.length();
                    stream.append(IMAGE_BLOCK);
                    customBlocks.add(objectMapper.createObjectNode().put("startIndex", start).put("blockId", image.id));
                }
                return;
            }
            if (isWord(element, "t") || isWord(element, "delText")) {
                stream.append(element.getTextContent());
                return;
            }
            if (isWord(element, "tab")) {
                stream.append('\t');
                return;
            }
            if (isWord(element, "br") || isWord(element, "cr")) {
                stream.append("page".equals(attribute(element, "type")) ? '\f' : '\n');
                return;
            }
            for (var child : childElements(element)) appendInline(child, inheritedStyle);
        }

        private void appendRun(Element run, ObjectNode inheritedStyle) {
            var start = stream.length();
            var properties = firstElement(run, "rPr");
            for (var child : childElements(run)) {
                if (!isWord(child, "rPr")) appendInline(child, inheritedStyle);
            }
            if (stream.length() > start) {
                var runNode = objectMapper.createObjectNode()
                        .put("st", start)
                        .put("ed", stream.length() - 1);
                var style = styleCatalog.runStyle(inheritedStyle, properties);
                if (!style.isEmpty()) runNode.set("ts", style);
                textRuns.add(runNode);
            }
        }

        private void appendTable(Element table) {
            if (stream.length() > 0 && stream.charAt(stream.length() - 1) != '\r') {
                paragraphs.add(objectMapper.createObjectNode().put("startIndex", stream.length()).put("paragraphIndex", 0));
                stream.append('\r');
            }
            var tableId = "table-" + (++tableSequence);
            var start = stream.length();
            stream.append(TABLE_START);
            var source = objectMapper.createObjectNode().put("tableId", tableId);
            var rows = objectMapper.createArrayNode();
            var directRows = directChildren(table, "tr");
            var maxColumns = 0;
            for (var row : directRows) {
                var rowColumns = 0;
                for (var cell : directChildren(row, "tc")) {
                    var cellGridSpan = firstElement(firstElement(cell, "tcPr"), "gridSpan");
                    rowColumns += cellGridSpan == null
                            ? 1
                            : Math.max(1, parseInt(attribute(cellGridSpan, "val"), 1));
                }
                maxColumns = Math.max(maxColumns, rowColumns);
            }
            var columns = objectMapper.createArrayNode();
            var grid = firstElement(table, "tblGrid");
            var gridColumns = grid == null ? List.<Element>of() : directChildren(grid, "gridCol");
            var columnCount = Math.max(maxColumns, gridColumns.size());
            for (var index = 0; index < columnCount; index++) {
                var width = index < gridColumns.size() ? twips(attribute(gridColumns.get(index), "w"), 72) : 72;
                columns.add(objectMapper.createObjectNode().set("size", objectMapper.createObjectNode()
                        .put("type", 1).set("width", objectMapper.createObjectNode().put("v", width))));
            }
            source.set("tableColumns", columns);
            source.set("tableRows", rows);
            source.put("align", tableAlignment(table));
            // Word tables are inline with the document flow unless an explicit
            // drawing anchor is present. Univer's WRAP mode treats a normal
            // table as a floating object and can place its slices incorrectly.
            source.put("textWrap", 0);
            source.put("layout", "fixed".equalsIgnoreCase(attribute(firstElement(firstElement(table, "tblPr"), "tblLayout"), "type")) ? 1 : 0);
            var tableProperties = firstElement(table, "tblPr");
            var tableBorders = firstElement(tableProperties, "tblBorders");
            for (var rowIndex = 0; rowIndex < directRows.size(); rowIndex++) {
                var row = directRows.get(rowIndex);
                stream.append(TABLE_ROW_START);
                var rowNode = objectMapper.createObjectNode();
                var cells = objectMapper.createArrayNode();
                rowNode.set("tableCells", cells);
                var trHeight = firstElement(firstElement(row, "trPr"), "trHeight");
                var exactHeight = "exact".equalsIgnoreCase(attribute(trHeight, "hRule"));
                var requestedHeight = twips(attribute(trHeight, "val"), 0);
                // OOXML often stores a very small auto-height hint (for
                // example 8 twips).  Passing that value through makes Univer
                // overlap 14pt Chinese paragraphs inside the row.  The
                // official Docs table builder uses 30 as its auto-height
                // baseline, so use the same baseline for imported rows.
                var rowHeight = Math.max(30, requestedHeight);
                rowNode.set("trHeight", objectMapper.createObjectNode()
                        .put("hRule", exactHeight ? 2 : 0)
                        .set("val", objectMapper.createObjectNode().put("v", rowHeight)));
                var occupiedColumns = 0;
                for (var cell : directChildren(row, "tc")) {
                    stream.append(TABLE_CELL_START);
                    var cellNode = objectMapper.createObjectNode();
                    cellNode.set("margin", cellMargin(tableProperties));
                    var tcPr = firstElement(cell, "tcPr");
                    var gridSpan = firstElement(tcPr, "gridSpan");
                    var vMerge = firstElement(tcPr, "vMerge");
                    var columnSpan = gridSpan == null
                            ? 1
                            : Math.max(1, parseInt(attribute(gridSpan, "val"), 1));
                    occupiedColumns += columnSpan;
                    if (columnSpan > 1) cellNode.put("columnSpan", columnSpan);
                    if (vMerge != null && (attribute(vMerge, "val").isBlank() || "continue".equalsIgnoreCase(attribute(vMerge, "val")))) {
                        cellNode.put("rowSpan", 0).put("columnSpan", 0);
                    }
                    var cellWidth = firstElement(tcPr, "tcW");
                    if (cellWidth != null) {
                        cellNode.set("size", objectMapper.createObjectNode().put("type", 1)
                                .set("width", objectMapper.createObjectNode().put("v", twips(attribute(cellWidth, "w"), 72))));
                    }
                    applyCellBorders(
                            cellNode,
                            tcPr,
                            firstElement(tcPr, "shd"),
                            tableBorders,
                            rowIndex,
                            directRows.size(),
                            occupiedColumns - columnSpan,
                            columnSpan,
                            columnCount
                    );
                    var verticalAlign = firstElement(tcPr, "vAlign");
                    if (verticalAlign != null) cellNode.put("vAlign", switch (attribute(verticalAlign, "val").toLowerCase(Locale.ROOT)) {
                        case "center" -> 3;
                        case "bottom" -> 4;
                        default -> 2;
                    });
                    appendCellContent(cell);
                    if (stream.length() == 0 || stream.charAt(stream.length() - 1) != '\r') {
                        paragraphs.add(objectMapper.createObjectNode().put("startIndex", stream.length()).put("paragraphIndex", 0));
                        stream.append('\r');
                    }
                    sectionBreaks.add(objectMapper.createObjectNode().put("startIndex", stream.length()));
                    stream.append('\n').append(TABLE_CELL_END);
                    cells.add(cellNode);

                    // Univer's document table model stores one covered cell for
                    // every grid column consumed by a colspan.  OOXML stores
                    // only the originating w:tc, so expand the missing cells in
                    // both the data stream and tableRows.  Without these
                    // placeholders a valid Word table is treated as malformed
                    // and the Docs renderer drops the whole table block.
                    for (var covered = 1; covered < columnSpan; covered++) {
                        appendCoveredCell(cells, cellMargin(tableProperties));
                    }
                }
                while (occupiedColumns < columnCount) {
                    appendCoveredCell(cells, cellMargin(tableProperties), tableBorders, rowIndex, directRows.size(), occupiedColumns, 1, columnCount);
                    occupiedColumns++;
                }
                stream.append(TABLE_ROW_END);
                rows.add(rowNode);
            }
            stream.append(TABLE_END);
            tables.add(objectMapper.createObjectNode().put("startIndex", start).put("endIndex", stream.length()).put("tableId", tableId));
            var tableWidth = 0;
            for (var column : columns) {
                tableWidth += column.path("size").path("width").path("v").asInt(0);
            }
            source.set("cellMargin", cellMargin(tableProperties));
            source.set("size", objectMapper.createObjectNode().put("type", 1).set("width", objectMapper.createObjectNode().put("v", tableWidth)));
            source.set("indent", objectMapper.createObjectNode().put("v", 0));
            source.set("dist", objectMapper.createObjectNode().put("distT", 0).put("distB", 0).put("distL", 0).put("distR", 0));
            var position = objectMapper.createObjectNode();
            // Univer's table defaults anchor the table to the page.  Using the
            // Word paragraph/margin enum values here makes the table fall out
            // of the Docs layout tree, so keep the canonical PAGE/PAGE anchor.
            position.set("positionH", objectMapper.createObjectNode().put("relativeFrom", 0).put("posOffset", 0));
            position.set("positionV", objectMapper.createObjectNode().put("relativeFrom", 0).put("posOffset", 0));
            source.set("position", position);
            tableSource.set(tableId, source);
        }

        private void appendCoveredCell(com.fasterxml.jackson.databind.node.ArrayNode cells, ObjectNode margin) {
            stream.append(TABLE_CELL_START).append('\r');
            sectionBreaks.add(objectMapper.createObjectNode().put("startIndex", stream.length()));
            stream.append('\n').append(TABLE_CELL_END);
            var cell = objectMapper.createObjectNode();
            cell.set("margin", margin);
            cell.put("rowSpan", 0).put("columnSpan", 0);
            cells.add(cell);
        }

        private void appendCoveredCell(
                com.fasterxml.jackson.databind.node.ArrayNode cells,
                ObjectNode margin,
                Element tableBorders,
                int rowIndex,
                int rowCount,
                int columnIndex,
                int columnSpan,
                int columnCount
        ) {
            stream.append(TABLE_CELL_START).append('\r');
            sectionBreaks.add(objectMapper.createObjectNode().put("startIndex", stream.length()));
            stream.append('\n').append(TABLE_CELL_END);
            var cell = objectMapper.createObjectNode();
            cell.set("margin", margin);
            cell.put("rowSpan", 0).put("columnSpan", 0);
            applyCellBorders(cell, null, null, tableBorders, rowIndex, rowCount, columnIndex, columnSpan, columnCount);
            cells.add(cell);
        }

        private ObjectNode cellMargin(Element tableProperties) {
            var margin = objectMapper.createObjectNode();
            var cellMargins = firstElement(tableProperties, "tblCellMar");
            margin.set("start", objectMapper.createObjectNode().put("v", twips(attribute(firstElement(cellMargins, "left"), "w"), 5)));
            margin.set("end", objectMapper.createObjectNode().put("v", twips(attribute(firstElement(cellMargins, "right"), "w"), 5)));
            margin.set("top", objectMapper.createObjectNode().put("v", twips(attribute(firstElement(cellMargins, "top"), "w"), 2)));
            margin.set("bottom", objectMapper.createObjectNode().put("v", twips(attribute(firstElement(cellMargins, "bottom"), "w"), 2)));
            return margin;
        }

        private void applyCellBorders(
                ObjectNode cell,
                Element tcProperties,
                Element shading,
                Element borders,
                int rowIndex,
                int rowCount,
                int columnIndex,
                int columnSpan,
                int columnCount
        ) {
            var cellBorders = firstElement(tcProperties, "tcBorders");
            for (var side : List.of("top", "right", "bottom", "left")) {
                var border = firstElement(cellBorders, side);
                if (!hasVisibleBorder(border)) {
                    var tableSide = switch (side) {
                        case "top" -> rowIndex == 0 ? "top" : "insideH";
                        case "bottom" -> rowIndex == rowCount - 1 ? "bottom" : "insideH";
                        case "left" -> columnIndex == 0 ? "left" : "insideV";
                        case "right" -> columnIndex + columnSpan >= columnCount ? "right" : "insideV";
                        default -> side;
                    };
                    border = firstElement(borders, tableSide);
                }
                if (!hasVisibleBorder(border)) {
                    cell.set("border" + Character.toUpperCase(side.charAt(0)) + side.substring(1), defaultBorderStyle());
                } else if (hasVisibleBorder(border)) {
                    cell.set("border" + Character.toUpperCase(side.charAt(0)) + side.substring(1), borderStyle(border));
                }
            }
            if (shading != null && !attribute(shading, "fill").isBlank() && !"auto".equalsIgnoreCase(attribute(shading, "fill"))) {
                cell.set("backgroundColor", objectMapper.createObjectNode().put("rgb", "#" + attribute(shading, "fill")));
            }
        }

        private boolean hasVisibleBorder(Element border) {
            if (border == null) return false;
            var value = attribute(border, "val");
            return !Set.of("nil", "none", "0", "false").contains(value.toLowerCase(Locale.ROOT));
        }

        private boolean hasAnyVisibleBorder(Element borders) {
            if (borders == null) return false;
            for (var side : List.of("top", "right", "bottom", "left", "insideH", "insideV")) {
                if (hasVisibleBorder(firstElement(borders, side))) return true;
            }
            return false;
        }

        private ObjectNode borderStyle(Element border) {
            var style = objectMapper.createObjectNode();
            var color = attribute(border, "color");
            style.set("color", objectMapper.createObjectNode().put("rgb", color.isBlank() || "auto".equalsIgnoreCase(color) ? "#000000" : "#" + color));
            style.set("width", objectMapper.createObjectNode().put("v", Math.max(1d, parseInt(attribute(border, "sz"), 4) / 8d)));
            style.put("dashStyle", switch (attribute(border, "val").toLowerCase(Locale.ROOT)) {
                case "dotted" -> 2;
                case "dashed" -> 3;
                default -> 1;
            });
            style.put("padding", 0);
            return style;
        }

        private ObjectNode defaultBorderStyle() {
            var style = objectMapper.createObjectNode();
            style.set("color", objectMapper.createObjectNode().put("rgb", "#000000"));
            style.set("width", objectMapper.createObjectNode().put("v", 1));
            style.put("dashStyle", 1);
            return style;
        }

        private void appendCellContent(Element cell) {
            var content = new ArrayList<Element>();
            for (var child : childElements(cell)) {
                if (isWord(child, "tcPr")) continue;
                content.add(child);
            }
            for (var child : content) {
                if (isWord(child, "p")) appendParagraph(child);
                else if (isWord(child, "tbl")) appendTable(child);
                else if (isWord(child, "sdt")) {
                    var nested = firstElement(child, "sdtContent");
                    if (nested != null) processContainer(nested);
                }
            }
        }

        private int tableAlignment(Element table) {
            var jc = firstElement(firstElement(table, "tblPr"), "jc");
            return switch (attribute(jc, "val").toLowerCase(Locale.ROOT)) {
                case "center" -> 1;
                case "right", "end" -> 2;
                default -> 0;
            };
        }
    }

    private record ImageInfo(String id) { }
    private record Size(int width, int height) { }

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

        private int unsupportedFeatureCount() {
            var count = 0;
            if (hasMacros) count++;
            if (hasActiveX) count++;
            if (hasOleObjects) count++;
            if (hasExternalLinks) count++;
            if (hasEmbeddedObjects) count++;
            if (hasFootnotes) count++;
            if (hasEndnotes) count++;
            if (hasComments) count++;
            return count;
        }
    }

    private record NodeFrame(String nodeId, int level) {
    }
}
