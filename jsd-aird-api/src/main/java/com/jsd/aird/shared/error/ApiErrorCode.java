package com.jsd.aird.shared.error;

public enum ApiErrorCode {

    VALIDATION_ERROR("SYS_VALIDATION_ERROR", "请求参数校验失败", 422),
    BAD_REQUEST("SYS_BAD_REQUEST", "请求内容无法解析", 400),
    NOT_FOUND("SYS_NOT_FOUND", "资源不存在", 404),
    OPERATION_FORBIDDEN("OPERATION_FORBIDDEN", "当前身份无权执行此操作", 403),
    INVALID_SCHEMA("INVALID_SCHEMA", "Schema、Mapping 或命令结构非法", 400),
    OPTIMISTIC_LOCK_CONFLICT("OPTIMISTIC_LOCK_CONFLICT", "草稿已被其他会话更新", 409),
    TEMPLATE_VERSION_IMMUTABLE("TEMPLATE_VERSION_IMMUTABLE", "发布或停用版本不可修改", 409),
    RECOGNITION_UNCONFIRMED("RECOGNITION_UNCONFIRMED", "仍有未确认的识别建议", 422),
    BINDING_INVALID("BINDING_INVALID", "Mapping 存在失效或冲突绑定", 422),
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
