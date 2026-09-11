package com.jsd.aird.iam.application;

import java.util.List;

import com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition;

/**
 * The business permission catalog deliberately contains atomic actions only.
 * A business permission named {@code *.manage} is not a valid authorization
 * contract: an administrator must be able to grant upload, publish, delete,
 * and review independently.
 */
public final class IamPermissionCatalog {

    private IamPermissionCatalog() { }

    public static List<PermissionDefinition> definitions() {
        return List.of(
                // System administration is intentionally outside this atomic business-permission migration.
                p("system.user.view", "system", "查看用户", "HIGH", "ALL"),
                p("system.user.manage", "system", "管理用户", "CRITICAL", "ALL"),
                p("system.role.view", "system", "查看角色", "HIGH", "ALL"),
                p("system.role.create", "system", "创建角色", "CRITICAL", "ALL"),
                p("system.role.manage", "system", "管理角色", "CRITICAL", "ALL"),
                p("system.permission.manage", "system", "配置权限", "CRITICAL", "ALL"),
                p("system.audit.view", "system", "查看操作日志", "HIGH", "ALL"),
                p("system.dictionary.manage", "system", "管理基础字典", "HIGH", "ALL"),
                p("system.parameter.manage", "system", "管理系统参数", "CRITICAL", "ALL"),

                p("customer.view", "customer", "查看客户", "LOW", "ALL"),
                p("customer.create", "customer", "新建客户", "MEDIUM", "SELF"),
                p("customer.update", "customer", "编辑客户", "MEDIUM", "SELF"),
                p("customer.delete", "customer", "删除客户资料", "HIGH", "SELF"),

                p("project.view", "project", "查看项目", "LOW", "ALL"),
                p("project.export", "project", "导出项目", "HIGH", "ALL"),
                p("project.create", "project", "新建项目", "MEDIUM", "SELF"),
                p("project.update", "project", "编辑项目", "MEDIUM", "SELF"),
                p("project.copy", "project", "复制项目", "MEDIUM", "SELF"),
                p("project.delete", "project", "删除项目", "HIGH", "SELF"),
                p("project.assign", "project", "关联项目资料/分配项目成员", "HIGH", "PROJECT"),

                p("template.view", "template", "查看模板", "LOW", "ALL"),
                p("template.create", "template", "新建模板", "MEDIUM", "SELF"),
                p("template.update", "template", "编辑模板/移动分类/新建修订", "MEDIUM", "SELF"),
                p("template.upload", "template", "上传模板文件", "MEDIUM", "ALL"),
                p("template.copy", "template", "复制模板", "MEDIUM", "ALL"),
                p("template.recognition", "template", "识别与复核模板字段", "HIGH", "ALL"),
                p("template.review", "template", "提交/通过/驳回模板审核", "HIGH", "ALL"),
                p("template.publish", "template", "发布模板", "HIGH", "ALL"),
                p("template.rollback", "template", "回退历史模板版本", "HIGH", "ALL"),
                p("template.delete", "template", "删除/停用模板", "CRITICAL", "ALL"),
                p("template.export", "template", "导出模板", "HIGH", "ALL"),
                p("category.create", "template", "新建模板分类", "MEDIUM", "ALL"),
                p("category.update", "template", "编辑模板分类", "MEDIUM", "ALL"),
                p("category.delete", "template", "删除模板分类", "HIGH", "ALL"),

                p("experiment.view", "experiment", "查看实验记录", "LOW", "ALL"),
                p("experiment.create", "experiment", "新建实验记录", "MEDIUM", "SELF"),
                p("experiment.update", "experiment", "编辑实验记录", "MEDIUM", "SELF"),
                p("experiment.delete", "experiment", "删除实验记录", "HIGH", "SELF"),
                p("experiment.submit", "experiment", "提交实验审核", "MEDIUM", "SELF"),
                p("experiment.approve", "experiment", "通过实验审核", "HIGH", "ASSIGNED"),
                p("experiment.review", "experiment", "退回/作废实验记录", "HIGH", "ASSIGNED"),

                p("research-test.report.view", "research-test", "查看综合测试报告", "LOW", "ALL"),
                p("research-test.report.create", "research-test", "新建综合测试报告", "MEDIUM", "SELF"),
                p("research-test.report.update", "research-test", "编辑综合测试报告", "MEDIUM", "SELF"),
                p("research-test.report.delete", "research-test", "删除综合测试报告", "HIGH", "SELF"),
                p("research-test.report.submit", "research-test", "提交综合测试报告审核", "MEDIUM", "SELF"),
                p("research-test.report.approve", "research-test", "审核综合测试报告", "HIGH", "ASSIGNED"),
                p("research-test.report.publish", "research-test", "发布综合测试报告", "HIGH", "ASSIGNED"),
                p("research-test.report.export", "research-test", "导出综合测试报告", "MEDIUM", "ALL"),
                p("research-test.standard.view", "research-test", "查看测试标准", "LOW", "ALL"),
                p("research-test.standard.create", "research-test", "新建测试标准", "MEDIUM", "SELF"),
                p("research-test.standard.update", "research-test", "编辑测试标准", "MEDIUM", "SELF"),
                p("research-test.standard.delete", "research-test", "删除测试标准", "HIGH", "SELF"),
                p("research-test.standard.submit", "research-test", "提交测试标准审核", "MEDIUM", "SELF"),
                p("research-test.standard.approve", "research-test", "审核测试标准", "HIGH", "ASSIGNED"),
                p("research-test.standard.publish", "research-test", "发布测试标准", "HIGH", "ASSIGNED"),
                p("research-test.standard.export", "research-test", "导出测试标准", "MEDIUM", "ALL"),

                p("production.view", "production", "查看生产单", "LOW", "ALL"),
                p("production.create", "production", "新建生产单", "MEDIUM", "SELF"),
                p("production.update", "production", "编辑生产单草稿", "MEDIUM", "SELF"),
                p("production.delete", "production", "删除生产单", "HIGH", "SELF"),
                p("production.submit", "production", "提交生产单", "HIGH", "SELF"),
                p("production.cancel", "production", "取消生产单", "HIGH", "ASSIGNED"),
                p("production.export", "production", "导出生产单", "HIGH", "ALL"),

                p("knowledge.view", "knowledge", "查看知识文档", "LOW", "ALL"),
                p("knowledge.upload", "knowledge", "上传知识文档", "MEDIUM", "SELF"),
                p("knowledge.create", "knowledge", "新建知识文档", "MEDIUM", "SELF"),
                p("knowledge.update", "knowledge", "编辑/移动知识文档", "MEDIUM", "SELF"),
                p("knowledge.delete", "knowledge", "删除知识文档", "HIGH", "SELF"),
                p("knowledge.submit", "knowledge", "提交知识文档审核", "MEDIUM", "SELF"),
                p("knowledge.review", "knowledge", "审核/驳回知识文档", "HIGH", "ASSIGNED"),
                p("knowledge.approve", "knowledge", "通过知识文档审核", "HIGH", "ASSIGNED"),
                p("knowledge.publish", "knowledge", "发布知识文档", "HIGH", "ALL"),
                p("knowledge.export", "knowledge", "导出知识文档", "HIGH", "ALL"),
                p("knowledge.download", "knowledge", "下载知识文档", "HIGH", "ALL"),
                p("data.view", "data", "查看数据", "LOW", "ALL"),
                p("data.create", "data", "新建/导入数据任务", "MEDIUM", "SELF"),
                p("data.update", "data", "编辑数据任务", "MEDIUM", "SELF"),
                p("data.delete", "data", "删除数据", "CRITICAL", "SELF"),
                p("data.submit", "data", "提交数据导入", "HIGH", "SELF"),
                p("data.approve", "data", "审核训练数据", "HIGH", "ASSIGNED"),
                p("data.export", "data", "导出数据", "HIGH", "ALL"),
                p("data.download", "data", "下载数据", "HIGH", "ALL"),

                p("quality.view", "quality", "查看品管数据", "LOW", "ALL"),
                p("quality.upload", "quality", "上传品管源文件", "MEDIUM", "ALL"),
                p("quality.create", "quality", "新增品管数据", "MEDIUM", "ALL"),
                p("quality.update", "quality", "编辑/移动品管数据", "MEDIUM", "ALL"),
                p("quality.delete", "quality", "删除品管数据", "HIGH", "ALL"),
                p("quality.publish", "quality", "发布品管版本", "HIGH", "ALL"),
                p("quality.export", "quality", "导出品管数据", "HIGH", "ALL"),

                p("inventory.view", "inventory", "查看库存", "LOW", "ALL"),
                p("inventory.create", "inventory", "新增库存业务", "MEDIUM", "ALL"),
                p("inventory.update", "inventory", "维护库存策略", "MEDIUM", "ALL"),
                p("inventory.reverse", "inventory", "冲销库存流水", "HIGH", "ALL"),

                p("spectrum.view", "spectrum", "查看谱图/图谱", "LOW", "ALL"),
                p("spectrum.create", "spectrum", "新建图谱", "MEDIUM", "SELF"),
                p("spectrum.update", "spectrum", "编辑图谱", "MEDIUM", "SELF"),
                p("spectrum.delete", "spectrum", "删除图谱", "HIGH", "SELF"),
                p("spectrum.export", "spectrum", "导出图谱", "HIGH", "ALL"),
                p("spectrum.download", "spectrum", "下载谱图数据", "HIGH", "ALL"),
                p("ai.use", "ai", "使用 AI 能力", "MEDIUM", "ALL"),
                p("ai.model.manage", "ai", "管理配方模型", "CRITICAL", "ALL"),
                p("ai.external", "ai", "允许知识文档 AI 授权", "CRITICAL", "SELECTED"),
                p("ops.file.view", "ops", "查看文件", "LOW", "ALL"),
                p("ops.file.upload", "ops", "上传文件", "MEDIUM", "SELF"),
                p("ops.file.download", "ops", "下载文件", "HIGH", "ALL")
        );
    }

    private static PermissionDefinition p(String code, String module, String name, String risk, String scope) {
        return new PermissionDefinition(code, module, name, risk, scope);
    }
}
