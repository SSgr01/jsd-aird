package com.jsd.aird.core.adapter.in.web;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.core.application.BusinessObjectService;
import com.jsd.aird.core.application.port.BusinessObjectRepository;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/business-objects")
public class BusinessObjectController {

    private final BusinessObjectService service;

    public BusinessObjectController(BusinessObjectService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<BusinessObjectRepository.ObjectRow>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseFactory.success(service.list(type, keyword, limit), RequestIdHolder.currentOrUnknown());
    }

    @PostMapping
    public ApiResponse<BusinessObjectRepository.ObjectRow> create(@Valid @RequestBody CreateRequest request) {
        return ResponseFactory.success(service.create(request.objectType(), request.externalId(), request.name(),
                request.sourceSystem(), request.metadata()), RequestIdHolder.currentOrUnknown());
    }

    public record CreateRequest(@NotBlank String objectType, @NotBlank @Size(max = 160) String externalId,
                                @NotBlank @Size(max = 260) String name, @Size(max = 80) String sourceSystem,
                                JsonNode metadata) { }
}
