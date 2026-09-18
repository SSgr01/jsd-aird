package com.jsd.aird.ai.rnd.api;

import com.jsd.aird.ai.rnd.modeling.ModelingConfigurationController;
import com.jsd.aird.ai.rnd.eligibility.EligibilityController;
import com.jsd.aird.ai.rnd.facts.UnifiedFactController;
import com.jsd.aird.ai.rnd.training.TrainingController;
import com.jsd.aird.ai.rnd.prediction.PredictionController;
import com.jsd.aird.ai.rnd.research.FormulaDesignController;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Keeps the frozen ai-rnd.v1 failure envelope independent from the legacy API envelope. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ModelingConfigurationController.class, EligibilityController.class,
        UnifiedFactController.class, TrainingController.class, PredictionController.class, FormulaDesignController.class})
public class AiRndExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AiRndExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
        return response(exception.errorCode(), exception.getMessage(), exception.detail());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class
    })
    ResponseEntity<ErrorResponse> handleMalformedRequest(Exception exception) {
        log.debug("Invalid AI R&D request", exception);
        return response(ApiErrorCode.BAD_REQUEST, ApiErrorCode.BAD_REQUEST.defaultMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unexpected AI R&D API error", exception);
        return response(ApiErrorCode.INTERNAL_ERROR, ApiErrorCode.INTERNAL_ERROR.defaultMessage(), null);
    }

    private ResponseEntity<ErrorResponse> response(ApiErrorCode code, String message, Object detail) {
        return ResponseEntity.status(code.httpStatus())
                .body(new ErrorResponse(code.code(), message, RequestIdHolder.currentOrUnknown(), detail));
    }

    public record ErrorResponse(String code, String message, String requestId, Object detail) { }
}
