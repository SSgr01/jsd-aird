package com.jsd.aird.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PermissionRouteFilterTest {
    @Test
    void projectChildIdsAreResolvedByTheirServices() throws Exception {
        var filter = new PermissionRouteFilter(check ->
                new com.jsd.aird.iam.api.PermissionDecision(true, check.permissionCode(), "ALLOW", "ALL", "TEST"),
                new ObjectMapper(), false);
        var method = PermissionRouteFilter.class.getDeclaredMethod("resourceId", jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        var projectId = java.util.UUID.randomUUID();
        var childId = java.util.UUID.randomUUID();

        assertThat(method.invoke(filter, new MockHttpServletRequest("GET",
                "/api/v1/projects/" + projectId + "/tasks/" + childId))).isEqualTo(projectId);
        for (var path : java.util.List.of("/api/v1/tasks/", "/api/v1/stages/", "/api/v1/meetings/")) {
            assertThat(method.invoke(filter, new MockHttpServletRequest("GET", path + childId))).isNull();
        }
    }

    @Test
    void customerChildPathUsesParentIdAndCrmRecordUsesServiceLookup() throws Exception {
        var filter = new PermissionRouteFilter(check ->
                new com.jsd.aird.iam.api.PermissionDecision(true, check.permissionCode(), "ALLOW", "ALL", "TEST"),
                new ObjectMapper(), false);
        var method = PermissionRouteFilter.class.getDeclaredMethod("resourceId", jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        var customerId = java.util.UUID.randomUUID();
        var childId = java.util.UUID.randomUUID();

        assertThat(method.invoke(filter, new MockHttpServletRequest("GET",
                "/api/v1/business-partners/" + customerId + "/contacts/" + childId))).isEqualTo(customerId);
        assertThat(method.invoke(filter, new MockHttpServletRequest("GET",
                "/api/v1/crm/requirements/" + childId))).isNull();
    }

    @Test
    void customerRoutesUseConfiguredPageOperations() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "alice", "USER");
        var codes = new java.util.ArrayList<String>();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        actor, null, java.util.List.of()));
        try {
            var filter = new PermissionRouteFilter(check -> {
                codes.add(check.permissionCode());
                return new com.jsd.aird.iam.api.PermissionDecision(true, check.permissionCode(), "ALLOW", "CREATOR", "TEST");
            }, new ObjectMapper(), false);
            for (var request : java.util.List.of(
                    new MockHttpServletRequest("GET", "/api/v1/business-partners"),
                    new MockHttpServletRequest("GET", "/api/v1/business-partners/00000000-0000-0000-0000-000000000101/contacts"),
                    new MockHttpServletRequest("GET", "/api/v1/crm/requirements"),
                    new MockHttpServletRequest("GET", "/api/v1/crm/communications"))) {
                filter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(),
                        (servletRequest, servletResponse) -> { });
            }
            assertThat(codes).containsExactly("customer.list.view", "customer.overview.view",
                    "customer.needs.view", "customer.followup.view");
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }


    @Test
    void mapsReportCreationAndTemplateReadsToPageOperations() {
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("GET", "/api/v1/experiment-imports/00000000-0000-0000-0000-000000000001/source"))
                .isEqualTo("eln.upload.view");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("GET", "/api/v1/data/experiment-import-jobs/00000000-0000-0000-0000-000000000001/source"))
                .isEqualTo("eln.upload.view");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("GET", "/api/v1/template-imports/00000000-0000-0000-0000-000000000001/suggestions"))
                .isEqualTo("template.view");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("POST", "/api/v1/comprehensive-reports"))
                .isEqualTo("research-test.report.view.create");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("POST", "/api/v1/test-standards"))
                .isEqualTo("research-test.standard.create");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("GET", "/api/v1/templates"))
                .isEqualTo("template.view.view");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("GET", "/api/v1/template-versions/00000000-0000-0000-0000-000000000001/edit-model"))
                .isEqualTo("template.view.view");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("GET", "/api/v1/template-versions/00000000-0000-0000-0000-000000000001/snapshot"))
                .isEqualTo("template.view.view");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("GET", "/api/v1/templates/00000000-0000-0000-0000-000000000001/versions"))
                .isEqualTo("template.view.view");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("GET", "/api/v1/template-versions/00000000-0000-0000-0000-000000000001/review"))
                .isEqualTo("template.view.view");
        assertThat(PermissionRouteFilter.researchTestTemplateOperation("GET", "/api/v1/templates/00000000-0000-0000-0000-000000000001/export"))
                .isNull();
        assertThat(code("DELETE", "/api/v1/template-imports/00000000-0000-0000-0000-000000000001"))
                .isEqualTo("template.upload.delete");
    }

    @Test
    void templateUploadViewCanReadRecognitionWithoutCreatePermission() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "viewer", "USER");
        var checkedCodes = new java.util.ArrayList<String>();
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var guarded = new PermissionRouteFilter(check -> {
                checkedCodes.add(check.permissionCode());
                return new com.jsd.aird.iam.api.PermissionDecision(
                        "template.upload.view".equals(check.permissionCode()), check.permissionCode(), "ALLOW", "ALL", "TEST");
            }, new ObjectMapper().findAndRegisterModules(), false);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            guarded.doFilter(new MockHttpServletRequest("GET",
                    "/api/v1/template-imports/00000000-0000-0000-0000-000000000001/suggestions"),
                    response, (request, servletResponse) -> forwarded.set(true));
            assertThat(forwarded).isTrue();
            assertThat(checkedCodes).containsExactly("template.upload.view");
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void resourceLinkWriteAcceptsTheRecordEditPermission() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "editor", "USER");
        var jobId = java.util.UUID.randomUUID();
        var checks = new java.util.ArrayList<com.jsd.aird.iam.api.PermissionCheck>();
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var guarded = new PermissionRouteFilter(check -> {
                checks.add(check);
                return new com.jsd.aird.iam.api.PermissionDecision(
                        "data.view.update".equals(check.permissionCode()), check.permissionCode(), "ALLOW", "ALL", "TEST");
            }, new ObjectMapper().findAndRegisterModules(), false);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            guarded.doFilter(new MockHttpServletRequest("PUT",
                    "/api/v1/project-resource-links/DATA_IMPORT_JOB/" + jobId),
                    response, (request, servletResponse) -> forwarded.set(true));

            assertThat(forwarded).isTrue();
            assertThat(checks).anySatisfy(check -> {
                assertThat(check.permissionCode()).isEqualTo("data.view.update");
                assertThat(check.resourceType()).isEqualTo("DATA");
                assertThat(check.resourceId()).isEqualTo(jobId);
            });
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void projectReferencesAcceptTheSourceRecordEditPermission() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "editor", "USER");
        var checks = new java.util.ArrayList<com.jsd.aird.iam.api.PermissionCheck>();
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var guarded = new PermissionRouteFilter(check -> {
                checks.add(check);
                return new com.jsd.aird.iam.api.PermissionDecision(
                        "spectrum.update".equals(check.permissionCode()), check.permissionCode(), "ALLOW", "ALL", "TEST");
            }, new ObjectMapper().findAndRegisterModules(), false);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            guarded.doFilter(new MockHttpServletRequest("POST", "/api/v1/project-references"),
                    response, (request, servletResponse) -> forwarded.set(true));

            assertThat(forwarded).isTrue();
            assertThat(checks).anySatisfy(check -> assertThat(check.permissionCode()).isEqualTo("project.assign"));
            assertThat(checks).anySatisfy(check -> assertThat(check.permissionCode()).isEqualTo("spectrum.update"));
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void spectrumChartWritesAcceptThePageOperationCodes() {
        var chartId = java.util.UUID.randomUUID();
        assertThat(PermissionRouteFilter.spectrumChartPageOperation("DELETE", "/api/v1/spc/charts/" + chartId))
                .isEqualTo("spectrum.view.delete");
        assertThat(PermissionRouteFilter.spectrumChartPageOperation("PUT", "/api/v1/spc/charts/" + chartId))
                .isEqualTo("spectrum.view.update");
        assertThat(PermissionRouteFilter.spectrumChartPageOperation("POST", "/api/v1/spc/charts/export"))
                .isNull();
        assertThat(PermissionRouteFilter.spectrumChartPageOperation("DELETE", "/api/v1/spc/categories/" + chartId))
                .isNull();
    }

    private final PermissionRouteFilter filter = new PermissionRouteFilter(
            check -> null, new ObjectMapper(), false);

    @Test
    void businessFileReadDoesNotRequireHiddenFilePermission() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "viewer", "USER");
        var fileId = java.util.UUID.randomUUID();
        var files = org.mockito.Mockito.mock(FileBusinessPermissionResolver.class);
        org.mockito.Mockito.when(files.mayRead(actor, fileId)).thenReturn(true);
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
            var guarded = new PermissionRouteFilter(check -> {
                throw new AssertionError("Generic file permission must not be checked");
            }, new ObjectMapper().findAndRegisterModules(), false, files);
            var request = new MockHttpServletRequest("GET", "/api/v1/files/" + fileId + "/content");
            guarded.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(),
                    (servletRequest, servletResponse) -> forwarded.set(true));
            assertThat(forwarded).isTrue();
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void experimentViewCreateGrantCannotCreateExperiment() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "engineer", "USER");
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var checked = new java.util.ArrayList<String>();
            var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
            var filter = new PermissionRouteFilter(check -> {
                checked.add(check.permissionCode());
                var allowed = "eln.view.create".equals(check.permissionCode());
                return new com.jsd.aird.iam.api.PermissionDecision(allowed, check.permissionCode(),
                        allowed ? "ALLOW" : "DENY", "ALL", "TEST");
            }, new ObjectMapper().findAndRegisterModules(), false);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/experiments"), response,
                    (request, servletResponse) -> forwarded.set(true));
            assertThat(forwarded).isFalse();
            assertThat(checked).containsExactly("eln.create.create");
            assertThat(response.getStatus()).isEqualTo(403);
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void mapsDictionaryReadsAndWritesToSystemPageOperations() {
        assertThat(PermissionRouteFilter.systemDictionaryOperation("GET", "/api/v1/standard-fields"))
                .isEqualTo("system.dictionary.view");
        assertThat(PermissionRouteFilter.systemDictionaryOperation("GET", "/api/v1/standard-field-requests"))
                .isEqualTo("system.dictionary.view");
        assertThat(PermissionRouteFilter.systemDictionaryOperation("POST", "/api/v1/standard-field-requests"))
                .isEqualTo("system.dictionary.create");
        assertThat(PermissionRouteFilter.systemDictionaryOperation("POST",
                "/api/v1/standard-field-requests/00000000-0000-0000-0000-000000000001/approve"))
                .isEqualTo("system.dictionary.update");
    }

    @Test
    void templateReviewAloneCannotApproveDictionaryRequests() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "reviewer", "USER");
        var checkedCodes = new java.util.ArrayList<String>();
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var guarded = new PermissionRouteFilter(check -> {
                checkedCodes.add(check.permissionCode());
                return new com.jsd.aird.iam.api.PermissionDecision(
                        "template.review".equals(check.permissionCode()), check.permissionCode(), "DENY", "ALL", "TEST");
            }, new ObjectMapper().findAndRegisterModules(), false);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            guarded.doFilter(new MockHttpServletRequest("POST",
                    "/api/v1/standard-field-requests/00000000-0000-0000-0000-000000000001/approve"),
                    response, (request, servletResponse) -> forwarded.set(true));
            assertThat(forwarded).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(checkedCodes).containsExactly("system.dictionary.update");
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void mapsCategoryMaintenanceToItsOwnPermissionModule() {
        var category = "/00000000-0000-0000-0000-000000000001";
        assertThat(PermissionRouteFilter.categoryOperation("GET", "/api/v1/data/categories"))
                .isEqualTo("data.category.view");
        assertThat(PermissionRouteFilter.categoryOperation("POST", "/api/v1/template-categories"))
                .isEqualTo("template.category.create");
        assertThat(PermissionRouteFilter.categoryOperation("PUT", "/api/v1/template-categories" + category))
                .isEqualTo("template.category.update");
        assertThat(PermissionRouteFilter.categoryOperation("DELETE", "/api/v1/experiment-categories" + category))
                .isEqualTo("eln.category.delete");
        assertThat(PermissionRouteFilter.categoryOperation("POST", "/api/v1/quality/categories"))
                .isEqualTo("quality.category.create");
        assertThat(PermissionRouteFilter.categoryOperation("PUT", "/api/v1/spc/categories" + category))
                .isEqualTo("spectrum.category.update");
        assertThat(PermissionRouteFilter.categoryOperation("DELETE", "/api/v1/knowledge/categories" + category))
                .isEqualTo("knowledge.category.delete");
        // Moving a record between categories edits the record, not the category set.
        assertThat(PermissionRouteFilter.categoryOperation("PUT", "/api/v1/data/sources" + category + "/category"))
                .isNull();
        assertThat(PermissionRouteFilter.categoryOperation("PUT", "/api/v1/spc/charts" + category + "/category"))
                .isNull();
    }

    @Test
    void categoryEndpointsKeepAcceptingPreviouslyGrantedCodes() {
        var category = "/00000000-0000-0000-0000-000000000001";
        assertThat(PermissionRouteFilter.categoryFallbackOperation("GET", "/api/v1/quality/categories"))
                .isEqualTo("quality.view");
        assertThat(PermissionRouteFilter.categoryFallbackOperation("POST", "/api/v1/template-categories"))
                .isEqualTo("category.create");
        assertThat(PermissionRouteFilter.categoryFallbackOperation("PUT", "/api/v1/template-categories" + category))
                .isEqualTo("category.update");
        assertThat(PermissionRouteFilter.categoryFallbackOperation("DELETE", "/api/v1/experiment-categories" + category))
                .isEqualTo("experiment.update");
        assertThat(PermissionRouteFilter.categoryFallbackOperation("POST", "/api/v1/knowledge/categories"))
                .isEqualTo("knowledge.create");
        assertThat(PermissionRouteFilter.categoryFallbackOperation("GET", "/api/v1/experiments"))
                .isNull();
    }

    @Test
    void categoryIdsAreNotResolvedAsDataScopeTargets() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "engineer", "USER");
        var categoryId = java.util.UUID.randomUUID();
        var resourceIds = new java.util.ArrayList<java.util.UUID>();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var filter = new PermissionRouteFilter(check -> {
                resourceIds.add(check.resourceId());
                return new com.jsd.aird.iam.api.PermissionDecision(true, check.permissionCode(), "ALLOW", "CREATOR", "TEST");
            }, new ObjectMapper().findAndRegisterModules(), false);
            filter.doFilter(new MockHttpServletRequest("DELETE", "/api/v1/quality/categories/" + categoryId),
                    new org.springframework.mock.web.MockHttpServletResponse(), (request, response) -> { });
            assertThat(resourceIds).hasSize(1);
            assertThat(resourceIds.get(0)).isNull();
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void mapsProjectWritesToTheirPageOperations() {
        var project = "/api/v1/projects/00000000-0000-0000-0000-000000000001";
        assertThat(PermissionRouteFilter.projectPageOperation("GET", "/api/v1/projects"))
                .isEqualTo("project.list.view");
        assertThat(PermissionRouteFilter.projectPageOperation("GET", project))
                .isEqualTo("project.list.view");
        assertThat(PermissionRouteFilter.projectPageOperation("POST", "/api/v1/projects"))
                .isEqualTo("project.list.create");
        assertThat(PermissionRouteFilter.projectPageOperation("POST", project + "/stages"))
                .isEqualTo("project.stage.create");
        assertThat(PermissionRouteFilter.projectPageOperation("PUT", project + "/stages/reorder"))
                .isEqualTo("project.stage.update");
        assertThat(PermissionRouteFilter.projectPageOperation("DELETE", "/api/v1/tasks/00000000-0000-0000-0000-000000000002"))
                .isEqualTo("project.task.delete");
        assertThat(PermissionRouteFilter.projectPageOperation("POST", project + "/documents/00000000-0000-0000-0000-000000000002/publish"))
                .isEqualTo("project.document.publish");
        assertThat(PermissionRouteFilter.projectPageOperation("POST", project + "/meetings/00000000-0000-0000-0000-000000000002/archive-to-kb"))
                .isEqualTo("project.meeting.publish");
        assertThat(PermissionRouteFilter.projectPageOperation("POST", project + "/experiments"))
                .isEqualTo("eln.create.create");
        assertThat(PermissionRouteFilter.projectPageOperation("DELETE", project + "/experiments/00000000-0000-0000-0000-000000000002"))
                .isEqualTo("eln.view.delete");
    }

    @Test
    void legacyProjectAndExperimentGrantsDoNotAllowProjectExperimentWrites() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "engineer", "USER");
        var project = "/api/v1/projects/00000000-0000-0000-0000-000000000001/experiments";
        var experiment = project + "/00000000-0000-0000-0000-000000000002";
        var checkedCodes = new java.util.ArrayList<String>();
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var denied = new PermissionRouteFilter(check -> {
                checkedCodes.add(check.permissionCode());
                return new com.jsd.aird.iam.api.PermissionDecision(
                        java.util.Set.of("project.create", "project.delete", "experiment.create", "experiment.delete")
                                .contains(check.permissionCode()), check.permissionCode(), "DENY", "ALL", "TEST");
            }, new ObjectMapper().findAndRegisterModules(), false);
            for (var request : java.util.List.of(new MockHttpServletRequest("POST", project),
                    new MockHttpServletRequest("DELETE", experiment))) {
                checkedCodes.clear();
                forwarded.set(false);
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                denied.doFilter(request, response, (req, res) -> forwarded.set(true));
                assertThat(forwarded).isFalse();
                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(checkedCodes).containsExactly(request.getMethod().equals("POST")
                        ? "eln.create.create" : "eln.view.delete");
            }
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void selectedStageActionWorksWithoutLegacyGrantButPersonalDenyStillWins() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "engineer", "USER");
        var path = "/api/v1/projects/00000000-0000-0000-0000-000000000001/stages";
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var allowed = new PermissionRouteFilter(check -> new com.jsd.aird.iam.api.PermissionDecision(
                    "project.stage.create".equals(check.permissionCode()), check.permissionCode(),
                    "project.stage.create".equals(check.permissionCode()) ? "ALLOW" : "DENY", "ALL", "TEST"),
                    new ObjectMapper().findAndRegisterModules(), false);
            allowed.doFilter(new MockHttpServletRequest("POST", path), new org.springframework.mock.web.MockHttpServletResponse(),
                    (request, response) -> forwarded.set(true));
            assertThat(forwarded).isTrue();

            forwarded.set(false);
            var denied = new PermissionRouteFilter(check -> new com.jsd.aird.iam.api.PermissionDecision(
                    false, check.permissionCode(), "DENY", "ALL", "USER_OPERATION_OVERRIDE"),
                    new ObjectMapper().findAndRegisterModules(), false);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            denied.doFilter(new MockHttpServletRequest("POST", path), response,
                    (request, servletResponse) -> forwarded.set(true));
            assertThat(forwarded).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void projectDetailAndStagesUsePageQueriesEvenWhenLegacyViewIsDenied() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "engineer", "USER");
        var projectId = java.util.UUID.randomUUID();
        var checkedCodes = new java.util.ArrayList<String>();
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var authorization = new PermissionRouteFilter(check -> {
                checkedCodes.add(check.permissionCode());
                assertThat(check.resourceId()).isEqualTo(projectId);
                var allowed = java.util.Set.of("project.list.view", "project.stage.view").contains(check.permissionCode());
                return new com.jsd.aird.iam.api.PermissionDecision(
                        allowed, check.permissionCode(), allowed ? "ALLOW" : "DENY", "CREATOR", "USER_OPERATION_OVERRIDE");
            }, new ObjectMapper().findAndRegisterModules(), false);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            authorization.doFilter(new MockHttpServletRequest("GET", "/api/v1/projects/" + projectId), response,
                    (request, servletResponse) -> forwarded.set(true));
            assertThat(forwarded).isTrue();
            forwarded.set(false);
            authorization.doFilter(new MockHttpServletRequest("GET", "/api/v1/projects/" + projectId + "/stages"),
                    new org.springframework.mock.web.MockHttpServletResponse(),
                    (request, servletResponse) -> forwarded.set(true));
            assertThat(forwarded).isTrue();
            assertThat(checkedCodes).containsExactly("project.list.view", "project.stage.view");
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void knowledgeReviewUsesTheKnowledgeEditAndApprovePermissions() {
        var document = "/api/v1/knowledge/documents/00000000-0000-0000-0000-000000000001";
        var version = document + "/versions/00000000-0000-0000-0000-000000000002";
        assertThat(code("GET", "/api/v1/knowledge/review-queue")).isEqualTo("knowledge.view");
        assertThat(code("GET", version + "/review")).isEqualTo("knowledge.view");
        assertThat(code("PUT", version + "/review")).isEqualTo("knowledge.view.update");
        assertThat(code("PUT", version + "/review/00000000-0000-0000-0000-000000000003/tables/00000000-0000-0000-0000-000000000004"))
                .isEqualTo("knowledge.view.update");
        assertThat(code("POST", version + "/reject")).isEqualTo("knowledge.view.approve");
        assertThat(code("POST", version + "/publish")).isEqualTo("knowledge.publish");
    }

    @Test
    void knowledgeReviewSaveDeniesUsersWithoutKnowledgeEditPermission() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "reviewer", "USER");
        var documentId = java.util.UUID.randomUUID();
        var versionId = java.util.UUID.randomUUID();
        var checks = new java.util.ArrayList<com.jsd.aird.iam.api.PermissionCheck>();
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            // 只保留旧校对码而不具备知识库编辑权限时，校对内容不可保存。
            var guarded = new PermissionRouteFilter(check -> {
                checks.add(check);
                return new com.jsd.aird.iam.api.PermissionDecision(
                        "knowledge.review".equals(check.permissionCode()), check.permissionCode(), "ALLOW", "ALL", "TEST");
            }, new ObjectMapper().findAndRegisterModules(), false);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            guarded.doFilter(new MockHttpServletRequest("PUT",
                    "/api/v1/knowledge/documents/" + documentId + "/versions/" + versionId + "/review"),
                    response, (request, servletResponse) -> forwarded.set(true));

            assertThat(forwarded).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(checks).anySatisfy(check -> assertThat(check.permissionCode()).isEqualTo("knowledge.view.update"));
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void knowledgeReviewSavePassesWithKnowledgeEditPermission() throws Exception {
        var actor = new com.jsd.aird.shared.security.Actor(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "editor", "USER");
        var documentId = java.util.UUID.randomUUID();
        var versionId = java.util.UUID.randomUUID();
        var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
        com.jsd.aird.shared.security.ActorContext.set(actor);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        try {
            var guarded = new PermissionRouteFilter(check -> new com.jsd.aird.iam.api.PermissionDecision(
                    "knowledge.view.update".equals(check.permissionCode()), check.permissionCode(), "ALLOW", "ALL", "TEST"),
                    new ObjectMapper().findAndRegisterModules(), false);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            guarded.doFilter(new MockHttpServletRequest("PUT",
                    "/api/v1/knowledge/documents/" + documentId + "/versions/" + versionId + "/review"),
                    response, (request, servletResponse) -> forwarded.set(true));

            assertThat(forwarded).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            com.jsd.aird.shared.security.ActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void dataReviewAndPublicationUseSeparatePermissions() {
        var path="/api/v1/data/sources/00000000-0000-0000-0000-000000000001/publication/";
        assertThat(code("POST",path+"confirm")).isEqualTo("data.update");
        assertThat(code("POST",path+"submit")).isEqualTo("data.submit");
        assertThat(code("POST",path+"withdraw")).isEqualTo("data.submit");
        assertThat(code("POST",path+"approve")).isEqualTo("data.approve");
        assertThat(code("POST",path+"publish")).isEqualTo("data.view.publish");
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
                .isEqualTo("template.view.review");
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
                .isEqualTo("project.document.view");
        assertThat(code("PUT", "/api/v1/projects/00000000-0000-0000-0000-000000000001/documents/00000000-0000-0000-0000-000000000002/content"))
                .isEqualTo("project.update");
    }

    @Test
    void mapsProjectDetailReadsToTheirPageQueries() {
        var projectId = "00000000-0000-0000-0000-000000000001";
        var meetingId = "00000000-0000-0000-0000-000000000002";

        assertThat(code("GET", "/api/v1/projects")).isEqualTo("project.list.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId)).isEqualTo("project.list.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId + "/stages")).isEqualTo("project.stage.view");
        assertThat(code("GET", "/api/v1/project-stages")).isEqualTo("project.stage.view");
        assertThat(code("GET", "/api/v1/stages/" + meetingId + "/tasks")).isEqualTo("project.task.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId + "/experiments")).isEqualTo("project.task.view");
        assertThat(code("GET", "/api/v1/tasks")).isEqualTo("project.task.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId + "/documents")).isEqualTo("project.document.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId + "/requirements")).isEqualTo("project.customer-needs.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId + "/references")).isEqualTo("project.reference.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId + "/meetings")).isEqualTo("project.meeting.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId + "/logs")).isEqualTo("project.log.view");
        assertThat(code("GET", "/api/v1/projects/" + projectId + "/materials")).isEqualTo("project.materials.view");
        assertThat(codeWithProjectId("GET", "/api/v1/quality/uploads", projectId)).isEqualTo("project.materials.view");
        assertThat(codeWithProjectId("GET", "/api/v1/production-uploads", projectId)).isEqualTo("project.materials.view");
        assertThat(code("GET", "/api/v1/quality/uploads")).isEqualTo("quality.upload.view");
        assertThat(code("GET", "/api/v1/production-uploads")).isEqualTo("production.upload.view");
        assertThat(code("POST", "/api/v1/production-uploads")).isEqualTo("production.upload.create");
        assertThat(code("POST", "/api/v1/production-uploads/" + meetingId + "/retry"))
                .isEqualTo("production.upload.create");
        assertThat(code("DELETE", "/api/v1/production-uploads/" + meetingId))
                .isEqualTo("production.upload.delete");

        assertThat(code("POST", "/api/v1/projects/" + projectId + "/meetings")).isEqualTo("project.create");
        assertThat(code("PUT", "/api/v1/projects/" + projectId + "/meetings/" + meetingId)).isEqualTo("project.update");
        assertThat(code("DELETE", "/api/v1/projects/" + projectId + "/meetings/" + meetingId)).isEqualTo("project.delete");
        assertThat(code("POST", "/api/v1/projects/" + projectId + "/meetings/" + meetingId + "/archive-to-kb"))
                .isEqualTo("project.update");
    }

    @Test
    void mapsUploadCreationAndRetryToCreateOperations() {
        var id = "00000000-0000-0000-0000-000000000001";

        assertThat(code("POST", "/api/v1/comprehensive-reports/uploads"))
                .isEqualTo("research-test.report.upload.create");
        assertThat(code("POST", "/api/v1/comprehensive-reports/uploads/" + id + "/retry"))
                .isEqualTo("research-test.report.upload.create");
        assertThat(code("POST", "/api/v1/test-standards/uploads"))
                .isEqualTo("research-test.standard.upload.create");
        assertThat(code("POST", "/api/v1/test-standards/uploads/" + id + "/retry"))
                .isEqualTo("research-test.standard.upload.create");
        assertThat(code("POST", "/api/v1/quality/uploads"))
                .isEqualTo("quality.upload.create");
        assertThat(code("POST", "/api/v1/quality/uploads/" + id + "/retry"))
                .isEqualTo("quality.upload.create");
    }

    @Test
    void mapsExperimentImportReviewToCreateOperation() {
        var root = "/api/v1/data/experiment-import-jobs/00000000-0000-0000-0000-000000000001";

        assertThat(code("GET", root + "/preview")).isEqualTo("eln.upload.view");
        assertThat(code("GET", root + "/workbook-snapshot")).isEqualTo("eln.upload.view");
        assertThat(code("PUT", root + "/workbook-draft")).isEqualTo("eln.upload.create");
        assertThat(code("POST", root + "/commit")).isEqualTo("eln.upload.create");
        assertThat(code("POST", root + "/experiment-sync")).isEqualTo("eln.upload.create");
    }

    @Test
    void mapsKnowledgeSearchToAiPermission() {
        assertThat(code("POST", "/api/v1/knowledge/search")).isEqualTo("ai.use");
        assertThat(code("POST", "/api/v1/knowledge/assistant")).isEqualTo("ai.use");
        assertThat(code("PUT", "/api/v1/project-resource-links/KNOWLEDGE_DOCUMENT/00000000-0000-0000-0000-000000000000"))
                .isEqualTo("knowledge.update");
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
    void mapsExperimentImportAndSourceUploadActionsToUploadPagePermissions() {
        assertThat(code("GET", "/api/v1/experiment-imports")).isEqualTo("eln.upload.view");
        assertThat(code("POST", "/api/v1/experiment-imports")).isEqualTo("eln.upload.create");
        assertThat(code("POST", "/api/v1/experiment-imports/00000000-0000-0000-0000-000000000001/retry"))
                .isEqualTo("eln.upload.retry");
        assertThat(code("DELETE", "/api/v1/experiment-imports/00000000-0000-0000-0000-000000000001"))
                .isEqualTo("eln.upload.delete");
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "EXPERIMENT_SOURCE"))
                .isEqualTo("eln.upload.create");
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "TEMPLATE_SOURCE"))
                .isEqualTo("template.upload.create");
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "TEMPLATE_SNAPSHOT"))
                .isEqualTo("template.view.update");
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "PRODUCTION_SNAPSHOT"))
                .isEqualTo("production.view.update");
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "KNOWLEDGE"))
                .isEqualTo("knowledge.upload.create");
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "UNKNOWN"))
                .isEqualTo("ops.file.upload");
        assertThat(code("POST", "/api/v1/data/import-jobs/00000000-0000-0000-0000-000000000001/experiment-sync"))
                .isEqualTo("data.create");
    }

    @Test
    void mapsQuotationRoutesToQuotationPermissions() {
        var id = "00000000-0000-0000-0000-000000000001";
        assertThat(code("GET", "/api/v1/quotations")).isEqualTo("quotation.view");
        assertThat(code("GET", "/api/v1/quotations/" + id)).isEqualTo("quotation.view");
        assertThat(code("GET", "/api/v1/quotation-imports")).isEqualTo("quotation.view");
        assertThat(code("POST", "/api/v1/quotation-imports")).isEqualTo("quotation.create");
        assertThat(code("POST", "/api/v1/quotation-imports/" + id + "/retry")).isEqualTo("quotation.create");
        assertThat(code("DELETE", "/api/v1/quotations/" + id)).isEqualTo("quotation.delete");
        assertThat(code("DELETE", "/api/v1/quotation-imports/" + id)).isEqualTo("quotation.delete");
        assertThat(codeWithKind("POST", "/api/v1/files/staged", "QUOTATION_SOURCE"))
                .isEqualTo("quotation.create");
    }

    @Test
    void mapsExperimentLifecycleActionsToTheirActualPermissions() {
        var id = "00000000-0000-0000-0000-000000000001";
        assertThat(code("GET", "/api/v1/experiments")).isEqualTo("experiment.view");
        assertThat(code("POST", "/api/v1/experiments")).isEqualTo("eln.create.create");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/draft")).isEqualTo("experiment.update");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/start")).isEqualTo("experiment.update");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/submit-review"))
                .isEqualTo("experiment.submit");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/approve"))
                .isEqualTo("experiment.approve");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/publish"))
                .isEqualTo("eln.view.publish");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/return"))
                .isEqualTo("experiment.review");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/void"))
                .isEqualTo("experiment.review");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/versions"))
                .isEqualTo("experiment.update");
    }

    @Test
    void mapsReportWritesToPageScopedOperations() {
        var id = "00000000-0000-0000-0000-000000000001";

        assertThat(code("POST", "/api/v1/comprehensive-reports/" + id + "/draft"))
                .isEqualTo("research-test.report.view.update");
        assertThat(code("PUT", "/api/v1/comprehensive-reports/" + id + "/rename"))
                .isEqualTo("research-test.report.view.update");
        assertThat(code("POST", "/api/v1/comprehensive-reports/" + id + "/versions"))
                .isEqualTo("research-test.report.view.update");
        assertThat(code("DELETE", "/api/v1/comprehensive-reports/" + id))
                .isEqualTo("research-test.report.view.delete");
        assertThat(code("POST", "/api/v1/comprehensive-reports/" + id + "/submit-review"))
                .isEqualTo("research-test.report.view.submit");
        assertThat(code("POST", "/api/v1/comprehensive-reports/" + id + "/approve"))
                .isEqualTo("research-test.report.view.approve");
        assertThat(code("POST", "/api/v1/comprehensive-reports/" + id + "/return"))
                .isEqualTo("research-test.report.view.approve");
        assertThat(code("POST", "/api/v1/comprehensive-reports/" + id + "/publish"))
                .isEqualTo("research-test.report.view.publish");
        assertThat(code("POST", "/api/v1/comprehensive-reports/" + id + "/archive"))
                .isEqualTo("research-test.report.view.archive");
        assertThat(code("GET", "/api/v1/comprehensive-reports/" + id + "/export"))
                .isEqualTo("research-test.report.view.export");

        // Test standards are configured on the module-level row "research-test.standard".
        assertThat(code("POST", "/api/v1/test-standards/" + id + "/draft"))
                .isEqualTo("research-test.standard.update");
        assertThat(code("DELETE", "/api/v1/test-standards/" + id))
                .isEqualTo("research-test.standard.delete");
        assertThat(code("POST", "/api/v1/test-standards/" + id + "/publish"))
                .isEqualTo("research-test.standard.publish");
    }

    @Test
    void mapsReviewAndPublishToTheirOwnPageColumns() {
        var id = "00000000-0000-0000-0000-000000000001";

        assertThat(code("POST", "/api/v1/experiments/" + id + "/submit-review")).isEqualTo("experiment.submit");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/approve")).isEqualTo("experiment.approve");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/publish")).isEqualTo("eln.view.publish");
        assertThat(code("POST", "/api/v1/experiments/" + id + "/return")).isEqualTo("experiment.review");

        assertThat(code("POST", "/api/v1/template-versions/" + id + "/review/submit")).isEqualTo("template.view.submit");
        assertThat(code("POST", "/api/v1/template-versions/" + id + "/review/approve")).isEqualTo("template.view.approve");
        assertThat(code("POST", "/api/v1/template-versions/" + id + "/review/reject")).isEqualTo("template.view.review");

        assertThat(code("POST", "/api/v1/production-orders/" + id + "/submit")).isEqualTo("production.submit");
        assertThat(code("POST", "/api/v1/production-orders/" + id + "/publish")).isEqualTo("production.view.publish");

        assertThat(code("POST", "/api/v1/workspace-reviews/PROJECT_DOCUMENT/" + id + "/submit"))
                .isEqualTo("project.document.submit");
        assertThat(code("POST", "/api/v1/workspace-reviews/PROJECT_DOCUMENT/" + id + "/approve"))
                .isEqualTo("project.document.approve");
        assertThat(code("POST", "/api/v1/workspace-reviews/QUALITY_RECORD/" + id + "/submit"))
                .isEqualTo("quality.view.submit");
        assertThat(code("POST", "/api/v1/workspace-reviews/QUALITY_RECORD/" + id + "/approve"))
                .isEqualTo("quality.view.approve");
        assertThat(code("POST", "/api/v1/workspace-reviews/QUALITY_RECORD/" + id + "/reject"))
                .isEqualTo("quality.view.approve");
        assertThat(code("POST", "/api/v1/workspace-reviews/PRODUCTION_ORDER/" + id + "/submit"))
                .isEqualTo("production.view.submit");
        assertThat(code("POST", "/api/v1/workspace-reviews/PRODUCTION_ORDER/" + id + "/approve"))
                .isEqualTo("production.view.approve");
        assertThat(code("POST", "/api/v1/workspace-reviews/PRODUCTION_UPLOAD/" + id + "/submit"))
                .isEqualTo("production.view.submit");
        assertThat(code("POST", "/api/v1/workspace-reviews/PRODUCTION_UPLOAD/" + id + "/approve"))
                .isEqualTo("production.view.approve");
        assertThat(code("POST", "/api/v1/workspace-reviews/PRODUCTION_UPLOAD/" + id + "/reject"))
                .isEqualTo("production.view.approve");
        assertThat(code("GET", "/api/v1/workspace-reviews/PRODUCTION_UPLOAD/" + id + "/history"))
                .isEqualTo("production.view.view");
        assertThat(code("POST", "/api/v1/workspace-reviews/KNOWLEDGE_VERSION/" + id + "/submit"))
                .isEqualTo("knowledge.view.submit");
        assertThat(code("POST", "/api/v1/workspace-reviews/KNOWLEDGE_VERSION/" + id + "/approve"))
                .isEqualTo("knowledge.view.approve");
        assertThat(code("POST", "/api/v1/workspace-reviews/KNOWLEDGE_VERSION/" + id + "/reject"))
                .isEqualTo("knowledge.view.approve");
        assertThat(code("GET", "/api/v1/workspace-reviews/KNOWLEDGE_VERSION/" + id + "/history"))
                .isEqualTo("knowledge.view.view");

        assertThat(code("POST", "/api/v1/data/sources/" + id + "/publication/publish")).isEqualTo("data.view.publish");
        assertThat(code("POST", "/api/v1/quality/records/" + id + "/revisions"))
                .isEqualTo("quality.view.update");
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
