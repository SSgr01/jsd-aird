package com.jsd.aird.iam.infrastructure;

import java.io.IOException;
import java.util.List;
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
    private final FileBusinessPermissionResolver fileBusinessPermissions;

    public PermissionRouteFilter(AuthorizationService authorization, ObjectMapper objectMapper,
                                 @Value("${app.identity.development-mode:false}") boolean developmentMode) {
        this(authorization, objectMapper, developmentMode, null);
    }

    public PermissionRouteFilter(AuthorizationService authorization, ObjectMapper objectMapper,
                                 boolean developmentMode, FileBusinessPermissionResolver fileBusinessPermissions) {
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.developmentMode = developmentMode;
        this.fileBusinessPermissions = fileBusinessPermissions;
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
        // Legacy /api/v1/ai endpoints were removed in R10. Let Spring's
        // dispatcher produce a normal 404 instead of exposing a compatibility
        // permission or a misleading 403 response.
        if (isRemovedLegacyAi(request)) {
            chain.doFilter(request, response);
            return;
        }
        var actor = ActorContext.current();
        if (actor == null || SecurityContextHolder.getContext().getAuthentication() == null) {
            chain.doFilter(request, response);
            return;
        }
        var filePath = request.getRequestURI();
        if (fileBusinessPermissions != null && "GET".equals(request.getMethod())
                && filePath.matches("/api/v1/files/[0-9a-fA-F-]{36}/(content|preview)")) {
            if (fileBusinessPermissions.mayRead(actor, resourceId(request))) chain.doFilter(request, response);
            else writeError(request, response, ApiErrorCode.PERMISSION_DENIED);
            return;
        }
        var permission = permission(request);
        if (permission == null) {
            writeError(request, response, ApiErrorCode.PERMISSION_DENIED);
            return;
        }
        var started = System.nanoTime();
        var decision = authorization.check(new PermissionCheck(actor.organizationId(), actor.userId(), permission.code(),
                permission.resourceType(), resourceId(request), permission.operation()));
        if (!decision.allowed() && !String.valueOf(decision.source()).startsWith("USER_")) {
            // 维护某条业务记录的项目关联时，除了 project.assign，该记录自身的「编辑」
            // 权限同样有效（数据查看的编辑、知识文档的编辑都算）。
            var sourceEdit = projectResourceEditCheck(actor, request.getMethod(), request.getRequestURI());
            if (sourceEdit != null) decision = authorization.check(sourceEdit);
        }
        if (!decision.allowed() && !String.valueOf(decision.source()).startsWith("USER_")
                && isReferenceWriteRequest(request.getMethod(), request.getRequestURI())) {
            // 参考资料的记录 id 在请求体里，过滤器只能先按候选的「编辑」码放行，
            // 具体是哪条记录由 ProjectResourceController 按 resourceId 复核。
            for (var code : REFERENCE_SOURCE_EDIT_CODES) {
                decision = authorization.check(new PermissionCheck(actor.organizationId(), actor.userId(),
                        code, "PROJECT", null, "WRITE"));
                if (decision.allowed()) break;
            }
        }
        if (!decision.allowed() && !String.valueOf(decision.source()).startsWith("USER_")) {
            var pageCode = projectPageOperation(request.getMethod(), request.getRequestURI());
            if (pageCode == null) pageCode = systemDictionaryOperation(request.getMethod(), request.getRequestURI());
            if (pageCode == null) pageCode = categoryFallbackOperation(request.getMethod(), request.getRequestURI());
            if (pageCode == null) pageCode = researchTestTemplateOperation(request.getMethod(), request.getRequestURI());
            if (pageCode == null) pageCode = spectrumChartPageOperation(request.getMethod(), request.getRequestURI());
            if (pageCode != null && !pageCode.equals(permission.code())) {
                decision = authorization.check(new PermissionCheck(actor.organizationId(), actor.userId(), pageCode,
                        permission.resourceType(), resourceId(request), permission.operation()));
            }
        }
        if (request.getRequestURI().startsWith("/api/v1/assistant/qa")) {
            RequestTimingHolder.put("permissionCheckMs", elapsedMs(started));
        }
        if (!decision.allowed()) {
            writeError(request, response, "ai.use".equals(permission.code())
                    ? ApiErrorCode.AI_PERMISSION_DENIED : ApiErrorCode.PERMISSION_DENIED);
            return;
        }
        chain.doFilter(request, response);
    }

    static String researchTestTemplateOperation(String method, String path) {
        if ("GET".equals(method) && (path.startsWith("/api/v1/experiment-imports")
                || path.startsWith("/api/v1/data/experiment-import-jobs")))
            return "eln.upload.view";
        if ("GET".equals(method) && (path.equals("/api/v1/template-imports")
                || path.matches("/api/v1/template-imports/[0-9a-fA-F-]{36}(/.*)?")))
            return "template.view";
        if ("POST".equals(method) && (path.equals("/api/v1/comprehensive-reports")
                || path.equals("/api/v1/comprehensive-reports/generate")))
            return "research-test.report.view.create";
        if ("POST".equals(method) && path.equals("/api/v1/test-standards"))
            return "research-test.standard.create";
        if ("GET".equals(method)
                && (path.startsWith("/api/v1/templates") || path.startsWith("/api/v1/template-versions"))
                && !path.contains("/export") && !path.contains("/word-document")
                && !path.contains("/word-preview"))
            return "template.view.view";
        return null;
    }

    static String projectPageOperation(String method, String path) {
        var project = "/api/v1/projects/[0-9a-fA-F-]{36}";
        var id = "[0-9a-fA-F-]{36}";
        if ("POST".equals(method) && path.matches("/api/v1/projects/import(/commit)?")) return "project.list.create";
        if ("POST".equals(method) && path.equals("/api/v1/projects/copy")) return "project.list.create";
        if ("GET".equals(method) && path.matches("/api/v1/projects/export(\\.csv)?")) return "project.list.view";
        if ("GET".equals(method) && (path.equals("/api/v1/projects") || path.matches(project))) return "project.list.view";
        if ("GET".equals(method)) {
            if (path.matches("/api/v1/stages/" + id + "/tasks(/.*)?")
                    || path.matches("/api/v1/tasks(/.*)?")
                    || path.matches(project + "/(tasks|experiments|templates)(/.*)?")) return "project.task.view";
            if (path.equals("/api/v1/project-stages") || path.matches("/api/v1/stages/" + id + "(/.*)?")
                    || path.matches(project + "/stages(/.*)?")) return "project.stage.view";
            if (path.matches(project + "/documents(/.*)?")) return "project.document.view";
            if (path.matches(project + "/requirements(/.*)?")) return "project.customer-needs.view";
            if (path.matches(project + "/references(/.*)?")) return "project.reference.view";
            if (path.matches("/api/v1/meetings/" + id + "(/.*)?")
                    || path.matches(project + "/meetings(/.*)?")) return "project.meeting.view";
            if (path.matches(project + "/logs(/.*)?")) return "project.log.view";
            if (path.matches(project + "/materials(/.*)?")
                    || path.matches("/api/v1/materials(/.*)?")) return "project.materials.view";
        }
        if (path.matches(project + "/experiments(/.*)?")) {
            if ("POST".equals(method) && (path.matches(project + "/experiments") || path.endsWith("/copy"))) return "eln.create.create";
            if ("DELETE".equals(method)) return "eln.view.delete";
            if ("POST".equals(method) && (path.endsWith("/approve") || path.endsWith("/return") || path.endsWith("/void"))) return "eln.view.approve";
            if ("POST".equals(method) && path.endsWith("/publish")) return "eln.view.approve";
            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) return "eln.view.update";
        }
        if (path.matches(project + "/documents(/.*)?")) {
            if ("GET".equals(method) && path.endsWith("/export")) return "project.document.view";
            if ("POST".equals(method) && (path.endsWith("/approve") || path.endsWith("/review"))) return "project.document.approve";
            if ("POST".equals(method) && path.endsWith("/publish")) return "project.document.publish";
            if ("POST".equals(method) && (path.matches(project + "/documents") || path.endsWith("/import"))) return "project.document.create";
            if ("PUT".equals(method) && path.endsWith("/content")) return "project.document.update";
            if ("DELETE".equals(method)) return "project.document.delete";
        }
        if (path.matches(project + "/requirements(/.*)?")) {
            if ("POST".equals(method)) return "project.customer-needs.create";
            if ("PUT".equals(method)) return "project.customer-needs.update";
            if ("DELETE".equals(method)) return "project.customer-needs.delete";
        }
        if (path.matches(project + "/meetings(/.*)?") || path.matches("/api/v1/meetings/[0-9a-fA-F-]{36}")) {
            if ("POST".equals(method) && path.endsWith("/archive-to-kb")) return "project.meeting.publish";
            if ("POST".equals(method)) return "project.meeting.create";
            if ("PUT".equals(method)) return "project.meeting.update";
            if ("DELETE".equals(method)) return "project.meeting.delete";
        }
        if (path.matches(project + "/stages(/.*)?") || path.matches("/api/v1/stages/[0-9a-fA-F-]{36}")) {
            if ("POST".equals(method)) return "project.stage.create";
            if ("PUT".equals(method)) return "project.stage.update";
            if ("DELETE".equals(method)) return "project.stage.delete";
        }
        if (path.matches(project + "/tasks(/.*)?") || path.matches("/api/v1/tasks/[0-9a-fA-F-]{36}")) {
            if ("POST".equals(method)) return "project.task.create";
            if ("PUT".equals(method)) return "project.task.update";
            if ("DELETE".equals(method)) return "project.task.delete";
        }
        if (path.matches("/api/v1/project-references/[0-9a-fA-F-]{36}(/restore)?")
                && ("POST".equals(method) || "DELETE".equals(method))) return "project.reference.update";
        if (path.equals("/api/v1/projects")) return "POST".equals(method) ? "project.list.create" : null;
        if (path.matches(project)) {
            if ("PUT".equals(method)) return "project.list.update";
            if ("DELETE".equals(method)) return "project.list.delete";
        }
        return null;
    }

    static String systemDictionaryOperation(String method, String path) {
        if (path.equals("/api/v1/standard-fields") && method.equals("GET")) return "system.dictionary.view";
        if (path.equals("/api/v1/standard-field-requests")) {
            if (method.equals("GET")) return "system.dictionary.view";
            if (method.equals("POST")) return "system.dictionary.create";
        }
        if (path.matches("/api/v1/standard-field-requests/[0-9a-fA-F-]{36}/(approve|reject)") && method.equals("POST"))
            return "system.dictionary.update";
        return null;
    }

    private static final java.util.regex.Pattern PROJECT_RESOURCE_LINK =
            java.util.regex.Pattern.compile("/api/v1/project-resource-links/([A-Z_]+)/([0-9a-fA-F-]{36})");

    /**
     * 记录自身的「编辑」权限允许维护该记录的项目关联：数据查看（DATA_IMPORT_JOB）与
     * 知识文档（KNOWLEDGE_DOCUMENT）。数据范围按记录 ID 计算，编辑他人创建的记录
     * 仍会被 SELF/CREATOR 范围拒绝。
     */
    /**
     * The chart list gates edit/delete on the page operations
     * {@code spectrum.view.update/delete}; keep them working for roles that were
     * only granted the page operation.
     */
    static String spectrumChartPageOperation(String method, String path) {
        var lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("/api/v1/spc/charts/") && !lower.startsWith("/api/v1/spectrum/charts/")) return null;
        if ("DELETE".equals(method)) return "spectrum.view.delete";
        if ("PUT".equals(method) || "PATCH".equals(method)) return "spectrum.view.update";
        return null;
    }

    /** Whether the request writes project references, whose record sits in the JSON body. */
    static boolean isReferenceWriteRequest(String method, String path) {
        return "POST".equals(method) && "/api/v1/project-references".equals(path);
    }

    /**
     * Record level edit codes that may maintain that record's project references.
     * They are only a coarse gate here; the controller rechecks the concrete
     * resource id carried by the request body.
     */
    static final List<String> REFERENCE_SOURCE_EDIT_CODES = List.of(
            "spectrum.update", "spectrum.view.update",
            "knowledge.update", "knowledge.view.update",
            "data.update", "data.view.update");

    static PermissionCheck projectResourceEditCheck(Actor actor, String method, String path) {
        if ("GET".equals(method) || !path.startsWith("/api/v1/project-resource-links/")) return null;
        var matcher = PROJECT_RESOURCE_LINK.matcher(path);
        if (!matcher.matches()) return null;
        var resourceId = UUID.fromString(matcher.group(2));
        return switch (matcher.group(1)) {
            case "DATA_IMPORT_JOB" -> new PermissionCheck(actor.organizationId(), actor.userId(),
                    "data.view.update", "DATA", resourceId, "WRITE");
            case "KNOWLEDGE_DOCUMENT" -> new PermissionCheck(actor.organizationId(), actor.userId(),
                    "knowledge.update", "KNOWLEDGE", resourceId, "WRITE");
            default -> null;
        };
    }

    /**
     * Category maintenance lives in its own permission module on every page
     * that shows category cards, so the endpoints are mapped to
     * {@code <module>.category.*} instead of the record's own create/update/delete.
     */
    static String categoryModule(String path) {
        var lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("/api/v1/template-categories")) return "template";
        if (lower.startsWith("/api/v1/experiment-categories")) return "eln";
        if (lower.startsWith("/api/v1/quality/categories")) return "quality";
        if (lower.startsWith("/api/v1/spc/categories") || lower.startsWith("/api/v1/spectrum/categories"))
            return "spectrum";
        if (lower.startsWith("/api/v1/knowledge/categories")) return "knowledge";
        if (lower.startsWith("/api/v1/data/categories")) return "data";
        return null;
    }

    static String categoryOperation(String method, String path) {
        var module = categoryModule(path);
        if (module == null) return null;
        var read = Set.of("GET", "HEAD", "OPTIONS").contains(method);

        if (read) return module + ".category.view";
        if ("DELETE".equals(method)) return module + ".category.delete";
        if ("PUT".equals(method) || "PATCH".equals(method)) return module + ".category.update";
        if ("POST".equals(method)) return module + ".category.create";
        return null;
    }

    /**
     * Previously granted category permissions keep working: the atomic module
     * codes (and the legacy {@code category.*} codes for templates) are accepted
     * when the new module code was not granted.
     */
    static String categoryFallbackOperation(String method, String path) {
        var module = categoryModule(path);
        if (module == null) return null;
        var read = Set.of("GET", "HEAD", "OPTIONS").contains(method);
        if (read) return "eln".equals(module) ? "experiment.view" : module + ".view";
        var action = "DELETE".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                ? "update" : "POST".equals(method) ? "create" : null;
        if (action == null) return null;
        if ("template".equals(module)) return "category." + action;
        if ("eln".equals(module)) return "experiment." + action;
        return module + "." + action;
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private boolean isExcluded(HttpServletRequest request) {
        var path = request.getRequestURI();
        // This exact help endpoint is available to every authenticated user.
        // SecurityConfig still enforces session authentication and CSRF.
        return ("POST".equals(request.getMethod()) && "/api/v1/operation-assistant/qa/stream".equals(path))
                || !path.startsWith("/api/v1/") || path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/iam/");
    }

    private boolean isRemovedLegacyAi(HttpServletRequest request) {
        var path = request.getRequestURI().toLowerCase(java.util.Locale.ROOT);
        return path.startsWith("/api/v1/ai/") && !path.startsWith("/api/v1/ai/rnd/");
    }

    private Permission permission(HttpServletRequest request) {
        var path = request.getRequestURI().toLowerCase(java.util.Locale.ROOT);
        var method = request.getMethod();
        var read = Set.of("GET", "HEAD", "OPTIONS").contains(method);

        if (path.startsWith("/api/v1/agents/")) return permission("ai.use", "AI", read ? "READ" : "WRITE");

        if (path.startsWith("/api/v1/workspace-reviews/")) {
            var parts = path.split("/");
            if (parts.length < 6) return null;
            var prefix = switch (parts[4]) {
                case "project_document" -> "project.document";
                case "quality_record" -> "quality.view";
                case "production_order" -> "production.view";
                case "production_upload" -> "production.view";
                case "knowledge_version" -> "knowledge.view";
                default -> null;
            };
            if (prefix == null) return null;
            var action = read ? "view" : path.endsWith("/submit") ? "submit"
                    : path.endsWith("/approve") || ((parts[4].equals("quality_record") || parts[4].equals("production_upload") || parts[4].equals("knowledge_version")) && path.endsWith("/reject")) ? "approve" : null;
            return action == null ? null : permission(prefix + "." + action,
                    parts[4].equals("project_document") ? "PROJECT_DOCUMENT" : parts[4].equals("quality_record") ? "QUALITY" : parts[4].equals("knowledge_version") ? "KNOWLEDGE" : "PRODUCTION",
                    read ? "READ" : "WRITE");
        }

        // Category maintenance is configured as its own module row without a data
        // scope, so it is resolved before the owning record's permission block.
        var categoryCode = categoryOperation(method, path);
        if (categoryCode != null) {
            return permission(categoryCode, "CATEGORY", read ? "READ" : "WRITE");
        }

        if (path.startsWith("/api/v1/business-partners")) {
            var prefix = path.contains("/contacts") ? "customer.overview" : "customer.list";
            return action(method, prefix + ".view", prefix + ".create", prefix + ".update",
                    prefix + ".delete", "CUSTOMER");
        }
        if (path.startsWith("/api/v1/crm/requirements")) {
            return action(method, "customer.needs.view", "customer.needs.create", "customer.needs.update",
                    "customer.needs.delete", "CUSTOMER");
        }
        if (path.startsWith("/api/v1/crm/communications")) {
            return action(method, "customer.followup.view", "customer.followup.create", "customer.followup.update",
                    "customer.followup.delete", "CUSTOMER");
        }
        if (path.startsWith("/api/v1/partners") || path.startsWith("/api/v1/crm")) {
            return action(method, "customer.view", "customer.create", "customer.update", "customer.delete", "CUSTOMER");
        }

        // Experiment upload jobs are part of the ELN module, but use a separate
        // resource path from /experiments. Keep their API permissions aligned
        // with the upload page and ExperimentImportService checks.
        if (path.startsWith("/api/v1/experiment-imports")) {
            if (read) return permission("eln.upload.view", "EXPERIMENT_IMPORT", "READ");
            if (method.equals("DELETE")) return permission("eln.upload.delete", "EXPERIMENT", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH"))
                return permission("eln.upload.create", "EXPERIMENT", "WRITE");
            if (method.equals("POST") && path.endsWith("/retry"))
                return permission("eln.upload.retry", "EXPERIMENT", "WRITE");
            if (method.equals("POST")) return permission("eln.upload.create", "EXPERIMENT", "WRITE");
            return null;
        }

        // Project-detail experiments use the canonical ELN service, but the
        // project detail entry point is governed by project permissions.
        if (path.matches("/api/v1/projects/[0-9a-f-]{36}/experiments(/.*)?")) {
            if (read) return permission("project.task.view", "PROJECT", "READ");
            var pageCode = projectPageOperation(method, path);
            if (pageCode != null) return permission(pageCode, "PROJECT", "WRITE");
            return null;
        }

        // Experiment routes can be nested under a project task, so check them before the project prefix.
        if (path.startsWith("/api/v1/experiments")
                || path.contains("/experiments/") || path.endsWith("/experiments")) {
            if (method.equals("POST") && path.matches("/api/v1/experiments/[0-9a-f-]{36}/template-drafts"))
                return permission("experiment.view", "EXPERIMENT", "READ");
            if (method.equals("POST") && path.equals("/api/v1/experiments"))
                return permission("eln.create.create", "EXPERIMENT", "WRITE");
            if (read) return permission("experiment.view", "EXPERIMENT", "READ");
            if (path.contains("/submit-review")) return permission("experiment.submit", "EXPERIMENT", "WRITE");
            if (path.contains("/approve")) return permission("experiment.approve", "EXPERIMENT", "WRITE");
            // Publishing is granted by its own page column; keep approve as a legacy alias.
            if (path.endsWith("/publish")) return permission("eln.view.publish", "EXPERIMENT", "WRITE");
            if (path.contains("/return") || path.contains("/void")) return permission("experiment.review", "EXPERIMENT", "WRITE");
            if (path.contains("/start")) return permission("experiment.update", "EXPERIMENT", "WRITE");
            if (method.equals("DELETE")) return permission("experiment.delete", "EXPERIMENT", "WRITE");
            if (path.endsWith("/draft") || path.contains("/versions")) return permission("experiment.update", "EXPERIMENT", "WRITE");
            if (method.equals("POST")) return permission("experiment.create", "EXPERIMENT", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission("experiment.update", "EXPERIMENT", "WRITE");
            return null;
        }

        // Project details expose read-only views backed by the quality and production upload APIs.
        // Keep their module pages protected by their own permissions, but authorize project-scoped
        // list requests with the same project.view permission as the other detail tabs.
        if (read && path.equals("/api/v1/quality/uploads") && request.getParameter("projectId") != null)
            return permission("project.materials.view", "PROJECT", "READ");
        if (path.startsWith("/api/v1/production-uploads")) {
            if (read) return request.getParameter("projectId") != null
                    ? permission("project.materials.view", "PROJECT", "READ")
                    : permission("production.upload.view", "PRODUCTION_UPLOAD", "READ");
            if (path.endsWith("/retry")) return permission("production.upload.create", "PRODUCTION", "WRITE");
            if (path.equals("/api/v1/production-uploads") && method.equals("POST"))
                return permission("production.upload.create", "PRODUCTION", "WRITE");
            if (path.endsWith("/select-template")) return permission("production.update", "PRODUCTION", "WRITE");
            if (method.equals("DELETE")) return permission("production.upload.delete", "PRODUCTION", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission("production.update", "PRODUCTION", "WRITE");
            if (method.equals("POST")) return permission("production.create", "PRODUCTION", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/projects") || path.startsWith("/api/v1/tasks") || path.startsWith("/api/v1/stages")
                || path.startsWith("/api/v1/project-stages") || path.startsWith("/api/v1/materials")
                || path.startsWith("/api/v1/meetings")) {
            if (path.matches("/api/v1/projects/[0-9a-f-]{36}/references(/.*)?"))
                return read ? permission("project.reference.view", "PROJECT", "READ")
                        : permission("project.assign", "PROJECT", "WRITE");
            if (path.contains("/materials/link") || path.contains("/materials/unlink") || path.contains("/materials/associations"))
                return permission("project.assign", "PROJECT", "WRITE");
            if (path.contains("/meetings/") && path.endsWith("/archive-to-kb"))
                return permission("project.update", "PROJECT", "WRITE");
            if (path.equals("/api/v1/projects/import") || path.equals("/api/v1/projects/import/commit"))
                return permission("project.create", "PROJECT", "WRITE");
            if (path.equals("/api/v1/projects/copy")) return permission("project.copy", "PROJECT", "WRITE");
            if (path.equals("/api/v1/projects/export") || path.equals("/api/v1/projects/export.csv"))
                return permission("project.export", "PROJECT", "READ");
            if (read) {
                var pageCode = projectPageOperation("GET", path);
                if (pageCode != null) return permission(pageCode, "PROJECT", "READ");
            }
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
            if (path.contains("/knowledge_document/"))
                return permission("knowledge.update", "KNOWLEDGE", read ? "READ" : "WRITE");
            return read ? permission("project.view", "PROJECT", "READ")
                    : permission("project.assign", "PROJECT", "WRITE");
        }
        if (path.startsWith("/api/v1/project-references")) {
            return permission("project.assign", "PROJECT", "WRITE");
        }

        if (path.startsWith("/api/v1/template-imports")) {
            if (read) return permission("template.upload.view", "TEMPLATE_IMPORT", "READ");
            if (path.endsWith("/retry"))
                return permission("template.upload.create", "TEMPLATE_IMPORT", "WRITE");
            if (path.contains("/suggestions/"))
                return permission("template.recognition", "TEMPLATE_IMPORT", "WRITE");
            if (method.equals("DELETE")) return permission("template.upload.delete", "TEMPLATE_IMPORT", "WRITE");
            if (path.equals("/api/v1/template-imports")) return permission("template.upload", "TEMPLATE_IMPORT", "WRITE");
            return null;
        }
        if (path.startsWith("/api/v1/template-files")) {
            return method.equals("POST") ? permission("template.upload", "TEMPLATE", "WRITE") : read ? permission("template.view", "TEMPLATE", "READ") : null;
        }
        if (path.startsWith("/api/v1/standard-field-requests")) {
            if (read) return permission("template.view", "TEMPLATE", "READ");
            if (path.endsWith("/approve") || path.endsWith("/reject")) return permission("system.dictionary.update", "TEMPLATE", "WRITE");
            return method.equals("POST") ? permission("template.create", "TEMPLATE", "WRITE") : null;
        }
        if (path.startsWith("/api/v1/standard-fields")) {
            return read ? permission("template.view", "TEMPLATE", "READ") : null;
        }
        if (path.startsWith("/api/v1/templates") || path.startsWith("/api/v1/template-versions")) {
            if (path.equals("/api/v1/templates/export.csv") || path.contains("/export"))
                return permission("template.export", "TEMPLATE", "READ");
            if (path.contains("/word-document") || path.contains("/word-preview"))
                return permission("template.export", "TEMPLATE", "READ");
            if (path.contains("/copies")) return permission("template.copy", "TEMPLATE", "WRITE");
            if (path.contains("/rollback")) return permission("template.rollback", "TEMPLATE", "WRITE");
            if (path.contains("/review")) {
                if (read) return permission("template.view.view", "TEMPLATE", "READ");
                // Submit/approve/reject are separate page columns; the bare review
                // endpoint stays on the module action for legacy grants.
                if (path.endsWith("/submit")) return permission("template.view.submit", "TEMPLATE", "WRITE");
                if (path.endsWith("/approve")) return permission("template.view.approve", "TEMPLATE", "WRITE");
                if (path.endsWith("/reject")) return permission("template.view.review", "TEMPLATE", "WRITE");
                return permission("template.review", "TEMPLATE", "WRITE");
            }
            if (path.contains("/recognition") || path.contains("/suggestions") || path.contains("/retry"))
                return read ? permission("template.view.view", "TEMPLATE", "READ") : permission("template.recognition", "TEMPLATE", "WRITE");
            if (path.contains("/publish")) return permission("template.publish", "TEMPLATE", "WRITE");
            if (path.contains("/retire") || method.equals("DELETE")) return permission("template.delete", "TEMPLATE", "WRITE");
            if (path.contains("/draft") || path.contains("/revisions") || path.contains("/category"))
                return permission("template.update", "TEMPLATE", "WRITE");
            if (method.equals("PATCH") && path.matches("/api/v1/templates/[0-9a-f-]{36}"))
                return permission("template.update", "TEMPLATE", "WRITE");
            if (path.equals("/api/v1/templates")) return method.equals("POST")
                    ? permission("template.create", "TEMPLATE", "WRITE")
                    : read ? permission("template.view.view", "TEMPLATE", "READ") : null;
            // Batch operations are authorized again by their concrete action in the application service.
            if (path.equals("/api/v1/templates/batch-actions")) return permission("template.view.view", "TEMPLATE", "READ");
            return read ? permission("template.view.view", "TEMPLATE", "READ") : null;
        }

        // 报价记录：上传任务与查看列表同属一个业务模块，读写权限独立授予。
        if (path.startsWith("/api/v1/quotation-imports") || path.startsWith("/api/v1/quotations")) {
            if (read) return permission("quotation.view", "QUOTATION", "READ");
            if (path.endsWith("/rollback")) return permission("quotation.edit", "QUOTATION", "WRITE");
            if (path.endsWith("/review/submit")) return permission("quotation.submit", "QUOTATION", "WRITE");
            if (path.endsWith("/review/approve") || path.endsWith("/review/reject"))
                return permission("quotation.review", "QUOTATION", "WRITE");
            if (method.equals("DELETE")) return permission("quotation.delete", "QUOTATION", "WRITE");
            if (method.equals("PUT")) return permission("quotation.edit", "QUOTATION", "WRITE");
            if (method.equals("POST")) return permission("quotation.create", "QUOTATION", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/production-orders")) {
            if (path.contains("/export")) return permission("production.export", "PRODUCTION", "READ");
            if (read) return permission("production.view", "PRODUCTION", "READ");
            if (path.endsWith("/submit")) return permission("production.submit", "PRODUCTION", "WRITE");
            if (path.endsWith("/publish")) return permission("production.view.publish", "PRODUCTION", "WRITE");
            if (path.endsWith("/cancel")) return permission("production.cancel", "PRODUCTION", "WRITE");
            if (method.equals("DELETE")) return permission("production.delete", "PRODUCTION", "WRITE");
            if (path.endsWith("/draft") || method.equals("PUT")) return permission("production.update", "PRODUCTION", "WRITE");
            if (method.equals("POST")) return permission("production.create", "PRODUCTION", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/comprehensive-reports") || path.startsWith("/api/v1/test-standards")) {
            var report = path.startsWith("/api/v1/comprehensive-reports");
            var prefix = report ? "research-test.report." : "research-test.standard.";
            // Report actions are granted on the page row "research-test.report.view"
            // (see PermissionModelCatalog), so the operation codes must be page-scoped.
            // Test standards are granted on the module-level row "research-test.standard".
            var operationPrefix = report ? "research-test.report.view." : prefix;
            var uploadPrefix = prefix + "upload.";
            if (path.contains("/uploads")) {
                if (read) return permission(uploadPrefix + "view", "RESEARCH_TEST_UPLOAD", "READ");
                if (method.equals("DELETE")) return permission(uploadPrefix + "delete", "RESEARCH_TEST", "WRITE");
                if (path.endsWith("/retry")) return permission(uploadPrefix + "create", "RESEARCH_TEST", "WRITE");
                if (method.equals("POST")) return permission(uploadPrefix + "create", "RESEARCH_TEST", "WRITE");
            }
            if (path.equals("/api/v1/comprehensive-reports/next-number"))
                return permission(operationPrefix + "create", "RESEARCH_TEST", "WRITE");
            if (read && report && path.equals("/api/v1/comprehensive-reports") && request.getParameter("projectId") != null)
                return permission("project.view", "PROJECT", "READ");
            if (path.contains("/export")) return permission(operationPrefix + "export", "RESEARCH_TEST", "READ");
            if (read) return permission(prefix + "view", "RESEARCH_TEST", "READ");
            if (path.endsWith("/submit-review")) return permission(operationPrefix + "submit", "RESEARCH_TEST", "WRITE");
            if (path.endsWith("/approve") || path.endsWith("/return")) return permission(operationPrefix + "approve", "RESEARCH_TEST", "WRITE");
            if (path.endsWith("/archive")) return permission(operationPrefix + "archive", "RESEARCH_TEST", "WRITE");
            if (path.endsWith("/publish")) return permission(operationPrefix + "publish", "RESEARCH_TEST", "WRITE");
            if (method.equals("DELETE")) return permission(operationPrefix + "delete", "RESEARCH_TEST", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission(operationPrefix + "update", "RESEARCH_TEST", "WRITE");
            if (path.endsWith("/draft") || path.contains("/versions")) return permission(operationPrefix + "update", "RESEARCH_TEST", "WRITE");
            if (method.equals("POST")) return permission(operationPrefix + "create", "RESEARCH_TEST", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/knowledge")) {
            if (path.equals("/api/v1/knowledge/search") || path.equals("/api/v1/knowledge/assistant"))
                return permission("ai.use", "KNOWLEDGE", "WRITE");
            if (path.startsWith("/api/v1/knowledge/upload-tasks")) {
                if (read) return permission("knowledge.view", "KNOWLEDGE", "READ");
                if (method.equals("POST") || method.equals("PUT") || method.equals("DELETE"))
                    return permission("knowledge.upload", "KNOWLEDGE", "WRITE");
                return null;
            }
            if (path.contains("/download")) return permission("knowledge.download", "KNOWLEDGE", "READ");
            if (path.contains("/export")) return permission("knowledge.export", "KNOWLEDGE", "READ");
            if (path.contains("/ai-grant") || path.contains("/batch/ai-usage")) return permission("ai.external", "KNOWLEDGE", "WRITE");
            if (path.contains("/reparse")) return permission("knowledge.update", "KNOWLEDGE", "WRITE");
            // 校对保存属于知识文档编辑，驳回属于审核：与权限配置页「知识库查看」
            // 行发放的页面操作码保持一致。
            if (path.endsWith("/reject")) return permission("knowledge.view.approve", "KNOWLEDGE", "WRITE");
            if (path.contains("/review")) return read ? permission("knowledge.view", "KNOWLEDGE", "READ") : permission("knowledge.view.update", "KNOWLEDGE", "WRITE");
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

        if (path.startsWith("/api/v1/data/experiment-import-jobs")) {
            if (read) return permission("eln.upload.view", "DATA", "READ");
            if (method.equals("DELETE")) return permission("eln.upload.delete", "EXPERIMENT", "WRITE");
            if (method.equals("POST") && path.endsWith("/retry"))
                return permission("eln.upload.retry", "EXPERIMENT", "WRITE");
            if (path.contains("/commit") || path.contains("/experiment-sync"))
                return permission("eln.upload.create", "EXPERIMENT", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH"))
                return permission("eln.upload.create", "EXPERIMENT", "WRITE");
            if (method.equals("POST")) return permission("eln.upload.create", "EXPERIMENT", "WRITE");
            return null;
        }
        if (path.startsWith("/api/v1/data")) {
            if (path.contains("/export")) return permission("data.export", "DATA", "READ");
            if (path.contains("/download")) return permission("data.download", "DATA", "READ");
            if (path.contains("/approve")) return permission("data.approve", "DATA", "WRITE");
            if (path.endsWith("/publication/publish")) return permission("data.view.publish", "DATA", "WRITE");
            if (path.endsWith("/publication/disable") || path.endsWith("/publication/restore"))
                return permission("data.update", "DATA", "WRITE");
            if (path.endsWith("/publication/submit") || path.endsWith("/publication/withdraw"))
                return permission("data.submit", "DATA", "WRITE");
            if (path.endsWith("/publication/confirm") || path.endsWith("/publication/revise"))
                return permission("data.update", "DATA", "WRITE");
            if (path.contains("/commit")) return permission("data.submit", "DATA", "WRITE");
            if (path.matches("/api/v1/data/recognition-jobs/[0-9a-f-]{36}/finalize"))
                return permission("data.submit", "DATA", "WRITE");
            if (read) return permission("data.view", "DATA", "READ");
            if (method.equals("DELETE")) return permission("data.delete", "DATA", "WRITE");
            if (method.equals("PUT")) return permission("data.update", "DATA", "WRITE");
            if (method.equals("POST")) return permission(
                    path.matches("/api/v1/data/recognition-jobs/[0-9a-f-]{36}/.*")
                            ? "data.update" : "data.create", "DATA", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/quality")) {
            if (path.contains("/uploads")) {
                if (read) return permission("quality.upload.view", "QUALITY_UPLOAD", "READ");
                if (method.equals("DELETE")) return permission("quality.upload.delete", "QUALITY", "WRITE");
                if (path.endsWith("/retry")) return permission("quality.upload.create", "QUALITY", "WRITE");
                if (method.equals("POST")) return permission("quality.upload.create", "QUALITY", "WRITE");
            }
            if(path.equals("/api/v1/quality/records/delete")) return permission("quality.delete","QUALITY","WRITE");
            if (path.endsWith("/revisions")) return permission("quality.view.update", "QUALITY", "WRITE");
            if (path.endsWith("/publish")) return permission("quality.publish", "QUALITY", "WRITE");
            if (path.contains("/export")) return permission("quality.export", "QUALITY", "READ");
            if (read) return permission("quality.view", "QUALITY", "READ");
            if (method.equals("DELETE")) return permission("quality.delete", "QUALITY", "WRITE");
            if (method.equals("PUT")) return permission("quality.update", "QUALITY", "WRITE");
            if (method.equals("POST")) return permission("quality.update", "QUALITY", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/inventory")) {
            if (read) return permission("inventory.view", "INVENTORY", "READ");
            if (path.endsWith("/reverse")) return permission("inventory.reverse", "INVENTORY", "WRITE");
            if (method.equals("DELETE")) return permission("inventory.update", "INVENTORY", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission("inventory.update", "INVENTORY", "WRITE");
            if (method.equals("POST")) return permission("inventory.create", "INVENTORY", "WRITE");
            return null;
        }

        if (path.startsWith("/api/v1/spc") || path.startsWith("/api/v1/spectrum")) {
            if (method.equals("DELETE") && path.matches("/api/v1/spc/uploads/attempts/[0-9a-fA-F-]{36}"))
                return permission("spectrum.upload.delete", "SPECTRUM", "WRITE");
            if (path.startsWith("/api/v1/spc/chat")) return permission("ai.use", "AI", "USE");
            if (path.contains("/export")) return permission("spectrum.export", "SPECTRUM", "READ");
            if (path.contains("/download")) return permission("spectrum.download", "SPECTRUM", "READ");
            if (read) return permission("spectrum.view", "SPECTRUM", "READ");
            if (method.equals("DELETE")) return permission("spectrum.delete", "SPECTRUM", "WRITE");
            if (method.equals("PUT") || method.equals("PATCH")) return permission("spectrum.update", "SPECTRUM", "WRITE");
            if (method.equals("POST")) return permission("spectrum.create", "SPECTRUM", "WRITE");
            return null;
        }
        if (path.startsWith("/api/v1/ai/rnd")) return aiRndPermission(method, path);
        if (path.startsWith("/api/v1/assistant") || path.startsWith("/api/v1/search"))
            return permission("ai.use", "AI", "USE");
        if (path.startsWith("/api/v1/files/staged")) {
            var kind = request.getParameter("kind") == null ? "" : request.getParameter("kind").toUpperCase(java.util.Locale.ROOT);
            if ("AI_MODELING_CATALOG".equals(kind))
                return permission("ai.modeling.manage", "AI_MODELING", "WRITE");
            if ("PROJECT_DOCUMENT".equals(kind))
                return permission("project.document.create", "PROJECT", "WRITE");
            if ("TEMPLATE_SOURCE".equals(kind))
                return permission("template.upload.create", "TEMPLATE", "WRITE");
            if ("TEMPLATE_SNAPSHOT".equals(kind))
                return permission("template.view.update", "TEMPLATE", "WRITE");
            if ("EXPERIMENT_SOURCE".equals(kind))
                return permission("eln.upload.create", "EXPERIMENT", "WRITE");
            if ("QUALITY_SOURCE".equals(kind))
                return permission("quality.upload.create", "QUALITY", "WRITE");
            if ("PRODUCTION_SOURCE".equals(kind) || "IMPORT".equals(kind))
                return permission("production.upload.create", "PRODUCTION", "WRITE");
            if ("PRODUCTION_SNAPSHOT".equals(kind))
                return permission("production.view.update", "PRODUCTION", "WRITE");
            if ("KNOWLEDGE".equals(kind))
                return permission("knowledge.upload.create", "KNOWLEDGE", "WRITE");
            if ("DATA_SOURCE".equals(kind))
                return permission("data.upload.create", "DATA", "WRITE");
            if ("SPC_CHART".equals(kind))
                return permission("spectrum.upload.create", "SPECTRUM", "WRITE");
            if ("RESEARCH_TEST_SOURCE".equals(kind))
                return permission("research-test.report.upload.create", "RESEARCH_TEST", "WRITE");
            if ("RESEARCH_TEST_STANDARD_SOURCE".equals(kind))
                return permission("research-test.standard.upload.create", "RESEARCH_TEST", "WRITE");
            if ("QUOTATION_SOURCE".equals(kind))
                return permission("quotation.create", "QUOTATION", "WRITE");
        }
        if (path.startsWith("/api/v1/files")) {
            if (path.endsWith("/content")) return permission("ops.file.download", "FILE", "READ");
            return method.equals("POST") ? permission("ops.file.upload", "FILE", "WRITE") : read ? permission("ops.file.view", "FILE", "READ") : null;
        }
        return null;
    }

    private Permission aiRndPermission(String method, String path) {
        boolean read = Set.of("GET", "HEAD", "OPTIONS").contains(method);
        if (path.equals("/api/v1/ai/rnd/test-metric-catalog/imports"))
            return method.equals("POST") ? permission("ai.modeling.manage", "AI_MODELING", "WRITE")
                    : read ? permission("ai.modeling.read", "AI_MODELING", "READ") : null;
        if (path.matches("/api/v1/ai/rnd/test-metric-catalog/imports/[0-9a-f-]{36}/indicators") && read)
            return permission("ai.modeling.read", "AI_MODELING", "READ");
        if (path.equals("/api/v1/ai/rnd/predictions") && method.equals("POST"))
            return permission("ai.performance.predict", "AI_PERFORMANCE", "USE");
        if (path.equals("/api/v1/ai/rnd/formula-designs") && method.equals("POST"))
            return permission("ai.formula.predict", "AI_FORMULA", "USE");
        if (path.equals("/api/v1/ai/rnd/experiment-optimizations") && method.equals("POST"))
            return permission("ai.experiment.optimize", "AI_EXPERIMENT", "USE");
        if (path.startsWith("/api/v1/ai/rnd/modeling-workbench/datasets") && read)
            return permission("ai.modeling.read", "AI_MODELING", "READ");
        if (path.matches("/api/v1/ai/rnd/modeling-workbench/runs/[0-9a-f-]{36}") && read)
            return permission("ai.modeling.read", "AI_MODELING", "READ");
        if (path.matches("/api/v1/ai/rnd/modeling-workbench/runs/[0-9a-f-]{36}/register") && method.equals("POST"))
            return permission("ai.model.publish", "AI_MODEL", "PUBLISH");
        if (path.equals("/api/v1/ai/rnd/modeling-workbench/runs") && method.equals("POST"))
            return permission("ai.training.operate", "AI_TRAINING", "OPERATE");
        if ((path.startsWith("/api/v1/ai/rnd/optimization-datasets")
                || path.matches("/api/v1/ai/rnd/experiment-optimizations/[0-9a-f-]{36}")) && read)
            return permission("ai.experiment.optimize", "AI_EXPERIMENT", "READ");
        if (path.matches("/api/v1/ai/rnd/research-runs/[0-9a-f-]{36}/observations") && read)
            return permission("ai.experiment.optimize", "AI_EXPERIMENT", "READ");
        if (path.matches("/api/v1/ai/rnd/research-runs/[0-9a-f-]{36}/observations") && method.equals("PUT"))
            return permission("ai.experiment.optimize", "AI_EXPERIMENT", "WRITE");
        if (path.matches("/api/v1/ai/rnd/research-runs/[0-9a-f-]{36}/next-round") && method.equals("POST"))
            return permission("ai.experiment.optimize", "AI_EXPERIMENT", "WRITE");
        if (path.startsWith("/api/v1/ai/rnd/research-runs") && read)
            return permission("ai.formula.predict", "AI_FORMULA", "READ");
        if (path.startsWith("/api/v1/ai/rnd/prediction-records") && read)
            return permission("ai.performance.predict", "AI_PERFORMANCE", "READ");
        if (path.equals("/api/v1/ai/rnd/prediction-context") && read)
            return permission("ai.performance.predict", "AI_PERFORMANCE", "READ");
        if (path.equals("/api/v1/ai/rnd/formula-design-context") && read)
            return permission("ai.formula.predict", "AI_FORMULA", "READ");
        if (path.startsWith("/api/v1/ai/rnd/models")) {
            if (read) return permission("ai.model.read", "AI_MODEL", "READ");
            if (method.equals("POST") && (path.endsWith("/rollback") || path.endsWith("/pause")))
                return permission("ai.model.publish", "AI_MODEL", "PUBLISH");
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
        // Category definitions are organization level configuration, not a
        // record: the category id in the path must not be resolved as a data
        // scope target.
        if (categoryModule(path) != null) return null;
        if (path.startsWith("/api/v1/crm/")) return null;
        var customerMatcher = java.util.regex.Pattern.compile("/api/v1/business-partners/([0-9a-fA-F-]{36})(?:/|$)").matcher(path);
        if (customerMatcher.find()) return UUID.fromString(customerMatcher.group(1));
        var dataSourceMatcher = java.util.regex.Pattern.compile("/api/v1/data/sources/([0-9a-fA-F-]{36})(?:/|$)").matcher(path);
        if (dataSourceMatcher.find()) return UUID.fromString(dataSourceMatcher.group(1));
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
        if (path.startsWith("/api/v1/stages/") || path.startsWith("/api/v1/tasks/")
                || path.startsWith("/api/v1/meetings/")) return null;
        var segments = path.split("/");
        for (var index = segments.length - 1; index >= 0; index--) {
            try { return UUID.fromString(segments[index]); } catch (Exception ignored) { }
        }
        return null;
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, ApiErrorCode code) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        if (request.getRequestURI().startsWith("/api/v1/ai/rnd")) {
            objectMapper.writeValue(response.getWriter(), new AiRndPermissionError(
                    code.code(), code.defaultMessage(), RequestIdHolder.currentOrUnknown(), null));
        } else {
            objectMapper.writeValue(response.getWriter(), ResponseFactory.error(code, RequestIdHolder.currentOrUnknown()));
        }
    }

    private record Permission(String code, String resourceType, String operation) { }
    private record AiRndPermissionError(String code, String message, String requestId, Object detail) { }
}
