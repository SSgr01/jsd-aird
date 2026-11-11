package com.jsd.aird.iam.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.jsd.aird.iam.application.port.IamStore.Binding;

/**
 * Canonical permission vocabulary shared by the v2 IAM seed and its adapters.
 * Menu keys intentionally stay independent from atomic operation codes: a
 * role can open a feature without granting a write action, while a level can
 * grant an action without opening an unrelated menu.
 */
public final class PermissionModelCatalog {

    private PermissionModelCatalog() { }

    public record Menu(String key, String label, String parent, String route) { }

    public record Level(String code, String name, String description, int tier, boolean builtin) { }

    private static final List<Menu> MENUS = List.of(
            m("workbench", "工作台", null, "/"),
            m("customer", "客户管理", null, "/partners"),
            m("customer.list", "客户列表", "customer", "/partners"),
            m("customer.overview", "客户概览", "customer", "/partners"),
            m("customer.needs", "客户需求", "customer", "/partners"),
            m("customer.projects", "关联项目", "customer", "/partners"),
            m("customer.followup", "跟进记录", "customer", "/partners"),
            m("template", "模板中心", null, "/templates/library"),
            m("template.upload", "模板上传", "template", "/templates/upload"),
            m("template.view", "模板查看", "template", "/templates/library"),
            m("template.category", "模板分类", "template", "/templates/library"),
            m("project", "项目管理", null, "/projects/list"),
            m("project.list", "项目列表", "project", "/projects/list"),
            m("project.stage", "阶段", "project", "/projects/list"),
            m("project.task", "任务", "project", "/projects/list"),
            m("project.document", "项目文档", "project", "/projects/list"),
            m("project.customer-needs", "客户需求", "project", "/projects/list"),
            m("project.reference", "资料参考", "project", "/projects/list"),
            m("project.meeting", "会议纪要", "project", "/projects/list"),
            m("project.log", "项目日志", "project", "/projects/list"),
            m("project.materials", "关联资料", "project", "/projects/list"),
            m("eln", "电子实验记录本", null, "/experiments/list"),
            m("eln.create", "新建实验", "eln", "/experiments/new"),
            m("eln.upload", "实验上传", "eln", "/experiments/upload"),
            m("eln.view", "实验查看", "eln", "/experiments/list"),
            m("eln.category", "实验分类", "eln", "/experiments/list"),
            m("research-test", "研发测试中心", null, "/research-test/reports"),
            m("research-test.report.upload", "综合测试报告上传", "research-test", "/research-test/upload"),
            m("research-test.report.view", "综合测试报告查看", "research-test", "/research-test/reports"),
            m("research-test.standard.upload", "测试标准上传", "research-test", "/research-test/standard-upload"),
            m("research-test.standard", "测试标准方法", "research-test", "/research-test/standards"),
            m("quality", "品管部数据", null, "/quality/view"),
            m("quality.upload", "品管数据上传", "quality", "/quality/upload"),
            m("quality.view", "品管数据查看", "quality", "/quality/view"),
            m("quality.category", "品管数据分类", "quality", "/quality/view"),
            m("spectrum", "图谱中心", null, "/spectrum/view"),
            m("spectrum.upload", "图谱上传", "spectrum", "/spectrum/view"),
            m("spectrum.view", "图谱查看", "spectrum", "/spectrum/view"),
            m("spectrum.ai", "AI图谱分析", "spectrum", "/spectrum/view"),
            m("spectrum.category", "图谱分类", "spectrum", "/spectrum/view"),
            m("production", "生产单管理", null, "/production-orders/list"),
            m("production.upload", "生产单上传", "production", "/production-orders/list"),
            m("production.view", "生产单查看", "production", "/production-orders/list"),
            m("ai", "AI研发助手", null, "/assistant"),
            m("ai.search", "文件检索", "ai", "/knowledge/search"),
            m("ai.qa", "AI问答", "ai", "/assistant"),
            m("ai.formula", "AI配方预测", "ai", "/assistant"),
            m("ai.analysis", "AI研发分析", "ai", "/assistant"),
            m("ai.optimization", "AI实验优化", "ai", "/assistant/experiment-optimization"),
            m("ai.model", "模型中心", "ai", "/assistant/model-center"),
            m("knowledge", "研发知识库", null, "/knowledge/view"),
            m("knowledge.review", "审核工作台", "knowledge", "/knowledge/review"),
            m("knowledge.upload", "资料上传", "knowledge", "/knowledge/library"),
            m("knowledge.view", "知识库查看", "knowledge", "/knowledge/view"),
            m("knowledge.category", "知识分类", "knowledge", "/knowledge/view"),
            m("data", "数据中心", null, "/data/view"),
            m("data.upload", "数据上传", "data", "/data/upload"),
            m("data.view", "数据查看", "data", "/data/view"),
            m("data.category", "数据分类", "data", "/data/view"),
            m("inventory", "库存管理", null, "/inventory/rnd"),
            m("inventory.query", "库存查询", "inventory", "/inventory/query"),
            m("inventory.rnd", "研发库存表", "inventory", "/inventory/rnd"),
            m("inventory.production", "生产库存表", "inventory", "/inventory/production"),
            m("inventory.sample", "发样记录表", "inventory", "/inventory/samples"),
            m("inventory.shipment", "出货记录表", "inventory", "/inventory/shipments"),
            m("system", "系统设置", null, "/system/users"),
            m("system.users", "用户管理", "system", "/system/users"),
            m("system.permissions", "用户权限配置", "system", "/system/user-permissions"),
            m("system.dictionary", "基础字典", "system", "/system/dictionary"),
            m("system.audit", "操作日志", "system", "/system/audit-logs"),
            m("system.parameters", "系统参数配置", "system", "/system/parameters")
    );

    private static final List<Level> LEVELS = List.of(
            new Level("L0_VIEWER", "L0 基础查看级", "新员工/试用期：以查看、查询、检索为主；数据范围优先本人或最小参与范围。", 0, true),
            new Level("L1_OPERATOR", "L1 标准操作级", "正式员工：在基础查看上增加新增、编辑、上传、保存、提交审核和日常关联等操作。", 1, true),
            new Level("L2_ADVANCED", "L2 高级操作级", "业务骨干：增加复制、导入导出、下载、分类管理、调整及更大数据范围。", 2, true),
            new Level("L3_ADMIN", "L3 管理级", "业务负责人/管理员：增加删除、审核、发布、停用及系统管理类高风险操作；数据范围默认全部。", 3, true)
    );

    public static List<Menu> menus() { return MENUS; }

    public static List<Level> levels() { return LEVELS; }

    public static Set<String> allMenuKeys() {
        return MENUS.stream().map(Menu::key).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public static List<String> presetMenus(String roleCode) {
        if ("SYSTEM_ADMIN".equals(roleCode)) return new ArrayList<>(allMenuKeys());
        var common = new LinkedHashSet<String>();
        common.add("workbench");
        switch (roleCode) {
            case "RND_MANAGER" -> common.addAll(List.of("customer", "project", "template", "eln", "research-test",
                    "spectrum", "ai", "knowledge", "data", "production", "inventory"));
            case "RND_ENGINEER" -> common.addAll(List.of("customer", "project", "template", "eln", "research-test",
                    "spectrum", "ai", "knowledge", "data"));
            case "QUALITY_MANAGER" -> common.addAll(List.of("project", "research-test", "quality", "spectrum", "ai", "knowledge"));
            case "PRODUCTION_OPERATOR" -> common.addAll(List.of("project", "production", "inventory", "knowledge"));
            case "普通用户", "USER", "" -> common.addAll(List.of("project", "eln", "research-test", "ai", "knowledge"));
            default -> common.addAll(List.of("project", "knowledge", "ai"));
        }
        var expanded = new LinkedHashSet<String>(common);
        MENUS.stream().filter(menu -> menu.parent() != null && common.contains(menu.parent())).forEach(menu -> expanded.add(menu.key()));
        return new ArrayList<>(expanded);
    }

    public static String defaultLevelCode(String roleCode) {
        return switch (roleCode) {
            case "SYSTEM_ADMIN" -> "L3_ADMIN";
            case "RND_MANAGER", "QUALITY_MANAGER" -> "L2_ADVANCED";
            default -> "L1_OPERATOR";
        };
    }

    public static List<Binding> presetLevelBindings(String levelCode,
                                                     List<com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition> definitions) {
        return definitions.stream()
                .filter(definition -> include(definition, levelCode))
                .map(definition -> new Binding(definition.code(), "ALLOW",
                        "ALL", List.of()))
                .toList();
    }

    private static boolean include(com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition definition,
                                   String levelCode) {
        if ("L3_ADMIN".equals(levelCode)) return true;
        if (definition.code().startsWith("system.")) return false;
        if ("L0_VIEWER".equals(levelCode)) return definition.code().endsWith(".view") || definition.code().equals("ai.use")
                || definition.code().equals("ops.file.view");
        if ("L1_OPERATOR".equals(levelCode)) return !Set.of("HIGH", "CRITICAL").contains(definition.risk())
                && !definition.code().equals("system.permission.manage");
        return !"CRITICAL".equals(definition.risk()) && !definition.code().startsWith("system.");
    }

    private static Menu m(String key, String label, String parent, String route) {
        return new Menu(key, label, parent, route);
    }
}
