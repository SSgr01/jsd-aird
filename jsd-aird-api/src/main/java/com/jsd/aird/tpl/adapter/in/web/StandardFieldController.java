package com.jsd.aird.tpl.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.tpl.application.StandardFieldService;
import com.jsd.aird.tpl.application.port.StandardFieldRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StandardFieldController {

    private final StandardFieldService service;

    public StandardFieldController(StandardFieldService service) {
        this.service = service;
    }

    @GetMapping("/standard-fields")
    public ApiResponse<List<StandardFieldRepository.StandardField>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String valueType
    ) {
        return success(service.search(keyword, valueType));
    }

    @PostMapping("/standard-field-requests")
    public ApiResponse<StandardFieldRepository.Request> request(
            @Valid @RequestBody StandardFieldRequestBody request
    ) {
        return success(service.request(new StandardFieldService.RequestCommand(
                request.templateVersionId(), request.fieldId(), request.displayName(), request.valueType(),
                request.uiType(), request.groupCode(), request.description()
        )));
    }

    @GetMapping("/standard-field-requests")
    public ApiResponse<List<StandardFieldRepository.Request>> requests(
            @RequestParam(required = false) String status
    ) {
        return success(service.requests(status));
    }

    @PostMapping("/standard-field-requests/{requestId}/approve")
    public ApiResponse<StandardFieldRepository.StandardField> approve(
            @PathVariable UUID requestId,
            @RequestBody(required = false) ApprovalBody body
    ) {
        var value = body == null ? new ApprovalBody(null, null) : body;
        return success(service.approve(requestId, new StandardFieldService.ApprovalCommand(
                value.fieldCode(), value.reviewComment()
        )));
    }

    @PostMapping("/standard-field-requests/{requestId}/reject")
    public ApiResponse<Void> reject(
            @PathVariable UUID requestId,
            @RequestBody(required = false) RejectBody body
    ) {
        service.reject(requestId, body == null ? null : body.reviewComment());
        return success(null);
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record StandardFieldRequestBody(
            @NotNull UUID templateVersionId,
            String fieldId,
            @NotBlank String displayName,
            String valueType,
            String uiType,
            String groupCode,
            String description
    ) {
    }

    public record ApprovalBody(String fieldCode, String reviewComment) {
    }

    public record RejectBody(String reviewComment) {
    }
}
