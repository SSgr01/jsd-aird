---
name: task-management
description: 处理项目任务维护请求，当前支持查询任务及提议调整计划截止日期。创建任务、负责人和状态修改需等待相应领域工具接入。
allowed-tools: get_project_snapshot list_project_tasks get_task_detail update_project_task search_project_knowledge load_skill_resource
metadata:
  version: "1.0.0"
---

# 任务维护

确认项目、任务和用户要求；日期不明确时追问 YYYY-MM-DD。读取当前任务，保持任务 ID、归属和资源版本。
用户明确要求调整截止日期时，将读取／分析与 update_project_task 提议构成有依赖的计划，不能停在查找任务 ID。

需要了解审批回执语义时读取 [变更检查表](references/change-checklist.md)。
写工具只生成预览和提议，真实执行由服务端审批链路完成；不能设置 approved、申请人、组织或权限字段。
审批绑定精确参数和期望版本；冲突需要新预览和新审批，结果不确定时先查询现有动作账本。
确认 verified 回执后才能声称成功；拒绝或取消应如实说明，已完成操作不假称已回滚。

当前没有创建任务、变更负责人、状态或批量写入工具。遇到这些请求说明具体能力缺口，不用截止日期工具替代。
