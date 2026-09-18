package com.jsd.aird.ai.rnd.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingPolicyRulesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsCompletePoliciesForEverySupportedResultType() throws Exception {
        for (var type : new String[]{"CONTINUOUS", "ORDINAL", "BINARY", "CATEGORICAL"}) {
            var qualification = json.readTree(type.equals("CONTINUOUS")
                    ? "{\"minimumTrainableSamples\":12,\"minimumIndependentLineages\":6,\"minimumSourceGroups\":4}"
                    : "{\"minimumTrainableSamples\":12,\"minimumIndependentLineages\":6,\"minimumSourceGroups\":4,\"minimumPerClass\":3}");
            var validation = json.readTree("{\"foldCount\":4,\"primaryMetric\":\"mae\",\"metricThreshold\":2.5,\"requireFairComparison\":true}");
            var algorithm = switch (type) {
                case "CONTINUOUS" -> "GAUSSIAN_PROCESS";
                case "ORDINAL" -> "ORDINAL_CUMULATIVE_LOGIT";
                default -> "LOGISTIC_REGRESSION";
            };
            var replicate = switch (type) {
                case "CONTINUOUS" -> "MEDIAN";
                case "ORDINAL" -> "MEDIAN_GRADE";
                default -> "CONSENSUS_ONLY";
            };
            var training = json.readTree("""
                    {"autoTrainingEnabled":true,"dataNature":"REAL","retrainMinimumNewSamples":2,
                     "minimumIntervalHours":12,"seed":17,"timeoutMinutes":30,
                     "replicateHandling":"%s","candidateAlgorithms":["%s"]}
                    """.formatted(replicate, algorithm));
            assertThat(TrainingPolicyRules.validate(type, qualification, validation, training)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScientificValuesAndIncompatibleAlgorithms() throws Exception {
        var issues = TrainingPolicyRules.validate("CONTINUOUS", json.createObjectNode(), json.createObjectNode(),
                json.readTree("""
                        {"autoTrainingEnabled":false,"dataNature":"SYNTHETIC","retrainMinimumNewSamples":0,
                         "minimumIntervalHours":0,"seed":1,"timeoutMinutes":5,"replicateHandling":"MAJORITY",
                         "candidateAlgorithms":["LOGISTIC_REGRESSION"]}
                        """));
        assertThat(issues).extracting(issue -> issue.fieldCode())
                .contains("minimumTrainableSamples", "minimumIndependentLineages", "minimumSourceGroups",
                        "foldCount", "primaryMetric", "metricThreshold", "training.replicateHandling",
                        "training.candidateAlgorithms");
    }
}
