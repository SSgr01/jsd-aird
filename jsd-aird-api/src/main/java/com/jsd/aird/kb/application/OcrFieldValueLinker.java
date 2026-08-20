package com.jsd.aird.kb.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.jsd.aird.kb.domain.DocumentParser;

/**
 * Keeps adjacent OCR form labels and scalar values searchable as one evidence
 * unit while retaining the original OCR blocks for provenance.
 */
final class OcrFieldValueLinker {

    private static final Pattern PUNCTUATED_TEXT = Pattern.compile(".*[，。；：:,.!?！？()（）\\[\\]{}-].*");
    private static final Pattern VALUE_SENTENCE_PUNCTUATION = Pattern.compile(".*[，。；：:!?！？()（）\\[\\]{}].*");
    private static final Pattern NUMBER_OR_UNIT = Pattern.compile(
            "^[+-]?(?:\\d+(?:[.,]\\d+)?|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})(?:\\s*[\\p{L}\\p{IsHan}%°℃/._-]+)?$");
    private static final Pattern SHORT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_./-]{1,48}$");

    private OcrFieldValueLinker() { }

    static List<DocumentParser.TextBlock> link(List<DocumentParser.TextBlock> blocks) {
        if (blocks == null || blocks.size() < 2) return blocks == null ? List.of() : List.copyOf(blocks);
        var result = new ArrayList<DocumentParser.TextBlock>(blocks.size() + 8);
        for (int index = 0; index < blocks.size(); index++) {
            var field = blocks.get(index);
            result.add(field);
            if (index + 1 >= blocks.size()) continue;
            var value = blocks.get(index + 1);
            if (!Objects.equals(field.pageNo(), value.pageNo()) || field.pageNo() == null
                    || !isFieldLabel(field.content()) || !isFieldValue(value.content())) continue;
            var fieldLabel = field.content().strip();
            var fieldValue = value.content().strip();
            result.add(value);
            result.add(new DocumentParser.TextBlock(field.pageNo(), "ocr-field-value",
                    fieldLabel + " = " + fieldValue, field.sheetName(), field.cellRange(),
                    field.paragraphId(), field.bbox(), field.startTimeMs(), field.endTimeMs(),
                    field.confidence(), Map.of("fieldLabel", fieldLabel, "fieldValue", fieldValue,
                            "relation", "ADJACENT_OCR_FIELD_VALUE")));
            index++;
        }
        return List.copyOf(result);
    }

    private static boolean isFieldLabel(String value) {
        if (value == null) return false;
        var text = value.strip();
        if (text.isBlank() || text.length() > 24 || text.chars().anyMatch(Character::isWhitespace)
                || PUNCTUATED_TEXT.matcher(text).matches()) return false;
        if (text.matches("[\\p{N}\\p{Punct}\\s]+")) return false;
        return text.codePoints().anyMatch(codePoint -> Character.isLetter(codePoint)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF));
    }

    private static boolean isFieldValue(String value) {
        if (value == null) return false;
        var text = value.strip();
        if (text.isBlank() || text.length() > 80 || text.contains("\n")
                || VALUE_SENTENCE_PUNCTUATION.matcher(text).matches()) return false;
        var normalized = text.toLowerCase(Locale.ROOT);
        // Keep the automatic relation conservative: a short Chinese string
        // may itself be the next form label (for example “备注” followed by
        // “适用温度”), while numeric/unit and code values are unambiguous.
        return NUMBER_OR_UNIT.matcher(normalized).matches() || SHORT_CODE.matcher(text).matches();
    }
}
