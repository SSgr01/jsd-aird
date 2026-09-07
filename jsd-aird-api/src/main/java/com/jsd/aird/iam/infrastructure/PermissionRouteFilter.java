package com.jsd.aird.iam.infrastructure;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.platform.web.RequestTimingHolder;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class PermissionRouteFilter extends OncePerRequestFilter {

    private final AuthorizationService authorization;
    private final ObjectMapper objectMapper;
    private final boolean developmentMode;

    public PermissionRouteFilter(AuthorizationService authorization, ObjectMapper objectMapper,
                                 @Value("${app.identity.development-mode:false}") boolean developmentMode) {
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.developmentMode = developmentMode;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (developmentMode || isExcluded(request)) {
            chain.doFilter(request, response);
            return;
        }
        var actor = ActorContext.current();
        if (actor == null || SecurityContextHolder.getContext().getAuthentication() == null) {
            chain.doFilter(request, response);
            return;
        }
        var permission = permission(request);
        if (permission == null) {
            writeError(response, ApiErrorCode.PERMISSION_DENIED);
            return;
        }
        var started = System.nanoTime();
        var decision = authorization.check(new PermissionCheck(actor.organizationId(), actor.userId(), permission.code(),
                permission.resourceType(), resourceId(request), permission.operation()));
        if (request.getRequestURI().startsWith("/api/v1/assistant/qa")) {
            RequestTimingHolder.put("permissionCheckMs", elapsedMs(started));
        }
        if (!decision.allowed()) {
            writeError(response, "ai.use".equals(permission.code())
                    ? ApiErrorCode.AI_PERMISSION_DENIED : ApiErrorCode.PERMISSION_DENIED);
            return;
        }
        chain.doFilter(request, response);
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private boolean isExcluded(HttpServletRequest request) {
        var path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/iam/");
    }

    private Permission permission(HttpServletRequest request) {
        var path = request.getRequestURI().toLowerCase(java.util.Locale.ROOT);
        var method = request.getMethod();
        var read = Set.of("GET", "HEAD", "OPTIONS").contains(method);

        if (path.startsWith("/api/v1/partners") || path.startsWith("/api/v1/crm") || path.startsWith("/api/v1/business-partners")) {
            return action(method, "customer.view", "customer.create", "customer.update", "customer.delete", "CUSTOMER");
        }

        // Experiment upload jobs are part of the ELN module, but use a separate
        // resource path from /experiments. Keep their API permissions aligned
        // with the upload page and ExperimentImportService checks.
        if (path.startsWith("/api/v1/experiment-imports")) {
            if (read) return permission("experiment.view", "EXPERIMENT", "READ");
            if (method.equals("DELETE")) return permission("experiment.update", "EXPERIMENT", "WRITE");
            if (method.equals("POST")) return permission("experiment.create", "EXPERIMENT", "WRITE");
            return null;
        }

        // Project-detail experiments use the canonical ELN service, but the
        // project detail entry point is governed by project permissions.
        if (path.matches("/api/v1/projects/[0-9a-f-]{36}/experiments(/.*)?")) {
            if (read) return permission("project.view", "PROJECT", "READ");
            if (method.equals("DELETE")) return permission("project.delete", "PROJECT", "WRITE");
            if (path.endsWith("/draft") || path.contains("/versions") || path.endsWith("/publish")
                    || path.endsWith("/start") || path.endsWith("/submit-review")
                    || path.endsWith("/approve") || path.endsWith("/return") || path.endsWith("/void"))
                return permission("project.update", "PROJECT", "WRITE");
            if (method.equals("POST")) return permission("project.create", "PROJECT", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission("project.update", "PROJECT", "WRITE");
            return null;
        }

        // Experiment routes can be nested under a project task, so check them before the project prefix.
        if (path.startsWith("/api/v1/experiments") || path.startsWith("/api/v1/experiment-categories")
                || path.contains("/experiments/") || path.endsWith("/experiments")) {
            if (read) return permission("experiment.view", "EXPERIMENT", "READ");
            if (path.contains("/submit-review")) return permission("experiment.submit", "EXPERIMENT", "WRITE");
            if (path.contains("/approve")) return permission("experiment.approve", "EXPERIMENT", "WRITE");
            if (path.contains("/return") || path.contains("/void")) return permission("experiment.review", "EXPERIMENT", "WRITE");
            if (path.contains("/start")) return permission("experiment.update", "EXPERIMENT", "WRITE");
            if (method.equals("DELETE")) return permission("experiment.delete", "EXPERIMENT", "WRITE");
            if (path.startsWith("/api/v1/experiment-categories") && method.equals("POST"))
                return permission("experiment.create", "EXPERIMENT", "WRITE");
            if (path.startsWith("/api/v1/experiment-categories")) return permission("experiment.update", "EXPERIMENT", "WRITE");
            if (path.endsWith("/draft") || path.contains("/versions")) return permission("experiment.update", "EXPERIMENT", "WRITE");
            if (method.equals("POST")) return permission("experiment.create", "EXPERIMENT", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission("experiment.update", "EXPERIMENT", "WRITE");
            return null;
        }

        // Project details expose read-only views backed by the quality and production upload APIs.
        // Keep their module pages protected by their own permissions, but authorize project-scoped
        // list requests with the same project.view permission as the other detail tabs.
        if (read && path.equals("/api/v1/quality/uploads") && request.getParameter("projectId") != null)
            return permission("project.view", "PROJECT", "READ");
        if (path.startsWith("/api/v1/production-uploads")) {
            if (read) return request.getParameter("projectId") != null
                    ? permission("project.view", "PROJECT", "READ")
                    : permission("production.view", "PRODUCTION", "READ");
            if (path.endsWith("/select-template")) return permission("production.update", "PRODUCTION", "WRITE");
            if (method.equals("DELETE")) return permission("production.delete", "PRODUCTION", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission("production.update", "PRODUCTION", "WRITE");
            if (method.equals("POST")) return permission("production.create", "PRODUCTION", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/projects") || path.startsWith("/api/v1/tasks") || path.startsWith("/api/v1/stages")
                || path.startsWith("/api/v1/project-stages") || path.startsWith("/api/v1/materials")
                || path.startsWith("/api/v1/meetings")) {
            if (path.matches("/api/v1/projects/[0-9a-f-]{36}/references(/.*)?"))
                return read ? permission("project.view", "PROJECT", "READ")
                        : permission("project.assign", "PROJECT", "WRITE");
            if (path.contains("/materials/link") || path.contains("/materials/unlink") || path.contains("/materials/associations"))
                return permission("project.assign", "PROJECT", "WRITE");
            if (path.contains("/meetings/") && path.endsWith("/archive-to-kb"))
                return permission("project.update", "PROJECT", "WRITE");
            if (path.equals("/api/v1/projects/copy")) return permission("project.copy", "PROJECT", "WRITE");
            if (path.equals("/api/v1/projects/export") || path.equals("/api/v1/projects/export.csv"))
                return permission("project.export", "PROJECT", "READ");
            if (read && (path.equals("/api/v1/projects") || path.matches("/api/v1/projects/[0-9a-f-]{36}")
                    || path.matches("/api/v1/projects/[0-9a-f-]{36}/(stages|tasks|materials|documents|meetings|logs|templates|requirements)(/.*)?")
                    || path.matches("/api/v1/(tasks|stages|materials|project-stages|meetings)(/.*)?")))
                return permission("project.view", "PROJECT", "READ");
            if (method.equals("DELETE") && (path.matches("/api/v1/projects/[0-9a-f-]{36}")
                    || path.matches("/api/v1/projects/[0-9a-f-]{36}/(stages|tasks|materials|documents|meetings|requirements)/.*")
                    || path.matches("/api/v1/(tasks|stages|materials)(/[0-9a-f-]{36})?"))) return permission("project.delete", "PROJECT", "WRITE");
            if (method.equals("POST") && (path.equals("/api/v1/projects")
                    || path.matches("/api/v1/projects/[0-9a-f-]{36}/(stages|tasks|documents|meetings|requirements)(/.*)?")
                    || path.matches("/api/v1/(tasks|materials|meetings)(/.*)?"))) return permission("project.create", "PROJECT", "WRITE");
            if ((method.equals("PUT") || method.equals("PATCH")) && (path.matches("/api/v1/projects/[0-9a-f-]{36}")
                    || path.matches("/api/v1/projects/[0-9a-f-]{36}/stages/reorder")
                    || path.matches("/api/v1/projects/[0-9a-f-]{36}/(stages|tasks|materials|documents|meetings|requirements)/.*")
                    || path.matches("/api/v1/(tasks|stages|materials|meetings)/[0-9a-f-]{36}"))) return permission("project.update", "PROJECT", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/project-resource-links")) {
            return read ? permission("project.view", "PROJECT", "READ")
                    : permission("project.assign", "PROJECT", "WRITE");
        }
        if (path.startsWith("/api/v1/project-references")) {
            return permission("project.assign", "PROJECT", "WRITE");
        }

        if (path.startsWith("/api/v1/template-imports")) {
            if (read) return permission("template.view", "TEMPLATE", "READ");
            if (path.endsWith("/retry") || path.contains("/suggestions/"))
                return permission("template.recognition", "TEMPLATE", "WRITE");
            if (method.equals("DELETE")) return permission("template.delete", "TEMPLATE", "WRITE");
            if (path.equals("/api/v1/template-imports")) return permission("template.upload", "TEMPLATE", "WRITE");
            return null;
        }
        if (path.startsWith("/api/v1/template-files")) {
            return method.equals("POST") ? permission("template.upload", "TEMPLATE", "WRITE") : read ? permission("template.view", "TEMPLATE", "READ") : null;
        }
        if (path.startsWith("/api/v1/standard-field-requests")) {
            if (read) return permission("template.view", "TEMPLATE", "READ");
            if (path.endsWith("/approve") || path.endsWith("/reject")) return permission("template.review", "TEMPLATE", "WRITE");
            return method.equals("POST") ? permission("template.create", "TEMPLATE", "WRITE") : null;
        }
        if (path.startsWith("/api/v1/standard-fields")) {
            return read ? permission("template.view", "TEMPLATE", "READ") : null;
        }
        if (path.startsWith("/api/v1/template-categories")) {
            if (read) return permission("template.view", "TEMPLATE", "READ");
            if (method.equals("POST")) return permission("category.create", "TEMPLATE", "WRITE");
            if (method.equals("PUT")) return permission("category.update", "TEMPLATE", "WRITE");
            if (method.equals("DELETE")) return permission("category.delete", "TEMPLATE", "WRITE");
            return null;
        }
        if (path.startsWith("/api/v1/templates") || path.startsWith("/api/v1/template-versions")) {
            if (path.equals("/api/v1/templates/export.csv") || path.contains("/export"))
                return permission("template.export", "TEMPLATE", "READ");
            if (path.contains("/word-document") || path.contains("/word-preview"))
                return permission("template.export", "TEMPLATE", "READ");
            if (path.contains("/copies")) return permission("template.copy", "TEMPLATE", "WRITE");
            if (path.contains("/rollback")) return permission("template.rollback", "TEMPLATE", "WRITE");
            if (path.contains("/review")) return read ? permission("template.view", "TEMPLATE", "READ") : permission("template.review", "TEMPLATE", "WRITE");
            if (path.contains("/recognition") || path.contains("/suggestions") || path.contains("/retry"))
                return read ? permission("template.view", "TEMPLATE", "READ") : permission("template.recognition", "TEMPLATE", "WRITE");
            if (path.contains("/publish")) return permission("template.publish", "TEMPLATE", "WRITE");
            if (path.contains("/retire") || method.equals("DELETE")) return permission("template.delete", "TEMPLATE", "WRITE");
            if (path.contains("/draft") || path.contains("/revisions") || path.contains("/category"))
                return permission("template.update", "TEMPLATE", "WRITE");
            if (method.equals("PATCH") && path.matches("/api/v1/templates/[0-9a-f-]{36}"))
                return permission("template.update", "TEMPLATE", "WRITE");
            if (path.equals("/api/v1/templates")) return method.equals("POST")
                    ? permission("template.create", "TEMPLATE", "WRITE")
                    : read ? permission("template.view", "TEMPLATE", "READ") : null;
            // Batch operations are authorized again by their concrete action in the application service.
            if (path.equals("/api/v1/templates/batch-actions")) return permission("template.view", "TEMPLATE", "READ");
            return read ? permission("template.view", "TEMPLATE", "READ") : null;
        }

        if (path.startsWith("/api/v1/production-orders")) {
            if (path.contains("/export")) return permission("production.export", "PRODUCTION", "READ");
            if (read) return permission("production.view", "PRODUCTION", "READ");
            if (path.endsWith("/submit")) return permission("production.submit", "PRODUCTION", "WRITE");
            if (path.endsWith("/cancel")) return permission("production.cancel", "PRODUCTION", "WRITE");
            if (method.equals("DELETE")) return permission("production.delete", "PRODUCTION", "WRITE");
            if (path.endsWith("/draft") || method.equals("PUT")) return permission("production.update", "PRODUCTION", "WRITE");
            if (method.equals("POST")) return permission("production.create", "PRODUCTION", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/comprehensive-reports") || path.startsWith("/api/v1/test-standards")) {
            var report = path.startsWith("/api/v1/comprehensive-reports");
            var prefix = report ? "research-test.report." : "research-test.standard.";
            if (read && report && path.equals("/api/v1/comprehensive-reports") && request.getParameter("projectId") != null)
                return permission("project.view", "PROJECT", "READ");
            if (path.contains("/export")) return permission(prefix + "export", "RESEARCH_TEST", "READ");
            if (read) return permission(prefix + "view", "RESEARCH_TEST", "READ");
            if (path.endsWith("/submit-review")) return permission(prefix + "submit", "RESEARCH_TEST", "WRITE");
            if (path.endsWith("/approve") || path.endsWith("/return")) return permission(prefix + "approve", "RESEARCH_TEST", "WRITE");
            if (path.endsWith("/publish") || path.endsWith("/archive")) return permission(prefix + "publish", "RESEARCH_TEST", "WRITE");
            if (method.equals("DELETE")) return permission(prefix + "delete", "RESEARCH_TEST", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission(prefix + "update", "RESEARCH_TEST", "WRITE");
            if (path.endsWith("/draft") || path.contains("/versions")) return permission(prefix + "update", "RESEARCH_TEST", "WRITE");
            if (method.equals("POST")) return permission(prefix + "create", "RESEARCH_TEST", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/knowledge")) {
            if (path.equals("/api/v1/knowledge/search") || path.equals("/api/v1/knowledge/assistant"))
                return permission("ai.use", "KNOWLEDGE", "WRITE");
            if (path.contains("/download")) return permission("knowledge.download", "KNOWLEDGE", "READ");
            if (path.contains("/export")) return permission("knowledge.export", "KNOWLEDGE", "READ");
            if (path.contains("/ai-grant") || path.contains("/batch/ai-usage")) return permission("ai.external", "KNOWLEDGE", "WRITE");
            if (path.contains("/reparse")) return permission("knowledge.update", "KNOWLEDGE", "WRITE");
            if (path.contains("/review") || path.endsWith("/reject")) return read ? permission("knowledge.review", "KNOWLEDGE", "READ") : permission("knowledge.review", "KNOWLEDGE", "WRITE");
            if (path.contains("/publish")) return permission("knowledge.publish", "KNOWLEDGE", "WRITE");
            if (read) return permission("knowledge.view", "KNOWLEDGE", "READ");
            if (path.endsWith("/uploads/preflight")) return permission("knowledge.upload", "KNOWLEDGE", "WRITE");
            if (method.equals("DELETE")) return permission("knowledge.delete", "KNOWLEDGE", "WRITE");
            if (method.equals("PUT")) return permission("knowledge.update", "KNOWLEDGE", "WRITE");
            if (method.equals("POST")) return permission(
                    request.getContentType() != null && request.getContentType().toLowerCase(java.util.Locale.ROOT).contains("application/json")
                            ? "knowledge.create" : "knowledge.upload", "KNOWLEDGE", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/data")) {
            if (path.contains("/export")) return permission("data.export", "DATA", "READ");
            if (path.contains("/download")) return permission("data.download", "DATA", "READ");
            if (path.contains("/approve")) return permission("data.approve", "DATA", "WRITE");
            if (path.contains("/commit")) return permission("data.submit", "DATA", "WRITE");
            if (read) return permission("data.view", "DATA", "READ");
            if (method.equals("DELETE")) return permission("data.delete", "DATA", "WRITE");
            if (method.equals("PUT")) return permission("data.update", "DATA", "WRITE");
            if (method.equals("POST")) return permission("data.create", "DATA", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/quality")) {
            if (path.endsWith("/publish")) return permission("quality.publish", "QUALITY", "WRITE");
            if (path.contains("/export")) return permission("quality.export", "QUALITY", "READ");
            if (read) return permission("quality.view", "QUALITY", "READ");
            if (method.equals("DELETE")) return permission("quality.delete", "QUALITY", "WRITE");
            if (method.equals("PUT")) return permission("quality.update", "QUALITY", "WRITE");
            if (method.equals("POST")) {
                if (path.endsWith("/uploads")) return permission("quality.upload", "QUALITY", "WRITE");
                if (path.matches(".*/uploads/[0-9a-f-]{36}/retry")) return permission("quality.upload", "QUALITY", "WRITE");
                if (path.endsWith("/categories")) return permission("quality.create", "QUALITY", "WRITE");
                return permission("quality.update", "QUALITY", "WRITE");
            }
            return null;
        }

        if (path.startsWith("/api/v1/inventory")) {
            if (read) return permission("inventory.view", "INVENTORY", "READ");
            if (path.endsWith("/reverse")) return permission("inventory.reverse", "INVENTORY", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission("inventory.update", "INVENTORY", "WRITE");
            if (method.equals("POST")) return permission("inventory.create", "INVENTORY", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/spc") || path.startsWith("/api/v1/spectrum")) {
            if (path.startsWith("/api/v1/spc/chat")) return permission("ai.use", "AI", "USE");
            if (path.contains("/export")) return permission("spectrum.export", "SPECTRUM", "READ");
            if (path.contains("/download")) return permission("spectrum.download", "SPECTRUM", "READ");
            if (read) return permission("spectrum.view", "SPECTRUM", "READ");
            if (method.equals("DELETE")) return permission("spectrum.delete", "SPECTRUM", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission("spectrum.update", "SPECTRUM", "WRITE");
            if (method.equals("POST")) return permission("spectrum.create", "SPECTRUM", "WRITE");
            return null;
        }
        if (path.startsWith("/api/v1/assistant") || path.startsWith("/api/v1/search"))
            return permission("ai.use", "AI", "USE");
        if (path.startsWith("/api/v1/files/staged")) {
            if ("PROJECT_DOCUMENT".equalsIgnoreCase(request.getParameter("kind")))
                return permission("project.create", "PROJECT", "WRITE");
            if ("TEMPLATE_SOURCE".equalsIgnoreCase(request.getParameter("kind")))
                return permission("template.upload", "TEMPLATE", "WRITE");
            if ("EXPERIMENT_SOURCE".equalsIgnoreCase(request.getParameter("kind")))
                return permission("experiment.create", "EXPERIMENT", "WRITE");
            if ("RESEARCH_TEST_SOURCE".equalsIgnoreCase(request.getParameter("kind")))
                return permission("research-test.report.create", "RESEARCH_TEST", "WRITE");
        }
        if (path.startsWith("/api/v1/files")) {
            if (path.endsWith("/content")) return permission("ops.file.download", "FILE", "READ");
            return method.equals("POST") ? permission("ops.file.upload", "FILE", "WRITE") : read ? permission("ops.file.view", "FILE", "READ") : null;
        }
        return null;
    }

    // Package-visible for contract tests; the filter remains the only runtime caller.
    String permissionCode(HttpServletRequest request) {
        var resolved = permission(request);
        return resolved == null ? null : resolved.code();
    }

    private Permission action(String method, String view, String create, String update, String delete, String resourceType) {
        if (Set.of("GET", "HEAD", "OPTIONS").contains(method)) return permission(view, resourceType, "READ");
        if (method.equals("POST")) return permission(create, resourceType, "WRITE");
        if (method.equals("PUT") || method.equals("PATCH")) return permission(update, resourceType, "WRITE");
        if (method.equals("DELETE")) return permission(delete, resourceType, "WRITE");
        return null;
    }

    private Permission permission(String code, String resourceType, String operation) {
        return new Permission(code, resourceType, operation);
    }

    private UUID resourceId(HttpServletRequest request) {
        var path = request.getRequestURI();
        if (path.startsWith("/api/v1/project-resource-links") || path.startsWith("/api/v1/project-references")) {
            // These endpoints authorize every project in the application service because the project id is in
            // the request body or the reference record, not reliably in the URL.
            return null;
        }
        if ((path.equals("/api/v1/quality/uploads") || path.startsWith("/api/v1/production-uploads"))
                && request.getParameter("projectId") != null) {
            try { return UUID.fromString(request.getParameter("projectId")); } catch (Exception ignored) { return null; }
        }
        var projectMatcher = java.util.regex.Pattern.compile("/api/v1/projects/([0-9a-fA-F-]{36})(?:/|$)").matcher(path);
        if (projectMatcher.find()) {
            try { return UUID.fromString(projectMatcher.group(1)); } catch (Exception ignored) { return null; }
        }
        var segments = path.split("/");
        for (var index = segments.length - 1; index >= 0; index--) {
            try { return UUID.fromString(segments[index]); } catch (Exception ignored) { }
        }
        return null;
    }

    private void writeError(HttpServletResponse response, ApiErrorCode code) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ResponseFactory.error(code, RequestIdHolder.currentOrUnknown()));
    }

    private record Permission(String code, String resourceType, String operation) { }
}
