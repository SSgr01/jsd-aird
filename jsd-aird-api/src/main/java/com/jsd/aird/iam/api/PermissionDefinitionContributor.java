package com.jsd.aird.iam.api;

import java.util.List;

public interface PermissionDefinitionContributor {
    List<PermissionDefinition> definitions();

    record PermissionDefinition(String code, String module, String name, String risk, String defaultScope) { }
}
