package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class UvpuAnalysisProfile {
    private static final String RESOURCE = "/ai/task-profiles/uvpu-analysis-profile.v1.4.json";
    private final Definition definition;
    private final Map<String, MaterialDefinition> materialsByAlias;

    public UvpuAnalysisProfile(ObjectMapper json) {
        try (var input = UvpuAnalysisProfile.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("UV/PU分析档案不存在：" + RESOURCE);
            definition = json.readValue(input, Definition.class);
        } catch (IOException exception) {
            throw new IllegalStateException("UV/PU分析档案无法读取", exception);
        }
        var aliases = new LinkedHashMap<String, MaterialDefinition>();
        definition.materials().forEach(material -> {
            aliases.put(normalize(material.code()), material);
            material.aliases().forEach(alias -> aliases.put(normalize(alias), material));
        });
        materialsByAlias = Map.copyOf(aliases);
    }

    public Definition definition() {
        return definition;
    }

    public Optional<MaterialDefinition> material(String materialCode, String materialName) {
        var byCode = materialsByAlias.get(normalize(materialCode));
        if (byCode != null) return Optional.of(byCode);
        return Optional.ofNullable(materialsByAlias.get(normalize(materialName)));
    }

    public List<TargetDefinition> matchingTargets(String label) {
        var normalized = normalize(label);
        var result = new ArrayList<TargetDefinition>();
        for (var target : definition.targets()) {
            if (!target.includeAll().stream().map(UvpuAnalysisProfile::normalize).allMatch(normalized::contains)) continue;
            if (!target.includeAny().isEmpty()
                    && target.includeAny().stream().map(UvpuAnalysisProfile::normalize).noneMatch(normalized::contains)) continue;
            if (target.exclude().stream().map(UvpuAnalysisProfile::normalize).anyMatch(normalized::contains)) continue;
            result.add(target);
        }
        return List.copyOf(result);
    }

    public Optional<String> structuralZeroRule(String itemSourceKey) {
        var policy = definition.formulaBlankPolicy();
        if (policy == null || !policy.closedFormulaAbsentMeansZero() || itemSourceKey == null
                || !itemSourceKey.startsWith("MATRIX:")) return Optional.empty();
        var lastSeparator = itemSourceKey.lastIndexOf(':');
        if (lastSeparator <= "MATRIX:".length()) return Optional.empty();
        var projectionId = itemSourceKey.substring("MATRIX:".length(), lastSeparator);
        if (!policy.listProjectionIds().contains(projectionId)) return Optional.empty();
        return Optional.of(policy.structuralZeroRuleVersion());
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.ROOT).replaceAll("[\\s，,（）()；;：:]", "")
                .replace("Μ", "U").replace("μ", "U").replace("℃", "度");
    }

    public record Definition(
            String taskProfileCode,
            String analysisProfileVersion,
            String formulaBasis,
            BigDecimal formulaTotal,
            FormulaNormalizationPolicy formulaNormalizationPolicy,
            List<String> modelRequiredProcessFacts,
            List<String> modelRequiredContextFacts,
            BigDecimal lineageL1Threshold,
            FormulaBlankPolicy formulaBlankPolicy,
            List<MaterialDefinition> materials,
            List<TargetDefinition> targets
    ) {
        public Definition {
            if (!"PERCENT_SUM_100".equals(formulaBasis) || formulaTotal == null
                    || formulaTotal.signum() <= 0 || formulaNormalizationPolicy == null) {
                throw new IllegalArgumentException("UV/PU分析档案必须声明有效的封闭百分比配方及归一化规则");
            }
            materials = materials == null ? List.of() : List.copyOf(materials);
            targets = targets == null ? List.of() : List.copyOf(targets);
            modelRequiredProcessFacts = modelRequiredProcessFacts == null
                    ? List.of() : List.copyOf(modelRequiredProcessFacts);
            modelRequiredContextFacts = modelRequiredContextFacts == null
                    ? List.of() : List.copyOf(modelRequiredContextFacts);
        }
    }

    public record FormulaNormalizationPolicy(
            String policy,
            String ruleVersion
    ) {
        public FormulaNormalizationPolicy {
            policy = policy == null ? "" : policy;
            ruleVersion = ruleVersion == null ? "" : ruleVersion;
            if (!"SUM_TO_100".equals(policy) || ruleVersion.isBlank()) {
                throw new IllegalArgumentException("UV/PU封闭配方必须配置SUM_TO_100及规则版本");
            }
        }
    }

    public record FormulaBlankPolicy(
            boolean closedFormulaAbsentMeansZero,
            String structuralZeroRuleVersion,
            List<String> listProjectionIds
    ) {
        public FormulaBlankPolicy {
            structuralZeroRuleVersion = structuralZeroRuleVersion == null ? "" : structuralZeroRuleVersion;
            listProjectionIds = listProjectionIds == null ? List.of() : List.copyOf(listProjectionIds);
            if (closedFormulaAbsentMeansZero
                    && (structuralZeroRuleVersion.isBlank() || listProjectionIds.isEmpty())) {
                throw new IllegalArgumentException("启用封闭配方结构零时必须配置规则版本和矩阵白名单");
            }
        }
    }

    public record MaterialDefinition(
            String code,
            String role,
            String family,
            boolean modelAllowed,
            List<String> aliases
    ) {
        public MaterialDefinition {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    public record TargetDefinition(
            String targetKey,
            String valueType,
            String unit,
            String testMethod,
            String substrate,
            String stage,
            String condition,
            String parserCode,
            List<String> includeAll,
            List<String> includeAny,
            List<String> exclude
    ) {
        public TargetDefinition {
            parserCode = parserCode == null ? "" : parserCode.strip();
            if (parserCode.isBlank()) throw new IllegalArgumentException("目标定义必须声明确定性parserCode：" + targetKey);
            includeAll = includeAll == null ? List.of() : List.copyOf(includeAll);
            includeAny = includeAny == null ? List.of() : List.copyOf(includeAny);
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }
    }
}
