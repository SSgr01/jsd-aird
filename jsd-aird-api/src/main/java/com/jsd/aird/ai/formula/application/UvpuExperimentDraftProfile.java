package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Versioned mapping from UV/PU research output to a customer experiment template. */
@Component
public class UvpuExperimentDraftProfile {
    private static final String RESOURCE = "/ai/task-profiles/uvpu-experiment-draft-profile.v1.json";
    private final Definition definition;
    private final Map<String, ProcessFact> processByCode;

    public UvpuExperimentDraftProfile(ObjectMapper json) {
        try (var input = UvpuExperimentDraftProfile.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("UV/PU实验草稿档案不存在：" + RESOURCE);
            definition = json.readValue(input, Definition.class);
        } catch (IOException exception) {
            throw new IllegalStateException("UV/PU实验草稿档案无法读取", exception);
        }
        var facts = new LinkedHashMap<String, ProcessFact>();
        definition.processFacts().forEach(item -> facts.put(item.code(), item));
        processByCode = Map.copyOf(facts);
    }

    public Definition definition() {
        return definition;
    }

    public Optional<ProcessFact> processFact(String code) {
        return Optional.ofNullable(processByCode.get(code));
    }

    public record Definition(String taskProfileCode, String draftProfileVersion, String templateCode,
                             List<ProcessFact> processFacts) {
        public Definition {
            if (taskProfileCode == null || taskProfileCode.isBlank()
                    || draftProfileVersion == null || draftProfileVersion.isBlank()
                    || templateCode == null || templateCode.isBlank()) {
                throw new IllegalArgumentException("UV/PU实验草稿档案缺少任务、版本或模板编码");
            }
            processFacts = processFacts == null ? List.of() : List.copyOf(processFacts);
        }
    }

    public record ProcessFact(String code, String displayName, String unit) {
        public ProcessFact {
            code = code == null ? "" : code.strip();
            displayName = displayName == null ? "" : displayName.strip();
            unit = unit == null ? "" : unit.strip();
            if (code.isBlank() || displayName.isBlank()) {
                throw new IllegalArgumentException("实验草稿工艺字段必须配置编码和中文名称");
            }
        }
    }
}
