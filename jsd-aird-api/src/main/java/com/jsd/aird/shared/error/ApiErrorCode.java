package com.jsd.aird.shared.error;

public enum ApiErrorCode {

    VALIDATION_ERROR("SYS_VALIDATION_ERROR", "请求参数校验失败", 422),
    BAD_REQUEST("SYS_BAD_REQUEST", "请求内容无法解析", 400),
    NOT_FOUND("SYS_NOT_FOUND", "资源不存在", 404),
    RESOURCE_CONFLICT("RESOURCE_CONFLICT", "资源已存在或发生冲突", 409),
    OPERATION_FORBIDDEN("OPERATION_FORBIDDEN", "当前身份无权执行此操作", 403),
    AUTH_REQUIRED("AUTH_REQUIRED", "请先登录", 401),
    AUTH_INVALID("AUTH_INVALID", "用户名或密码错误", 401),
    ACCOUNT_DISABLED("ACCOUNT_DISABLED", "账号已停用", 403),
    ACCOUNT_LOCKED("ACCOUNT_LOCKED", "账号暂时锁定，请稍后再试", 423),
    AUTH_RATE_LIMITED("AUTH_RATE_LIMITED", "登录请求过于频繁，请稍后再试", 429),
    PERMISSION_DENIED("PERMISSION_DENIED", "当前用户没有执行该操作的权限", 403),
    DATA_SCOPE_DENIED("DATA_SCOPE_DENIED", "当前用户没有访问该数据范围的权限", 403),
    AI_EXTERNAL_NOT_ALLOWED("AI_EXTERNAL_NOT_ALLOWED", "当前资源未授权 AI 外发", 403),
    POLICY_VERSION_CONFLICT("POLICY_VERSION_CONFLICT", "权限策略已被其他管理员更新，请刷新后重试", 409),
    DEV_IDENTITY_FORBIDDEN("DEV_IDENTITY_FORBIDDEN", "生产环境禁止使用开发身份请求头", 403),
    INVALID_SCHEMA("INVALID_SCHEMA", "Schema、Mapping 或命令结构非法", 400),
    OPTIMISTIC_LOCK_CONFLICT("OPTIMISTIC_LOCK_CONFLICT", "草稿已被其他会话更新", 409),
    TEMPLATE_VERSION_IMMUTABLE("TEMPLATE_VERSION_IMMUTABLE", "发布或停用版本不可修改", 409),
    RECOGNITION_UNCONFIRMED("RECOGNITION_UNCONFIRMED", "仍有未确认的识别建议", 422),
    BINDING_INVALID("BINDING_INVALID", "Mapping 存在失效或冲突绑定", 422),
    REVIEW_REQUIRED("REVIEW_REQUIRED", "模板必须审核通过后才能发布", 422),
    REVIEW_REASON_REQUIRED("REVIEW_REASON_REQUIRED", "驳回原因不能为空", 422),
    REVIEW_STATE_CONFLICT("REVIEW_STATE_CONFLICT", "模板审核状态不允许当前操作", 409),
    TEMPLATE_REQUIRED_FIELD_UNBOUND("TEMPLATE_REQUIRED_FIELD_UNBOUND", "存在必填字段未绑定位置", 422),
    TEMPLATE_REQUIRED_VALUE_MISSING("TEMPLATE_REQUIRED_VALUE_MISSING", "存在必填字段未填写", 422),
    DATA_SCHEMA_INVALID("DATA_SCHEMA_INVALID", "业务数据不符合 Schema", 422),
    WORKBOOK_DATA_DIVERGED("WORKBOOK_DATA_DIVERGED", "编辑器值与 JSONB 值不一致，需要显式协调", 422),
    MAPPING_RECONCILIATION_REQUIRED(
            "MAPPING_RECONCILIATION_REQUIRED",
            "结构编辑后存在失效或歧义绑定，需要协调",
            422
    ),
    SNAPSHOT_VERSION_UNSUPPORTED(
            "SNAPSHOT_VERSION_UNSUPPORTED",
            "当前运行时无法安全加载该编辑器快照",
            422
    ),
    FILE_NOT_READY("FILE_NOT_READY", "快照或源文件尚未激活", 423),
    KNOWLEDGE_NOT_READY("KNOWLEDGE_NOT_READY", "知识文件尚未完成解析", 423),
    AI_NOT_CONFIGURED("AI_NOT_CONFIGURED", "AI 模型尚未配置", 503),
    AI_DOCUMENT_NOT_APPROVED("AI_DOCUMENT_NOT_APPROVED", "文件尚未获得 AI 使用授权", 422),
    AI_PROVIDER_UNAVAILABLE("AI_PROVIDER_UNAVAILABLE", "AI 服务暂不可用", 503),
    SNAPSHOT_PERSIST_FAILED("SNAPSHOT_PERSIST_FAILED", "编辑器快照未能安全持久化", 500),
    INTERNAL_ERROR("SYS_INTERNAL_ERROR", "系统内部错误", 500);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    ApiErrorCode(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
