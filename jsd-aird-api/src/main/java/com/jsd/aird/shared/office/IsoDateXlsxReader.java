package com.jsd.aird.shared.office;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalQueries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Reads ISO date cells before POI's transitional schema tries to decode t="d". */
public final class IsoDateXlsxReader {
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String SHEET = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml";
    private static final String BOOK = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";

    private IsoDateXlsxReader() { }

    public static XSSFWorkbook open(InputStream source) throws Exception {
        var pack = OPCPackage.open(source);
        try {
            XlsxStyleNamespaceCompatibility.prepare(pack);
            boolean date1904 = false;
            for (var part : pack.getPartsByContentType(BOOK)) {
                try (var input = part.getInputStream()) {
                    var properties = xml(input).getElementsByTagNameNS(NS, "workbookPr");
                    if (properties.getLength() > 0) {
                        var value = ((Element) properties.item(0)).getAttribute("date1904");
                        date1904 = "1".equals(value) || "true".equals(value);
                    }
                }
            }
            var formats = new HashMap<String, List<DateCell>>();
            for (var part : pack.getPartsByContentType(SHEET)) {
                // Ordinary XLSX sheets stay in their original representation;
                // only sheets with ISO date cells need a second XML tree.
                try (var input = part.getInputStream()) {
                    if (!hasIsoDates(input)) continue;
                }
                Document document;
                try (var input = part.getInputStream()) { document = xml(input); }
                var converted = new ArrayList<DateCell>();
                boolean changed = false;
                var cells = document.getElementsByTagNameNS(NS, "c");
                for (int index = 0; index < cells.getLength(); index++) {
                    var cell = (Element) cells.item(index);
                    if (!"d".equals(cell.getAttribute("t"))) continue;
                    var values = cell.getElementsByTagNameNS(NS, "v");
                    var value = values.getLength() == 0 ? null : values.item(0);
                    if (value == null || value.getTextContent().isBlank()) {
                        // An empty ISO cell remains blank, not numeric zero / 1900-01-00.
                        cell.removeAttribute("t");
                        if (value != null) cell.removeChild(value);
                    } else {
                        var date = number(value.getTextContent().strip(), date1904, cell.getAttribute("r"));
                        value.setTextContent(Double.toString(date.value()));
                        cell.setAttribute("t", "n");
                        converted.add(new DateCell(cell.getAttribute("r"), date.format()));
                    }
                    changed = true;
                }
                if (!changed) continue;
                var output = new ByteArrayOutputStream();
                var transformers = TransformerFactory.newDefaultInstance();
                transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                transformers.newTransformer().transform(new DOMSource(document), new StreamResult(output));
                part.clear();
                try (var target = part.getOutputStream()) { output.writeTo(target); }
                formats.put(part.getPartName().getName(), converted);
            }
            var workbook = new XSSFWorkbook(pack);
            try {
                var defaultStyles = new HashMap<String, org.apache.poi.xssf.usermodel.XSSFCellStyle>();
                for (var sheet : workbook) {
                    var dates = formats.get(((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).getPackagePart().getPartName().getName());
                    if (dates == null) continue;
                    for (var date : dates) {
                        var address = new org.apache.poi.ss.util.CellReference(date.address());
                        var cell = sheet.getRow(address.getRow()).getCell(address.getCol());
                        var old = cell.getCellStyle();
                        if (old.getDataFormat() != 0) continue;
                        var key = old.getIndex() + ":" + date.format();
                        var style = defaultStyles.computeIfAbsent(key, ignored -> {
                            var created = workbook.createCellStyle();
                            created.cloneStyleFrom(old);
                            created.setDataFormat(workbook.createDataFormat().getFormat(date.format()));
                            return created;
                        });
                        cell.setCellStyle(style);
                    }
                }
                return workbook;
            } catch (Exception error) {
                workbook.close();
                throw error;
            }
        } catch (Exception error) {
            pack.revert();
            throw error;
        }
    }

    private static Document xml(InputStream input) throws Exception {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(input);
    }

    private static boolean hasIsoDates(InputStream input) throws Exception {
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        var reader = factory.createXMLStreamReader(input);
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && NS.equals(reader.getNamespaceURI())
                        && "c".equals(reader.getLocalName()) && "d".equals(reader.getAttributeValue(null, "t"))) return true;
            }
            return false;
        } finally { reader.close(); }
    }

    private static DateNumber number(String text, boolean date1904, String address) {
        try {
            if (!text.contains("T") && text.indexOf(':') == 2) {
                var time = DateTimeFormatter.ISO_TIME.parse(text).query(TemporalQueries.localTime());
                return new DateNumber(time.toNanoOfDay() / 86_400_000_000_000d, "hh:mm:ss.000");
            }
            LocalDateTime date;
            boolean hasTime = text.contains("T");
            if (hasTime) {
                var parsed = DateTimeFormatter.ISO_DATE_TIME.parse(text);
                // Excel has no timezone: retain the explicitly written wall time,
                // never reinterpret it in the server's timezone.
                date = LocalDateTime.of(parsed.query(TemporalQueries.localDate()), parsed.query(TemporalQueries.localTime()));
            } else {
                date = LocalDate.parse(text, DateTimeFormatter.ISO_DATE).atStartOfDay();
            }
            double value = DateUtil.getExcelDate(date, date1904);
            if (value < 0) throw new IllegalArgumentException("date precedes workbook epoch");
            return new DateNumber(value, hasTime ? "yyyy-mm-dd hh:mm:ss.000" : "yyyy-mm-dd");
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("单元格 " + address + " 的 ISO 日期无效：" + text, error);
        }
    }

    private record DateCell(String address, String format) { }
    private record DateNumber(double value, String format) { }
}
