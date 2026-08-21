package com.jsd.aird.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
    void mapsKnowledgeSearchToAiPermission() {
        assertThat(code("POST", "/api/v1/knowledge/search")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/knowledge/assistant")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/assistant/qa")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/assistant/qa/stream")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/search/files")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/knowledge/documents/00000000-0000-0000-0000-000000000000/versions/00000000-0000-0000-0000-000000000000/reparse"))
                .isEqualTo("knowledge.update");
    }

    private String code(String method, String uri) {
        return filter.permissionCode(new MockHttpServletRequest(method, uri));
    }
}
