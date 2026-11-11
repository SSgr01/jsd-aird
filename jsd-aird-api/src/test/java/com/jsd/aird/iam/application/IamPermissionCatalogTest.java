package com.jsd.aird.iam.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class IamPermissionCatalogTest {

    @Test
    void businessPermissionDefaultsAreAllAtEveryBuiltInLevel() {
        var definitions = IamPermissionCatalog.definitions();
        assertThat(definitions).allSatisfy(definition ->
                assertThat(definition.defaultScope()).isEqualTo("ALL"));
        PermissionModelCatalog.levels().forEach(level ->
                assertThat(PermissionModelCatalog.presetLevelBindings(level.code(), definitions))
                        .allSatisfy(binding -> assertThat(binding.scopeType()).isEqualTo("ALL")));
    }

    @Test
    void businessPermissionsAreAtomicAndCodesAreUnique() {
        var definitions = IamPermissionCatalog.definitions();
        var codes = definitions.stream().map(definition -> definition.code()).toList();

        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).contains(
                "template.create", "template.update", "template.upload", "template.copy",
                "template.recognition", "template.review", "template.publish", "template.rollback",
                "template.delete", "template.export", "category.create", "category.update",
                "category.delete", "project.create", "project.update", "project.copy",
                "project.delete", "project.assign");
        var explicitlyDesignedManagementPermissions = Set.of(
                "ai.modeling.manage", "ai.config.manage");
        assertThat(codes.stream()
                .filter(code -> !code.startsWith("system."))
                .filter(code -> code.endsWith(".manage"))
                .filter(code -> !explicitlyDesignedManagementPermissions.contains(code)))
                .isEmpty();
    }

    @Test
    void administratorFacingNamesExplainTheAction() {
        var byCode = IamPermissionCatalog.definitions().stream()
                .collect(java.util.stream.Collectors.toMap(definition -> definition.code(), definition -> definition.name()));

        assertThat(byCode).containsEntry("template.upload", "上传模板文件")
                .containsEntry("template.publish", "发布模板")
                .containsEntry("template.review", "提交/通过/驳回模板审核")
                .containsEntry("project.create", "新建项目")
                .containsEntry("project.update", "编辑项目")
                .containsEntry("project.delete", "删除项目")
                .containsEntry("project.assign", "关联项目资料/分配项目成员");
    }

    @Test
    void featureMenusExposeOnlyApplicableStandardActions() {
        var codes = IamPermissionCatalog.definitions().stream().map(definition -> definition.code()).collect(java.util.stream.Collectors.toSet());
        assertThat(codes).contains("eln.view.create", "eln.view.approve", "quality.view.publish",
                "research-test.report.view.approve", "production.view.delete", "project.meeting.publish",
                "knowledge.review.publish", "template.upload.download", "template.upload.retry",
                "research-test.report.upload.delete", "production.upload.download",
                "knowledge.upload.retry", "data.upload.delete", "quality.upload.download");
        assertThat(codes).doesNotContain("ai.search.create", "project.materials.delete");
        assertThat(codes).doesNotContain("spectrum.view.publish");
    }

    @Test
    void pageMenusOnlyImplyReadPermissions() {
        assertThat(MenuViewPermissions.impliedBy(Set.of("template.view", "eln.view", "system.users")))
                .contains("template.view", "experiment.view", "system.user.view")
                .doesNotContain("template.view.create", "eln.view.delete", "system.users.reset");
    }
}
