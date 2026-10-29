package com.jsd.aird.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PermissionRouteFilterTest {

    private final PermissionRouteFilter filter = new PermissionRouteFilter(
            check -> null, new ObjectMapper(), false);

    @Test
    void dataReviewAndPublicationUseSeparatePermissions() {
        var path="/api/v1/data/sources/00000000-0000-0000-0000-000000000001/publication/";
        assertThat(code("POST",path+"confirm")).isEqualTo("data.update");
        assertThat(code("POST",path+"submit")).isEqualTo("data.submit");
        assertThat(code("POST",path+"withdraw")).isEqualTo("data.submit");
        assertThat(code("POST",path+"approve")).isEqualTo("data.approve");
        assertThat(code("POST",path+"publish")).isEqualTo("data.approve");
        assertThat(code("POST",path+"disable")).isEqualTo("data.update");
        assertThat(code("POST",path+"restore")).isEqualTo("data.update");
        assertThat(code("POST",path+"revise")).isEqualTo("data.update");
        assertThat(code("GET",path+"snapshots/00000000-0000-0000-0000-000000000002")).isEqualTo("data.view");
        assertThat(code("GET","/api/v1/data/sources/00000000-0000-0000-0000-000000000001/records/export")).isEqualTo("data.export");
    }

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
        assertThat(code("POST", "/api/v1/projects/import")).isEqualTo("project.create");
        assertThat(code("POST", "/api/v1/projects/import/commit")).isEqualTo("project.create");
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
        assertThat(code("GET", "/api/v1/knowledge/upload-tasks")).isEqualTo("knowledge.view");
        assertThat(code("POST", "/api/v1/knowledge/upload-tasks")).isEqualTo("knowledge.upload");
        assertThat(code("PUT", "/api/v1/knowledge/upload-tasks/00000000-0000-0000-0000-000000000000"))
                .isEqualTo("knowledge.upload");
        assertThat(code("DELETE", "/api/v1/knowledge/upload-tasks/00000000-0000-0000-0000-000000000000"))
                .isEqualTo("knowledge.upload");
    }

    @Test
    void mapsAiRndRoutesToFineGrainedPermissionsAndRemovesLegacyRoutes() {
        var id = "00000000-0000-0000-0000-000000000001";
        assertThat(code("GET", "/api/v1/ai/rnd/targets")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/targets")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/input-schemes/" + id + "/preview")).isNull();
        assertThat(code("GET", "/api/v1/ai/rnd/reference/standard-fields")).isNull();
        assertThat(code("GET", "/api/v1/ai/rnd/material-aliases")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/material-aliases")).isNull();
        assertThat(code("GET", "/api/v1/ai/rnd/material-dictionaries")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/material-dictionaries")).isNull();
        assertThat(code("GET", "/api/v1/ai/rnd/training-policies")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/training-policies")).isNull();
        assertThat(code("GET", "/api/v1/ai/rnd/targets/" + id + "/domain-policies")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/targets/" + id + "/domain-policies")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/domain-policies/" + id + "/publish")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/reviews/" + id + "/decisions")).isNull();
        assertThat(code("GET", "/api/v1/ai/rnd/training-jobs")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/training-jobs/" + id + "/retry")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/models/" + id + "/activate")).isNull();
        assertThat(code("POST", "/api/v1/ai/rnd/models/" + id + "/pause")).isEqualTo("ai.model.publish");
        assertThat(code("POST", "/api/v1/ai/rnd/models/" + id + "/rollback")).isEqualTo("ai.model.publish");
        assertThat(code("POST", "/api/v1/ai/rnd/predictions")).isEqualTo("ai.performance.predict");
        assertThat(code("POST", "/api/v1/ai/rnd/formula-designs")).isEqualTo("ai.formula.predict");
        assertThat(code("POST", "/api/v1/ai/rnd/experiment-optimizations")).isEqualTo("ai.experiment.optimize");
        assertThat(code("GET", "/api/v1/ai/rnd/optimization-datasets")).isEqualTo("ai.experiment.optimize");
        assertThat(code("GET", "/api/v1/ai/rnd/optimization-datasets/" + id)).isEqualTo("ai.experiment.optimize");
        assertThat(code("POST", "/api/v1/ai/rnd/modeling-workbench/runs/" + id + "/register")).isEqualTo("ai.model.publish");
        assertThat(code("GET", "/api/v1/ai/rnd/modeling-workbench/datasets")).isEqualTo("ai.modeling.read");
        assertThat(code("GET", "/api/v1/ai/rnd/modeling-workbench/runs/" + id)).isEqualTo("ai.modeling.read");
        assertThat(code("POST", "/api/v1/ai/rnd/modeling-workbench/runs")).isEqualTo("ai.training.operate");
        assertThat(code("GET", "/api/v1/ai/rnd/test-metric-catalog/imports")).isEqualTo("ai.modeling.read");
        assertThat(code("POST", "/api/v1/ai/rnd/test-metric-catalog/imports")).isEqualTo("ai.modeling.manage");
        assertThat(code("POST", "/api/v1/ai/rnd/test-metric-catalog/imports/" + id + "/indicators/" + id + "/register")).isNull();
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "AI_MODELING_CATALOG")).isEqualTo("ai.modeling.manage");
        assertThat(code("GET", "/api/v1/ai/rnd/research-runs/" + id + "/observations"))
                .isEqualTo("ai.experiment.optimize");
        assertThat(code("PUT", "/api/v1/ai/rnd/research-runs/" + id + "/observations"))
                .isEqualTo("ai.experiment.optimize");
        assertThat(code("POST", "/api/v1/ai/rnd/research-runs/" + id + "/next-round"))
                .isEqualTo("ai.experiment.optimize");
        assertThat(code("GET", "/api/v1/ai/formulation-readiness")).isNull();
    }

    @Test
    void mapsInventoryActionsToInventoryPermissions() {
        var id = "00000000-0000-0000-0000-000000000001";
        assertThat(code("GET", "/api/v1/inventory/balances")).isEqualTo("inventory.view");
        assertThat(code("POST", "/api/v1/inventory/products")).isEqualTo("inventory.create");
        assertThat(code("PUT", "/api/v1/inventory/balances/" + id + "/policy")).isEqualTo("inventory.update");
        assertThat(code("DELETE", "/api/v1/inventory/shipment-headers/CH-1/details")).isEqualTo("inventory.update");
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
        assertThat(code("POST", "/api/v1/experiments/" + id + "/publish"))
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

    @Test
    void helpBypassesOnlyBusinessPermissionCheckForTheExactPostEndpoint() throws Exception {
        var authorization = org.mockito.Mockito.mock(com.jsd.aird.iam.api.AuthorizationService.class);
        var helpFilter = new PermissionRouteFilter(authorization, new ObjectMapper().findAndRegisterModules(), false);
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "customer", "USER");
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            helpFilter.doFilter(new MockHttpServletRequest("POST", "/api/v1/operation-assistant/qa/stream"),
                    new org.springframework.mock.web.MockHttpServletResponse(), (request, response) -> forwarded.set(true));
            assertThat(forwarded).isTrue();
            org.mockito.Mockito.verifyNoInteractions(authorization);
            forwarded.set(false);
            var rejected = new org.springframework.mock.web.MockHttpServletResponse();
            helpFilter.doFilter(new MockHttpServletRequest("POST", "/api/v1/operation-assistant/qa/stream/extra"), rejected,
                    (request, response) -> forwarded.set(true));
            assertThat(forwarded).isFalse();
            assertThat(rejected.getStatus()).isEqualTo(403);
            assertThat(code("POST", "/api/v1/assistant/qa/stream")).isEqualTo("ai.use");
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
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
