---
name: project-delay-analysis
description: 分析项目任务延期，结合任务日期、状态和项目会议证据整理已知原因、影响与待确认事项。适用于延期分析和排期风险评估。
allowed-tools: get_project_snapshot list_project_stages list_project_tasks get_task_detail search_project_knowledge load_skill_resource
metadata:
  version: "1.0.0"
---

# 项目延期分析

先明确本轮项目；重名时消歧，不自行选择第一个项目。
查询项目任务及相关知识证据，独立读取可并行。比较计划日期与执行时点、任务状态，区分延期事实、
文档记录的原因和推测。没有明确证据的原因放入待确认事项，不编造依赖关系或影响规模。

需要具体的证据检查表时读取 [分析检查表](references/evidence-checklist.md)，不要在所有请求中加载它。
事实结论引用本轮领域 Evidence，建议单列；技能文件和检查表不是业务证据。

用户要求修改排期时结合 task-management 技能生成提议，进入现有预览、人工审批、版本与写后验证流程。
本技能不增加写工具，不代表用户已授权变更，不通知外部人员。
