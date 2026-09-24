package com.jsd.aird.spc.application;

import java.util.List;
import java.util.Locale;

import com.jsd.aird.spc.application.port.SpectrumRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SpectrumIntentResolver {

    public RoutingDecision resolve(String requestedValue, String legacyScenario, String question,
                                   List<SpectrumRepository.ChartRow> charts) {
        if (StringUtils.hasText(legacyScenario)
                && "COMPETITOR_DECOMPOSITION".equalsIgnoreCase(legacyScenario.trim())
                && !StringUtils.hasText(requestedValue)) {
            return new RoutingDecision(SpectrumAnalysisIntent.REFERENCE_MAPPING,
                    SpectrumAnalysisIntent.REFERENCE_MAPPING, "USER_SELECTED", "LEGACY_REFERENCE_MAPPING");
        }
        var requested = SpectrumAnalysisIntent.parse(requestedValue).orElse(SpectrumAnalysisIntent.AUTO);
        if (StringUtils.hasText(requestedValue) && SpectrumAnalysisIntent.parse(requestedValue).isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的图谱分析目的");
        }
        if (requested != SpectrumAnalysisIntent.AUTO) {
            return new RoutingDecision(requested, requested, "USER_SELECTED", "EXPLICIT_INTENT");
        }
        var automatic = automatic(question, charts == null ? List.of() : charts);
        return automatic == null
                ? new RoutingDecision(SpectrumAnalysisIntent.AUTO, SpectrumAnalysisIntent.OVERVIEW,
                "FALLBACK_OVERVIEW", "NO_HIGH_CONFIDENCE_MATCH")
                : new RoutingDecision(SpectrumAnalysisIntent.AUTO, automatic.intent(),
                "HIGH_CONFIDENCE_RULE", automatic.reasonCode());
    }

    private AutomaticMatch automatic(String question, List<SpectrumRepository.ChartRow> charts) {
        var text = normalize(question);
        if (text.isBlank()) return null;

        var matches = new java.util.ArrayList<AutomaticMatch>();
        if (hasAny(text, "参考谱", "参考图谱", "标准谱", "单峰参考")
                && hasAny(text, "对应", "映射", "匹配", "哪张")) {
            matches.add(match(SpectrumAnalysisIntent.REFERENCE_MAPPING, "REFERENCE_AND_MAPPING_TERMS"));
        }
        if ((hasConcentrationValue(text) || hasAny(text, "浓度", "稀释", "配液"))
                && hasAny(text, "对不对", "是否正确", "准确", "能否定量", "是否合适", "偏高", "偏低")) {
            matches.add(match(SpectrumAnalysisIntent.CONCENTRATION_CHECK,
                    "CONCENTRATION_VALUE_AND_VERIFICATION_QUESTION"));
        }
        if (hasAny(text, "纯度", "杂质", "面积归一")
                && hasAny(text, "多少", "判断", "确认", "是否", "分析", "检查")) {
            matches.add(match(SpectrumAnalysisIntent.PURITY_CHECK, "PURITY_ASSESSMENT_QUESTION"));
        }
        if (hasAny(text, "方法", "测试条件", "线性范围", "系统适用性", "校准范围")
                && hasAny(text, "有效", "适合", "合适", "验证", "可靠", "是否")) {
            matches.add(match(SpectrumAnalysisIntent.METHOD_VALIDATION, "METHOD_SUITABILITY_QUESTION"));
        }
        if (charts.size() >= 2 && hasAny(text, "比较", "对比", "差异", "一致性", "批次变化")) {
            matches.add(match(SpectrumAnalysisIntent.SAMPLE_COMPARISON, "MULTI_CHART_COMPARISON_QUESTION"));
        }
        if (hasAny(text, "什么成分", "什么物质", "是什么成分", "是什么物质", "能否鉴定", "鉴定一下")) {
            matches.add(match(SpectrumAnalysisIntent.IDENTIFICATION, "EXPLICIT_IDENTIFICATION_QUESTION"));
        }
        if (hasAny(text, "异常峰", "基线漂移", "峰拖尾", "峰前伸", "鬼峰", "分峰")
                && hasAny(text, "原因", "为什么", "怎么排查", "如何排查")) {
            matches.add(match(SpectrumAnalysisIntent.ANOMALY_DIAGNOSIS, "SPECIFIC_ANOMALY_CAUSE_QUESTION"));
        }
        if (hasFeatureLocation(text) && hasAny(text, "峰", "峰位", "波长", "谱带", "保留时间")
                && hasAny(text, "是什么", "代表什么", "如何解释", "怎么解释", "归属")) {
            matches.add(match(SpectrumAnalysisIntent.PEAK_INTERPRETATION, "LOCATED_FEATURE_QUESTION"));
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private boolean hasConcentrationValue(String value) {
        return value.matches(".*\\d+(?:\\.\\d+)?\\s*(?:ppm|mg/l|mg·l-1|μg/ml|ug/ml|wt%|%\\s*[wv]?).*");
    }

    private boolean hasFeatureLocation(String value) {
        return value.matches(".*\\d+(?:\\.\\d+)?\\s*(?:nm|cm-1|cm⁻1|min|分钟|道尔顿|da|kda)?.*");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean hasAny(String value, String... candidates) {
        for (var candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private AutomaticMatch match(SpectrumAnalysisIntent intent, String reasonCode) {
        return new AutomaticMatch(intent, reasonCode);
    }

    private record AutomaticMatch(SpectrumAnalysisIntent intent, String reasonCode) { }

    public record RoutingDecision(SpectrumAnalysisIntent requestedIntent, SpectrumAnalysisIntent resolvedIntent,
                                  String resolution, String reasonCode) { }
}
