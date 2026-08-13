package com.jsd.aird.shared.api;

import java.time.Instant;

import com.jsd.aird.shared.error.ApiErrorCode;

public final class ResponseFactory {

    private static final String SUCCESS_CODE = "SUCCESS";
    private static final String SUCCESS_MESSAGE = "成功";

    private ResponseFactory() {
    }

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(
                SUCCESS_CODE,
                SUCCESS_MESSAGE,
                data,
                traceId,
                Instant.now()
        );
    }

    public static ApiResponse<Void> error(ApiErrorCode errorCode, String traceId) {
        return error(errorCode, errorCode.defaultMessage(), traceId);
    }

    public static ApiResponse<Void> error(ApiErrorCode errorCode, String message, String traceId) {
        return new ApiResponse<>(
                errorCode.code(),
                message,
                null,
                traceId,
                Instant.now()
        );
    }

    public static <T> ApiResponse<T> error(ApiErrorCode errorCode, T data, String traceId) {
        return new ApiResponse<>(
                errorCode.code(),
                errorCode.defaultMessage(),
                data,
                traceId,
                Instant.now()
        );
    }

    public static <T> ApiResponse<T> error(ApiErrorCode errorCode, String message, T data, String traceId) {
        return new ApiResponse<>(errorCode.code(), message, data, traceId, Instant.now());
    }
}
