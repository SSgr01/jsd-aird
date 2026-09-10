package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class UvpuResearchProfile {
    private static final String RESOURCE = "/ai/task-profiles/uvpu-research-profile.v1.1.json";
    private final Definition definition;
    private final Map<String, Target> byKey;

    public UvpuResearchProfile(ObjectMapper json) {
        try (var input = UvpuResearchProfile.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("UV/PU研究档案不存在：" + RESOURCE);
            definition = json.readValue(input, Definition.class);
        } catch (IOException exception) {
            throw new IllegalStateException("UV/PU研究档案无法读取", exception);
        }
        var targets = new LinkedHashMap<String, Target>();
        definition.targets().forEach(target -> targets.put(target.targetKey(), target));
        byKey = Map.copyOf(targets);
    }

    public Definition definition() { return definition; }

    public Optional<Target> target(String key) { return Optional.ofNullable(byKey.get(key)); }

    public Optional<Target> recognize(String text) {
        var normalized = normalize(text);
        return definition.targets().stream().filter(target -> target.aliases().stream()
                .map(UvpuResearchProfile::normalize).anyMatch(normalized::contains)).findFirst();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[\\s，,（）()；;：:]", "");
    }

    public record Definition(String taskProfileCode, String researchProfileVersion,
                             String similarCaseBaselineVersion, List<Target> targets) {
        public Definition { targets = targets == null ? List.of() : List.copyOf(targets); }
    }

    public record Target(String targetKey, String name, String valueType, String unit,
                         String direction, List<String> aliases) {
        public Target { aliases = aliases == null ? List.of() : List.copyOf(aliases); }
    }
}
