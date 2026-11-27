package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels;
import com.jsd.aird.rnd.domain.ExperimentStatus;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExperimentExportServiceTest {
    @Test void keepsRichTextInResultPreviewAndExportsSavedCoordinates() throws Exception {
        var json = new ObjectMapper();
        var parsed = new XlsxStructureParser(json).parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"));
        var baseline = new UnifiedLabTemplateCatalog(json).compile(UnifiedLabTemplateCatalog.TEST, parsed).payload().path("snapshot");
        var snapshot = LabWorkbook.prepare(json, baseline, baseline, "export-regression");
        var sheet = LabWorkbook.sheet(snapshot);
        int identity = LabWorkbook.findField(sheet, "sample.experimentNo")[0];
        int material = identity + 1;
        int total = LabWorkbook.findField(sheet, "formula.total")[0];
        writeRichText(LabWorkbook.cell(sheet, identity, 3), "XZ3584");
        writeRichText(LabWorkbook.cell(sheet, material, 1), "EN-49/40%(S-18)");
        LabWorkbook.cell(sheet, identity, 6).put("v", "/");
        LabWorkbook.cell(sheet, material + 1, 1).put("v", "JD-7B");
        LabWorkbook.cell(sheet, material + 1, 6).put("v", 0);
        LabWorkbook.cell(sheet, total, 6).put("v", 0);
        sheet.withObject("/columnData/6").put("hd", 1);

        var projection = LabWorkbook.present(json, snapshot);
        assertThat(projection.locationMap().path("columns").has("3")).isTrue();
        assertThat(projection.locationMap().path("rows").has(Integer.toString(material))).isTrue();
        var model = LabWorkbook.extract(json, snapshot.deepCopy(), json.createObjectNode(), "export-regression");
        assertThat(model.path("sourceGroups").findValuesAsText("sourceIdentity")).contains("XZ3584");
        assertThat(model.path("templateInstance").path("materialLines").findValuesAsText("materialCode")).contains("EN-49/40%(S-18)");

        var repository = mock(ExperimentRepository.class);
        var detail = mock(ExperimentModels.Detail.class);
        var summary = mock(ExperimentModels.Summary.class);
        when(detail.summary()).thenReturn(summary);
        when(summary.title()).thenReturn("Materials test");
        when(summary.status()).thenReturn(ExperimentStatus.DRAFT);
        when(summary.versionNo()).thenReturn(1);
        when(detail.editModel()).thenReturn(json.createObjectNode().set("documentSnapshot", snapshot));
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "test");
        var id = UUID.randomUUID();
        when(repository.detail(actor.organizationId(), id)).thenReturn(Optional.of(detail));
        ActorContext.set(actor);
        try {
            var service = new ExperimentExportService(repository, null, null, json, new SnapshotWorkbookExporter(json), null, null);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(id).content()))) {
                var exported = workbook.getSheetAt(0);
                assertThat(exported.getRow(identity).getCell(3).getStringCellValue()).isEqualTo("XZ3584");
                assertThat(exported.getRow(identity).getCell(6).getStringCellValue()).isEqualTo("/");
                assertThat(exported.getRow(material).getCell(1).getStringCellValue()).isEqualTo("EN-49/40%(S-18)");
                assertThat(exported.getRow(material + 1).getCell(6).getNumericCellValue()).isZero();
                assertThat(exported.getRow(total).getCell(6).getCellFormula()).isEqualTo(LabWorkbook.cell(sheet, total, 6).path("f").asText().substring(1));
                assertThat(exported.getRow(total).getCell(6).getNumericCellValue()).isZero();
                assertThat(exported.isColumnHidden(6)).isTrue();
            }
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(id, "BLANK").content()))) {
                var exported = workbook.getSheetAt(0);
                assertThat(exported.getRow(identity).getCell(3).getStringCellValue()).isEmpty();
                assertThat(exported.getRow(material).getCell(1).getStringCellValue()).isEmpty();
            }
        } finally { ActorContext.clear(); }
    }

    private static void writeRichText(ObjectNode cell, String value) {
        cell.remove("v");
        cell.withObject("/p/body").put("dataStream", value + "\r\n");
    }

    @Test void exportsAddedFormulasAndPicturesFromTheSavedLabWorkingCopy() throws Exception {
        var json = new ObjectMapper();
        var parsed = new XlsxStructureParser(json).parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"));
        var snapshot = new UnifiedLabTemplateCatalog(json).compile(UnifiedLabTemplateCatalog.TEST, parsed).payload().path("snapshot").deepCopy();
        var sheet = LabWorkbook.sheet(snapshot);
        LabWorkbook.cell(sheet,160,11).put("f","SUM(K161:K163)").put("v",60);
        LabWorkbook.cell(sheet,160,10).put("v",10);
        assertThat(LabWorkbook.present(json,snapshot).snapshot()).isEqualTo(snapshot);
        var bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        var resources = ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot).putArray("resources");
        var drawings = json.createObjectNode();
        var data = drawings.putObject(sheet.path("id").asText());
        data.putArray("order").add("picture");
        var picture = data.putObject("data").putObject("picture");
        picture.put("drawingType",0).put("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray()));
        picture.putObject("transform").put("left",40).put("top",50).put("width",80).put("height",90);
        resources.addObject().put("name","SHEET_DRAWING_PLUGIN").put("data",json.writeValueAsString(drawings));
        assertThat(LabWorkbook.present(json,snapshot).snapshot()).isEqualTo(snapshot);
        var drawingOnly = snapshot.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)LabWorkbook.sheet(drawingOnly).path("cellData")).remove("160");
        assertThat(LabWorkbook.present(json,drawingOnly).snapshot()).isEqualTo(drawingOnly);
        var repository = mock(ExperimentRepository.class);
        var detail = mock(ExperimentModels.Detail.class);
        var summary = mock(ExperimentModels.Summary.class);
        when(detail.summary()).thenReturn(summary);
        when(summary.title()).thenReturn("Complex report");
        when(summary.status()).thenReturn(ExperimentStatus.DRAFT);
        when(summary.versionNo()).thenReturn(1);
        when(detail.editModel()).thenReturn(json.createObjectNode().set("documentSnapshot",snapshot));
        var actor = new Actor(UUID.randomUUID(),UUID.randomUUID(),"test");
        var id = UUID.randomUUID();
        when(repository.detail(actor.organizationId(),id)).thenReturn(Optional.of(detail));
        ActorContext.set(actor);
        try {
            var service = new ExperimentExportService(repository,null,null,json,new SnapshotWorkbookExporter(json),null,null);
            var download = service.export(id);
            var imported = new XlsxStructureParser(json).parseSnapshot(new ByteArrayInputStream(download.content()));
            assertThat(imported.path("resources")).hasSize(1);
            for(var importedSheet:imported.path("sheets")) assertThat(importedSheet.path("name").asText()).isNotEqualTo(SnapshotWorkbookExporter.MANIFEST_SHEET);
            var roundTrip = new SnapshotWorkbookExporter(json).export(imported,json.createArrayNode(),json.createObjectNode(),
                    new SnapshotWorkbookExporter.Manifest(null,null,null,"DRAFT",null));
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(roundTrip.content()))) {
                assertThat(workbook.getAllPictures()).hasSize(1);
                assertThat(workbook.getSheetAt(0).getRow(160).getCell(11).getCellFormula()).isEqualTo("SUM(K161:K163)");
            }
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(download.content()))) {
                assertThat(workbook.getSheetAt(0).getRow(160).getCell(11).getCellFormula()).isEqualTo("SUM(K161:K163)");
                assertThat(workbook.getAllPictures()).hasSize(1);
                assertThat(workbook.getSheetAt(0).getDrawingPatriarch().getShapes()).hasSize(1);
            }
        } finally { ActorContext.clear(); }
    }
}
