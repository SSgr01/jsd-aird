package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.TaskProfile;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Loads the immutable, shared Java/Python production task profile. */
@Component
public class FormulaModelTaskProfileRegistry {
    public static final String UVPU_CODE = "UVPU_APPLICATION_FORMULATION";
    public static final String PRODUCTION_VERSION = "1.1.2";
    public static final String CANDIDATE_VERSION = "1.2";
    private static final String RESOURCE =
            "ai-model-task-profiles/uvpu_application_formulation.v1.1.2.json";
    private static final String CANDIDATE_RESOURCE =
            "ai-model-task-profiles/uvpu_application_formulation.v1.2.json";

    private final TaskProfile profile;
    private final JsonNode profileJson;
    private final String profileHash;
    private final TaskProfile candidateProfile;
    private final JsonNode candidateProfileJson;
    private final String candidateProfileHash;

    public FormulaModelTaskProfileRegistry(ObjectMapper json, JsonCanonicalizer canonicalizer) {
        var production = load(json, canonicalizer, RESOURCE, "生产");
        this.profile = production.profile();
        this.profileJson = production.json();
        this.profileHash = production.hash();
        var candidate = load(json, canonicalizer, CANDIDATE_RESOURCE, "候选");
        this.candidateProfile = candidate.profile();
        this.candidateProfileJson = candidate.json();
        this.candidateProfileHash = candidate.hash();
        if (!UVPU_CODE.equals(profile.code()) || !PRODUCTION_VERSION.equals(profile.version())) {
            throw new IllegalStateException("UV/PU生产任务档案版本不正确");
        }
        if (profile.contextFeatures().stream().anyMatch(item -> "actualFilmThicknessUm".equals(item.code())
                || "filmThicknessUm".equals(item.code()))) {
            throw new IllegalStateException("基础任务档案不能要求实验后实测膜厚");
        }
        if (!UVPU_CODE.equals(candidateProfile.code()) || !CANDIDATE_VERSION.equals(candidateProfile.version())) {
            throw new IllegalStateException("UV/PU候选任务档案版本不正确");
        }
        if (candidateProfile.contextFeatures().stream().anyMatch(item -> "actualFilmThicknessUm".equals(item.code())
                || "filmThicknessUm".equals(item.code()))) {
            throw new IllegalStateException("候选基础任务档案不能要求实验后实测膜厚");
        }
    }

    public TaskProfile production() {
        return profile;
    }

    public JsonNode productionJson() {
        return profileJson.deepCopy();
    }

    public String productionHash() {
        return profileHash;
    }

    public TaskProfile byVersion(String version) {
        if (PRODUCTION_VERSION.equals(version)) return profile;
        if (CANDIDATE_VERSION.equals(version)) return candidateProfile;
        throw new IllegalArgumentException("未登记的UV/PU任务档案版本：" + version);
    }

    public JsonNode candidateJson() {
        return candidateProfileJson.deepCopy();
    }

    public String candidateHash() {
        return candidateProfileHash;
    }

    private LoadedProfile load(ObjectMapper json, JsonCanonicalizer canonicalizer, String resource, String kind) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            var sourceJson = json.readTree(input);
            var loaded = json.treeToValue(sourceJson, TaskProfile.class);
            var contractJson = json.copy().setSerializationInclusion(JsonInclude.Include.ALWAYS);
            JsonNode materialized = contractJson.valueToTree(loaded);
            return new LoadedProfile(loaded, materialized, canonicalizer.hash(materialized));
        } catch (IOException exception) {
            throw new IllegalStateException("UV/PU" + kind + "任务档案无法加载", exception);
        }
    }

    private record LoadedProfile(TaskProfile profile, JsonNode json, String hash) {
    }
}
