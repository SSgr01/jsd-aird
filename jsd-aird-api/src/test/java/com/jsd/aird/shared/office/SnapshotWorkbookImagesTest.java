package com.jsd.aird.shared.office;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.junit.jupiter.api.Test;

class SnapshotWorkbookImagesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SnapshotWorkbookExporter exporter = new SnapshotWorkbookExporter(mapper);

    @Test
    void exportsUniver120UploadAndPasteLayout() throws Exception {
        var snapshot = univer120Snapshot(false);
        var bytes = exporter.export(snapshot, null, null, null).content();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var shapes = workbook.getSheetAt(0).getDrawingPatriarch().getShapes();
            assertThat(shapes).hasSize(2);
            var picture = (XSSFPicture) shapes.get(0);
            assertThat(picture.getClientAnchor().getRow1()).isEqualTo(3);
            assertThat((int) picture.getClientAnchor().getCol1()).isEqualTo(2);
            assertThat(picture.getCTPicture().getSpPr().getXfrm().getExt().getCx()).isCloseTo(200 * 9525L, within(9525L));
            assertThat(workbook.getSheetAt(0).getRow(2).getCell(1).getCellFormula()).isEqualTo("SUM(B2,2)");
        }
        var output = java.nio.file.Path.of("target/univer-smoke");
        java.nio.file.Files.createDirectories(output);
        java.nio.file.Files.write(output.resolve("univer-1.2.0.xlsx"), bytes);
    }

    @Test
    void exportsNativeCellImageAsPictureAnchoredToItsCell() throws Exception {
        var snapshot = univer120Snapshot(true);
        var bytes = exporter.export(snapshot, null, null, null).content();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var shapes = workbook.getSheetAt(0).getDrawingPatriarch().getShapes();
            assertThat(shapes).hasSize(2); // one floating image plus the D5 cell image
            var picture = (XSSFPicture) shapes.get(1);
            assertThat(picture.getClientAnchor().getRow1()).isEqualTo(4);
            assertThat((int) picture.getClientAnchor().getCol1()).isEqualTo(3);
            assertThat(picture.getClientAnchor().getAnchorType())
                    .isEqualTo(org.apache.poi.ss.usermodel.ClientAnchor.AnchorType.MOVE_AND_RESIZE);
        }
    }

    @Test
    void exportsPicturesWithCurrentAnchorSizeAndLayerOrder() throws Exception {
        var snapshot = snapshot();
        var drawings = mapper.createObjectNode();
        drawings.set("first", image("first", 3, 2, 120, 80));
        drawings.set("second", image("second", 7, 4, 60, 40));
        var sheetResource = mapper.createObjectNode();
        sheetResource.set("data", drawings);
        sheetResource.set("order", mapper.createArrayNode().add("second").add("first"));
        snapshot.putArray("resources").addObject().put("name", "SHEET_DRAWING_PLUGIN")
                .put("data", mapper.createObjectNode().set("sheet-1", sheetResource).toString());
        var result = exporter.export(snapshot, null, null, null);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result.content()))) {
            var pictures = workbook.getSheetAt(0).getDrawingPatriarch().getShapes();
            assertThat(pictures).hasSize(2);
            var second = (XSSFPicture) pictures.get(0);
            assertThat(second.getClientAnchor().getRow1()).isEqualTo(7);
            assertThat((int) second.getClientAnchor().getCol1()).isEqualTo(4);
            assertThat(second.getClientAnchor().getDx1()).isEqualTo(10 * 9525);
            assertThat(second.getCTPicture().getSpPr().getXfrm().getExt().getCx()).isCloseTo(60 * 9525L, within(9525L));
            assertThat(second.getPictureData().getData()).isEqualTo(png());
        }
    }

    @Test
    void emptyDrawingsAndOldSnapshotsExportWithoutResurrectingPictures() throws Exception {
        for (boolean withResource : new boolean[]{false, true}) {
            var snapshot = snapshot();
            if (withResource) snapshot.putArray("resources").addObject().put("name", "SHEET_DRAWING_PLUGIN")
                    .put("data", "{\"sheet-1\":{\"data\":{},\"order\":[]}}");
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exporter.export(snapshot, null, null, null).content()))) {
                assertThat(workbook.getAllPictures()).isEmpty();
            }
        }
    }

    @Test
    void rejectsCorruptedPictureInsteadOfDroppingIt() throws Exception {
        var snapshot = snapshot();
        var drawing = image("broken", 0, 0, 100, 100).put("source", "data:image/png;base64,YmFk");
        var group = mapper.createObjectNode().set("data", mapper.createObjectNode().set("broken", drawing));
        snapshot.putArray("resources").addObject().put("name", "SHEET_DRAWING_PLUGIN")
                .put("data", mapper.createObjectNode().set("sheet-1", group).toString());
        assertThatThrownBy(() -> exporter.export(snapshot, null, null, null)).hasMessageContaining("图片");
    }

    private ObjectNode snapshot() throws Exception {
        return (ObjectNode) mapper.readTree("{\"sheetOrder\":[\"sheet-1\"],\"sheets\":{\"sheet-1\":{\"name\":\"Sheet1\",\"cellData\":{}}}}");
    }

    // Keep the 1.2.0 upload/paste resource shape without storing browser snapshots
    // and their embedded image payloads in the repository.
    private ObjectNode univer120Snapshot(boolean cellImage) throws Exception {
        var snapshot = snapshot().put("id", "smoke-book").put("appVersion", "1.2.0");
        var cells = snapshot.withObject("/sheets/sheet-1/cellData");
        cells.putObject("1").putObject("1").put("v", 3);
        cells.putObject("2").putObject("1").put("f", "=SUM(B2,2)").put("v", 5);
        var drawings = mapper.createObjectNode();
        if (!cellImage) drawings.set("upload", image("upload", 3, 2, 200, 120));
        drawings.set("paste", image("paste", 9, 5, 120, 80));
        var group = mapper.createObjectNode();
        group.set("data", drawings);
        var order = group.putArray("order");
        if (!cellImage) order.add("upload");
        order.add("paste");
        snapshot.putArray("resources").addObject().put("name", "SHEET_DRAWING_PLUGIN")
                .put("data", mapper.createObjectNode().set("sheet-1", group).toString());
        if (cellImage) {
            var document = cells.putObject("4").putObject("3").putObject("p").put("id", "cell-doc");
            document.putObject("body").put("dataStream", "\r\b\r\n")
                    .putArray("customBlocks").addObject().put("startIndex", 1).put("blockId", "cell-image");
            var drawing = image("cell-image", 0, 0, 62, 41).put("layoutType", 0);
            drawing.putObject("docTransform").putObject("size").put("width", 62).put("height", 41);
            document.putObject("drawings").set("cell-image", drawing);
            document.putArray("drawingsOrder").add("cell-image");
        }
        return snapshot;
    }

    private ObjectNode image(String id, int row, int column, int width, int height) throws Exception {
        var image = mapper.createObjectNode().put("drawingId", id).put("drawingType", 0)
                .put("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png()));
        image.putObject("transform").put("width", width).put("height", height);
        image.putObject("sheetTransform").putObject("from").put("row", row).put("column", column)
                .put("columnOffset", 10).put("rowOffset", 5);
        return image;
    }

    private byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
