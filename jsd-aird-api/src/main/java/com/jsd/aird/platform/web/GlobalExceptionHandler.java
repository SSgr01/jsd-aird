package com.jsd.aird.platform.web;

import java.util.List;

import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<List<FieldValidationError>>> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldValidationError(error.getField(), error.getDefaultMessage()))
                .toList();
        var traceId = RequestIdHolder.currentOrUnknown();

        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ResponseFactory.error(ApiErrorCode.VALIDATION_ERROR, errors, traceId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.debug("Unreadable request body", exception);
        return ResponseEntity.status(ApiErrorCode.BAD_REQUEST.httpStatus())
                .body(ResponseFactory.error(
                        ApiErrorCode.BAD_REQUEST,
                        RequestIdHolder.currentOrUnknown()
                ));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.errorCode().httpStatus())
                .body(ResponseFactory.error(
                        exception.errorCode(),
                        exception.getMessage(),
                        RequestIdHolder.currentOrUnknown()
                ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unexpected API error", exception);
        return ResponseEntity.status(ApiErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ResponseFactory.error(
                        ApiErrorCode.INTERNAL_ERROR,
                        RequestIdHolder.currentOrUnknown()
                ));
    }
}
