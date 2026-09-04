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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final String libreOfficeExecutable;
    private final Duration libreOfficeTimeout;

    @Autowired
    public TemplateFileNormalizationService(
            FileStorageFacade storage,
            @Value("${app.template-normalization.libreoffice.executable:soffice}") String libreOfficeExecutable,
            @Value("${app.template-normalization.libreoffice.timeout:90s}") Duration libreOfficeTimeout
    ) {
        this.storage = storage;
        this.libreOfficeExecutable = libreOfficeExecutable == null || libreOfficeExecutable.isBlank()
                ? "soffice" : libreOfficeExecutable.strip();
        this.libreOfficeTimeout = libreOfficeTimeout == null || libreOfficeTimeout.isNegative()
                || libreOfficeTimeout.isZero() ? Duration.ofSeconds(90) : libreOfficeTimeout;
    }

    TemplateFileNormalizationService(FileStorageFacade storage) {
        this(storage, "soffice", Duration.ofSeconds(90));
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
        var sourceFacts = legacyWorkbookFacts(bytes);
        var normalized = convertWithLibreOffice(bytes, "xls", "xlsx", sourceFormat);
        validateMagic("XLSX", normalized);
        validateXlsConversion(sourceFacts, normalized);
        return new Result(name, replaceExtension(name, "xlsx"), XLSX_MIME,
                sourceFormat, "XLSX", normalized, "NORMALIZED", "已通过 LibreOffice 高保真转换为标准 XLSX 工作区");
    }

    /**
     * Converts legacy binary Office files without rebuilding the document with
     * POI.  A POI value-only copy silently discards borders, fills, merges,
     * dimensions and other layout facts that the template recognizer needs.
     */
    private byte[] convertWithLibreOffice(
            byte[] bytes, String sourceExtension, String targetExtension, String sourceFormat
    ) {
        Path workDirectory = null;
        Process process = null;
        try {
            workDirectory = Files.createTempDirectory("jsd-aird-" + sourceExtension + "-convert-")
                    .toAbsolutePath().normalize();
            Path sourceFile = workDirectory.resolve("source." + sourceExtension);
            Path outputFile = workDirectory.resolve("source." + targetExtension);
            Path profileDirectory = workDirectory.resolve("libreoffice-profile");
            Files.write(sourceFile, bytes);
            Files.createDirectories(profileDirectory);

            process = new ProcessBuilder(
                    libreOfficeExecutable,
                    "--headless",
                    "--nologo",
                    "--nodefault",
                    "--nofirststartwizard",
                    "-env:UserInstallation=" + profileDirectory.toUri(),
                    "--convert-to",
                    targetExtension,
                    "--outdir",
                    workDirectory.toString(),
                    sourceFile.toString()
            ).redirectErrorStream(true).start();

            boolean completed = process.waitFor(Math.max(1, libreOfficeTimeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new ApiException(ApiErrorCode.FILE_NOT_READY,
                        sourceFormat + " 转换超时（" + libreOfficeTimeout.toSeconds()
                                + " 秒），请检查 LibreOffice 服务");
            }
            String converterOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0 || !Files.isRegularFile(outputFile)) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST,
                        "LibreOffice 无法转换 " + sourceFormat + " 文件"
                                + (converterOutput.isBlank() ? "" : "：" + converterOutput));
            }
            return Files.readAllBytes(outputFile);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY,
                    "LibreOffice 转换器不可用，请配置 app.template-normalization.libreoffice.executable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(ApiErrorCode.FILE_NOT_READY,
                    sourceFormat + " 转换任务已中断，请稍后重试");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteTemporaryDirectory(workDirectory);
        }
    }

    private LegacyWorkbookFacts legacyWorkbookFacts(byte[] bytes) {
        try (var workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes))) {
            return workbookFacts(workbook);
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "XLS 文件损坏或无法读取");
        }
    }

    private LegacyWorkbookFacts workbookFacts(Workbook workbook) {
        var sheetNames = new ArrayList<String>();
        int nonEmptyCells = 0;
        int styledCells = 0;
        int borderedCells = 0;
        int mergedRegions = 0;
        int formulaCount = 0;
        int customRowDimensions = 0;
        int customColumnDimensions = 0;
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            var sheet = workbook.getSheetAt(sheetIndex);
            sheetNames.add(sheet.getSheetName());
            mergedRegions += sheet.getNumMergedRegions();
            for (Row row : sheet) {
                if (row.getZeroHeight() || row.getHeight() != sheet.getDefaultRowHeight()) customRowDimensions++;
                for (Cell cell : row) {
                    if (hasCellValue(cell)) nonEmptyCells++;
                    if (cell.getCellType() == CellType.FORMULA) formulaCount++;
                    if (cell.getCellStyle() != null && cell.getCellStyle().getIndex() != 0) styledCells++;
                    if (hasBorder(cell.getCellStyle())) borderedCells++;
                }
            }
            // BIFF8 workbooks have 256 columns. Inspect the complete legacy
            // grid so a width/hidden flag on an otherwise empty layout column
            // is still part of the conversion-fidelity check.
            for (int column = 0; column < 256; column++) {
                if (sheet.isColumnHidden(column)
                        || sheet.getColumnWidth(column) != sheet.getDefaultColumnWidth() * 256) {
                    customColumnDimensions++;
                }
            }
        }
        return new LegacyWorkbookFacts(sheetNames, nonEmptyCells, styledCells, borderedCells,
                mergedRegions, formulaCount, customRowDimensions, customColumnDimensions);
    }

    private void validateXlsConversion(LegacyWorkbookFacts source, byte[] converted) {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(converted))) {
            if (workbook.getNumberOfSheets() != source.sheetNames().size()) {
                throw invalidXlsConversion("工作表数量变化");
            }
            for (int index = 0; index < source.sheetNames().size(); index++) {
                if (!source.sheetNames().get(index).equals(workbook.getSheetName(index))) {
                    throw invalidXlsConversion("工作表名称或顺序变化");
                }
            }
            var target = workbookFacts(workbook);
            if (source.nonEmptyCells() > 0
                    && target.nonEmptyCells() < minimumRetained(source.nonEmptyCells(), 0.98d)) {
                throw invalidXlsConversion("转换后非空单元格丢失");
            }
            if (source.mergedRegions() > 0
                    && target.mergedRegions() < minimumRetained(source.mergedRegions(), 0.90d)) {
                throw invalidXlsConversion("转换后合并关系丢失");
            }
            if (source.styledCells() > 0
                    && target.styledCells() < minimumRetained(source.styledCells(), 0.90d)) {
                throw invalidXlsConversion("转换后单元格样式丢失");
            }
            if (source.borderedCells() > 0
                    && target.borderedCells() < minimumRetained(source.borderedCells(), 0.90d)) {
                throw invalidXlsConversion("转换后边框结构丢失");
            }
            if (source.formulaCount() > 0 && target.formulaCount() < source.formulaCount()) {
                throw invalidXlsConversion("转换后公式丢失");
            }
            if (source.customRowDimensions() > 0 && target.customRowDimensions() == 0) {
                throw invalidXlsConversion("转换后行高或隐藏行设置丢失");
            }
            if (source.customColumnDimensions() > 0 && target.customColumnDimensions() == 0) {
                throw invalidXlsConversion("转换后列宽或隐藏列设置丢失");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidXlsConversion("转换结果不是可解析的 XLSX 文件");
        }
    }

    private int minimumRetained(int sourceCount, double ratio) {
        return Math.max(1, (int) Math.ceil(sourceCount * ratio));
    }

    private boolean hasCellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> !cell.getStringCellValue().isBlank();
            case NUMERIC, BOOLEAN, FORMULA, ERROR -> true;
            case BLANK, _NONE -> false;
        };
    }

    private boolean hasBorder(org.apache.poi.ss.usermodel.CellStyle style) {
        return style != null && (style.getBorderTop() != BorderStyle.NONE
                || style.getBorderRight() != BorderStyle.NONE
                || style.getBorderBottom() != BorderStyle.NONE
                || style.getBorderLeft() != BorderStyle.NONE);
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
        LegacyDocFacts sourceFacts = legacyDocFacts(bytes);
        Path workDirectory = null;
        Process process = null;
        try {
            workDirectory = Files.createTempDirectory("jsd-aird-doc-convert-").toAbsolutePath().normalize();
            Path sourceFile = workDirectory.resolve("source.doc");
            Path outputFile = workDirectory.resolve("source.docx");
            Path profileDirectory = workDirectory.resolve("libreoffice-profile");
            Files.write(sourceFile, bytes);
            Files.createDirectories(profileDirectory);

            process = new ProcessBuilder(
                    libreOfficeExecutable,
                    "--headless",
                    "--nologo",
                    "--nodefault",
                    "--nofirststartwizard",
                    "-env:UserInstallation=" + profileDirectory.toUri(),
                    "--convert-to",
                    "docx",
                    "--outdir",
                    workDirectory.toString(),
                    sourceFile.toString()
            ).redirectErrorStream(true).start();

            boolean completed = process.waitFor(Math.max(1, libreOfficeTimeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new ApiException(ApiErrorCode.FILE_NOT_READY,
                        "DOC 转换超时（" + libreOfficeTimeout.toSeconds() + " 秒），请检查 LibreOffice 服务");
            }
            String converterOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0 || !Files.isRegularFile(outputFile)) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST,
                        "LibreOffice 无法转换 DOC 文件" + (converterOutput.isBlank() ? "" : "：" + converterOutput));
            }

            byte[] normalized = Files.readAllBytes(outputFile);
            validateMagic("DOCX", normalized);
            validateDocConversion(sourceFacts, normalized);
            return new Result(name, replaceExtension(name, "docx"), DOCX_MIME,
                    "DOC", "DOCX", normalized, "NORMALIZED", "已通过 LibreOffice 转换为标准 DOCX 工作区");
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY,
                    "LibreOffice 转换器不可用，请配置 app.template-normalization.libreoffice.executable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "DOC 转换任务已中断，请稍后重试");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteTemporaryDirectory(workDirectory);
        }
    }

    private LegacyDocFacts legacyDocFacts(byte[] bytes) {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = normalizeDocumentText(document.getRange().text());
            int tableCount = 0;
            int maxTableLevel = 0;
            int nonEmptyCells = 0;
            int mergedCells = 0;
            TableIterator tables = new TableIterator(document.getRange());
            while (tables.hasNext()) {
                Table table = tables.next();
                tableCount++;
                maxTableLevel = Math.max(maxTableLevel, table.getTableLevel());
                for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                    TableRow row = table.getRow(rowIndex);
                    for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                        TableCell cell = row.getCell(cellIndex);
                        if (!normalizeDocumentText(cell.text()).isBlank()) nonEmptyCells++;
                        if (cell.isMerged() || cell.isVerticallyMerged()) mergedCells++;
                    }
                }
            }
            return new LegacyDocFacts(text, tableCount, maxTableLevel, nonEmptyCells, mergedCells,
                    countCharacter(text, '?'));
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "DOC 文件损坏或无法读取");
        }
    }

    private void validateDocConversion(LegacyDocFacts source, byte[] converted) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(converted))) {
            var target = docxFacts(document);
            if (target.text().isBlank() || characterCoverage(source.text(), target.text()) < 0.98d) {
                throw invalidDocConversion("转换后正文覆盖不足");
            }
            if (target.text().length() > source.text().length() * 1.20d + 100) {
                throw invalidDocConversion("转换后正文异常重复");
            }
            if (source.tableCount() > 0 && target.tableCount() < source.tableCount()) {
                throw invalidDocConversion("转换后表格数量减少");
            }
            if (source.maxTableLevel() > 0 && target.maxTableDepth() < source.maxTableLevel()) {
                throw invalidDocConversion("转换后表格层级丢失");
            }
            int minimumCells = (int) Math.floor(source.nonEmptyCells() * 0.90d);
            if (source.nonEmptyCells() > 0 && target.nonEmptyCells() < minimumCells) {
                throw invalidDocConversion("转换后非空单元格丢失");
            }
            if (source.mergedCells() > 0 && target.mergedCells() == 0) {
                throw invalidDocConversion("转换后合并单元格关系丢失");
            }
            if (target.questionMarks() > source.questionMarks() + 2) {
                throw invalidDocConversion("转换后出现异常问号，可能包含未清理的 Word 控制字符");
            }
            if (source.tableCount() > 0 && target.firstParagraphLength() > source.text().length() * 0.60d) {
                throw invalidDocConversion("整篇正文被错误复制到首段");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidDocConversion("转换结果不是可解析的 DOCX 文件");
        }
    }

    private DocxFacts docxFacts(XWPFDocument document) {
        StringBuilder text = new StringBuilder();
        int firstParagraphLength = 0;
        for (IBodyElement element : document.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                String value = normalizeDocumentText(paragraph.getText());
                if (firstParagraphLength == 0 && !value.isBlank()) firstParagraphLength = value.length();
                text.append(value);
            } else if (element instanceof XWPFTable table) {
                appendTableText(table, text);
            }
        }
        var counters = new int[4];
        for (XWPFTable table : document.getTables()) collectTableFacts(table, 1, counters);
        String normalized = normalizeDocumentText(text.toString());
        return new DocxFacts(normalized, counters[0], counters[1], counters[2], counters[3],
                countCharacter(normalized, '?'), firstParagraphLength);
    }

    private void appendTableText(XWPFTable table, StringBuilder text) {
        for (var row : table.getRows()) {
            for (var cell : row.getTableCells()) {
                for (IBodyElement element : cell.getBodyElements()) {
                    if (element instanceof XWPFParagraph paragraph) text.append(paragraph.getText());
                    else if (element instanceof XWPFTable nested) appendTableText(nested, text);
                }
            }
        }
    }

    private void collectTableFacts(XWPFTable table, int depth, int[] counters) {
        counters[0]++;
        counters[1] = Math.max(counters[1], depth);
        for (var row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                if (!normalizeDocumentText(cell.getText()).isBlank()) counters[2]++;
                var properties = cell.getCTTc().getTcPr();
                if (properties != null && (properties.isSetGridSpan() || properties.isSetVMerge())) counters[3]++;
                for (XWPFTable nested : cell.getTables()) collectTableFacts(nested, depth + 1, counters);
            }
        }
    }

    private double characterCoverage(String source, String target) {
        if (source.isBlank()) return target.isBlank() ? 1d : 0d;
        Map<Integer, Integer> available = new HashMap<>();
        target.codePoints().forEach(value -> available.merge(value, 1, Integer::sum));
        int matched = 0;
        for (int value : source.codePoints().toArray()) {
            int count = available.getOrDefault(value, 0);
            if (count > 0) {
                matched++;
                available.put(value, count - 1);
            }
        }
        return matched / (double) source.codePointCount(0, source.length());
    }

    private String normalizeDocumentText(String value) {
        if (value == null) return "";
        return value.replace("\u0007", "")
                .replace("\u000B", "")
                .replace("\uFFFD", "")
                .replaceAll("\\s+", "")
                .strip();
    }

    private int countCharacter(String value, char character) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) if (value.charAt(index) == character) count++;
        return count;
    }

    private ApiException invalidDocConversion(String reason) {
        return new ApiException(ApiErrorCode.BAD_REQUEST, "DOC 高保真转换校验失败：" + reason);
    }

    private ApiException invalidXlsConversion(String reason) {
        return new ApiException(ApiErrorCode.BAD_REQUEST, "XLS 高保真转换校验失败：" + reason);
    }

    private void deleteTemporaryDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup of the unique conversion directory.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of the unique conversion directory.
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

    private record LegacyWorkbookFacts(
            List<String> sheetNames,
            int nonEmptyCells,
            int styledCells,
            int borderedCells,
            int mergedRegions,
            int formulaCount,
            int customRowDimensions,
            int customColumnDimensions
    ) { }

    private record LegacyDocFacts(String text, int tableCount, int maxTableLevel, int nonEmptyCells,
                                  int mergedCells, int questionMarks) { }

    private record DocxFacts(String text, int tableCount, int maxTableDepth, int nonEmptyCells,
                             int mergedCells, int questionMarks,
                             int firstParagraphLength) { }
}
