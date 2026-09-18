package com.jsd.aird.ai.rnd.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.api.FormulaModelV2Contracts;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AiRndContractTest {

    private static final Set<String> R02_OPERATIONS = Set.of(
            "readTargets", "readTarget", "createTarget", "readTargetVersions", "createTargetVersion",
            "updateTargetVersion", "publishTargetVersion", "retireTarget",
            "readInputFields", "readInputField", "readInputFieldVersions", "createInputField",
            "createInputFieldVersion", "publishInputFieldVersion", "readSourceMappings", "createSourceMapping",
            "publishSourceMapping", "readInputSchemes", "createInputScheme", "previewInputScheme",
            "freezeInputScheme", "readTrainingPolicies", "createTrainingPolicy", "publishTrainingPolicy",
            "readStandardFieldReferences", "readMaterialReferences", "readMaterialAliases", "createMaterialAlias",
            "retireMaterialAlias", "readMaterialDictionaries", "createMaterialDictionary",
            "freezeMaterialDictionary", "retireMaterialDictionary"
    );

    @Test
    @SuppressWarnings("unchecked")
    void openApiParsesAndEveryOperationResolvesToOnePermission() {
        var stream = getClass().getResourceAsStream("/contracts/ai-rnd.v1.openapi.yaml");
        assertThat(stream).isNotNull();
        var document = (Map<String, Object>) new Yaml().load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        assertThat(document.get("openapi")).isEqualTo("3.1.0");
        var components = (Map<String, Object>) document.get("components");
        var operations = (Map<String, Map<String, Object>>) components.get("x-operations");
        assertThat(operations).isNotEmpty();
        assertThat(operations.values()).allSatisfy(operation -> {
            assertThat(operation.get("operationId")).isNotNull();
            assertThat(operation.get("x-permission")).isInstanceOf(String.class);
            assertThat(operation.get("parameters").toString()).contains("/RequestId");
        });
        var paths = (Map<String, Map<String, Map<String, String>>>) document.get("paths");
        paths.values().forEach(methods -> methods.forEach((method, reference) -> {
            var ref = reference.get("$ref");
            assertThat(ref).startsWith("#/components/x-operations/");
            var operation = operations.get(ref.substring(ref.lastIndexOf('/') + 1));
            assertThat(operation).isNotNull();
            if (!method.equals("get")) {
                assertThat(operation.get("parameters").toString()).contains("/IdempotencyKey");
            }
        }));
    }

    @Test
    void javaMirrorReadsSharedGoldenTypedResults() throws Exception {
        var root = Path.of("..").toAbsolutePath().normalize();
        var golden = root.resolve("jsd-aird-ai/contracts/formula-model.v2/examples/formula-model.v2.golden.json");
        assertThat(Files.exists(golden)).isTrue();
        var mapper = new ObjectMapper();
        var document = mapper.readTree(golden.toFile());
        assertThat(document.path("formula").path("recordedTotal").decimalValue()).isEqualByComparingTo("98.8");
        var success = mapper.treeToValue(
                document.path("typedResults").get(0), FormulaModelV2Contracts.TargetSuccess.class);
        assertThat(success.result()).isInstanceOf(FormulaModelV2Contracts.ContinuousResult.class);
        assertThat(document.path("modelBindings")).hasSize(4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void r02OperationsUseConcreteResponsesAndMutationSchemas() {
        var stream = getClass().getResourceAsStream("/contracts/ai-rnd.v1.openapi.yaml");
        assertThat(stream).isNotNull();
        var document = (Map<String, Object>) new Yaml().load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        var components = (Map<String, Object>) document.get("components");
        var operations = (Map<String, Map<String, Object>>) components.get("x-operations");

        assertThat(operations).containsKeys(R02_OPERATIONS.toArray(String[]::new));
        R02_OPERATIONS.forEach(operationId -> {
            var operation = operations.get(operationId);
            var responses = (Map<String, Object>) operation.get("responses");
            assertThat(responses).containsKey("200");
            var response200 = (Map<String, String>) responses.get("200");
            assertThat(response200.get("$ref"))
                    .doesNotEndWith("/Ok")
                    .doesNotEndWith("/Page");

            if (operation.get("requestBody") instanceof Map<?, ?> requestBody) {
                var content = (Map<String, Object>) requestBody.get("content");
                var json = (Map<String, Object>) content.get("application/json");
                var schema = (Map<String, String>) json.get("schema");
                assertThat(schema.get("$ref")).doesNotEndWith("/JsonObject");
            }
        });
    }
}
