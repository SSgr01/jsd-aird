package com.jsd.aird.kb.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.domain.DocumentParser;
import org.springframework.stereotype.Component;

/** Deterministic first-phase field extraction. Ordinary documents never leave the system for LLM extraction. */
@Component
public class KnowledgeFieldExtractor {

    private static final List<FieldRule> RULES = List.of(
            new FieldRule("PRODUCT", "产品/型号", Pattern.compile("(?im)(?:产品(?:名称)?|品名|型号|product(?: name)?)\\s*[:：]\\s*([^\\r\\n,，;；]{1,100})"), null),
            new FieldRule("BATCH", "批次/批号", Pattern.compile("(?im)(?:批次|批号|lot(?: no)?|batch(?: no)?)\\s*[:：#]?\\s*([A-Za-z0-9_./-]{2,80})"), null),
            new FieldRule("REPORT_NO", "报告编号", Pattern.compile("(?im)(?:报告编号|报告号|report\\s*(?:no|number))\\s*[:：#]?\\s*([A-Za-z0-9_./-]{2,80})"), null),
            new FieldRule("PROJECT", "项目", Pattern.compile("(?im)(?:项目(?:名称|编号)?|project)\\s*[:：#]?\\s*([^\\r\\n,，;；]{1,100})"), null),
            new FieldRule("EXPERIMENT", "实验编号", Pattern.compile("(?im)(?:实验编号|试验编号|experiment\\s*(?:no|id)?)\\s*[:：#]?\\s*([A-Za-z0-9_./-]{2,80})"), null),
            new FieldRule("FORMULA", "配方编号", Pattern.compile("(?im)(?:配方编号|配方号|formula\\s*(?:no|id)?)\\s*[:：#]?\\s*([A-Za-z0-9_./-]{2,80})"), null),
            new FieldRule("DATE", "日期", Pattern.compile("(?im)(?:日期|检测日期|实验日期|date)\\s*[:：]?\\s*((?:19|20)\\d{2}[年/.-]\\d{1,2}[月/.-]\\d{1,2}日?)"), "date"),
            new FieldRule("VISCOSITY", "粘度", Pattern.compile("(?im)(?:粘度|黏度|viscosity)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(mPa[·.]?s|cP|cps)?"), "viscosity"),
            new FieldRule("SOLIDS", "固含量", Pattern.compile("(?im)(?:固含量|固体份|solids?)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(%)?"), "percent")
    );

    private final ObjectMapper objectMapper;

    public KnowledgeFieldExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeGovernanceRepository.ExtractedFieldWrite> extract(
            String documentType, List<DocumentParser.TextBlock> blocks) {
        var text = (blocks == null ? List.<DocumentParser.TextBlock>of() : blocks).stream()
                .map(DocumentParser.TextBlock::content).filter(value -> value != null && !value.isBlank())
                .reduce("", (left, right) -> left + "\n" + right);
        var matches = new LinkedHashMap<String, List<Value>>();
        for (var rule : RULES) {
            var matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                var raw = matcher.group(1).trim();
                var unit = matcher.groupCount() > 1 && matcher.group(2) != null ? matcher.group(2).trim() : null;
                matches.computeIfAbsent(rule.code(), ignored -> new ArrayList<>()).add(new Value(raw, unit));
            }
        }
        var result = new ArrayList<KnowledgeGovernanceRepository.ExtractedFieldWrite>();
        for (var rule : RULES) {
            var values = matches.getOrDefault(rule.code(), List.of()).stream().distinct().toList();
            if (values.isEmpty() && !required(documentType, rule.code())) continue;
            var first = values.isEmpty() ? new Value(null, null) : values.getFirst();
            var candidates = objectMapper.valueToTree(values.stream().map(Value::raw).toList());
            result.add(new KnowledgeGovernanceRepository.ExtractedFieldWrite(
                    rule.code(), rule.name(), first.raw(), normalize(first.raw(), rule.normalizer()), first.unit(),
                    standardUnit(rule.normalizer()), values.isEmpty() ? 0.0 : values.size() == 1 ? 0.92 : 0.65,
                    required(documentType, rule.code()), values.size() > 1, candidates));
        }
        return result;
    }

    private boolean required(String documentType, String code) {
        var type = documentType == null ? "" : documentType.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "COA", "CERTIFICATE_OF_ANALYSIS" -> code.equals("PRODUCT") || code.equals("BATCH");
            case "PRODUCT_INFO", "TDS", "SDS" -> code.equals("PRODUCT");
            case "EXPERIMENT_REPORT" -> code.equals("EXPERIMENT") || code.equals("REPORT_NO");
            case "FORMULA", "FORMULA_INFO" -> code.equals("FORMULA");
            default -> false;
        };
    }

    private String normalize(String value, String normalizer) {
        if (value == null) return null;
        if ("date".equals(normalizer)) {
            var cleaned = value.replace('年', '-').replace('月', '-').replace("日", "")
                    .replace('/', '-').replace('.', '-');
            for (var pattern : List.of("yyyy-M-d", "yyyy-MM-dd")) {
                try { return LocalDate.parse(cleaned, DateTimeFormatter.ofPattern(pattern)).toString(); }
                catch (DateTimeParseException ignored) { }
            }
        }
        return value.trim();
    }

    private String standardUnit(String normalizer) {
        return switch (normalizer == null ? "" : normalizer) {
            case "viscosity" -> "mPa·s";
            case "percent" -> "%";
            default -> null;
        };
    }

    private record FieldRule(String code, String name, Pattern pattern, String normalizer) { }
    private record Value(String raw, String unit) { }
}
