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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
        var resource=com.jsd.aird.shared.resource.ResourceFailures.code(exception);
        if(resource!=null)return resourceError(resource);
        log.debug("Unreadable request body", exception);
        return ResponseEntity.status(ApiErrorCode.BAD_REQUEST.httpStatus())
                .body(ResponseFactory.error(
                        ApiErrorCode.BAD_REQUEST,
                        RequestIdHolder.currentOrUnknown()
                ));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class})
    ResponseEntity<ApiResponse<Void>> handleInvalidParameter(Exception exception) {
        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ResponseFactory.error(ApiErrorCode.VALIDATION_ERROR, RequestIdHolder.currentOrUnknown()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> handleMalformedParameter(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.status(ApiErrorCode.BAD_REQUEST.httpStatus())
                .body(ResponseFactory.error(ApiErrorCode.BAD_REQUEST, RequestIdHolder.currentOrUnknown()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException exception) {
        log.debug("API resource not found: {}", exception.getResourcePath());
        return ResponseEntity.status(ApiErrorCode.NOT_FOUND.httpStatus())
                .body(ResponseFactory.error(
                        ApiErrorCode.NOT_FOUND,
                        RequestIdHolder.currentOrUnknown()
                ));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<?>> handleApiException(ApiException exception) {
        var builder=ResponseEntity.status(exception.errorCode().httpStatus());
        if(exception.errorCode()==ApiErrorCode.SERVER_BUSY || exception.errorCode()==ApiErrorCode.DATABASE_BUSY)builder.header("Retry-After","2");
        return builder
                .body(exception.detail() == null
                        ? ResponseFactory.error(exception.errorCode(), exception.getMessage(), RequestIdHolder.currentOrUnknown())
                        : ResponseFactory.error(exception.errorCode(), exception.getMessage(), exception.detail(),
                                RequestIdHolder.currentOrUnknown()));
    }

    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    ResponseEntity<ApiResponse<Void>> handleConcurrentTransaction(org.springframework.dao.ConcurrencyFailureException exception) {
        var code=com.jsd.aird.shared.resource.ResourceFailures.code(exception);
        if(code==null)code=ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT;
        return resourceError(code);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> handleTooLarge(Exception exception){return resourceError(ApiErrorCode.PAYLOAD_TOO_LARGE);}

    private ResponseEntity<ApiResponse<Void>> resourceError(ApiErrorCode code){
        var builder=ResponseEntity.status(code.httpStatus());if(code==ApiErrorCode.DATABASE_BUSY || code==ApiErrorCode.SERVER_BUSY)builder.header("Retry-After","2");
        return builder.body(ResponseFactory.error(code,RequestIdHolder.currentOrUnknown()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        var resource=com.jsd.aird.shared.resource.ResourceFailures.code(exception);
        if(resource!=null)return resourceError(resource);
        log.error("Unexpected API error", exception);
        return ResponseEntity.status(ApiErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ResponseFactory.error(
                        ApiErrorCode.INTERNAL_ERROR,
                        RequestIdHolder.currentOrUnknown()
                ));
    }
}
