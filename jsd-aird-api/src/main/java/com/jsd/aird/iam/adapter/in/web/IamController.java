package com.jsd.aird.iam.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jsd.aird.iam.application.IamAdminService;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/iam")
public class IamController {

    private final IamAdminService service;

    public IamController(IamAdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public ApiResponse<PageResponse<IamAdminService.UserView>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(service.users(ActorContext.required(), keyword, page, size));
    }

    @PostMapping("/users")
    public ApiResponse<IamAdminService.CreateUserResult> createUser(@Valid @RequestBody CreateUserRequest request) {
        return success(service.create(ActorContext.required(), new IamAdminService.CreateUserCommand(
                request.username(), request.displayName(), request.email(), request.phone(), request.departmentName(), request.roleId(), request.password())));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<IamAdminService.UserView> user(@PathVariable UUID userId) {
        return success(service.user(ActorContext.required(), userId));
    }

    @PatchMapping("/users/{userId}")
    public ApiResponse<IamAdminService.UserView> updateUser(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest request) {
        return success(service.update(ActorContext.required(), userId, new IamAdminService.UpdateUserCommand(
                request.displayName(), request.email(), request.phone(), request.departmentName(), request.roleId())));
    }

    @PostMapping("/users/{userId}/enable")
    public ApiResponse<Void> enable(@PathVariable UUID userId) { service.setStatus(ActorContext.required(), userId, true); return success(null); }

    @PostMapping("/users/{userId}/disable")
    public ApiResponse<Void> disable(@PathVariable UUID userId) { service.setStatus(ActorContext.required(), userId, false); return success(null); }

    @PostMapping("/users/{userId}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable UUID userId, @Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(ActorContext.required(), userId, request.password());
        return success(null);
    }

    @PostMapping("/users/{userId}/force-logout")
    public ApiResponse<Void> forceLogout(@PathVariable UUID userId) { service.forceLogout(ActorContext.required(), userId); return success(null); }

    @GetMapping("/roles")
    public ApiResponse<List<IamAdminService.RoleView>> roles() { return success(service.roles(ActorContext.required())); }

    @PostMapping("/roles")
    public ApiResponse<IamAdminService.RoleView> createRole(@Valid @RequestBody RoleRequest request) {
        return success(service.createRole(ActorContext.required(), request.code(), request.name()));
    }

    @PatchMapping("/roles/{roleId}")
    public ApiResponse<Void> renameRole(@PathVariable UUID roleId, @Valid @RequestBody RenameRoleRequest request) {
        service.renameRole(ActorContext.required(), roleId, request.name()); return success(null);
    }

    @DeleteMapping("/roles/{roleId}")
    public ApiResponse<Void> deleteRole(@PathVariable UUID roleId) { service.deleteRole(ActorContext.required(), roleId); return success(null); }

    @GetMapping("/roles/{roleId}/permissions")
    public ApiResponse<PermissionPayload> rolePermissions(@PathVariable UUID roleId) {
        var actor = ActorContext.required();
        var role = service.roles(actor).stream().filter(item -> item.id().equals(roleId)).findFirst().orElseThrow();
        return success(new PermissionPayload(role.policyVersion(), service.rolePermissions(actor, roleId)));
    }

    @PutMapping("/roles/{roleId}/permissions")
    public ApiResponse<VersionPayload> saveRolePermissions(@PathVariable UUID roleId, @Valid @RequestBody PermissionRequest request) {
        var version = service.saveRolePermissions(ActorContext.required(), roleId, request.expectedVersion(), toBindings(request.bindings()));
        return success(new VersionPayload(version));
    }

    @GetMapping("/users/{userId}/permissions")
    public ApiResponse<PermissionPayload> userPermissions(@PathVariable UUID userId) {
        var result = service.userPermissions(ActorContext.required(), userId);
        return success(new PermissionPayload(result.version(), result.bindings()));
    }

    @PutMapping("/users/{userId}/permissions")
    public ApiResponse<VersionPayload> saveUserPermissions(@PathVariable UUID userId, @Valid @RequestBody PermissionRequest request) {
        var version = service.saveUserPermissions(ActorContext.required(), userId, request.expectedVersion(), toBindings(request.bindings()));
        return success(new VersionPayload(version));
    }

    @DeleteMapping("/users/{userId}/permissions/{permissionCode}")
    public ApiResponse<Void> restoreUserPermission(@PathVariable UUID userId, @PathVariable String permissionCode) {
        service.deleteUserOverride(ActorContext.required(), userId, permissionCode); return success(null);
    }

    @GetMapping("/permission-definitions")
    public ApiResponse<List<com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition>> definitions() {
        return success(service.permissionDefinitions(ActorContext.required()));
    }

    @GetMapping("/audit-logs")
    public ApiResponse<List<AuditLogFacade.AuditEntry>> auditLogs(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        return success(service.auditLogs(ActorContext.required(), actorId, action, from, to, limit));
    }

    private List<Binding> toBindings(List<BindingRequest> requests) {
        return (requests == null ? List.<BindingRequest>of() : requests).stream()
                .map(item -> new Binding(item.permissionCode(), item.effect(), item.scopeType(), item.targetIds() == null ? List.of() : item.targetIds()))
                .toList();
    }

    private <T> ApiResponse<T> success(T data) { return ResponseFactory.success(data, RequestIdHolder.currentOrUnknown()); }

    public record CreateUserRequest(@NotBlank String username, @NotBlank String displayName, String email, String phone,
                                    String departmentName, @NotNull UUID roleId,
                                    @NotBlank @jakarta.validation.constraints.Size(min = 6, max = 200) String password) { }
    public record ResetPasswordRequest(@NotBlank @jakarta.validation.constraints.Size(min = 6, max = 200) String password) { }
    public record UpdateUserRequest(@NotBlank String displayName, String email, String phone, String departmentName,
                                    @NotNull UUID roleId) { }
    public record RoleRequest(@NotBlank String code, @NotBlank String name) { }
    public record RenameRoleRequest(@NotBlank String name) { }
    public record PermissionRequest(long expectedVersion, List<BindingRequest> bindings) { }
    public record BindingRequest(@NotBlank String permissionCode, @NotBlank String effect, @NotBlank String scopeType,
                                 List<UUID> targetIds) { }
    public record PermissionPayload(long version, List<Binding> bindings) { }
    public record VersionPayload(long version) { }
}
