package com.jsd.aird.rnd.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.api.TemplateFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * 报价记录文件解析：文件头部为“标签-值”形式的客户信息，随后是“产品/单价/规格/报价日期/客户编码/备注”
 * 明细表，表尾的备注行归入记录备注。解析器只负责结构归一，落库由 {@link QuotationImportService} 完成。
 */
public final class QuotationWorkbookParser {

    private static final int MAX_ITEMS = 1000;
    private static final int MAX_HEADER_SCAN_ROWS = 60;
    private static final Pattern DATE_PATTERN = Pattern.compile("^(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?.*$");
    private static final Set<String> FOOTER_LABELS = Set.of("备注", "说明", "合计", "总计", "小计");

    private static final Map<String, String> CUSTOMER_LABELS = customerLabels();

    public ParsedQuotation parse(TemplateFormat format, String fileName, byte[] bytes) {
        if (format == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "报价文件格式不支持");
        if (format == TemplateFormat.CSV) return parseCsv(fileName, bytes);
        if (!Set.of(TemplateFormat.XLSX, TemplateFormat.XLS).contains(format)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "报价记录仅支持 XLSX / XLS / CSV 文件");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet target = null;
            List<List<String>> targetRows = null;
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                var rows = readRows(workbook.getSheetAt(index));
                if (rows.isEmpty()) continue;
                if (targetRows == null) {
                    target = workbook.getSheetAt(index);
                    targetRows = rows;
                }
                if (findHeader(rows) >= 0) {
                    target = workbook.getSheetAt(index);
                    targetRows = rows;
                    break;
                }
            }
            if (target == null || targetRows == null || targetRows.isEmpty()) {
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "报价文件为空");
            }
            return parseRows(targetRows, fileName);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "报价文件无法解析：" + message(exception));
        }
    }

    private ParsedQuotation parseCsv(String fileName, byte[] bytes) {
        var text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var rows = new ArrayList<List<String>>();
        for (var line : text.split("\\r?\\n")) {
            if (line.isBlank()) {
                rows.add(List.of());
                continue;
            }
            rows.add(splitCsvLine(line));
        }
        return parseRows(rows, fileName);
    }

    private ParsedQuotation parseRows(List<List<String>> rows, String fileName) {
        var headerIndex = findHeader(rows);
        if (headerIndex < 0) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                    "未找到“产品”与“单价/报价日期”表头，请按报价记录模板整理后重新上传");
        }
        var columns = headerColumns(rows.get(headerIndex));
        var customer = readCustomer(rows.subList(0, headerIndex));
        var contacts = readContacts(rows.subList(0, headerIndex));
        var fallbackName = fileName == null || fileName.isBlank()
                ? "未命名客户" : fileName.replaceFirst("(?i)\\.[^.]+$", "");
        var customerName = customer.customerName() == null || customer.customerName().isBlank()
                ? fallbackName : customer.customerName();
        var items = new ArrayList<Item>();
        var footers = new ArrayList<String>();
        for (int index = headerIndex + 1; index < rows.size(); index++) {
            var row = rows.get(index);
            var rowNo = index + 1;
            if (row.stream().allMatch(value -> value == null || value.isBlank())) continue;
            var product = value(row, columns.get("product"));
            if (product.isBlank() || FOOTER_LABELS.contains(normalize(product))) {
                var footer = footerText(row);
                if (!footer.isBlank()) footers.add(footer);
                continue;
            }
            var rawPrice = blankToNull(value(row, columns.get("price")));
            var rawSpec = blankToNull(value(row, columns.get("spec")));
            items.add(new Item(items.size() + 1, rowNo, product,
                    rawPrice, rawSpec, priceOptions(rawPrice, rawSpec),
                    parseDate(value(row, columns.get("date")), rowNo),
                    blankToNull(value(row, columns.get("code"))),
                    blankToNull(value(row, columns.get("note")))));
            if (items.size() > MAX_ITEMS) {
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "一次最多导入 " + MAX_ITEMS + " 条报价明细");
            }
        }
        if (items.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "导入文件没有报价明细");
        var note = appendNote(customer.note(), footers);
        return new ParsedQuotation(new Customer(customerName, customer.relatedCompany(),
                customer.contactName(), customer.contactTitle(), customer.phone(), customer.fax(),
                customer.email(), customer.addressLine1(), customer.addressLine2(),
                customer.website(), note), List.copyOf(contacts), List.copyOf(items));
    }

    private List<Contact> readContacts(List<List<String>> rows) {
        var columns = new LinkedHashMap<Integer, String[]>();
        for (var row : rows) {
            if (row.isEmpty()) continue;
            var label = CUSTOMER_LABELS.get(normalize(row.getFirst()));
            int field = switch (label == null ? "" : label) {
                case "contactName" -> 0;
                case "contactTitle" -> 1;
                case "phone" -> 2;
                default -> -1;
            };
            if (field < 0) continue;
            for (int column = 1; column < row.size(); column++) {
                if (!row.get(column).isBlank()) {
                    columns.computeIfAbsent(column, ignored -> new String[3])[field] = row.get(column).trim();
                }
            }
        }
        var contacts = new ArrayList<Contact>();
        columns.forEach((column, values) -> contacts.add(new Contact(
                contacts.size() + 1, column + 1, values[0], values[1], values[2])));
        return contacts;
    }

    private List<PriceOption> priceOptions(String rawPrice, String rawSpec) {
        if (rawPrice == null) return List.of();
        var prices = rawPrice.split("/", -1);
        var specs = rawSpec == null ? new String[0] : rawSpec.split("/", -1);
        if (specs.length != 0 && specs.length != prices.length) return List.of();
        var options = new ArrayList<PriceOption>();
        try {
            for (int index = 0; index < prices.length; index++) {
                var price = new BigDecimal(prices[index].trim());
                if (price.signum() < 0) return List.of();
                options.add(new PriceOption(index + 1, price,
                        specs.length == 0 ? null : blankToNull(specs[index])));
            }
        } catch (NumberFormatException exception) {
            return List.of();
        }
        return List.copyOf(options);
    }

    private Customer readCustomer(List<List<String>> rows) {
        var collected = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            for (int column = 0; column < row.size(); column++) {
                var label = CUSTOMER_LABELS.get(normalize(row.get(column)));
                if (label == null) continue;
                for (int next = column + 1; next < row.size(); next++) {
                    var candidate = row.get(next);
                    if (candidate == null || candidate.isBlank()) break;
                    collected.computeIfAbsent(label, key -> new ArrayList<>()).add(candidate.trim());
                }
            }
        }
        return new Customer(
                first(collected, "customerName"), first(collected, "relatedCompany"),
                all(collected, "contactName"), all(collected, "contactTitle"),
                all(collected, "phone"), first(collected, "fax"),
                first(collected, "email"), first(collected, "addressLine1"),
                first(collected, "addressLine2"), first(collected, "website"),
                first(collected, "note"));
    }

    private int findHeader(List<List<String>> rows) {
        var limit = Math.min(rows.size(), MAX_HEADER_SCAN_ROWS);
        for (int index = 0; index < limit; index++) {
            var columns = headerColumns(rows.get(index));
            if (columns.containsKey("product")
                    && (columns.containsKey("price") || columns.containsKey("date"))) return index;
        }
        return -1;
    }

    private Map<String, Integer> headerColumns(List<String> row) {
        var columns = new HashMap<String, Integer>();
        if (row == null) return columns;
        for (int index = 0; index < row.size(); index++) {
            var label = normalize(row.get(index));
            if (label.isEmpty()) continue;
            var field = switch (label) {
                case "产品", "品名", "产品名称", "物料名称" -> "product";
                case "单价", "价格", "报价" -> "price";
                case "规格", "规格型号", "包装规格" -> "spec";
                case "报价日期", "日期", "报价时间" -> "date";
                case "客户编码", "客户代码", "编码", "物料编码" -> "code";
                case "备注", "说明" -> "note";
                default -> null;
            };
            if (field != null) columns.putIfAbsent(field, index);
        }
        return columns;
    }

    private List<List<String>> readRows(Sheet sheet) {
        var rows = new ArrayList<List<String>>();
        if (sheet == null) return rows;
        var lastRow = sheet.getLastRowNum();
        for (int index = 0; index <= lastRow; index++) {
            Row row = sheet.getRow(index);
            if (row == null) {
                rows.add(List.of());
                continue;
            }
            var values = new ArrayList<String>();
            var lastCell = Math.max(row.getLastCellNum(), 0);
            for (int column = 0; column < lastCell; column++) {
                values.add(cellText(row.getCell(column)));
            }
            rows.add(values);
        }
        return rows;
    }

    private String cellText(Cell cell) {
        if (cell == null) return "";
        var type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue() == null ? "" : cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    var value = cell.getLocalDateTimeCellValue();
                    yield value == null ? "" : value.toLocalDate().toString();
                }
                yield numberText(BigDecimal.valueOf(cell.getNumericCellValue()));
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private String numberText(BigDecimal value) {
        var stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
    }

    private LocalDate parseDate(String raw, int rowNo) {
        if (raw == null || raw.isBlank()) return null;
        var text = raw.trim();
        var matcher = DATE_PATTERN.matcher(text);
        if (matcher.matches()) {
            try {
                return LocalDate.of(Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            } catch (RuntimeException ignored) {
                // fall through to the shared error below
            }
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException ignored) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                    "第 " + rowNo + " 行：报价日期无法识别（示例 2026.09.30）");
        }
    }

    private String value(List<String> row, Integer column) {
        if (column == null || row == null || column < 0 || column >= row.size()) return "";
        var value = row.get(column);
        return value == null ? "" : value.trim();
    }

    /** 表尾备注行：去掉“备注/说明”等标签，只保留正文。 */
    private String footerText(List<String> row) {
        var parts = new ArrayList<String>();
        for (var value : row) {
            if (value == null || value.isBlank()) continue;
            if (FOOTER_LABELS.contains(normalize(value))) continue;
            parts.add(value.trim());
        }
        return String.join(" ", parts);
    }

    private String appendNote(String note, List<String> footers) {
        var parts = new ArrayList<String>();
        if (note != null && !note.isBlank()) parts.add(note.trim());
        for (var footer : footers) {
            var text = footer.trim();
            if (text.isEmpty() || parts.contains(text)) continue;
            parts.add(text);
        }
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    private String first(Map<String, List<String>> values, String key) {
        var items = values.get(key);
        return items == null || items.isEmpty() ? null : items.getFirst();
    }

    private String all(Map<String, List<String>> values, String key) {
        var items = values.get(key);
        return items == null || items.isEmpty() ? null : String.join("、", items);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\s\\u00A0:：()（）]", "").toLowerCase(Locale.ROOT);
    }

    private List<String> splitCsvLine(String line) {
        var values = new ArrayList<String>();
        var builder = new StringBuilder();
        var quoted = false;
        for (int index = 0; index < line.length(); index++) {
            var current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    builder.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                values.add(builder.toString().trim());
                builder.setLength(0);
            } else {
                builder.append(current);
            }
        }
        values.add(builder.toString().trim());
        return values;
    }

    private String message(Exception exception) {
        var text = exception.getMessage();
        return text == null || text.isBlank() ? "文件内容无法读取" : text;
    }

    private static Map<String, String> customerLabels() {
        var labels = new HashMap<String, String>();
        put(labels, "customerName", "公司名称", "客户名称", "客户", "公司", "客户名", "单位名称");
        put(labels, "relatedCompany", "关联公司", "关联企业", "关联单位");
        put(labels, "contactName", "联系人", "联系人姓名", "对接人");
        put(labels, "contactTitle", "职称", "职务", "职位");
        put(labels, "phone", "电话", "联系电话", "手机", "手机号", "电话号码");
        put(labels, "fax", "传真", "传真号");
        put(labels, "email", "邮箱", "电子邮件", "邮箱地址", "email", "e-mail");
        put(labels, "addressLine1", "地址1", "地址一", "地址", "地址（一）");
        put(labels, "addressLine2", "地址2", "地址二", "地址（二）");
        put(labels, "website", "网站", "网址", "公司网站");
        put(labels, "note", "备注", "说明");
        return Map.copyOf(labels);
    }

    private static void put(Map<String, String> target, String field, String... labels) {
        for (var label : labels) {
            target.put(label.replaceAll("[\\s\\u00A0:：()（）]", "").toLowerCase(Locale.ROOT), field);
        }
    }

    public record ParsedQuotation(Customer customer, List<Contact> contacts, List<Item> items) { }
    public record Contact(int position, int sourceColumn, String name, String title, String phone) { }
    public record PriceOption(int position, BigDecimal unitPrice, String specification) { }

    public record Customer(String customerName, String relatedCompany, String contactName,
                           String contactTitle, String phone, String fax, String email,
                           String addressLine1, String addressLine2, String website, String note) { }

    public record Item(int lineNo, int sourceRowNo, String productName, String unitPrice,
                       String specification, List<PriceOption> priceOptions, LocalDate quoteDate,
                       String customerCode, String note) { }
}
