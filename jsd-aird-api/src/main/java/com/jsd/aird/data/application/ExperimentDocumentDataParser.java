package com.jsd.aird.data.application;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.ExperimentImportService;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

/** Projects Word and OCR observations into the staged shape used by template imports. */
@Component
final class ExperimentDocumentDataParser {
    private final FileStorageFacade files;
    private final ExperimentImportService sourceParser;
    private final ObjectMapper json;

    ExperimentDocumentDataParser(FileStorageFacade files, ExperimentImportService sourceParser, ObjectMapper json) {
        this.files = files;
        this.sourceParser = sourceParser;
        this.json = json;
    }

    TemplateDataImportFacade.ParsedTabularFile parse(UUID organizationId, UUID fileId, String fileName,
            String sourceHash, String format, List<TemplateDataImportFacade.FieldDefinition> fields) {
        var observations = "DOCX".equals(format)
                ? wordObservations(organizationId, fileId)
                : imageObservations(organizationId, fileId, fileName, sourceHash);
        if (observations.isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "未从文件中识别出可核对的数据，请检查文件或 OCR 服务");
        }
        var headers = new ArrayList<String>();
        var values = new ArrayList<String>();
        var candidates = json.createObjectNode();
        var cells = json.createArrayNode();
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            var aliases = new LinkedHashSet<String>();
            aliases.add(normalize(field.displayName()));
            if (field.aliases() != null) field.aliases().forEach(alias -> aliases.add(normalize(alias)));
            var matches = observations.stream().filter(item -> aliases.contains(normalize(item.label()))).toList();
            var status = matches.isEmpty() ? "MISSING" : matches.size() > 1 ? "AMBIGUOUS"
                    : "IMAGE".equals(format) ? "REVIEW_REQUIRED" : "MATCHED";
            var selected = matches.size() == 1 ? matches.getFirst() : null;
            headers.add(field.displayName());
            values.add(selected == null ? "" : selected.value());
            var column = columnName(i + 1);
            var candidate = candidates.putObject(column).put("fieldCode", field.fieldCode())
                    .put("status", status).put("sourceLabel", selected == null ? "" : selected.label())
                    .put("sourceLocation", selected == null ? "" : selected.location());
            var options = candidate.putArray("alternatives");
            matches.forEach(item -> options.addObject().put("value", item.value())
                    .put("sourceLocation", item.location()));
            cells.addObject().put("address", column + "2")
                    .put("sourceLocation", selected == null ? "" : selected.location())
                    .put("sourceLabel", selected == null ? "" : selected.label())
                    .put("recognitionStatus", status);
        }
        var layout = json.createObjectNode();
        layout.set("candidates", candidates);
        layout.set("cells", cells);
        var sheet = new TemplateDataImportFacade.ParsedSheet("source-observations", "识别数据", 0,
                1, 2, 1, fields.size(), List.of(1), 1, 2, List.of(headers, values), layout,
                "experiment-document-" + format.toLowerCase(Locale.ROOT));
        return new TemplateDataImportFacade.ParsedTabularFile(format, "experiment-document-data-v1", List.of(sheet));
    }

    private List<Observation> wordObservations(UUID organizationId, UUID fileId) {
        try (var stored = files.open(organizationId, fileId);
             var document = new XWPFDocument(new ByteArrayInputStream(stored.stream().readAllBytes()))) {
            var result = new ArrayList<Observation>();
            for (int i = 0; i < document.getParagraphs().size(); i++)
                addText(result, document.getParagraphs().get(i).getText(), "段落 " + (i + 1));
            for (int t = 0; t < document.getTables().size(); t++) {
                var table = document.getTables().get(t);
                for (int r = 0; r < table.getRows().size(); r++) {
                    var row = table.getRows().get(r);
                    if (row.getTableCells().size() >= 2) {
                        var label = row.getCell(0).getText().trim();
                        var value = row.getCell(1).getText().trim();
                        if (!label.isBlank() && !value.isBlank())
                            result.add(new Observation(label, value, "表格 " + (t + 1) + " 行 " + (r + 1)));
                    }
                    for (var cell : row.getTableCells())
                        addText(result, cell.getText(), "表格 " + (t + 1) + " 行 " + (r + 1));
                }
            }
            return result;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "Word 文件解析失败，请上传有效的 DOCX 文件");
        }
    }

    private List<Observation> imageObservations(UUID organizationId, UUID fileId, String fileName,
            String sourceHash) {
        var parsed = sourceParser.parseSourceFile(organizationId, fileId, fileName, sourceHash, TemplateFormat.IMAGE);
        var result = new ArrayList<Observation>();
        for (var sheet : parsed.initialEditorSnapshot().path("sheets")) {
            var rows = new LinkedHashMap<Integer, Map<Integer, String>>();
            sheet.path("cellData").fields().forEachRemaining(row -> {
                try {
                    var cells = new LinkedHashMap<Integer, String>();
                    row.getValue().fields().forEachRemaining(cell -> {
                        try { cells.put(Integer.parseInt(cell.getKey()), cell.getValue().path("v").asText("")); }
                        catch (NumberFormatException ignored) { }
                    });
                    rows.put(Integer.parseInt(row.getKey()), cells);
                } catch (NumberFormatException ignored) { }
            });
            rows.forEach((rowNo, row) -> row.forEach((column, value) -> {
                var location = "OCR 行 " + (rowNo + 1) + " 列 " + (column + 1);
                addText(result, value, location);
                var next = row.get(column + 1);
                if (next != null && !value.isBlank() && !next.isBlank()
                        && !value.contains(":") && !value.contains("："))
                    result.add(new Observation(value.trim(), next.trim(), location));
            }));
        }
        return result;
    }

    private void addText(List<Observation> target, String text, String location) {
        if (text == null) return;
        for (var line : text.split("\\R")) {
            var colon = line.indexOf('：');
            if (colon < 0) colon = line.indexOf(':');
            if (colon <= 0 || colon >= line.length() - 1) continue;
            var label = line.substring(0, colon).trim();
            var value = line.substring(colon + 1).trim();
            if (!label.isBlank() && !value.isBlank()) target.add(new Observation(label, value, location));
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_：:（）()\\-]+", "").trim();
    }

    private String columnName(int number) {
        var result = new StringBuilder();
        for (int value = number; value > 0; value = (value - 1) / 26)
            result.append((char) ('A' + (value - 1) % 26));
        return result.reverse().toString();
    }

    private record Observation(String label, String value, String location) {}
}
