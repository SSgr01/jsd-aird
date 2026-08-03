package com.jsd.aird.tpl.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.docx4j.TextUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

@Component
public class DocxStructureParser implements OfficeStructureParser {

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
            var plainText = TextUtils.getText(wordPackage.getMainDocumentPart());
            var documentXml = readPart(bytes, "word/document.xml");
            var dom = parseXml(documentXml);

            var summary = objectMapper.createObjectNode();
            summary.put("format", "DOCX");
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
            var snapshot = documentSnapshot(plainText);
            var documentIr = objectMapper.createObjectNode()
                    .put("text", plainText)
                    .put("paragraphCount", count(dom, "p"))
                    .put("tableCount", count(dom, "tbl"))
                    .set("packageFacts", summary.deepCopy());
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
                entry = zip.getNextEntry();
            }
        }
        return facts;
    }

    private void addUnsupportedIssues(PackageFacts facts, List<ParseIssue> issues) {
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

    private com.fasterxml.jackson.databind.JsonNode documentSnapshot(String text) {
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
        body.set("customRanges", objectMapper.createArrayNode());
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

    private static final class PackageFacts {
        private int headerCount;
        private int footerCount;
        private int imageCount;
        private boolean hasFootnotes;
        private boolean hasEndnotes;
        private boolean hasComments;
        private boolean hasExternalLinks;
        private boolean hasEmbeddedObjects;
    }
}
