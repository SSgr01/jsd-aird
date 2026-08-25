package com.jsd.aird.quality.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/** Best-effort parser for the structured fields used by the quality data hub. */
@Component
public class QualitySourceParser {
    public QualitySourceParser() {}

    public ObjectNode parse(QualityDataDefinitions.Type type, ObjectNode draft,
                            FileStorageFacade.StoredFile file) {
        try {
            var bytes = file.stream().readAllBytes();
            var text = extractText(file.originalName(), bytes);
            if (text != null && !text.isBlank()) applyLabeledText(type, draft, text);
            if (isCsv(file.originalName())) applyCsv(type, draft, text);
            else if (isSpreadsheet(file.originalName())) applySpreadsheet(type, draft, bytes);
            draft.put("sourceFile", file.originalName());
            draft.put("parseStatus", hasStructuredValue(type, draft) ? "已解析" : "待人工补齐");
        } catch (Exception ignored) {
            draft.put("parseStatus", "待人工补齐");
        }
        return draft;
    }

    private void applySpreadsheet(QualityDataDefinitions.Type type, ObjectNode draft, byte[] bytes) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) return;
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) return;
            var formatter = new DataFormatter(Locale.ROOT);
            var columns = new LinkedHashMap<String, Integer>();
            for (var cell : header) {
                var value = formatter.formatCellValue(cell);
                if (!value.isBlank()) columns.put(normalize(value), cell.getColumnIndex());
            }
            var row = sheet.getRow(sheet.getFirstRowNum() + 1);
            if (row == null) return;
            for (var field : type.fields()) {
                var column = columns.get(normalize(field.label()));
                if (column == null) column = columns.get(normalize(field.key()));
                if (column == null) continue;
                var value = formatter.formatCellValue(row.getCell(column));
                if (!value.isBlank()) draft.put(field.key(), value.trim());
            }
        }
    }

    private void applyCsv(QualityDataDefinitions.Type type, ObjectNode draft, String text) {
        if (text == null || text.isBlank()) return;
        var lines = text.replace("\r", "").split("\n");
        if (lines.length < 2) return;
        var headers = parseCsvLine(lines[0]);
        var values = parseCsvLine(lines[1]);
        for (var field : type.fields()) {
            var index = -1;
            for (var i = 0; i < headers.size(); i++) {
                if (normalize(headers.get(i)).equals(normalize(field.label()))
                        || normalize(headers.get(i)).equals(normalize(field.key()))) {
                    index = i;
                    break;
                }
            }
            if (index >= 0 && index < values.size() && !values.get(index).trim().isBlank())
                draft.put(field.key(), values.get(index).trim());
        }
    }

    private ArrayList<String> parseCsvLine(String line) {
        var values = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;
        for (var i = 0; i < line.length(); i++) {
            var ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else current.append(ch);
        }
        values.add(current.toString());
        return values;
    }

    private String extractText(String name, byte[] bytes) throws Exception {
        var lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) return new String(bytes, StandardCharsets.UTF_8);
        if (lower.endsWith(".pdf")) {
            try (var document = Loader.loadPDF(bytes)) {
                return new PDFTextStripper().getText(document);
            }
        }
        if (lower.endsWith(".docx")) {
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (!"word/document.xml".equals(entry.getName())) continue;
                    var xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    var factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    Document document = factory.newDocumentBuilder().parse(
                            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                    return document.getDocumentElement().getTextContent();
                }
            }
        }
        return null;
    }

    private void applyLabeledText(QualityDataDefinitions.Type type, ObjectNode draft, String text) {
        var normalizedText = text.replace('\u00a0', ' ').replace('\r', '\n');
        for (var field : type.fields()) {
            var labels = new ArrayList<String>();
            labels.add(field.label());
            labels.add(field.key());
            for (var label : labels) {
                var pattern = Pattern.compile("(?is)(?:^|[\\n;；])\\s*" + Pattern.quote(label)
                        + "\\s*[:：=]\\s*([^\\n;；]{1,300})");
                var match = pattern.matcher(normalizedText);
                if (match.find() && !match.group(1).trim().isBlank()) {
                    draft.put(field.key(), match.group(1).trim());
                    break;
                }
            }
        }
    }

    private boolean hasStructuredValue(QualityDataDefinitions.Type type, ObjectNode draft) {
        return type.fields().stream().anyMatch(field -> {
            var value = draft.path(field.key()).asText("").trim();
            return !value.isBlank() && !"待解析".equals(value) && !"待判定".equals(value);
        });
    }

    private boolean isSpreadsheet(String name) {
        var lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xls") || lower.endsWith(".xlsx");
    }

    private boolean isCsv(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s:_\\-（）()\\[\\]/]", "");
    }
}
