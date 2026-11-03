# Agent API v1

前缀 `/internal/v1/agents`。当前版本所有领域响应与事件均为 `simulated=true`。
服务端通过本地 Bearer 凭证确定申请人或审批人；请求体和工具参数不能声明身份。
字段类型的源定义见 `src/jsd_aird_agents/contracts/types.py`；运行时 OpenAPI 位于
`/internal/v1/agents/openapi.json`，Swagger 位于 `/internal/v1/agents/docs`。

## 创建与查看 Run

省略 `pack_ids` 时为 `["project"]`。显式质量示例：

```json
{
  "pack_ids": ["quality"],
  "resource_scopes": {"quality":"70000000-0000-0000-0000-000000000001"},
  "request": "检查质量异常并整理有引用的复检建议"
}
```

跨模块请求须显式传 `pack_ids:["project","quality"]` 及各自的已授权 `resource_scopes`。
可选 `workflow_id` 必须在所选模块中唯一注册，只开放该工作流声明的工具。
`project_id` 与 `resource_scopes.project` 同时出现时必须一致；范围不能包含未选择模块。
注册模块、传入资源 UUID 和拥有其他模块权限都不能代替当前模块的授权。

Run 快照新增 `pack_ids / pack_versions / resource_scopes / workflow_id / budget_limits`；结果新增
`trace`（模块、工具、Graph、Skill 版本）及 `artifact`（产物 ID、SHA-256、media_type）。
Evidence 新增 `pack_id / scope_id`，非项目模块的 `project_id` 为 null。
`GET /packs` 返回当前用户具有入口权限的已注册声明，`GET /skills?pack_id=quality` 只返回相应模块及公共技能元数据。

```http
POST /internal/v1/agents/runs
Authorization: Bearer <actor-token>
Idempotency-Key: <unique-client-request-key>
Content-Type: application/json

{
  "project_id": "30000000-0000-0000-0000-000000000001",
  "request": "分析延期并将耐候测试验证截止日期调整到2026-11-10"
}
```

返回 202：

```json
{
  "run_id": "<uuid>",
  "conversation_id": "<uuid>",
  "status": "QUEUED",
  "events_url": "/internal/v1/agents/runs/<uuid>/events",
  "simulated": true
}
```

可选 `conversation_id` 只接受该申请人已有的模拟会话；省略则新建。同会话存在活动或等待 Run 时返回
409 `CONVERSATION_BUSY`。同申请人、组织和幂等键的相同请求返回原 Run；参数变化返回
409 `IDEMPOTENCY_CONFLICT`。`request` 长度为 1–6000，额外字段返回 422。

`GET /runs/{id}` 返回该申请人的运行快照，关键字段：

| 字段 | 契约 |
|---|---|
| `id`, `conversation_id`, `organization_id`, `actor_id` | UUID，身份来自服务端 |
| `project_id`, `request`, `plan` | 解析后的项目、本轮输入、经过校验的 IntentPlan |
| `status` | QUEUED / RUNNING / WAITING_CLARIFICATION / WAITING_APPROVAL / SUCCEEDED / FAILED / CANCELLED |
| `steps` | `{step_id,status,result,error}` 列表；结果引用按步骤 ID 寻址 |
| `evidence` | 本轮证据快照列表 |
| `interrupt` | `{id,value}`；value 包含 type、question/candidates 或 action_id |
| `result` | 成功时含 answer、claims、citations、receipts、warnings、simulated |
| `error` | 失败时含 code、message、retryable |
| `model_calls`, `tool_calls`, `active_seconds` | 持久化预算计数；人工等待不计入活动时间 |

`SUCCEEDED` 表示计划节点执行完成；被拒绝的动作通过 `warnings` 和步骤 SKIPPED 表示。
调用方应核对回执及业务意图，不能仅凭终态认为所有自然语言要求都被模型正确理解。

## 澄清、重试与取消

`POST /runs/{id}/resume`：

```json
{"interrupt_id":"<current-interrupt-id>","answer":"30000000-0000-0000-0000-000000000001"}
```

只接受对应的澄清中断。可重试 FAILED Run 使用 `{"retry":true}`；需要新计划或重新审批的版本冲突等错误
不能直接沿用旧 Run 重试。被同会话新轮次取代的旧 Run 返回 409 `STALE_RUN`。恢复成功返回 202。

`POST /runs/{id}/cancel` 请求停止后续节点并撤销待执行动作。运行中的模型调用在 Worker 检查时取消；
已提交的模拟业务变更和回执继续存在。取消与执行通过数据库锁协调，取消不提供业务回滚。

## 审批与执行

WAITING_APPROVAL 的中断返回 `action_id`、`request_sha256`、变更前值、提议值与过期时间。
`GET /actions/{id}` 供申请人或有范围权限的审批人查看，包含：

- action/run/conversation/step ID、组织、项目、申请人和审批人。
- `tool`, `target_id`, `arguments`, `expected_version`, `risk`, `before_state`。
- `request_hash`、稳定的 `idempotency_key`、创建／过期／决策／消费时间。
- `approval_status`, `execution_status`, `receipt`。

批准请求必须使用审批人凭证，提交读取到的精确摘要：

```http
POST /internal/v1/agents/actions/<action-id>/decisions
Authorization: Bearer <approver-token>
Content-Type: application/json

{"decision":"APPROVED","request_sha256":"<request_hash-from-action>"}
```

`decision` 支持 APPROVED、REJECTED、REVOKED。申请人不能自批。`approved=true`、身份覆盖字段和任何
未定义字段均拒绝；恢复接口不提供审批。返回更新后的动作，Worker 自动对账并唤醒原 Run。
执行接口不向模型或外部 API 暴露，只有统一图节点调用 ActionGateway。

审批消费、版本比较、任务更新、读后验证、审计事件及回执在一个模拟权威事务内完成。
重复执行返回原回执；参数或风险改变、失效审批、资源版本冲突、失去权限均不能写入。
回执包含 action_id、run_id、target_id、真实模拟申请人／审批人、参数摘要、前后版本、
`verified=true` 与 `actual`。网络结果不确定时先查询动作，而不是新建动作重写。

## SSE

```http
GET /internal/v1/agents/runs/<run-id>/events
Authorization: Bearer <actor-token>
Last-Event-ID: <last-seq>
```

```text
id: 42
event: step.succeeded
data: {"run_id":"<run-id>","step_id":"tasks","status":"SUCCEEDED","simulated":true,"seq":42,"event":"step.succeeded"}

```

游标必须属于该 Run；省略从头读取。全局序号允许间隙，同 Run 按提交顺序递增；客户端持久保存并去重。
订阅断开不影响 Worker。没有新数据时发送 `: keepalive`，终态事件发送后关闭订阅。
常见事件为 `run.queued/started/planned/resumed/waiting_clarification/waiting_approval/succeeded/failed/cancelled`、
`step.running/succeeded/waiting_approval/skipped/failed`、`action.proposed/approved/rejected/revoked/executed`、
`model.completed`。`answer.delta` 当前一次发布校验后的完整正文，带 `validated=true`；不流出待校验的模型 token。

## Evidence 与错误

Skills：`GET /skills` 返回当前启用且可发现的技能元数据，包含 name、sha256、version、description、
allowed_tools、enabled、available、untrusted。`GET /runs/{id}/skills` 仅向原申请人返回所选精确版本、
已加载资源路径与审计，始终 `scripts_executable=false`。技能启停仅有维护 CLI，不开放模型可调用的写接口。
新增 SSE 类型为 skill.selected、skill.loaded、skill.resource_loaded、skill.tool_call、skill.load_failed、
skill.selection_failed。完整生命周期与安全边界见 [SKILLS.md](SKILLS.md)。

Evidence 包含 ID、Run／组织／项目、source_type、resource_id、resource_version、title、locator、content、
content_sha256、simulated。事实 Claim 必须含至少一个 evidence_id；建议单列。
引用检查校验已保存快照、归属、版本、摘要与访问范围，失败最多修正一次，持续失败不发布正式正文。
语义是否受到证据支持需另行评测，不能仅凭 ID 合法判断。

业务错误采用：

```json
{"error":{"code":"VERSION_CONFLICT","message":"任务版本变化，请重新规划、预览和审批","retryable":false,"detail":null},"simulated":true}
```

401 为缺少／错误凭证；403 为身份、权限或范围错误；404 隐藏不可访问对象；409 为状态、版本、审批或幂等冲突；
422 为输入或计划不合法；模型错误按 502/503/504 返回或保存在 Run.error。模型原始错误体与凭证不会返回。

`GET /capabilities` 报告模拟模式、模型配置与工具 Schema；`health/live` 探活；`health/ready`
检查迁移、数据库、活跃 Worker 和模型配置，返回 200 或 503。就绪不证明供应商当时一定可用。


## 统一 Schedule 与事件

创建调度需 Bearer 与 `Idempotency-Key`。操作者和组织来自已认证身份，不接受请求体覆盖：

```http
POST /internal/v1/agents/schedules
Content-Type: application/json

{
  "pack_id": "quality",
  "job_id": "daily-quality-anomaly-check",
  "scope_id": "70000000-0000-0000-0000-000000000001",
  "first_fire_at": "2026-10-23T09:00:00+08:00",
  "interval_seconds": 86400,
  "max_runs": 10,
  "model_call_budget": 160,
  "tool_call_budget": 320,
  "max_attempts": 3
}
```

`first_fire_at` 省略为当前时间，显式时间必须带时区。Job 和工作流从注册清单加载，请求不能注入工作流正文、工具或权限。
201 返回 Schedule，重试相同键返回原记录；参数不同为 409。

| 接口 | 行为 |
|---|---|
| `GET /schedules/{id}` | 仅当前组织的创建者查看，含 occurrence、Run ID 和调度审计 |
| `POST /schedules/{id}/pause` | 停止未来投递及自动重试，当前 Run 继续 |
| `POST /schedules/{id}/resume` | 重新授权后恢复；不能恢复 CANCELLED/FAILED/EXHAUSTED |
| `POST /schedules/{id}/cancel` | 停止未来投递并取消活动 Run，保留已完成回执 |
| `POST /schedules/{id}/events/trigger` | Body `{event_type:"quality.changed"}`，需要 Idempotency-Key；类型必须与该 Job 注册值一致 |
| `GET /artifacts/{id}` | 返回本组织当前操作者的报告内容及 SHA-256；其他操作者返回 404 |

Schedule 状态：ACTIVE、PAUSED、CANCELLED、EXHAUSTED、FAILED。Occurrence 状态：PENDING、DISPATCHED、SUCCEEDED、FAILED、CANCELLED。
等待人工审批/澄清仍为 DISPATCHED，可通过 occurrence.run_id 查看具体等待；不会因此创建第二次投递。
定时或事件来源不改变业务写入的审批约束。固定间隔停机后合并补跑，不支持 Cron 表达式。
预算是累计调用次数上限，金额计费未实现。缺少 Scheduler 不影响手动 Run，调度任务会保持持久化待处理。

自然语言长期任务、配置草稿确认、规则阈值、Cron/工作日历、通知策略、修改/立即执行/软删除等扩展，
见 [主动监控目标契约](PROACTIVE_MONITORING.md)。这些属于新增设计，尚不是当前接口字段或已开放操作。

## 首批主动巡检 API

五类 Job/Workflow、请求样例和全部边界见 [MONITORING.md](MONITORING.md)。
POST /runs 增加可选 monitoring（只适用于显式注册的巡检 workflow_id，且必须绑定明确范围）。
POST /schedules 增加 monitoring、trigger；缺省使用 Job 声明的 Cron，旧固定间隔任务保持兼容。
trigger 支持 kind=cron（expression、timezone、skip_dates）或 kind=date（带时区 at），不能和 interval_seconds 同用。
Run.monitoring 固定服务端 as_of、有效参数、Profile 和规则版本；结果包含 monitoring、publication_status=DRAFT。

新增认证接口：GET /monitoring/profiles、GET /monitoring/findings?scope_id=UUID&pack_id=project、
GET /notifications、POST /notifications/{id}/read。提醒仅为模拟站内记录，读取实时核验资源范围。
报告读取仍为 Run 申请人专属；审批提醒不授予读取申请人完整 Run 的权限。

## 普通任务执行（2026-10-23）

五类巡检的 workflow_id、job_id 和 URL 不变。monitoring.explain_with_model 默认 false；true 才要求模型配置。
Run 快照新增 execution_kind（task/graph）及 execution_version，由服务端注册决定，额外请求字段仍拒绝。
health/ready 增加 monitoring_ready、agent_ready；基础 ready 不再要求模型配置，模型状态单独报告。
capabilities 增加 execution_kinds、monitoring_requires_model=false；profiles 报告 execution_kind=task 和 AI 解释可选。
未配置模型时，普通巡检和调度可用，Agent/显式模型解释返回 503 MODEL_NOT_CONFIGURED。
升级前未完成的旧巡检变为 CANCELLED/MONITORING_EXECUTION_RETIRED，需新建；历史成功报告可查，复杂 Agent 保留。
完整行为和升级步骤见 [EXECUTION.md](EXECUTION.md)。
