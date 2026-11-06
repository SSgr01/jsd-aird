package com.jsd.aird.iam.application;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Read permissions implied by an explicitly visible page menu. */
public final class MenuViewPermissions {

    private static final Set<String> DEFINED = IamPermissionCatalog.definitions().stream()
            .map(definition -> definition.code())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static final Map<String, String> PAGE_VIEWS = Map.ofEntries(
            Map.entry("customer.list", "customer.view"),
            Map.entry("customer.overview", "customer.view"),
            Map.entry("customer.needs", "customer.view"),
            Map.entry("customer.projects", "customer.view"),
            Map.entry("customer.followup", "customer.view"),
            Map.entry("template.view", "template.view"),
            Map.entry("project.list", "project.view"),
            Map.entry("project.stage", "project.view"),
            Map.entry("project.task", "project.view"),
            Map.entry("project.document", "project.view"),
            Map.entry("project.customer-needs", "project.view"),
            Map.entry("project.reference", "project.view"),
            Map.entry("project.meeting", "project.view"),
            Map.entry("project.log", "project.view"),
            Map.entry("project.materials", "project.view"),
            Map.entry("eln.view", "experiment.view"),
            Map.entry("research-test.report.view", "research-test.report.view"),
            Map.entry("research-test.standard", "research-test.standard.view"),
            Map.entry("quality.view", "quality.view"),
            Map.entry("spectrum.view", "spectrum.view"),
            Map.entry("production.view", "production.view"),
            Map.entry("knowledge.view", "knowledge.view"),
            Map.entry("data.view", "data.view"),
            Map.entry("inventory.query", "inventory.view"),
            Map.entry("inventory.rnd", "inventory.view"),
            Map.entry("inventory.production", "inventory.view"),
            Map.entry("inventory.sample", "inventory.view"),
            Map.entry("inventory.shipment", "inventory.view"),
            Map.entry("system.users", "system.user.view"),
            Map.entry("system.permissions", "system.role.view"),
            Map.entry("system.audit", "system.audit.view"),
            Map.entry("ai.qa", "ai.use"),
            Map.entry("ai.model", "ai.model.read")
    );

    private MenuViewPermissions() { }

    public static Set<String> impliedBy(Set<String> menuKeys) {
        var permissions = new LinkedHashSet<String>();
        for (var key : menuKeys) {
            if (key.endsWith(".view") && DEFINED.contains(key)) permissions.add(key);
            if (DEFINED.contains(key + ".view")) permissions.add(key + ".view");
            var view = PAGE_VIEWS.get(key);
            if (view != null) permissions.add(view);
        }
        return permissions;
    }
}
