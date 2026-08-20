package com.jsd.aird.iam.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IamPermissionCatalogTest {

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
        assertThat(codes.stream()
                .filter(code -> !code.startsWith("system."))
                .filter(code -> code.endsWith(".manage")))
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
}
