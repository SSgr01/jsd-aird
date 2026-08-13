package com.jsd.aird.shared.error;

public class ApiException extends RuntimeException {

    private final ApiErrorCode errorCode;
    private final Object detail;

    public ApiException(ApiErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public ApiException(ApiErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ApiException(ApiErrorCode errorCode, String message, Object detail) {
        super(message);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ApiErrorCode errorCode() {
        return errorCode;
    }

    public Object detail() { return detail; }
}
