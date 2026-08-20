package com.jsd.aird.tpl.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.util.Units;
import org.springframework.stereotype.Service;

/**
 * Converts legacy office files into the two canonical template workspaces.
 * The original upload is staged separately by the controller and is never
 * discarded; the returned normalized file is the one used by recognition.
 */
@Service
public class TemplateFileNormalizationService {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final FileStorageFacade storage;

    public TemplateFileNormalizationService(FileStorageFacade storage) {
        this.storage = storage;
    }

    public Result normalize(String originalName, String contentType, byte[] source) {
        String name = originalName == null || originalName.isBlank() ? "template.bin" : originalName;
        String extension = extension(name);
        return switch (extension) {
            case "xlsx" -> passthrough(name, contentType, source, "XLSX");
            case "docx" -> passthrough(name, contentType, source, "DOCX");
            case "xls" -> convertWorkbook(name, source, "XLS");
            case "csv" -> convertCsv(name, source);
            case "doc" -> convertDoc(name, source);
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "模板中心仅支持 XLSX、DOCX、XLS、CSV 或 DOC 文件");
        };
    }

    private Result passthrough(String name, String contentType, byte[] bytes, String format) {
        validateMagic(format, bytes);
        return new Result(name, name, contentType == null ? "application/octet-stream" : contentType,
                format, format, bytes, "PASSTHROUGH", "当前文件已是标准模板工作区格式");
    }

    private Result convertWorkbook(String name, byte[] bytes, String sourceFormat) {
        validateMagic(sourceFormat, bytes);
        try (Workbook input = new HSSFWorkbook(new ByteArrayInputStream(bytes));
             XSSFWorkbook output = new XSSFWorkbook();
             ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            for (int sheetIndex = 0; sheetIndex < input.getNumberOfSheets(); sheetIndex++) {
                Sheet source = input.getSheetAt(sheetIndex);
                Sheet target = output.createSheet(source.getSheetName());
                for (Row sourceRow : source) {
                    Row targetRow = target.createRow(sourceRow.getRowNum());
                    for (Cell sourceCell : sourceRow) {
                        Cell targetCell = targetRow.createCell(sourceCell.getColumnIndex());
                        copyCellValue(sourceCell, targetCell);
                    }
                }
            }
            if (output.getNumberOfSheets() == 0) output.createSheet("Sheet1");
            output.write(result);
            return new Result(name, replaceExtension(name, "xlsx"), XLSX_MIME,
                    sourceFormat, "XLSX", result.toByteArray(), "NORMALIZED", "已转换为标准 XLSX 工作区");
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "XLS 文件损坏或无法转换为标准 XLSX");
        }
    }

    private Result convertCsv(String name, byte[] bytes) {
        validateMagic("CSV", bytes);
        String text = decodeCsv(bytes);
        try (XSSFWorkbook output = new XSSFWorkbook(); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            Sheet sheet = output.createSheet("Sheet1");
            try (Reader reader = new StringReader(text)) {
                int rowNumber = 0;
                for (String line : readLines(reader)) {
                    Row row = sheet.createRow(rowNumber++);
                    List<String> values = parseCsvLine(line);
                    for (int column = 0; column < values.size(); column++) row.createCell(column).setCellValue(values.get(column));
                }
            }
            output.write(result);
            return new Result(name, replaceExtension(name, "xlsx"), XLSX_MIME,
                    "CSV", "XLSX", result.toByteArray(), "NORMALIZED", "已转换为标准 XLSX 工作区");
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "CSV 文件无法解析或转换为标准 XLSX");
        }
    }

    private Result convertDoc(String name, byte[] bytes) {
        validateMagic("DOC", bytes);
        try (HWPFDocument input = new HWPFDocument(new ByteArrayInputStream(bytes));
             XWPFDocument output = new XWPFDocument();
             ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            Range range = input.getRange();
            String text = range.text();
            for (String paragraph : text.split("\\r?\\n")) {
                if (paragraph.isBlank()) continue;
                XWPFParagraph target = output.createParagraph();
                target.createRun().setText(paragraph);
            }
            copyDocTables(input, output);
            copyDocPictures(input, output);
            output.write(result);
            return new Result(name, replaceExtension(name, "docx"), DOCX_MIME,
                    "DOC", "DOCX", result.toByteArray(), "NORMALIZED", "已转换为标准 DOCX 工作区");
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "DOC 文件损坏或无法转换为标准 DOCX");
        }
    }

    private void copyDocTables(HWPFDocument input, XWPFDocument output) {
        TableIterator iterator = new TableIterator(input.getRange());
        while (iterator.hasNext()) {
            Table source = iterator.next();
            int rows = Math.max(1, source.numRows());
            int columns = source.numRows() == 0 ? 1 : Math.max(1, source.getRow(0).numCells());
            XWPFTable target = output.createTable(rows, columns);
            for (int rowIndex = 0; rowIndex < source.numRows(); rowIndex++) {
                TableRow sourceRow = source.getRow(rowIndex);
                XWPFTableRow targetRow = target.getRow(rowIndex);
                for (int cellIndex = 0; cellIndex < sourceRow.numCells(); cellIndex++) {
                    TableCell sourceCell = sourceRow.getCell(cellIndex);
                    XWPFTableCell targetCell = targetRow.getCell(cellIndex);
                    targetCell.setText(sourceCell.text().replace('\u0007', ' ').trim());
                }
            }
        }
    }

    private void copyDocPictures(HWPFDocument input, XWPFDocument output) {
        for (Picture picture : input.getPicturesTable().getAllPictures()) {
            try {
                int pictureType = picture.getMimeType() != null && picture.getMimeType().contains("png")
                        ? org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG
                        : org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG;
                XWPFParagraph paragraph = output.createParagraph();
                paragraph.createRun().addPicture(new ByteArrayInputStream(picture.getContent()), pictureType,
                        "image." + picture.suggestFileExtension(),
                        Units.toEMU(Math.max(1, picture.getWidth())), Units.toEMU(Math.max(1, picture.getHeight())));
            } catch (Exception ignored) {
                // Unsupported legacy picture encodings must not abort text/table normalization.
            }
        }
    }

    private void validateMagic(String format, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, format + " 文件为空或损坏");
        }
        boolean ole2 = bytes.length >= 8
                && (bytes[0] & 0xff) == 0xd0 && (bytes[1] & 0xff) == 0xcf
                && (bytes[2] & 0xff) == 0x11 && (bytes[3] & 0xff) == 0xe0
                && (bytes[4] & 0xff) == 0xa1 && (bytes[5] & 0xff) == 0xb1
                && (bytes[6] & 0xff) == 0x1a && (bytes[7] & 0xff) == 0xe1;
        boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 3 && bytes[3] == 4;
        boolean valid = switch (format) {
            case "DOC", "XLS" -> ole2;
            case "DOCX", "XLSX" -> zip;
            default -> true;
        };
        if (!valid) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, format + " 文件格式与扩展名不一致或文件损坏");
        }
    }

    private void copyCellValue(Cell source, Cell target) {
        switch (source.getCellType()) {
            case STRING -> target.setCellValue(source.getStringCellValue());
            case NUMERIC -> target.setCellValue(source.getNumericCellValue());
            case BOOLEAN -> target.setCellValue(source.getBooleanCellValue());
            case FORMULA -> target.setCellFormula(source.getCellFormula());
            case ERROR -> target.setCellErrorValue(source.getErrorCellValue());
            default -> { }
        }
    }

    private String decodeCsv(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) {
            try {
                return Charset.forName("GBK").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException exception) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "CSV 编码无法识别，请保存为 UTF-8 或 GBK");
            }
        }
    }

    private List<String> readLines(Reader reader) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = reader.read()) >= 0) {
            if (value == '\n') { lines.add(line.toString()); line.setLength(0); }
            else if (value != '\r') line.append((char) value);
        }
        if (!line.isEmpty() || lines.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                else quoted = !quoted;
            } else if (current == ',' && !quoted) { values.add(value.toString()); value.setLength(0); }
            else value.append(current);
        }
        values.add(value.toString());
        return values;
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String replaceExtension(String name, String extension) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + "." + extension;
    }

    public record Result(
            String originalName,
            String normalizedName,
            String normalizedContentType,
            String originalFormat,
            String normalizedFormat,
            byte[] normalizedBytes,
            String normalizationStatus,
            String normalizationMessage
    ) { }
}
