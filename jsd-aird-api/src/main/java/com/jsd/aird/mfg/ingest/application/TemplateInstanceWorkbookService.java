package com.jsd.aird.mfg.ingest.application;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.springframework.stereotype.Service;

/** Produces a fillable XLSX carrying stable template and Binding identities. */
@Service
public class TemplateInstanceWorkbookService {

    private final ProductionOrderRepository templates;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;
    private final JsonCanonicalizer canonicalizer;

    public TemplateInstanceWorkbookService(
            ProductionOrderRepository templates,
            FileObjectRepository files,
            ObjectStorage storage,
            ObjectMapper objectMapper,
            JsonCanonicalizer canonicalizer
    ) {
        this.templates = templates;
        this.files = files;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    public Download download(UUID versionId) {
        var actor = ActorContext.required();
        var template = templates.findPublishedTemplate(actor.organizationId(), versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "已发布模板不存在"));
        if (!"XLSX".equals(template.format())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "当前只支持下载 XLSX 实例模板");
        }
        var snapshot = loadSnapshot(actor.organizationId(), template.snapshotFileId());
        var content = write(template, snapshot);
        return new Download("production-instance-" + versionId + ".xlsx", content);
    }

    private JsonNode loadSnapshot(UUID organizationId, UUID fileId) {
        var file = files.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
        try (var stored = storage.get(file.objectKey())) {
            return objectMapper.readTree(stored.stream());
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照读取失败");
        }
    }

    private byte[] write(ProductionOrderRepository.PublishedTemplate template, JsonNode snapshot) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheetNames = new HashMap<String, String>();
            var styles = new HashMap<String, XSSFCellStyle>();
            for (var sheetIdNode : snapshot.path("sheetOrder")) {
                var sheetId = sheetIdNode.asText();
                var source = snapshot.path("sheets").path(sheetId);
                var name = safeSheetName(source.path("name").asText("Sheet"));
                var sheet = workbook.createSheet(name);
                sheetNames.put(sheetId, name);
                copyCells(workbook, snapshot.path("styles"), styles, source, sheet);
                copyGeometry(source, sheet);
            }
            if (workbook.getNumberOfSheets() == 0) workbook.createSheet("Sheet1");
            writeManifest(workbook, template, sheetNames);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "实例 XLSX 生成失败：" + exception.getMessage());
        }
    }

    private void copyCells(
            XSSFWorkbook workbook,
            JsonNode workbookStyles,
            Map<String, XSSFCellStyle> styles,
            JsonNode source,
            org.apache.poi.xssf.usermodel.XSSFSheet target
    ) {
        var rows = source.path("cellData").fields();
        while (rows.hasNext()) {
            var rowEntry = rows.next();
            var row = target.createRow(Integer.parseInt(rowEntry.getKey()));
            var cells = rowEntry.getValue().fields();
            while (cells.hasNext()) {
                var cellEntry = cells.next();
                var cell = row.createCell(Integer.parseInt(cellEntry.getKey()));
                var sourceCell = cellEntry.getValue();
                if (sourceCell.has("f")) {
                    var formula = sourceCell.path("f").asText().replaceFirst("^=", "");
                    if (!formula.isBlank()) cell.setCellFormula(formula);
                } else if (sourceCell.path("v").isBoolean()) {
                    cell.setCellValue(sourceCell.path("v").asBoolean());
                } else if (sourceCell.path("v").isNumber()) {
                    cell.setCellValue(sourceCell.path("v").asDouble());
                } else if (sourceCell.has("v")) {
                    cell.setCellValue(sourceCell.path("v").asText());
                } else {
                    cell.setCellType(CellType.BLANK);
                }
                var styleNode = sourceCell.path("s").isTextual()
                        ? workbookStyles.path(sourceCell.path("s").asText()) : sourceCell.path("s");
                if (styleNode.isObject() && !styleNode.isEmpty()) {
                    cell.setCellStyle(styles.computeIfAbsent(styleNode.toString(), ignored ->
                            createStyle(workbook, styleNode)));
                }
            }
        }
    }

    private XSSFCellStyle createStyle(XSSFWorkbook workbook, JsonNode source) {
        var style = workbook.createCellStyle();
        var font = workbook.createFont();
        var hasFont = false;
        if (source.has("ff")) { font.setFontName(source.path("ff").asText()); hasFont = true; }
        if (source.has("fs")) { font.setFontHeightInPoints((short) source.path("fs").asInt()); hasFont = true; }
        if (source.path("bl").asInt() == 1) { font.setBold(true); hasFont = true; }
        if (source.path("it").asInt() == 1) { font.setItalic(true); hasFont = true; }
        var fontColor = color(source.path("cl"));
        if (fontColor != null) { font.setColor(fontColor); hasFont = true; }
        if (hasFont) style.setFont(font);
        var background = color(source.path("bg"));
        if (background != null) {
            style.setFillForegroundColor(background);
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        style.setAlignment(switch (source.path("ht").asInt()) {
            case 1 -> HorizontalAlignment.LEFT;
            case 2 -> HorizontalAlignment.CENTER;
            case 3 -> HorizontalAlignment.RIGHT;
            case 4 -> HorizontalAlignment.JUSTIFY;
            case 5 -> HorizontalAlignment.FILL;
            case 6 -> HorizontalAlignment.DISTRIBUTED;
            default -> HorizontalAlignment.GENERAL;
        });
        style.setVerticalAlignment(switch (source.path("vt").asInt()) {
            case 1 -> VerticalAlignment.TOP;
            case 2 -> VerticalAlignment.CENTER;
            default -> VerticalAlignment.BOTTOM;
        });
        style.setWrapText(source.path("tb").asInt() == 3);
        var numberPattern = source.path("n").path("pattern").asText("");
        if (!numberPattern.isBlank()) style.setDataFormat(workbook.createDataFormat().getFormat(numberPattern));
        var borders = source.path("bd");
        style.setBorderTop(border(borders.path("t").path("s").asInt()));
        style.setBorderRight(border(borders.path("r").path("s").asInt()));
        style.setBorderBottom(border(borders.path("b").path("s").asInt()));
        style.setBorderLeft(border(borders.path("l").path("s").asInt()));
        return style;
    }

    private BorderStyle border(int value) {
        return switch (value) {
            case 1 -> BorderStyle.THIN;
            case 2 -> BorderStyle.HAIR;
            case 3 -> BorderStyle.DOTTED;
            case 4 -> BorderStyle.DASHED;
            case 5 -> BorderStyle.DASH_DOT;
            case 6 -> BorderStyle.DASH_DOT_DOT;
            case 7 -> BorderStyle.DOUBLE;
            case 8 -> BorderStyle.MEDIUM;
            case 9 -> BorderStyle.MEDIUM_DASHED;
            case 10 -> BorderStyle.MEDIUM_DASH_DOT;
            case 11 -> BorderStyle.MEDIUM_DASH_DOT_DOT;
            case 12 -> BorderStyle.SLANTED_DASH_DOT;
            case 13 -> BorderStyle.THICK;
            default -> BorderStyle.NONE;
        };
    }

    private XSSFColor color(JsonNode source) {
        var value = source.path("rgb").asText("").replace("#", "");
        if (!value.matches("[0-9A-Fa-f]{6}")) return null;
        var bytes = new byte[] {
                (byte) Integer.parseInt(value.substring(0, 2), 16),
                (byte) Integer.parseInt(value.substring(2, 4), 16),
                (byte) Integer.parseInt(value.substring(4, 6), 16)
        };
        return new XSSFColor(bytes, new DefaultIndexedColorMap());
    }

    private void copyGeometry(JsonNode source, org.apache.poi.xssf.usermodel.XSSFSheet target) {
        for (var merge : source.path("mergeData")) {
            target.addMergedRegion(new CellRangeAddress(
                    merge.path("startRow").asInt(), merge.path("endRow").asInt(),
                    merge.path("startColumn").asInt(), merge.path("endColumn").asInt()));
        }
        var rows = source.path("rowData").fields();
        while (rows.hasNext()) {
            var entry = rows.next();
            var row = target.getRow(Integer.parseInt(entry.getKey()));
            if (row == null) row = target.createRow(Integer.parseInt(entry.getKey()));
            if (entry.getValue().has("h")) row.setHeightInPoints((float) (entry.getValue().path("h").asDouble() * 72d / 96d));
            row.setZeroHeight(entry.getValue().path("hd").asInt() == 1);
        }
        var columns = source.path("columnData").fields();
        while (columns.hasNext()) {
            var entry = columns.next();
            var index = Integer.parseInt(entry.getKey());
            if (entry.getValue().has("w")) {
                target.setColumnWidth(index, Math.min(255 * 256,
                        Math.max(256, (int) Math.round(entry.getValue().path("w").asDouble() / 7d * 256d))));
            }
            target.setColumnHidden(index, entry.getValue().path("hd").asInt() == 1);
        }
    }

    private void writeManifest(
            XSSFWorkbook workbook,
            ProductionOrderRepository.PublishedTemplate template,
            Map<String, String> sheetNames
    ) {
        var meta = workbook.createSheet(InstanceWorkbookManifest.SHEET_NAME);
        put(meta, 0, "JSD_INSTANCE_TEMPLATE", "1");
        put(meta, 1, "templateVersionId", template.versionId().toString());
        put(meta, 2, "schemaHash", canonicalizer.hash(template.schema()));
        put(meta, 3, "mappingHash", canonicalizer.hash(template.mapping()));
        put(meta, 4, "structureFingerprint", structureFingerprint(template.mapping(), sheetNames));
        put(meta, 6, "bindingId", "definedName");
        var row = 7;
        for (var binding : template.mapping()) {
            var address = firstText(binding.path("locator"),
                    "logicalInputRange", "valueRange", "address", "range", "dataRange");
            var sheetName = resolveSheetName(binding.path("locator"), sheetNames);
            if (address.isBlank() || sheetName.isBlank()) continue;
            var definedName = definedName(binding.path("bindingId").asText(UUID.randomUUID().toString()));
            var name = workbook.createName();
            name.setNameName(definedName);
            name.setRefersToFormula("'" + sheetName.replace("'", "''") + "'!" + absolute(address));
            meta.createRow(row).createCell(0).setCellValue(binding.path("bindingId").asText());
            meta.getRow(row).createCell(1).setCellValue(definedName);
            row++;
        }
        workbook.setSheetVisibility(workbook.getSheetIndex(meta), SheetVisibility.VERY_HIDDEN);
    }

    private String structureFingerprint(JsonNode mapping, Map<String, String> sheetNames) {
        return canonicalizer.hashText(mapping.toString() + "|" + sheetNames.values());
    }

    private void put(org.apache.poi.ss.usermodel.Sheet sheet, int row, String key, String value) {
        var target = sheet.createRow(row);
        target.createCell(0).setCellValue(key);
        target.createCell(1).setCellValue(value);
    }

    private String resolveSheetName(JsonNode locator, Map<String, String> names) {
        var byId = names.get(locator.path("sheetId").asText());
        if (byId != null) return byId;
        return locator.path("sheetName").asText("");
    }

    private String firstText(JsonNode source, String... keys) {
        for (var key : keys) {
            var value = source.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String definedName(String bindingId) {
        return "JSD_B_" + bindingId.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
    }

    private String absolute(String address) {
        return address.replaceAll("(?i)([A-Z]+)([0-9]+)", "\\$$1\\$$2");
    }

    private String safeSheetName(String value) {
        var safe = value.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return safe.substring(0, Math.min(31, safe.length()));
    }

    public record Download(String fileName, byte[] content) {
    }
}
