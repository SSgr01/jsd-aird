---
name: experiment-comparison
description: 读取实验、比较结果、设计下一轮实验或按用户方案创建草稿。
---

# 实验读取与比较

使用 search_experiments 解析真实对象，get_experiment_record 固定版本。overview 给出样品、指标和分区目录，再按需要读取 samples、formulaItems、processSteps、testResults、tables，按 next_offset 继续。
以实验+版本+样品组合身份对齐配方、工艺、方法、条件和结果。不能因不同实验的 sample_key 相同合并样品。value 是规范值，rawValue 是原值；百分比不重复乘100，NOT_MEASURED/缺失不是0。缺少方法不能视为方法一致。新模板按实际字段读取，保留未知字段，不根据模板名字猜内容。
比较先说明可比条件及混杂因素，再给有依据的结论和下一轮验证建议。仅用户要求创建时调用 create_experiment_draft；缺业务必填信息再追问。
草稿使用真实分类及已发布模板版本；planned_date 必须来自用户，edit_model.plan 写方案。无项目时省略 project_id/stage_id/task_id 或传 null。不得复制旧实验结果、提交、审核或发布。创建后返回真实编辑入口；未知写入先对账，不重放。
