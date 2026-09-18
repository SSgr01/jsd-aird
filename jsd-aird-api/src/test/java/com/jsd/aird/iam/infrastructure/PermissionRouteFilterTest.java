package com.jsd.aird.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.springframework.mock.web.MockHttpServletRequest;

class PermissionRouteFilterTest {

    private final PermissionRouteFilter filter = new PermissionRouteFilter(
            check -> null, new ObjectMapper(), false);

    @Test
    void mapsTemplateActionsToIndependentPermissions() {
        assertThat(code("POST", "/api/v1/templates")).isEqualTo("template.create");
        assertThat(code("PUT", "/api/v1/template-versions/00000000-0000-0000-0000-000000000001/draft"))
                .isEqualTo("template.update");
        assertThat(code("POST", "/api/v1/template-versions/00000000-0000-0000-0000-000000000001/copies"))
                .isEqualTo("template.copy");
        assertThat(code("POST", "/api/v1/template-versions/00000000-0000-0000-0000-000000000001/rollback"))
                .isEqualTo("template.rollback");
        assertThat(code("POST", "/api/v1/template-versions/00000000-0000-0000-0000-000000000001/publish"))
                .isEqualTo("template.publish");
        assertThat(code("POST", "/api/v1/template-versions/00000000-0000-0000-0000-000000000001/review/reject"))
                .isEqualTo("template.review");
        assertThat(code("PATCH", "/api/v1/templates/00000000-0000-0000-0000-000000000001"))
                .isEqualTo("template.update");
        assertThat(code("DELETE", "/api/v1/template-versions/00000000-0000-0000-0000-000000000001"))
                .isEqualTo("template.delete");
        assertThat(code("GET", "/api/v1/templates/export.csv")).isEqualTo("template.export");
    }

    @Test
    void mapsProjectActionsAndRejectsUnknownBusinessWrites() {
        assertThat(code("POST", "/api/v1/projects")).isEqualTo("project.create");
        assertThat(code("PUT", "/api/v1/projects/00000000-0000-0000-0000-000000000001")).isEqualTo("project.update");
        assertThat(code("POST", "/api/v1/projects/copy")).isEqualTo("project.copy");
        assertThat(code("POST", "/api/v1/projects/00000000-0000-0000-0000-000000000001/materials/link"))
                .isEqualTo("project.assign");
        assertThat(code("PATCH", "/api/v1/projects/00000000-0000-0000-0000-000000000001/unknown-action")).isNull();
        assertThat(code("POST", "/api/v1/unknown-business-action")).isNull();
        assertThat(code("GET", "/api/v1/projects/00000000-0000-0000-0000-000000000001/documents/00000000-0000-0000-0000-000000000002"))
                .isEqualTo("project.view");
        assertThat(code("PUT", "/api/v1/projects/00000000-0000-0000-0000-000000000001/documents/00000000-0000-0000-0000-000000000002/content"))
                .isEqualTo("project.update");
    }

    @Test
    void mapsProjectDetailReadApisToProjectView() {
        var projectId = "00000000-0000-0000-0000-000000000001";
        var meetingId = "00000000-0000-0000-0000-000000000002";

        assertThat(code("GET", "/api/v1/projects/" + projectId + "/meetings")).isEqualTo("project.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId + "/logs")).isEqualTo("project.view");
        assertThat(codeWithProjectId("GET", "/api/v1/quality/uploads", projectId)).isEqualTo("project.view");
        assertThat(codeWithProjectId("GET", "/api/v1/production-uploads", projectId)).isEqualTo("project.view");
        assertThat(code("GET", "/api/v1/quality/uploads")).isEqualTo("quality.view");
        assertThat(code("GET", "/api/v1/production-uploads")).isEqualTo("production.view");

        assertThat(code("POST", "/api/v1/projects/" + projectId + "/meetings")).isEqualTo("project.create");
        assertThat(code("PUT", "/api/v1/projects/" + projectId + "/meetings/" + meetingId)).isEqualTo("project.update");
        assertThat(code("DELETE", "/api/v1/projects/" + projectId + "/meetings/" + meetingId)).isEqualTo("project.delete");
        assertThat(code("POST", "/api/v1/projects/" + projectId + "/meetings/" + meetingId + "/archive-to-kb"))
                .isEqualTo("project.update");
    }

    @Test
    void mapsKnowledgeSearchToAiPermission() {
        assertThat(code("POST", "/api/v1/knowledge/search")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/knowledge/assistant")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/assistant/qa/stream")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/search/files")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/knowledge/documents/00000000-0000-0000-0000-000000000000/ai-grant"))
                .isEqualTo("ai.external");
        assertThat(code("POST", "/api/v1/knowledge/documents/00000000-0000-0000-0000-000000000000/versions/00000000-0000-0000-0000-000000000000/reparse"))
                .isEqualTo("knowledge.update");
    }

    @Test
    void mapsAiRndRoutesToFineGrainedPermissionsAndRemovesLegacyRoutes() {
        var id = "00000000-0000-0000-0000-000000000001";
        assertThat(code("GET", "/api/v1/ai/rnd/targets")).isEqualTo("ai.modeling.read");
        assertThat(code("POST", "/api/v1/ai/rnd/targets")).isEqualTo("ai.modeling.manage");
        assertThat(code("POST", "/api/v1/ai/rnd/input-schemes/" + id + "/preview")).isEqualTo("ai.modeling.read");
        assertThat(code("GET", "/api/v1/ai/rnd/reference/standard-fields")).isEqualTo("ai.modeling.read");
        assertThat(code("GET", "/api/v1/ai/rnd/material-aliases")).isEqualTo("ai.modeling.read");
        assertThat(code("POST", "/api/v1/ai/rnd/material-aliases")).isEqualTo("ai.config.manage");
        assertThat(code("GET", "/api/v1/ai/rnd/material-dictionaries")).isEqualTo("ai.modeling.read");
        assertThat(code("POST", "/api/v1/ai/rnd/material-dictionaries")).isEqualTo("ai.config.manage");
        assertThat(code("GET", "/api/v1/ai/rnd/training-policies")).isEqualTo("ai.modeling.read");
        assertThat(code("POST", "/api/v1/ai/rnd/training-policies")).isEqualTo("ai.config.manage");
        assertThat(code("POST", "/api/v1/ai/rnd/reviews/" + id + "/decisions")).isEqualTo("ai.data.review");
        assertThat(code("GET", "/api/v1/ai/rnd/training-jobs")).isEqualTo("ai.model.read");
        assertThat(code("POST", "/api/v1/ai/rnd/training-jobs/" + id + "/retry")).isEqualTo("ai.training.operate");
        assertThat(code("POST", "/api/v1/ai/rnd/models/" + id + "/activate")).isEqualTo("ai.model.publish");
        assertThat(code("POST", "/api/v1/ai/rnd/predictions")).isEqualTo("ai.performance.predict");
        assertThat(code("POST", "/api/v1/ai/rnd/formula-designs")).isEqualTo("ai.formula.predict");
        assertThat(code("POST", "/api/v1/ai/rnd/experiment-optimizations")).isEqualTo("ai.experiment.optimize");
        assertThat(code("GET", "/api/v1/ai/formulation-readiness")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsEveryFrozenAiRndOperationToItsDeclaredPermission() {
        var stream = getClass().getResourceAsStream("/contracts/ai-rnd.v1.openapi.yaml");
        assertThat(stream).isNotNull();
        var document = (Map<String, Object>) new Yaml().load(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        var components = (Map<String, Object>) document.get("components");
        var operations = (Map<String, Map<String, Object>>) components.get("x-operations");
        var paths = (Map<String, Map<String, Map<String, String>>>) document.get("paths");
        var id = "00000000-0000-0000-0000-000000000001";

        paths.forEach((template, methods) -> methods.forEach((method, reference) -> {
            var ref = reference.get("$ref");
            var operationName = ref.substring(ref.lastIndexOf('/') + 1);
            var operation = operations.get(operationName);
            var uri = "/api/v1/ai/rnd" + template.replace("{id}", id);
            assertThat(code(method.toUpperCase(java.util.Locale.ROOT), uri))
                    .as(operationName + " " + method.toUpperCase(java.util.Locale.ROOT) + " " + uri)
                    .isEqualTo(operation.get("x-permission"));
        }));
    }

    @Test
    void mapsInventoryActionsToInventoryPermissions() {
        var id = "00000000-0000-0000-0000-000000000001";
        assertThat(code("GET", "/api/v1/inventory/balances")).isEqualTo("inventory.view");
        assertThat(code("POST", "/api/v1/inventory/products")).isEqualTo("inventory.create");
        assertThat(code("PUT", "/api/v1/inventory/balances/" + id + "/policy")).isEqualTo("inventory.update");
        assertThat(code("POST", "/api/v1/inventory/transactions/" + id + "/reverse")).isEqualTo("inventory.reverse");
        assertThat(code("POST", "/api/v1/inventory/transactions")).isEqualTo("inventory.create");
    }

    @Test
    void mapsExperimentImportAndSourceUploadActionsToExperimentPermissions() {
        assertThat(code("GET", "/api/v1/experiment-imports")).isEqualTo("experiment.view");
        assertThat(code("POST", "/api/v1/experiment-imports")).isEqualTo("experiment.create");
        assertThat(code("DELETE", "/api/v1/experiment-imports/00000000-0000-0000-0000-000000000001"))
                .isEqualTo("experiment.update");
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "EXPERIMENT_SOURCE"))
                .isEqualTo("experiment.create");
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "TEMPLATE_SOURCE"))
                .isEqualTo("template.upload");
        assertThat(code("POST", "/api/v1/data/import-jobs/00000000-0000-0000-0000-000000000001/experiment-sync"))
                .isEqualTo("data.create");
    }

    @Test
    void mapsExperimentLifecycleActionsToTheirActualPermissions() {
        var id = "00000000-0000-0000-0000-000000000001";
        assertThat(code("GET", "/api/v1/experiments")).isEqualTo("experiment.view");
        assertThat(code("POST", "/api/v1/experiments")).isEqualTo("experiment.create");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/draft")).isEqualTo("experiment.update");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/start")).isEqualTo("experiment.update");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/submit-review"))
                .isEqualTo("experiment.submit");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/approve"))
                .isEqualTo("experiment.approve");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/return"))
                .isEqualTo("experiment.review");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/void"))
                .isEqualTo("experiment.review");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/versions"))
                .isEqualTo("experiment.update");
    }

    private String code(String method, String uri) {
        return filter.permissionCode(new MockHttpServletRequest(method, uri));
    }

    private String codeWithProjectId(String method, String uri, String projectId) {
        var request = new MockHttpServletRequest(method, uri);
        request.addParameter("projectId", projectId);
        return filter.permissionCode(request);
    }

    private String codeWithKind(String method, String uri, String kind) {
        var request = new MockHttpServletRequest(method, uri);
        request.addParameter("kind", kind);
        return filter.permissionCode(request);
    }
}
