package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.StandardFact;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class UvpuProcessFactParser {
    public static final String RULE_VERSION = "uvpu-process-parser.v1";
    private static final String NUMBER = "(-?\\d+(?:\\.\\d+)?)";
    private static final Pattern RANGE = Pattern.compile(NUMBER + "\\s*[-~～—–至]\\s*" + NUMBER);
    private static final Pattern UVA_INTENSITY = Pattern.compile("UVA\\s*(?:光强|强度)?\\s*[:：]?\\s*" + NUMBER
            + "\\s*M?W\\s*/?\\s*CM(?:2|²)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UV_ENERGY = Pattern.compile("(?:UVA?|UV)\\s*(?:能量)?\\s*[:：]?\\s*" + NUMBER
            + "\\s*M?J\\s*/?\\s*CM(?:2|²)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MICRON = Pattern.compile(NUMBER + "\\s*(?:ΜM|UM|μM|微米)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELSIUS = Pattern.compile(NUMBER + "\\s*(?:°?C|℃)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RH = Pattern.compile(NUMBER + "\\s*%?\\s*RH", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT = Pattern.compile(NUMBER + "\\s*%");

    private final ObjectMapper json;

    public UvpuProcessFactParser(ObjectMapper json) {
        this.json = json;
    }

    public Projection parse(List<JsonNode> processFacts, List<JsonNode> testFacts) {
        var process = new LinkedHashMap<String, StandardFact>();
        var context = new LinkedHashMap<String, StandardFact>();
        for (var fact : processFacts) parseProcessFact(fact, process, context);
        for (var fact : testFacts) parseExplicitFilmThickness(fact, process);
        return new Projection(Map.copyOf(process), Map.copyOf(context));
    }

    private void parseProcessFact(JsonNode fact, Map<String, StandardFact> process, Map<String, StandardFact> context) {
        var label = text(fact, "operation", text(fact, "itemLabel", ""));
        var field = text(fact, "field", "");
        var raw = raw(fact);
        var normalizedLabel = UvpuAnalysisProfile.normalize(label);

        if ("TEMPERATURE".equals(field) || normalizedLabel.contains("温度")) {
            number(CELSIUS, raw).ifPresent(value -> context.putIfAbsent("temperatureC",
                    numeric("temperatureC", value, "degC", fact, raw, "TEMPERATURE_C_V1", "PARSED", "DIRECT_VALUE")));
        }
        if (normalizedLabel.contains("湿度") || raw.toUpperCase(Locale.ROOT).contains("RH")) {
            number(RH, raw).ifPresent(value -> context.putIfAbsent("humidityRhPct",
                    numeric("humidityRhPct", value, "%", fact, raw, "HUMIDITY_RH_V1", "PARSED", "DIRECT_VALUE")));
        }
        if (normalizedLabel.contains("涂料固含")) {
            process.putIfAbsent("coatingSolidsPct", percentage(fact, raw));
        }
        if (raw.contains("绕丝棒") || raw.contains("辊涂")) {
            context.putIfAbsent("applicationMethod", textFact("applicationMethod", "WIRE_BAR_ROLL_COATING",
                    fact, raw, "APPLICATION_METHOD_V1"));
            number(MICRON, raw).ifPresent(value -> process.putIfAbsent("applicatorSpecUm",
                    numeric("applicatorSpecUm", value, "um", fact, raw, "WIRE_BAR_SPEC_V1", "PARSED", "DIRECT_VALUE")));
        }
        if (raw.toUpperCase(Locale.ROOT).contains("汞灯") && raw.toUpperCase(Locale.ROOT).contains("UV")) {
            context.putIfAbsent("curingSource", textFact("curingSource", "MERCURY_UV",
                    fact, raw, "CURING_SOURCE_V1"));
        }
        number(UVA_INTENSITY, raw).ifPresent(value -> process.putIfAbsent("uvaIntensityMwCm2",
                numeric("uvaIntensityMwCm2", value, "mW/cm2", fact, raw, "UVA_INTENSITY_V1", "PARSED", "DIRECT_VALUE")));
        number(UV_ENERGY, raw).ifPresent(value -> process.putIfAbsent("uvEnergyMjCm2",
                numeric("uvEnergyMjCm2", value, "mJ/cm2", fact, raw, "UV_ENERGY_V1", "PARSED", "DIRECT_VALUE")));
    }

    private void parseExplicitFilmThickness(JsonNode fact, Map<String, StandardFact> process) {
        var label = text(fact, "testItem", text(fact, "itemLabel", ""));
        if (!UvpuAnalysisProfile.normalize(label).contains("膜厚")) return;
        var raw = raw(fact);
        var matcher = RANGE.matcher(raw.toUpperCase(Locale.ROOT));
        if (matcher.find()) {
            var minimum = decimal(matcher.group(1));
            var maximum = decimal(matcher.group(2));
            var midpoint = minimum.add(maximum).divide(BigDecimal.valueOf(2));
            process.put("filmThicknessUm", new StandardFact("filmThicknessUm", midpoint, null, minimum, maximum,
                    "um", raw, itemId(fact), refs(fact), RULE_VERSION + ":FILM_THICKNESS_RANGE_V1", "PARSED",
                    "RANGE_MIDPOINT_V1"));
            return;
        }
        number(MICRON, raw + (label.toLowerCase(Locale.ROOT).contains("μm") ? " μm" : ""))
                .or(() -> plainNumber(raw))
                .ifPresent(value -> process.put("filmThicknessUm",
                        numeric("filmThicknessUm", value, "um", fact, raw, "FILM_THICKNESS_V1", "PARSED", "DIRECT_VALUE")));
    }

    private StandardFact percentage(JsonNode fact, String raw) {
        if (missingText(raw)) {
            return new StandardFact("coatingSolidsPct", null, null, null, null, "%", raw, itemId(fact), refs(fact),
                    RULE_VERSION + ":PERCENT_V1", "MISSING", "SOURCE_VALUE_MISSING");
        }
        var textual = number(PERCENT, raw);
        if (textual.isPresent()) {
            return numeric("coatingSolidsPct", textual.get(), "%", fact, raw,
                    "PERCENT_TEXT_V1", "PARSED", "DIRECT_VALUE");
        }
        for (var ref : refs(fact)) {
            if (ref.path("fractionRepresentation").asBoolean(false) && ref.has("rawNumericValue")) {
                try {
                    return numeric("coatingSolidsPct", decimal(ref.path("rawNumericValue").asText()).movePointRight(2),
                            "%", fact, raw, "PERCENT_EXCEL_FRACTION_V1", "PARSED", "EXCEL_FRACTION_X100");
                } catch (RuntimeException ignored) {
                    break;
                }
            }
        }
        return new StandardFact("coatingSolidsPct", null, null, null, null, "%", raw, itemId(fact), refs(fact),
                RULE_VERSION + ":PERCENT_V1", "AMBIGUOUS", "PERCENT_REPRESENTATION_UNKNOWN");
    }

    private boolean missingText(String value) {
        return value == null || value.isBlank() || "/".equals(value.trim()) || "未测试".equals(value.trim());
    }

    private StandardFact numeric(String code, BigDecimal value, String unit, JsonNode fact, String raw,
                                 String rule, String status, String derivation) {
        return new StandardFact(code, value.stripTrailingZeros(), null, null, null, unit, raw, itemId(fact), refs(fact),
                RULE_VERSION + ":" + rule, status, derivation);
    }

    private StandardFact textFact(String code, String value, JsonNode fact, String raw, String rule) {
        return new StandardFact(code, null, value, null, null, "", raw, itemId(fact), refs(fact),
                RULE_VERSION + ":" + rule, "PARSED", "CONTROLLED_TERM");
    }

    private java.util.Optional<BigDecimal> number(Pattern pattern, String value) {
        var matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? java.util.Optional.of(decimal(matcher.group(1))) : java.util.Optional.empty();
    }

    private java.util.Optional<BigDecimal> plainNumber(String value) {
        var normalized = value == null ? "" : value.trim();
        return normalized.matches("-?\\d+(?:\\.\\d+)?")
                ? java.util.Optional.of(decimal(normalized)) : java.util.Optional.empty();
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value).stripTrailingZeros();
    }

    private String raw(JsonNode fact) {
        var value = fact.get("rawValue");
        if (value == null || value.isNull()) value = fact.get("value");
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String itemId(JsonNode fact) {
        var id = text(fact, "itemId", "");
        return id.isBlank() ? text(fact, "bindingId", "") : id;
    }

    private JsonNode refs(JsonNode fact) {
        var refs = fact.path("sourceRefs");
        return refs.isArray() ? refs.deepCopy() : json.createArrayNode();
    }

    private String text(JsonNode node, String field, String fallback) {
        var value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    public record Projection(Map<String, StandardFact> process, Map<String, StandardFact> context) {
    }
}
