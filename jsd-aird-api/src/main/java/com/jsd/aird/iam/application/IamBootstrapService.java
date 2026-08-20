package com.jsd.aird.iam.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IamBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(IamBootstrapService.class);
    private final IamStore store;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String organizationName;
    private final String adminUsername;
    private final String adminPassword;

    public IamBootstrapService(
            IamStore store,
            PasswordEncoder passwordEncoder,
            @Value("${app.iam.bootstrap.enabled:false}") boolean enabled,
            @Value("${app.iam.bootstrap.organization-name:本地开发组织}") String organizationName,
            @Value("${app.iam.bootstrap.admin-username:admin}") String adminUsername,
            @Value("${app.iam.bootstrap.admin-password:}") String adminPassword
    ) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.organizationName = organizationName;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        var organization = store.defaultOrganization().orElseGet(() ->
                enabled ? store.ensureDefaultOrganization(organizationName) : null);
        if (organization == null) {
            log.warn("IAM 默认组织不存在，跳过权限种子初始化");
            return;
        }

        IamPermissionCatalog.definitions().forEach(store::ensurePermission);
        var roles = seedRoles(organization.id());
        seedRolePermissions(organization.id(), roles);

        if (enabled && !store.hasSystemAdmin(organization.id())) {
            createBootstrapAdmin(organization.id(), roles.get("SYSTEM_ADMIN").id());
        }
    }

    private java.util.Map<String, IamStore.Role> seedRoles(UUID organizationId) {
        return java.util.Map.of(
                "SYSTEM_ADMIN", store.ensureRole(organizationId, "SYSTEM_ADMIN", "系统管理员", true),
                "RND_MANAGER", store.ensureRole(organizationId, "RND_MANAGER", "研发负责人", true),
                "RND_ENGINEER", store.ensureRole(organizationId, "RND_ENGINEER", "研发工程师", true),
                "QUALITY_MANAGER", store.ensureRole(organizationId, "QUALITY_MANAGER", "质量负责人", true),
                "PRODUCTION_OPERATOR", store.ensureRole(organizationId, "PRODUCTION_OPERATOR", "生产管理员", true)
        );
    }

    private void seedRolePermissions(UUID organizationId, java.util.Map<String, IamStore.Role> roles) {
        var all = IamPermissionCatalog.definitions().stream().map(def -> def.code()).toList();
        var responsibility = List.of(
                "customer.view", "customer.create", "customer.update",
                "project.view", "project.create", "project.update", "project.copy", "project.assign",
                "template.view", "template.create", "template.update", "template.upload", "template.copy",
                "template.recognition", "template.review", "template.publish", "template.rollback",
                "template.delete", "template.export", "category.create", "category.update",
                "category.delete", "experiment.view", "experiment.create", "experiment.update",
                "experiment.submit", "experiment.approve", "knowledge.view", "knowledge.upload",
                "knowledge.create", "knowledge.update", "knowledge.submit", "knowledge.review",
                "knowledge.approve", "knowledge.publish", "knowledge.export", "knowledge.download",
                "data.view", "data.create", "data.update", "data.submit", "data.approve", "data.export",
                "data.download", "spectrum.view", "spectrum.create", "spectrum.update", "spectrum.export",
                "spectrum.download", "ai.use", "production.view", "production.create", "production.update",
                "production.submit", "production.cancel", "production.export", "ops.file.view", "ops.file.upload");
        var production = List.of("production.view", "production.create", "production.update", "production.submit",
                "production.cancel", "template.view", "data.view", "ops.file.view", "ops.file.upload");
        var quality = List.of("knowledge.view", "knowledge.upload", "knowledge.create", "knowledge.update",
                "knowledge.submit", "knowledge.review", "knowledge.approve", "knowledge.publish", "data.view",
                "data.create", "data.update", "data.approve", "spectrum.view", "spectrum.create",
                "spectrum.update", "experiment.view", "project.view", "ops.file.view", "ops.file.upload");

        all.forEach(code -> store.ensureRoleBinding(organizationId, roles.get("SYSTEM_ADMIN").id(), new Binding(code, "ALLOW", "ALL", List.of())));
        responsibility.forEach(code -> store.ensureRoleBinding(organizationId, roles.get("RND_MANAGER").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
        responsibility.stream().filter(code -> Set.of("customer.view", "project.view", "project.create", "project.update",
                        "template.view", "template.create", "template.update", "template.upload", "template.copy",
                        "template.recognition", "experiment.view", "experiment.create", "experiment.update",
                        "experiment.submit", "knowledge.view", "knowledge.upload", "knowledge.create",
                        "knowledge.update", "knowledge.submit", "data.view", "data.create", "data.update",
                        "data.submit", "spectrum.view", "spectrum.create", "spectrum.update", "ai.use",
                        "production.view", "production.create", "production.update", "production.submit",
                        "ops.file.view", "ops.file.upload").contains(code))
                .forEach(code -> store.ensureRoleBinding(organizationId, roles.get("RND_ENGINEER").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
        quality.forEach(code -> store.ensureRoleBinding(organizationId, roles.get("QUALITY_MANAGER").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
        production.forEach(code -> store.ensureRoleBinding(organizationId, roles.get("PRODUCTION_OPERATOR").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
    }

    private String defaultScope(String code) {
        return IamPermissionCatalog.definitions().stream().filter(def -> def.code().equals(code))
                .map(def -> def.defaultScope()).findFirst().orElse("ALL");
    }

    private void createBootstrapAdmin(UUID organizationId, UUID adminRoleId) {
        if (adminPassword == null || adminPassword.length() < 12) {
            throw new IllegalStateException("启用 IAM 引导时必须通过环境变量提供至少 12 位管理员密码");
        }
        if (store.user(organizationId, adminUsername).isPresent()) {
            throw new IllegalStateException("IAM 引导管理员用户名已存在但尚未具备系统管理员角色：" + adminUsername);
        }
        store.insertUser(UUID.randomUUID(), organizationId, adminUsername, "系统管理员", null, null,
                null, adminRoleId, passwordEncoder.encode(adminPassword));
        log.info("IAM 首个管理员账号已初始化：{}（密码不会写入日志）", adminUsername);
    }
}
