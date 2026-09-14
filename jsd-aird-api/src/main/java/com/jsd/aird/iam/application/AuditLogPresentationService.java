package com.jsd.aird.iam.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.api.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns the append-only audit table into a customer-readable activity feed.
 * The stored audit rows are intentionally left untouched; all presentation is
 * resolved at read time so existing audit history remains immutable.
 */
@Service
public class AuditLogPresentationService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> GENERIC_USER_ACTIONS = Set.of(
            "CREATE", "CREATED", "UPDATE", "UPDATED", "DELETE", "DELETED", "REOPEN", "REOPENED",
            "REORDER", "RENAMED", "COPIED", "PUBLISHED", "DRAFT_SAVED", "REVISION_CREATED",
            "ROLLED_BACK", "SUBMIT", "SUBMITTED", "VOID", "VOIDED", "SAVED", "ASSIGNED",
            "UNASSIGNED", "EXPORTED", "DOWNLOAD");
    private static final Set<String> SYSTEM_ACTIONS = Set.of(
            "AI_MODEL_AUTH_FAILED", "AUTO_DETECTION_FAILED", "BM25_REJECTED", "GEOMETRY_DETECTION_FAILED",
            "IMAGE_DECODE_FAILED", "MODEL_CALL_FAILED", "MODEL_CONFLICT_REJECTED", "MODEL_RECOGNITION_FAILED",
            "MODEL_STRUCTURE_REJECTED", "MODEL_ONLY_AFTER_CONFIRMED_PHYSICAL", "PARSING_COMPLETED",
            "PENDING_NOT_CONFIRMED", "RETAINED_REJECTED_CANDIDATE", "SAVING_PARSED_DATA", "SNAPSHOT_PERSIST_FAILED",
            "TASK_FAILED", "TITLE_GENERATION_DISABLED", "VECTOR_REJECTED", "NOT_ASSIGNED", "NOT_COMMITTED",
            "NOT_STARTED", "NOT_SUBMITTED");
    private static final Map<String, EventDefinition> DEFINITIONS = definitions();

    private static final String ENRICHED_CTE = """
            WITH audit_source AS (
                SELECT id, organization_id, actor_id, action, aggregate_type, aggregate_id,
                       detail_jsonb, created_at, NULL::varchar AS source_operator_name
                FROM ops.audit_log
                UNION ALL
                SELECT ea.id, ea.organization_id, ea.operator_id,
                       CASE WHEN ea.action LIKE 'STATUS_%' THEN 'EXPERIMENT_STATUS_CHANGED' ELSE 'EXPERIMENT_' || ea.action END,
                       'EXPERIMENT', ea.experiment_id,
                       COALESCE(ea.after_jsonb, ea.before_jsonb, '{}'::jsonb), ea.created_at, ea.operator_name
                FROM rnd.experiment_audit ea
            ), enriched AS (
                SELECT al.id, al.actor_id, al.action, al.aggregate_type, al.aggregate_id,
                       al.detail_jsonb, al.created_at,
                       COALESCE(u.display_name, u.username, al.source_operator_name, '系统') AS operator_name,
                       COALESCE(
                           CASE al.aggregate_type
                               WHEN 'USER' THEN (SELECT COALESCE(x.display_name, x.username) FROM iam.app_user x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'ROLE' THEN (SELECT x.name FROM iam.role x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'AI_CONVERSATION' THEN (SELECT x.title FROM ai.assistant_conversation x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'BUSINESS_PARTNER' THEN (SELECT x.name FROM mdm.business_partner x WHERE x.id = al.aggregate_id)
                               WHEN 'CUSTOMER_REQUIREMENT' THEN (SELECT x.title FROM mdm.customer_requirement x WHERE x.id = al.aggregate_id)
                               WHEN 'PROJECT' THEN (SELECT x.name FROM mdm.project x WHERE x.id = al.aggregate_id)
                               WHEN 'PROJECT_STAGE' THEN (SELECT x.name FROM mdm.project_stage x WHERE x.id = al.aggregate_id)
                               WHEN 'PROJECT_TASK' THEN (SELECT x.name FROM mdm.project_task x WHERE x.id = al.aggregate_id)
                               WHEN 'PROJECT_DOCUMENT' THEN (SELECT x.title FROM mdm.project_document x WHERE x.id = al.aggregate_id)
                               WHEN 'EXPERIMENT' THEN (SELECT COALESCE(x.title, x.experiment_no) FROM rnd.experiment x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'SPECTRUM_CATEGORY' THEN (SELECT x.name FROM spc.chart_category x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'SPECTRUM_CHART' THEN (SELECT COALESCE(x.title, x.original_name) FROM spc.chart_asset x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'SPECTRUM_SESSION' THEN (SELECT x.title FROM spc.chat_session x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'KB_DOCUMENT' THEN (SELECT x.title FROM kb.document x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'TEMPLATE' THEN (SELECT x.name FROM tpl.template x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'TEMPLATE_VERSION' THEN (SELECT t.name || ' · 第 ' || x.version_no || ' 版' FROM tpl.template_version x JOIN tpl.template t ON t.id = x.template_id WHERE x.id = al.aggregate_id)
                               WHEN 'TEMPLATE_IMPORT_JOB' THEN (SELECT f.original_name FROM tpl.template_import_job x JOIN ops.file_object f ON f.id = x.source_file_id WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'DATA_IMPORT_JOB' THEN (SELECT x.source_file_name FROM data.import_job x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               WHEN 'PRODUCTION_ORDER' THEN (SELECT x.order_no FROM mfg.production_order x WHERE x.id = al.aggregate_id AND x.organization_id = al.organization_id)
                               ELSE NULL
                           END,
                           al.detail_jsonb ->> 'objectName', al.detail_jsonb ->> 'name', al.detail_jsonb ->> 'title',
                           al.detail_jsonb ->> 'fileName', al.detail_jsonb ->> 'file_name'
                       ) AS object_name
                FROM audit_source al
                LEFT JOIN iam.app_user u ON u.id = al.actor_id AND u.organization_id = al.organization_id
                WHERE al.organization_id = ?
            )
            """;

    private final JdbcTemplate jdbc;

    public AuditLogPresentationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<AuditLogView> page(UUID organizationId, AuditLogQuery query) {
        var safe = query == null ? new AuditLogQuery(null, null, null, null, null, null, 1, 20) : query;
        var where = new StringBuilder();
        var args = new ArrayList<Object>();
        where.append(" AND (").append(userActionPredicate()).append(")");
        appendModuleFilter(where, safe.module());
        appendOperationFilter(where, safe.operation());
        if (hasText(safe.operator())) {
            where.append(" AND lower(operator_name) LIKE ?");
            args.add(like(safe.operator()));
        }
        if (hasText(safe.keyword())) {
            where.append(" AND lower(concat_ws(' ', operator_name, object_name, action, detail_jsonb::text)) LIKE ?");
            args.add(like(safe.keyword()));
        }
        if (safe.from() != null) {
            where.append(" AND created_at >= ?");
            args.add(java.sql.Timestamp.from(safe.from()));
        }
        if (safe.to() != null) {
            where.append(" AND created_at < ?");
            args.add(java.sql.Timestamp.from(safe.to()));
        }

        var countArgs = new ArrayList<Object>();
        countArgs.add(organizationId);
        countArgs.addAll(args);
        var total = jdbc.queryForObject(ENRICHED_CTE + " SELECT count(*) FROM enriched WHERE 1=1" + where,
                Long.class, countArgs.toArray());
        var totalValue = total == null ? 0L : total;

        var itemArgs = new ArrayList<Object>();
        itemArgs.add(organizationId);
        itemArgs.addAll(args);
        itemArgs.add(safe.size());
        itemArgs.add((safe.page() - 1L) * safe.size());
        var rawRows = jdbc.query(ENRICHED_CTE + " SELECT id, actor_id, action, aggregate_type, aggregate_id, detail_jsonb, created_at, operator_name, object_name"
                        + " FROM enriched WHERE 1=1" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::map, itemArgs.toArray());
        var items = rawRows.stream().map(this::present).toList();
        var totalPages = totalValue == 0 ? 0 : (totalValue + safe.size() - 1) / safe.size();
        return new PageResponse<>(items, safe.page(), safe.size(), totalValue, totalPages);
    }

    private RawAuditRow map(ResultSet rs, int ignored) throws SQLException {
        return new RawAuditRow(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class),
                rs.getString("action"), rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                readJson(rs.getString("detail_jsonb")), rs.getTimestamp("created_at").toInstant(),
                rs.getString("operator_name"), rs.getString("object_name"));
    }

    private AuditLogView present(RawAuditRow row) {
        var definition = definition(row.action(), row.aggregateType());
        var objectType = objectTypeLabel(row.aggregateType());
        var objectName = text(row.objectName()) ? row.objectName() : "未命名" + objectType;
        var summary = summary(row, definition, objectType, objectName);
        var operator = text(row.operatorName()) ? row.operatorName() : "未知用户";
        return new AuditLogView(row.id(), row.createdAt(), definition.module(), definition.operation(), operator,
                objectType, objectName, summary, technical(row));
    }

    private TechnicalDetail technical(RawAuditRow row) {
        var fields = new LinkedHashMap<String, String>();
        row.detail().fields().forEachRemaining(entry -> {
            var key = entry.getKey();
            var value = entry.getValue();
            if (safeTechnicalKey(key) && value != null && value.isValueNode()) {
                var rendered = value.asText();
                if (text(rendered) && rendered.length() <= 240) fields.put(key, rendered);
            }
        });
        return new TechnicalDetail(row.id(), row.action(), row.aggregateType(), row.aggregateId(), fields);
    }

    private String summary(RawAuditRow row, EventDefinition definition, String objectType, String objectName) {
        var action = row.action();
        var object = objectName.equals("未命名" + objectType) ? objectType : objectType + "“" + objectName + "”";
        var detail = row.detail();
        var fileName = first(detail, "fileName", "file_name", "sourceFileName", "source_file_name");
        return switch (action) {
            case "IAM_USER_CREATED" -> "新增用户：" + objectName;
            case "IAM_USER_UPDATED" -> "修改用户：" + objectName;
            case "IAM_USER_ENABLED" -> "启用用户：" + objectName;
            case "IAM_USER_DISABLED" -> "停用用户：" + objectName;
            case "IAM_PASSWORD_RESET" -> "重置用户密码：" + objectName;
            case "IAM_FORCE_LOGOUT" -> "强制用户退出登录：" + objectName;
            case "IAM_ROLE_CREATED" -> "新增角色：" + objectName;
            case "IAM_ROLE_RENAMED" -> "修改角色：" + objectName;
            case "IAM_ROLE_DELETED" -> "删除角色：" + objectName;
            case "IAM_ROLE_PERMISSIONS_UPDATED", "IAM_USER_PERMISSIONS_UPDATED", "IAM_USER_PERMISSION_RESTORED" -> "更新访问权限：" + objectName;
            case "AI_QA_STARTED" -> "发起了一次 AI 问答" + (text(objectName) && !objectName.startsWith("未命名") ? "：" + objectName : "");
            case "DATA_IMPORT_COMMITTED", "DATA_RECORDS_COMMITTED" -> "完成数据导入" + (text(fileName) ? "：" + fileName : "");
            case "KB_DOCUMENT_CREATED" -> "新增知识文档：" + objectName;
            case "KB_DOCUMENT_RENAMED" -> "重命名知识文档：" + objectName;
            case "KB_DOCUMENT_DELETED" -> "删除知识文档：" + objectName;
            case "KB_DOCUMENT_VERSION_CREATED" -> "创建知识文档版本：" + objectName;
            case "KB_DOCUMENT_PUBLISHED" -> "发布知识文档：" + objectName;
            case "KB_DOCUMENT_REJECTED" -> "退回知识文档：" + objectName;
            case "KB_DOCUMENT_DOWNLOAD" -> "下载知识文档：" + objectName;
            case "PRODUCTION_ORDER_CREATED" -> "新增生产单：" + objectName;
            case "PRODUCTION_ORDER_SUBMITTED" -> "提交生产单：" + objectName;
            case "PRODUCTION_ORDER_CANCELLED" -> "取消生产单：" + objectName;
            case "PRODUCTION_ORDER_EXPORTED" -> "导出生产单：" + objectName;
            case "INVENTORY_MOVED" -> "调整库存：" + objectName;
            case "INVENTORY_REVERSED" -> "冲销库存流水：" + objectName;
            case "INVENTORY_PRODUCT_CREATED" -> "新增库存产品：" + objectName;
            case "AI_CONVERSATION_TITLE_STARTED" -> "开始生成会话标题：" + objectName;
            case "AI_CONVERSATION_TITLE_SUCCEEDED" -> "完成会话标题生成：" + objectName;
            default -> definition.operation() + "：" + object;
        };
    }

    private static String first(JsonNode node, String... names) {
        for (var name : names) {
            if (node.hasNonNull(name) && text(node.path(name).asText())) return node.path(name).asText();
        }
        return null;
    }

    private static boolean safeTechnicalKey(String key) {
        var normalized = key.toLowerCase(Locale.ROOT);
        return !(normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("authorization") || normalized.contains("cookie") || normalized.contains("prompt")
                || normalized.contains("queryhash") || normalized.contains("sha") || normalized.contains("hash")
                || normalized.contains("content") || normalized.contains("raw") || normalized.contains("filedata")
                || normalized.contains("apikey") || normalized.contains("credential"));
    }

    private static JsonNode readJson(String value) {
        try { return JSON.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (Exception ignored) { return JSON.createObjectNode(); }
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static boolean text(String value) { return hasText(value); }
    private static String like(String value) { return "%" + value.trim().toLowerCase(Locale.ROOT) + "%"; }

    private static String objectTypeLabel(String value) {
        if (value == null) return "业务记录";
        return switch (value) {
            case "USER" -> "用户";
            case "ROLE" -> "角色";
            case "AI_CONVERSATION" -> "会话";
            case "BUSINESS_PARTNER" -> "客户";
            case "CUSTOMER_REQUIREMENT" -> "客户需求";
            case "PROJECT" -> "项目";
            case "PROJECT_STAGE" -> "项目阶段";
            case "PROJECT_TASK" -> "项目任务";
            case "PROJECT_DOCUMENT" -> "项目文档";
            case "EXPERIMENT" -> "实验";
            case "QUALITY_CATEGORY" -> "品管分类";
            case "QUALITY_RECORD" -> "品管记录";
            case "QUALITY_UPLOAD" -> "品管文件";
            case "SPECTRUM_CATEGORY" -> "图谱分类";
            case "SPECTRUM_CHART" -> "图谱";
            case "SPECTRUM_SESSION" -> "图谱分析对话";
            case "SPECTRUM_ANALYSIS" -> "图谱分析";
            case "RESEARCH_TEST", "RESEARCH_TEST_UPLOAD" -> "研发测试记录";
            case "KB_DOCUMENT", "KB_UPLOAD" -> "知识文档";
            case "TEMPLATE", "TEMPLATE_VERSION", "TEMPLATE_IMPORT_JOB" -> "模板";
            case "DATA_IMPORT_JOB" -> "数据文件";
            case "PRODUCTION_ORDER" -> "生产单";
            case "INVENTORY_PRODUCT" -> "库存产品";
            case "INVENTORY_TRANSACTION" -> "库存流水";
            case "INVENTORY_SAMPLE_DISPATCH" -> "发样记录";
            case "INVENTORY_SHIPMENT" -> "出货记录";
            default -> "业务记录";
        };
    }

    private static String userActionPredicate() {
        var exact = DEFINITIONS.entrySet().stream().filter(item -> !item.getValue().systemEvent()).map(Map.Entry::getKey).toList();
        var placeholders = exact.stream().map(AuditLogPresentationService::sqlLiteral).collect(Collectors.joining(","));
        var generic = GENERIC_USER_ACTIONS.stream().map(AuditLogPresentationService::sqlLiteral).collect(Collectors.joining(","));
        return "(action IN (" + placeholders + ") OR action LIKE 'KB_AI_GRANT_%' OR (aggregate_type IN ('PROJECT','PROJECT_STAGE','PROJECT_TASK','EXPERIMENT','PROJECT_DOCUMENT') AND action IN (" + generic + ")))";
    }

    private static String sqlLiteral(String value) { return "'" + value.replace("'", "''") + "'"; }

    private static void appendModuleFilter(StringBuilder where, String module) {
        if (!hasText(module)) return;
        switch (module.trim().toUpperCase(Locale.ROOT)) {
            case "IAM" -> where.append(" AND action LIKE 'IAM_%'");
            case "AI" -> where.append(" AND (action LIKE 'AI_%' OR aggregate_type = 'AI_CONVERSATION')");
            case "DATA" -> where.append(" AND action LIKE 'DATA_%'");
            case "KB" -> where.append(" AND action LIKE 'KB_%'");
            case "TEMPLATE" -> where.append(" AND action LIKE 'TEMPLATE_%'");
            case "PROJECT" -> where.append(" AND (aggregate_type IN ('PROJECT','PROJECT_STAGE','PROJECT_TASK','PROJECT_DOCUMENT') OR action LIKE 'PROJECT_%')");
            case "EXPERIMENT" -> where.append(" AND (aggregate_type = 'EXPERIMENT' OR action LIKE 'EXPERIMENT_%')");
            case "QUALITY" -> where.append(" AND (aggregate_type LIKE 'QUALITY_%' OR action LIKE 'QUALITY_%')");
            case "SPECTRUM" -> where.append(" AND (aggregate_type LIKE 'SPECTRUM_%' OR action LIKE 'SPECTRUM_%')");
            case "RESEARCH_TEST" -> where.append(" AND (aggregate_type LIKE 'RESEARCH_TEST%' OR action LIKE 'RESEARCH_TEST_%')");
            case "CUSTOMER" -> where.append(" AND (action LIKE 'CUSTOMER_%' OR action LIKE 'CONTACT_%' OR action LIKE 'COMMUNICATION_%' OR aggregate_type IN ('BUSINESS_PARTNER','CUSTOMER_REQUIREMENT'))");
            case "PRODUCTION" -> where.append(" AND action LIKE 'PRODUCTION_%'");
            case "INVENTORY" -> where.append(" AND action LIKE 'INVENTORY_%'");
            default -> where.append(" AND 1=0");
        }
    }

    private static void appendOperationFilter(StringBuilder where, String operation) {
        if (!hasText(operation)) return;
        switch (operation.trim().toUpperCase(Locale.ROOT)) {
            case "CREATE" -> where.append(" AND (action LIKE '%_CREATED' OR action LIKE '%_COPIED' OR action IN ('CREATE','CREATED','COPIED','TEMPLATE_COPIED'))");
            case "UPDATE" -> where.append(" AND (action LIKE '%_UPDATED' OR action LIKE '%_SAVED' OR action LIKE '%_RENAMED' OR action IN ('UPDATE','UPDATED','RENAMED','DRAFT_SAVED'))");
            case "DELETE" -> where.append(" AND (action LIKE '%_DELETED' OR action IN ('DELETE','DELETED'))");
            case "PERMISSION" -> where.append(" AND (action LIKE '%PERMISSION%' OR action LIKE '%PERMISSIONS%' OR action LIKE '%GRANT%' OR action LIKE '%REVOKE%')");
            case "STATUS" -> where.append(" AND (action LIKE '%_ENABLED' OR action LIKE '%_DISABLED' OR action LIKE '%_ACTIVATED' OR action LIKE '%_DEACTIVATED' OR action LIKE '%_CANCELLED' OR action LIKE '%_STATUS_CHANGED' OR action LIKE '%_PUBLISHED')");
            case "IMPORT" -> where.append(" AND (action LIKE '%IMPORT%' OR action LIKE '%_UPLOAD%' OR action LIKE '%_RETRIED' OR action LIKE '%_EXPORTED' OR action LIKE '%_DOWNLOAD')");
            case "AI" -> where.append(" AND (action LIKE 'AI_%' OR action LIKE 'MODEL_%')");
            default -> where.append(" AND 1=0");
        }
    }

    private static EventDefinition definition(String action, String aggregateType) {
        if (action != null && DEFINITIONS.containsKey(action)) return DEFINITIONS.get(action);
        if (action != null && action.startsWith("KB_AI_GRANT_")) return new EventDefinition("研发知识库", "权限变更", false);
        if (action != null && action.startsWith("AI_CONVERSATION_TITLE_")) return new EventDefinition("AI研发助手", action.endsWith("STARTED") ? "开始处理" : "处理完成", true);
        if (action != null && SYSTEM_ACTIONS.contains(action)) return new EventDefinition(moduleFor(action, aggregateType), "系统处理", true);
        if (action != null && isGenericUserAction(action) && isBusinessAggregate(aggregateType)) return new EventDefinition(moduleFor(action, aggregateType), operationFor(action), false);
        return new EventDefinition(moduleFor(action, aggregateType), "系统操作", true);
    }

    private static boolean isGenericUserAction(String action) { return GENERIC_USER_ACTIONS.contains(action); }
    private static boolean isBusinessAggregate(String aggregateType) { return Set.of("PROJECT", "PROJECT_STAGE", "PROJECT_TASK", "EXPERIMENT", "PROJECT_DOCUMENT").contains(aggregateType); }

    private static String moduleFor(String action, String aggregateType) {
        var value = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if (value.startsWith("IAM_")) return "系统设置";
        if (value.startsWith("AI_") || "AI_CONVERSATION".equals(aggregateType)) return "AI研发助手";
        if (value.startsWith("DATA_")) return "数据中心";
        if (value.startsWith("KB_")) return "研发知识库";
        if (value.startsWith("TEMPLATE_")) return "模板中心";
        if (value.startsWith("EXPERIMENT_" ) || "EXPERIMENT".equals(aggregateType)) return "实验记录本";
        if (value.startsWith("QUALITY_" ) || aggregateType != null && aggregateType.startsWith("QUALITY_")) return "品管部";
        if (value.startsWith("SPECTRUM_" ) || aggregateType != null && aggregateType.startsWith("SPECTRUM_")) return "图谱中心";
        if (value.startsWith("RESEARCH_TEST_" ) || aggregateType != null && aggregateType.startsWith("RESEARCH_TEST")) return "研发测试中心";
        if (value.startsWith("PRODUCTION_")) return "生产单管理";
        if (value.startsWith("INVENTORY_")) return "库存管理";
        if (value.startsWith("CUSTOMER_") || value.startsWith("CONTACT_") || value.startsWith("COMMUNICATION_")
                || "BUSINESS_PARTNER".equals(aggregateType) || "CUSTOMER_REQUIREMENT".equals(aggregateType)) return "客户管理";
        if (Set.of("PROJECT", "PROJECT_STAGE", "PROJECT_TASK", "PROJECT_DOCUMENT").contains(aggregateType) || value.startsWith("PROJECT_")) return "项目管理";
        if (value.startsWith("MODEL_") || value.startsWith("PARSING_") || value.startsWith("GEOMETRY_")) return "模板中心";
        return "其他系统";
    }

    private static String operationFor(String action) {
        if (action == null) return "操作";
        var value = action.toUpperCase(Locale.ROOT);
        if (value.contains("PERMISSION") || value.contains("GRANT") || value.contains("REVOKE")) return "权限变更";
        if (value.endsWith("CREATED") || value.equals("CREATE") || value.equals("CREATED") || value.equals("COPIED")) return "新增";
        if (value.endsWith("UPDATED") || value.endsWith("SAVED") || value.equals("UPDATE") || value.equals("UPDATED") || value.equals("RENAMED") || value.equals("DRAFT_SAVED")) return "修改";
        if (value.endsWith("DELETED") || value.equals("DELETE") || value.equals("DELETED")) return "删除";
        if (value.endsWith("ENABLED") || value.endsWith("DISABLED") || value.endsWith("ACTIVATED") || value.endsWith("DEACTIVATED") || value.endsWith("CANCELLED")) return "状态变更";
        if (value.endsWith("EXPORTED")) return "导出";
        if (value.endsWith("DOWNLOAD")) return "下载";
        if (value.endsWith("PUBLISHED")) return "发布";
        if (value.endsWith("SUBMITTED")) return "提交";
        if (value.endsWith("REVERSED")) return "冲销";
        if (value.endsWith("STARTED")) return "发起";
        if (value.endsWith("FAILED")) return "处理失败";
        if (value.endsWith("SUCCEEDED") || value.endsWith("COMPLETED") || value.endsWith("COMMITTED")) return "处理完成";
        return "操作";
    }

    private static Map<String, EventDefinition> definitions() {
        var map = new LinkedHashMap<String, EventDefinition>();
        put(map, "IAM_USER_CREATED", "系统设置", "新增", false);
        put(map, "IAM_USER_UPDATED", "系统设置", "修改", false);
        put(map, "IAM_USER_ENABLED", "系统设置", "状态变更", false);
        put(map, "IAM_USER_DISABLED", "系统设置", "状态变更", false);
        put(map, "IAM_PASSWORD_RESET", "系统设置", "安全操作", false);
        put(map, "IAM_FORCE_LOGOUT", "系统设置", "安全操作", false);
        put(map, "IAM_ROLE_CREATED", "系统设置", "新增", false);
        put(map, "IAM_ROLE_RENAMED", "系统设置", "修改", false);
        put(map, "IAM_ROLE_DELETED", "系统设置", "删除", false);
        put(map, "IAM_ROLE_PERMISSIONS_UPDATED", "系统设置", "权限变更", false);
        put(map, "IAM_USER_PERMISSIONS_UPDATED", "系统设置", "权限变更", false);
        put(map, "IAM_USER_PERMISSION_RESTORED", "系统设置", "权限变更", false);
        put(map, "AI_QA_STARTED", "AI研发助手", "发起问答", false);
        put(map, "AI_CONVERSATION_TITLE_STARTED", "AI研发助手", "开始处理", true);
        put(map, "AI_CONVERSATION_TITLE_SUCCEEDED", "AI研发助手", "处理完成", true);
        put(map, "DATA_IMPORT_COMMITTED", "数据中心", "导入完成", false);
        put(map, "DATA_RECORDS_COMMITTED", "数据中心", "导入完成", false);
        put(map, "DATA_IMPORT_VALUE_CORRECTED", "数据中心", "修改数据", false);
        put(map, "EXPERIMENT_CREATED", "实验记录本", "新增", false);
        put(map, "EXPERIMENT_COPIED", "实验记录本", "复制", false);
        put(map, "EXPERIMENT_DRAFT_SAVED", "实验记录本", "保存", false);
        put(map, "EXPERIMENT_DELETED", "实验记录本", "删除", false);
        put(map, "EXPERIMENT_PUBLISHED", "实验记录本", "发布", false);
        put(map, "EXPERIMENT_STATUS_CHANGED", "实验记录本", "状态变更", false);
        put(map, "EXPERIMENT_REVISION_CREATED", "实验记录本", "创建修订", false);
        put(map, "EXPERIMENT_ROLLED_BACK", "实验记录本", "回滚版本", false);
        put(map, "EXPERIMENT_CATEGORY_CREATED", "实验记录本", "新增分类", false);
        put(map, "EXPERIMENT_CATEGORY_UPDATED", "实验记录本", "修改分类", false);
        put(map, "EXPERIMENT_CATEGORY_STATUS_CHANGED", "实验记录本", "分类状态变更", false);
        put(map, "QUALITY_CATEGORY_CREATED", "品管部", "新增分类", false);
        put(map, "QUALITY_CATEGORY_UPDATED", "品管部", "修改分类", false);
        put(map, "QUALITY_CATEGORY_DELETED", "品管部", "删除分类", false);
        put(map, "QUALITY_RECORD_RENAMED", "品管部", "修改记录", false);
        put(map, "QUALITY_RECORD_PUBLISHED", "品管部", "发布记录", false);
        put(map, "QUALITY_RECORD_DELETED", "品管部", "删除记录", false);
        put(map, "QUALITY_RECORD_SAVED", "品管部", "保存记录", false);
        put(map, "QUALITY_RECORD_MOVED", "品管部", "移动记录", false);
        put(map, "QUALITY_DEFECT_CREATED", "品管部", "生成不良报告", false);
        put(map, "QUALITY_UPLOAD_CREATED", "品管部", "上传文件", false);
        put(map, "QUALITY_UPLOAD_DELETED", "品管部", "删除上传记录", false);
        put(map, "QUALITY_UPLOAD_RETRIED", "品管部", "重新解析", false);
        put(map, "SPECTRUM_CATEGORY_CREATED", "图谱中心", "新增分类", false);
        put(map, "SPECTRUM_CATEGORY_UPDATED", "图谱中心", "修改分类", false);
        put(map, "SPECTRUM_CATEGORY_DELETED", "图谱中心", "删除分类", false);
        put(map, "SPECTRUM_CHART_CREATED", "图谱中心", "上传图谱", false);
        put(map, "SPECTRUM_CHART_UPDATED", "图谱中心", "修改图谱", false);
        put(map, "SPECTRUM_CHART_DELETED", "图谱中心", "删除图谱", false);
        put(map, "SPECTRUM_SESSION_CREATED", "图谱中心", "新建分析对话", false);
        put(map, "SPECTRUM_SESSION_RENAMED", "图谱中心", "重命名分析对话", false);
        put(map, "SPECTRUM_SESSION_DELETED", "图谱中心", "删除分析对话", false);
        put(map, "SPECTRUM_ANALYSIS_STARTED", "图谱中心", "发起分析", false);
        put(map, "RESEARCH_TEST_CREATED", "研发测试中心", "新增记录", false);
        put(map, "RESEARCH_TEST_SAVED", "研发测试中心", "保存记录", false);
        put(map, "RESEARCH_TEST_RENAMED", "研发测试中心", "修改记录", false);
        put(map, "RESEARCH_TEST_STATUS_CHANGED", "研发测试中心", "状态变更", false);
        put(map, "RESEARCH_TEST_REVISION_CREATED", "研发测试中心", "创建修订", false);
        put(map, "RESEARCH_TEST_COPIED", "研发测试中心", "复制记录", false);
        put(map, "RESEARCH_TEST_DELETED", "研发测试中心", "删除记录", false);
        put(map, "RESEARCH_TEST_UPLOAD_CREATED", "研发测试中心", "上传文件", false);
        put(map, "RESEARCH_TEST_UPLOAD_RETRIED", "研发测试中心", "重新解析", false);
        put(map, "RESEARCH_TEST_UPLOAD_DELETED", "研发测试中心", "删除上传记录", false);
        put(map, "KB_DOCUMENT_CREATED", "研发知识库", "新增", false);
        put(map, "KB_DOCUMENT_RENAMED", "研发知识库", "修改", false);
        put(map, "KB_DOCUMENT_DELETED", "研发知识库", "删除", false);
        put(map, "KB_DOCUMENT_VERSION_CREATED", "研发知识库", "创建版本", false);
        put(map, "KB_DOCUMENT_PUBLISHED", "研发知识库", "发布", false);
        put(map, "KB_DOCUMENT_REJECTED", "研发知识库", "退回", false);
        put(map, "KB_DOCUMENT_DOWNLOAD", "研发知识库", "下载", false);
        put(map, "PRODUCTION_ORDER_CREATED", "生产单管理", "新增", false);
        put(map, "PRODUCTION_ORDER_SAVED", "生产单管理", "保存", false);
        put(map, "PRODUCTION_ORDER_SUBMITTED", "生产单管理", "提交", false);
        put(map, "PRODUCTION_ORDER_CANCELLED", "生产单管理", "取消", false);
        put(map, "PRODUCTION_ORDER_EXPORTED", "生产单管理", "导出", false);
        put(map, "INVENTORY_PRODUCT_CREATED", "库存管理", "新增", false);
        put(map, "INVENTORY_POLICY_UPDATED", "库存管理", "修改", false);
        put(map, "INVENTORY_MOVED", "库存管理", "库存调整", false);
        put(map, "INVENTORY_REVERSED", "库存管理", "冲销", false);
        put(map, "INVENTORY_TRANSACTION_UPDATED", "库存管理", "修改", false);
        put(map, "INVENTORY_SAMPLE_UPDATED", "库存管理", "修改", false);
        put(map, "INVENTORY_SHIPMENT_UPDATED", "库存管理", "修改", false);
        put(map, "CUSTOMER_CREATED", "客户管理", "新增", false);
        put(map, "CUSTOMER_UPDATED", "客户管理", "修改", false);
        put(map, "CUSTOMER_ACTIVATED", "客户管理", "状态变更", false);
        put(map, "CUSTOMER_DEACTIVATED", "客户管理", "状态变更", false);
        put(map, "CUSTOMER_REQUIREMENT_CREATED", "客户管理", "新增", false);
        put(map, "CUSTOMER_REQUIREMENT_UPDATED", "客户管理", "修改", false);
        put(map, "CUSTOMER_REQUIREMENT_DELETED", "客户管理", "删除", false);
        put(map, "CONTACT_CREATED", "客户管理", "新增", false);
        put(map, "CONTACT_UPDATED", "客户管理", "修改", false);
        put(map, "CONTACT_ACTIVATED", "客户管理", "状态变更", false);
        put(map, "CONTACT_DEACTIVATED", "客户管理", "状态变更", false);
        put(map, "COMMUNICATION_CREATED", "客户管理", "新增", false);
        put(map, "COMMUNICATION_UPDATED", "客户管理", "修改", false);
        put(map, "COMMUNICATION_DELETED", "客户管理", "删除", false);
        return Collections.unmodifiableMap(map);
    }

    private static void put(Map<String, EventDefinition> map, String action, String module, String operation, boolean system) {
        map.put(action, new EventDefinition(module, operation, system));
    }

    public record AuditLogQuery(String keyword, String module, String operation, String operator,
                                Instant from, Instant to, int page, int size) {
        public AuditLogQuery {
            page = Math.max(1, page);
            size = Math.min(100, Math.max(1, size));
        }
    }

    public record AuditLogView(UUID id, Instant createdAt, String module, String operation, String operator,
                               String objectType, String objectName, String summary, TechnicalDetail technical) { }

    public record TechnicalDetail(UUID auditId, String actionCode, String aggregateType, UUID aggregateId,
                                  Map<String, String> fields) { }

    private record EventDefinition(String module, String operation, boolean systemEvent) { }

    private record RawAuditRow(UUID id, UUID actorId, String action, String aggregateType, UUID aggregateId,
                               JsonNode detail, Instant createdAt, String operatorName, String objectName) { }
}
