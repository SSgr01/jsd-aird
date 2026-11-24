package com.jsd.aird.iam.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.iam.application.DepartmentTreeService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/iam/departments")
public class DepartmentTreeController {
    private final DepartmentTreeService service;

    public DepartmentTreeController(DepartmentTreeService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<DepartmentTreeService.DepartmentNode>> list() {
        return success(service.list(ActorContext.required()));
    }

    @GetMapping("/{id}/users")
    public ApiResponse<List<DepartmentTreeService.DepartmentUser>> users(@PathVariable UUID id) {
        return success(service.users(ActorContext.required(), id));
    }

    @PostMapping
    public ApiResponse<DepartmentTreeService.DepartmentNode> create(@Valid @RequestBody DepartmentRequest request) {
        return success(service.create(ActorContext.required(), request.name(), request.parentId(), request.sortOrder()));
    }

    @PatchMapping("/{id}")
    public ApiResponse<DepartmentTreeService.DepartmentNode> update(@PathVariable UUID id,
                                                                      @Valid @RequestBody DepartmentRequest request) {
        return success(service.update(ActorContext.required(), id, request.name(), request.parentId(), request.sortOrder()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(ActorContext.required(), id);
        return success(null);
    }

    private <T> ApiResponse<T> success(T data) {
        return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown());
    }

    public record DepartmentRequest(@NotBlank String name, UUID parentId,
                                    @Min(0) @Max(999999) Integer sortOrder) { }
}
