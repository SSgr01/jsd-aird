# Shared AI Platform 与 Vertical Packs

当前代码采用共享平台、明确注册的业务模块和共享 Harness。`project` 是第一个完整模拟业务闭环；
`quality` 仅用于验证扩展，不包含生产质量管理或质量写操作。Java、前端、配方计算服务保持原运行方式。
本地使用 Python 与独立 PostgreSQL，不使用 Docker，也不需要 Redis。

## 目录与职责

```text
src/jsd_aird_agents/
  app/
    platform/
      packs.py                # VerticalPack Schema、白名单注册、版本绑定
      ports.py                # 模块领域适配与业务解析协议
      routing.py              # 已选模块的 Gateway / Action / 演示模型路由
      runtime/                # 统一 State、LangGraph、DAG 校验、Worker、恢复
      harness/                # 工具契约、参数、范围、风险、预算与审计
      models/                 # LangChain ChatOpenAI、结构化输出、重试与 usage
      skills/                 # 标准 SKILL.md、版本启停、按需加载、框架桥接
      context.py              # 模块上下文过滤、LangChain trim_messages、摘要适配
      monitoring/             # 普通规则任务/模板/步骤恢复、发现、Outbox 与模拟收件箱
      triggers.py             # APScheduler 仅计算 Cron/日期时间，账本仍由 PostgreSQL 管理
      scheduler.py            # 全模块统一时间/事件投递、去重、重试和生命周期
      artifacts/              # 通用证据与受访问控制的报告产物
      evaluation.py           # 通用验收执行器
    verticals/
      project/
        manifest.yaml         # 工具、Skills、工作流、巡检定义、权限、证据和评测
        pack.py               # 项目实体消歧、工具参数与回执证据适配
        contracts.py          # 项目/任务参数
        model.py              # 项目提示词与明确的脚本演示模型
        fixtures.py
        monitoring.py         # 首批五类检查和授权快照
        monitoring_fixtures.py # 可选巡检样例
        skills/               # 延期分析、任务管理、知识研究
      quality/
        manifest.yaml
        pack.py               # 只读示例与演示模型
        gateway.py            # 独立组织/数据集夹具，不依赖项目数据
        skills/quality-anomaly-review/SKILL.md
    connectors/mock_project.py # 模拟项目权威端，持久化审批、事务执行、读后验证
  api/                        # FastAPI、SSE、Run/Action/Schedule/Artifact API
  persistence/                # 共享运行存储与独立 SQL 迁移；官方 PostgreSQL Checkpointer
  services.py                 # 唯一显式装配入口，不从 YAML 动态 import
  contracts/                  # 通用消息和 API 契约、旧字段兼容
```

旧 `graph/`、`harness/`、`models/`、`skills/`、`worker.py` 与模拟适配器导入路径是兼容转发，
不保留第二套实现。`AssistantState` 是公共 State；`ProjectAssistantState` 保留为兼容名称。
`project_id`、`project_ref` 也是项目兼容字段；通用扩展使用 `resource_scopes` 和 `resource_refs`。

## 注册契约与运行绑定

`app/platform/packs.py` 的 Pydantic `VerticalPack` 拒绝额外字段、未知权限、重复声明、
未声明工具、未知工作流和 CRITICAL 工具。清单使用拒绝重复键及 YAML 别名的解析器，限制文件大小。

| 声明 | 含义 |
|---|---|
| `id / name / version / graph_version` | 模块身份与运行协议版本 |
| `resource_kind / entry_permission` | 权威资源范围类型与进入模块所需的真实权限 |
| `required_permissions` | 工具和证据依赖的权限码，不会授予权限；不要求只读用户拥有写权限 |
| `skills` | 唯一名称与模块内部 SKILL.md 路径 |
| `tools` | 名称、契约版本、真实权限码、资源类型、读写类型与风险 |
| `workflows` | ID、版本、Graph 版本及允许使用的工具集合 |
| `scheduled_jobs` | ID、工作流、固定巡检请求、周期、可选事件类型 |
| `evidence_rules` | 可接受的证据类型、资源范围与重新授权规则 |
| `evals` | 业务验收用例、必要证据类型与断言 |

`PackBinding` 用经过代码审核的对象绑定 Pydantic 工具参数类型、`VerticalBusiness`、
`PackDomainGateway`、提示词和演示模型。默认工作流复用同一共享 LangGraph；
有专属节点需求时可提供 `workflow_factories[workflow_id](shared_workflow, checkpointer)`，
返回遵守统一 State/Run/Harness 协议的已编译 Graph。清单不能指定 Python 导入、Shell 或任意执行入口。

`VerticalRegistry.register()` 检查代码与清单的一致性、工具/Skill 名称冲突、Skill 路径归属、
Graph 工厂声明和权限映射。当前已核对 Java `IamPermissionCatalog.java` 中的
`project.view`、`project.update`、`quality.view`、`ai.use`；这只是实际代码的子集映射，不是生产 IAM。

创建 Run 时在同一事务保存模块版本、清单摘要、工具版本及参数 Schema 摘要、工作流与 Graph 版本。
Skill 另按全包 SHA-256 固定版本。恢复时版本不符明确拒绝，不能用新模块静默接管旧执行。
已有 v1/v2 项目 Checkpoint 的字段与节点名称继续兼容；升级前应停止本服务进程、迁移、重新同步 Skills 再启动。

## 模块、权限与上下文隔离

当前通过 `pack_ids` 显式路由，省略时兼容 `project`。界面或调用方应传入用户正在使用的业务模块；
尚未实现按自然语言自动发现并扩大模块集合。跨模块请求必须显式选择相关 Pack，由统一规划器生成依赖图。
每个工具步骤的 `pack_id` 由注册工具归属校验/补全，不能伪造另一个模块身份。

Harness 以数据库中的 Run 绑定为依据，交集计算模块/工作流白名单、Skill 工具上限及用户权限。
伪造 RunContext、选择其他工具、扩大资源参数、加载其他模块技能或声明不存在的权限均不能扩大能力。
模块内未声明的 SKILL.md 不能被当作平台技能注册；显式配置的模块外技能目录才可承载公共技能。
加载不等于启用，新 Skill 精确版本默认禁用，脚本始终只读。

共享规划器只接收当前模块的工具、业务上下文和 Skills；会话切换模块时先过滤不属于当前模块集合的历史消息，
再使用 LangChain `trim_messages` 裁剪。证据及本轮步骤在新 Run 清空，历史引用不能作为当前证据发布。
跨模块读取及最终引用分别通过对应 Gateway 的权限、组织和资源范围检查。

所有业务写工具只返回 Action 提议。项目模拟权威端自身也校验模块和工作流范围；审批、参数摘要、版本、
审批人/操作者权限、幂等消费、实际修改、读后验证与回执依然由该权威端执行。
未来换成 Java HTTP 适配器时，正式授权和写入必须由 Java 实现，Python 不连接业务数据库。

## 统一调度

Scheduler 是独立进程：`python -m jsd_aird_agents scheduler`。它只消费已注册的 Job 定义，
创建同一套 Run，由现有 Worker 执行。不会为项目和质量另建 Scheduler，也不注册操作系统计划任务。

- 所有时间为带时区的时间戳，数据库统一 UTC；当前支持固定间隔及显式授权的事件触发，未实现 Cron 表达式。
- Schedule 绑定固定组织、真实演示操作者和明确资源范围；创建、恢复、事件触发、投递和执行会重新授权。
- PostgreSQL 行锁与唯一约束协调跨进程竞争；同 Schedule 同时只有一个执行中的 occurrence，包括人工等待。
- 时间触发键和事件幂等键稳定，投递使用稳定 Run 幂等键，闭合“Run 已提交、投递记录尚未提交”的窗口。
- 重试沿用原 Run/Checkpoint，尝试计数与重新入队在同一事务；有次数上限及退避时间。
- 暂停停止新投递和自动重试；已运行任务继续。恢复需重新授权。取消停止后续投递并请求取消活动 Run，
  已完成业务变更继续保留。失联时锁由 PostgreSQL 释放，投递状态从持久化记录恢复。
- 有总运行次数、总模型/工具调用预算；每个 Run 在创建时获得剩余额度，Worker 内原子扣减，恢复不重置。
  另有活动时间和单次模型输出 token 上限。当前成本控制使用这些可核验上限，尚无供应商金额计费模型。
- 默认两个巡检定义仅含只读工具；测试额外注册的写巡检也必须在人工审批点等待，批准后才产生一次模拟修改。
- 停机后错过的多个间隔合并为一次补跑，不进行无限补偿。事件来源当前为已认证 HTTP，不接生产 Outbox。

## 主动巡检与长期任务（新增设计）

在现有统一 Scheduler 之上正式建设 Proactive Monitoring，共享触发、历史观察、确定性规则、
Finding 生命周期、告警降噪、通知 Outbox 和后续复查；各 Pack 只注册业务规则、工作流与验收集。
完整场景、目标契约和实施顺序见 [PROACTIVE_MONITORING.md](PROACTIVE_MONITORING.md)。

首批优先每日项目健康检查、临期/逾期与停滞跟进、里程碑预检查、周报草稿和审批待办提醒。
扩展场景包括关键路径、计划基线漂移、资源冲突、变更影响、整改效果复查、实验结果事件分析、
数据/知识健康、项目收尾及平台自检。业务风险与巡检自身失败分开，数据不完整时不输出“全部正常”。

用户可通过对话提出长期任务，先形成结构化草稿，展示范围、规则、日历、预算和通知行为，确认配置摘要后才启用。
配置确认不批准未来业务写入；每项正式修改仍走原 Action。通知仅可按已授权策略向有权接收相应信息的人发送。
任务不保存长期用户 Session，运行和通知送达前重新授权；权限、范围或配置实质变化必须重新校验/确认。

上述自然语言建任务、Cron/企业日历、观察历史、Finding 和通知模块是后续实现要求，尚未纳入当前验收完成项。
先扩展现有状态库和 TriggerProvider，评估成熟触发库；不另建第二套 Scheduler/Run/审批系统。

## 框架复用与边界

运行、并行与 Interrupt 使用 LangGraph；持久化使用官方 `AsyncPostgresSaver`。
模型使用 LangChain `ChatOpenAI.with_structured_output`。上下文裁剪和可选摘要分别使用
`trim_messages` 与 `SummarizationMiddleware`，没有另写摘要引擎；摘要适配器目前未默认接入每个节点。
`skills/frameworks.py` 提供受 Harness 约束的 LangChain `StructuredTool` 桥接。

DeepAgents 没有安装或默认启用。保留其官方 `SkillsMiddleware` 的可选适配入口；缺少依赖会明确报错。
后续只能传入展示本轮已绑定技能快照的只读 Backend，并沿用同一组 Harness 工具；
当前未把 DeepAgents 的可选桥接标记为集成验收完成，更没有开放 Shell、任意文件系统或新权限通道。

报告 Artifact 使用独立状态库存储，按 Run/组织/操作者控制读取，附内容摘要；当前上限 1 MiB。
后续对象存储适配不改变证据验证协议。结构验收执行器不声称可以证明语义蕴含，语义质量继续由黄金集检验。

## 新增业务模块的最小步骤

1. 新建 `app/verticals/<id>/`；写经 Schema 校验的 manifest，列出实际已有的 Java 权限与资源范围。
2. 实现工具的 Pydantic 参数、领域 Gateway、实体消歧/业务提示；有写能力时接入权威 Action 接口。
3. 提供标准 SKILL.md、按需资源、受限工作流及业务验收集；需要巡检时只添加 Job 定义。
4. 在服务装配入口明确注册 `PackBinding`；默认共享 Graph，需要专属 Graph 时绑定审核过的工厂。
5. 迁移独立模拟数据（若需要），同步 Skills，审核并启用精确内容摘要；显式自定义技能根时包含新模块目录。
6. 运行权限/工具/上下文隔离、审批恢复、定时投递和业务评测。不得以模块注册取代用户授权。

`tests/test_vertical_packs.py` 在测试内注册第三个最小 Pack，并复用运行、模型、Harness、
自定义 Graph 工厂、Scheduler、证据及评测，无需修改这些核心实现。
跨模块隔离测试、质量扩展测试、调度与故障窗口测试分别位于 `test_vertical_packs.py`、
`test_platform_context.py` 与 `test_scheduler.py`。真实命令与结果见 [TEST_RESULTS.md](TEST_RESULTS.md)。

## 主动巡检扩展

具体实现和注册说明见 [MONITORING.md](MONITORING.md)。PackBinding.monitoring_profiles 绑定
MonitorProfile（规则版本、只读快照工具、evaluator、默认参数、Skill、基线工作流）。
VerticalRegistry 强制对应普通任务工厂、工具白名单和规则版本一致，并要求 MonitoringBusiness 通知策略。
领域收件人解析和临投递动作检查由模块策略实现，公共平台不查询 mock_domain 领域表。
APScheduler 3.11.3 仅提供 CronTrigger；没有共享 APScheduler JobStore 或额外消息代理。

## 固定任务与 Agent 分离（2026-10-23）

固定巡检绑定 TaskBinding(version, factory)，由普通 Worker 直接运行；复杂任务绑定原 Graph，互斥注册。
普通任务使用 Run.execution_kind=task、execution_version 及 steps，默认不调用模型或初始化 Graph/Checkpoint。
显式模型解释不需要图编排；自然语言多意图、动态规划、人工中断与恢复继续使用原 Agent。
monitoring/workflow.py 已删除，未完成的旧巡检在迁移 005 中明确终止；业务工具/权限契约不变。
执行器、默认值、无模型就绪和升级细节见 [EXECUTION.md](EXECUTION.md)。
