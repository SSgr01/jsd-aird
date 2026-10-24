package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class WordSnapshotImagesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WordDocumentSnapshotExporter exporter = new WordDocumentSnapshotExporter();

    @Test
    void exportsUniver120UploadAndPasteLayout() throws Exception {
        // Match the SDK's uploaded floating image and pasted inline image shapes
        // with generated PNG bytes rather than a captured browser JSON fixture.
        var snapshot = snapshot("图片验证\b\b\r\r\n").put("id", "smoke-doc");
        var blocks = snapshot.withObject("/body").putArray("customBlocks");
        blocks.addObject().put("startIndex", 4).put("blockId", "upload");
        blocks.addObject().put("startIndex", 5).put("blockId", "paste");
        var uploaded = image("upload", 200, 120).put("layoutType", 3);
        uploaded.withObject("/docTransform").putObject("positionH").put("relativeFrom", 0).put("posOffset", 160);
        uploaded.withObject("/docTransform").putObject("positionV").put("relativeFrom", 1).put("posOffset", 20);
        var drawings = snapshot.putObject("drawings");
        drawings.set("upload", uploaded);
        drawings.set("paste", image("paste", 120, 80));
        snapshot.putArray("drawingsOrder").add("upload").add("paste");
        var bytes = exporter.export(blank(), snapshot);
        try (var word = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(word.getAllPictures()).hasSize(2);
        }
        var xml = text(parts(bytes), "word/document.xml");
        assertThat(xml).contains("wp:anchor", "wp:inline", "cx=\"1905000\"", "cy=\"1143000\"", ">1524000<");
        // Reimport the generated Office document through the production parser.
        var parsed = new DocxStructureParser(mapper).parse(new ByteArrayInputStream(bytes));
        assertThat(parsed.initialEditorSnapshot().path("drawings").size()).isEqualTo(2);
        var output = java.nio.file.Path.of("target/univer-smoke");
        java.nio.file.Files.createDirectories(output);
        java.nio.file.Files.write(output.resolve("univer-1.2.0.docx"), bytes);
    }

    @Test
    void embedsNewImagesByBlockIdAndPreservesCurrentSizeAndFloatingPosition() throws Exception {
        var snapshot = snapshot("\b文本\b\r\n");
        snapshot.withObject("/body").putArray("customBlocks")
                .addObject().put("startIndex", 0).put("blockId", "second");
        snapshot.withObject("/body").withArray("customBlocks").addObject().put("startIndex", 3).put("blockId", "first");
        var drawings = snapshot.putObject("drawings");
        drawings.set("first", image("first", 90, 50));
        var second = image("second", 150, 80).put("layoutType", 2);
        second.withObject("/docTransform").putObject("positionH").put("relativeFrom", 0).put("posOffset", 24);
        second.withObject("/docTransform").putObject("positionV").put("relativeFrom", 1).put("posOffset", 12);
        drawings.set("second", second);
        var parts = parts(exporter.export(blank(), snapshot));
        var xml = text(parts, "word/document.xml");
        assertThat(xml).contains("wp:anchor", "wp:inline", "cx=\"1428750\"", "cy=\"762000\"", ">228600<", ">114300<");
        assertThat(text(parts, "word/_rels/document.xml.rels")).contains("/image", "media/jsd-image-");
        assertThat(text(parts, "[Content_Types].xml")).contains("image/png");
        assertThat(parts.entrySet().stream().filter(e -> e.getKey().startsWith("word/media/jsd-image-")).toList()).hasSize(2);
        try (var word = new XWPFDocument(new ByteArrayInputStream(exporter.export(blank(), snapshot)))) {
            assertThat(word.getAllPictures()).hasSize(2);
            assertThat(word.getAllPictures().get(0).getData()).isEqualTo(png());
        }
    }

    @Test
    void deletingAllImageBlocksDoesNotRestoreSourcePictures() throws Exception {
        var initial = snapshot("\b\r\n");
        initial.withObject("/body").putArray("customBlocks").addObject().put("startIndex", 0).put("blockId", "pic");
        initial.putObject("drawings").set("pic", image("pic", 80, 60));
        var source = exporter.export(blank(), initial);
        var cleared = exporter.export(source, snapshot("正文\r\n"));
        assertThat(text(parts(cleared), "word/document.xml")).doesNotContain("w:drawing", "wp:inline", "wp:anchor");
        assertThat(parts(cleared).keySet()).noneMatch(name -> name.startsWith("word/media/"));
        assertThat(text(parts(cleared), "word/_rels/document.xml.rels")).doesNotContain("/image");
    }

    @Test
    void repeatedSaveAndExportDoesNotAccumulateOldImagesOrRelationships() throws Exception {
        var current = snapshot("\b\r\n");
        current.withObject("/body").putArray("customBlocks").addObject().put("startIndex", 0).put("blockId", "pic");
        current.putObject("drawings").set("pic", image("pic", 120, 80));
        var saved = exporter.export(blank(), current);
        for (int index = 0; index < 3; index++) saved = exporter.export(saved, current);
        try (var word = new XWPFDocument(new ByteArrayInputStream(saved))) {
            assertThat(word.getAllPictures()).hasSize(1);
        }
        assertThat(new DocxStructureParser(mapper).parse(new ByteArrayInputStream(saved))
                .structureSummary().path("imageCount").asInt()).isEqualTo(1);
        assertThat(parts(saved).keySet().stream().filter(name -> name.startsWith("word/media/"))).hasSize(1);
    }

    @Test
    void deletingBodyImagePreservesMediaStillUsedByAHeader() throws Exception {
        var current = snapshot("\b\r\n");
        current.withObject("/body").putArray("customBlocks").addObject().put("startIndex", 0).put("blockId", "pic");
        current.putObject("drawings").set("pic", image("pic", 120, 80));
        byte[] source;
        try (var word = new XWPFDocument(new ByteArrayInputStream(exporter.export(blank(), current)));
             var output = new ByteArrayOutputStream()) {
            var header = word.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            header.createParagraph().createRun().addPicture(new ByteArrayInputStream(png()),
                    XWPFDocument.PICTURE_TYPE_PNG, "header.png", 19050, 19050);
            word.write(output);
            source = output.toByteArray();
        }
        var before = parts(source);
        // POI deduplicates identical bytes, so the header shares the body's existing media part.
        assertThat(before.keySet().stream().filter(name -> name.startsWith("word/media/"))).hasSize(1);
        var cleared = exporter.export(source, snapshot("正文\r\n"));
        try (var word = new XWPFDocument(new ByteArrayInputStream(cleared))) {
            assertThat(word.getHeaderList()).hasSize(1);
            assertThat(word.getHeaderList().getFirst().getAllPictures()).hasSize(1);
            assertThat(word.getHeaderList().getFirst().getAllPictures().getFirst().getData()).isEqualTo(png());
        }
        assertThat(text(parts(cleared), "word/document.xml")).doesNotContain("w:drawing");
    }

    @Test
    void rejectsMissingAndCorruptImageData() throws Exception {
        var snapshot = snapshot("\b\r\n");
        snapshot.withObject("/body").putArray("customBlocks").addObject().put("startIndex", 0).put("blockId", "pic");
        var source = blank();
        assertThatThrownBy(() -> exporter.export(source, snapshot)).hasMessageContaining("图片块");
        snapshot.putObject("drawings").set("pic", image("pic", 100, 100).put("source", "data:image/png;base64,YmFk"));
        assertThatThrownBy(() -> exporter.export(source, snapshot)).hasMessageContaining("图片");
    }

    private ObjectNode snapshot(String text) {
        var snapshot = mapper.createObjectNode().put("snapshotFormatVersion", 5);
        snapshot.putObject("body").put("dataStream", text);
        return snapshot;
    }
    private ObjectNode image(String id, int width, int height) throws Exception {
        var image = mapper.createObjectNode().put("drawingId", id).put("layoutType", 0)
                .put("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png()));
        image.putObject("docTransform").putObject("size").put("width", width).put("height", height);
        return image;
    }
    private byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
    private byte[] blank() throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph(); document.write(output); return output.toByteArray();
        }
    }
    private Map<String, byte[]> parts(byte[] bytes) throws Exception {
        var result = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) result.put(entry.getName(), zip.readAllBytes());
        }
        return result;
    }
    private String text(Map<String, byte[]> parts, String name) { return new String(parts.get(name), StandardCharsets.UTF_8); }
}
