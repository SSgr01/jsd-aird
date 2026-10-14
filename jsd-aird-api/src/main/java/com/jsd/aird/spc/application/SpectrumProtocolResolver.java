package com.jsd.aird.spc.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.port.SpectrumRepository;
import org.springframework.stereotype.Component;

@Component
public class SpectrumProtocolResolver {

    private final ObjectMapper objectMapper;

    public SpectrumProtocolResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> resolve(String question, List<SpectrumRepository.ChartRow> charts) {
        var result = new ArrayList<String>();
        for (var chart : charts) {
            var code = chart.categoryCode();
            if (!"HPLC_GPC".equalsIgnoreCase(code)) {
                result.add(code);
                continue;
            }
            result.add(resolveChromatography(question, chart));
        }
        return result.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private String resolveChromatography(String question, SpectrumRepository.ChartRow chart) {
        var metadata = parse(chart.metadataJson());
        for (var key : List.of("analysisTechnique", "chromatographyType")) {
            var value = metadata.path(key).asText("").trim().toUpperCase(Locale.ROOT);
            if ("HPLC".equals(value) || "GPC".equals(value)) return value;
        }
        var searchable = (question + " " + chart.title() + " " + chart.testConditions() + " " + chart.metadataJson())
                .toLowerCase(Locale.ROOT);
        var gpc = containsAny(searchable, "mn", "mw", "mz", "分子量", "分子量分布", "多分散", "pdi", "gpc", "sec");
        var hplc = containsAny(searchable, "保留时间", "峰面积", "流动相", "色谱柱", "纯度", "杂质", "hplc");
        if (gpc && !hplc) return "GPC";
        if (hplc && !gpc) return "HPLC";
        return "HPLC_GPC_GENERIC";
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String value) {
        try { return value == null || value.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(value); }
        catch (Exception ignored) { return objectMapper.createObjectNode(); }
    }

    private boolean containsAny(String value, String... candidates) {
        for (var candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }
}
