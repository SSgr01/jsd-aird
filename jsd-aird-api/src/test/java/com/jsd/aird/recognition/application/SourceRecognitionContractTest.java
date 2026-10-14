package com.jsd.aird.recognition.application;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRecognitionContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void freezesLogicalSampleAndTechnicalReplicateIdentifiers() {
        var stream = getClass().getResourceAsStream("/contracts/source-recognition.v1.openapi.yaml");
        assertThat(stream).isNotNull();
        var document = (Map<String, Object>) new Yaml().load(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        var components = (Map<String, Object>) document.get("components");
        var schemas = (Map<String, Object>) components.get("schemas");

        assertProperties(schemas, "ExperimentBoundary", "experimentBoundaryId", "samples", "sharedConditions");
        assertProperties(schemas, "SampleBoundary", "sampleBoundaryId", "logicalSampleKey", "sourceGroupKeys");
        assertProperties(schemas, "Candidate", "experimentBoundaryId", "sampleBoundaryId", "sourceGroupKey",
                "observationId", "replicateGroupKey", "measurementIndex");
        assertProperties(schemas, "FinalizeResult", "recognitionJobId", "confirmedSubmissionId", "drafts");

        var paths = (Map<String, Object>) document.get("paths");
        assertThat(paths.keySet()).contains(
                "/data/recognition-jobs/{id}/boundaries",
                "/experiment-imports/{id}/boundaries",
                "/data/recognition-jobs/{id}/finalize",
                "/experiment-imports/{id}/finalize");
    }

    @SuppressWarnings("unchecked")
    private void assertProperties(Map<String, Object> schemas, String schemaName, String... names) {
        var schema = (Map<String, Object>) schemas.get(schemaName);
        var properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.keySet()).containsAll(Set.of(names));
    }
}
