package com.jsd.aird.shared.error;

public enum ApiErrorCode {

    VALIDATION_ERROR("SYS_VALIDATION_ERROR", "请求参数校验失败", 422),
    BAD_REQUEST("SYS_BAD_REQUEST", "请求内容无法解析", 400),
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
