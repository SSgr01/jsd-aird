package com.jsd.aird.iam.application;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Download and export operations implied by the corresponding page view. */
public final class TransferViewPermissions {
    private static final Map<String, String> VIEWS = buildViews();
    private static final Map<String, String> ALTERNATE_VIEWS = Map.ofEntries(
            Map.entry("template.upload.download", "template.view"),
            Map.entry("template.view.export", "template.view"),
            Map.entry("eln.upload.download", "experiment.view"),
            Map.entry("eln.view.download", "experiment.view"),
            Map.entry("eln.view.export", "experiment.view"),
            Map.entry("research-test.report.upload.download", "research-test.report.view"),
            Map.entry("research-test.report.view.download", "research-test.report.view"),
            Map.entry("research-test.report.view.export", "research-test.report.view"),
            Map.entry("research-test.standard.upload.download", "research-test.standard.view"),
            Map.entry("production.upload.download", "production.view"),
            Map.entry("production.view.download", "production.view"),
            Map.entry("production.view.export", "production.view"),
            Map.entry("knowledge.upload.download", "knowledge.view"),
            Map.entry("data.upload.download", "data.view"),
            Map.entry("data.view.download", "data.view"),
            Map.entry("quality.upload.download", "quality.view"),
            Map.entry("quality.view.download", "quality.view"),
            Map.entry("quality.view.export", "quality.view"),
            Map.entry("spectrum.view.download", "spectrum.view"),
            Map.entry("spectrum.view.export", "spectrum.view"));

    private TransferViewPermissions() { }

    public static String viewFor(String permissionCode) {
        return VIEWS.get(permissionCode);
    }

    public static List<String> viewsFor(String permissionCode) {
        var primary = viewFor(permissionCode);
        if (primary == null) return List.of();
        var alternate = ALTERNATE_VIEWS.get(permissionCode);
        return alternate == null ? List.of(primary) : List.of(primary, alternate);
    }

    public static Set<String> impliedBy(Set<String> granted, Set<String> personallyDenied) {
        var result = new LinkedHashSet<String>();
        VIEWS.forEach((transfer, view) -> {
            if (viewsFor(transfer).stream().anyMatch(granted::contains)
                    && !personallyDenied.contains(transfer)) result.add(transfer);
        });
        return result;
    }

    private static Map<String, String> buildViews() {
        var definitions = IamPermissionCatalog.definitions();
        var codes = definitions.stream().map(definition -> definition.code())
                .collect(java.util.stream.Collectors.toSet());
        var result = new LinkedHashMap<String, String>();
        for (var definition : definitions) {
            var code = definition.code();
            if (!code.endsWith(".download") && !code.endsWith(".export")) continue;
            var view = switch (code) {
                case "customer.export" -> "customer.list.view";
                case "project.export" -> "project.list.view";
                default -> code.substring(0, code.lastIndexOf('.')) + ".view";
            };
            if (!codes.contains(view)) throw new IllegalStateException("Missing view permission for " + code + ": " + view);
            result.put(code, view);
        }
        return Map.copyOf(result);
    }
}
