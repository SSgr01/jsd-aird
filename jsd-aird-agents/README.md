# jsd-aird-agents

共享 AI 平台 + 可插拔 Vertical Packs + Enterprise Harness，使用 Python、FastAPI、LangGraph 和 LangChain。
项目管理是首个完整模拟模块，质量 Mock 用于扩展验证。目录、注册协议、隔离规则、调度及新增模块步骤见 [ARCHITECTURE.md](ARCHITECTURE.md)。
首批五类主动检查已实现，默认由普通 Python 任务执行，零模型调用；执行边界见 [EXECUTION.md](EXECUTION.md)，场景见 [MONITORING.md](MONITORING.md)。
定时分析、项目巡检、主动跟进、告警降噪、长期任务和后续场景见 [主动监控蓝图](PROACTIVE_MONITORING.md)，其中新增能力按当前/待实现状态分别标注。
本机开发使用原生 Python 和独立 PostgreSQL，**无需 Java、Docker、Redis、MinIO 或 pgvector**。

当前业务适配器只有 `mock`，读取、证据、任务修改与审批均来自本模块的持久化模拟数据。
结果及 SSE 数据标记 `simulated=true`；这些测试不能证明生产 Java 的授权与写入安全已经完成。

已支持通用 SKILL.md 的技能注册、精确版本启停、按需加载、图编排与运行审计。
使用方式和安全边界见 [SKILLS.md](SKILLS.md)，开发环境实际技能清单见 [SKILLS_DISCOVERY.md](SKILLS_DISCOVERY.md)。
迁移 `003_vertical_platform.sql` 保存模块、统一调度、质量夹具和产物；`004_proactive_monitoring.sql` 增加巡检、发现和模拟站内提醒；`005_task_execution.sql` 分离普通任务和 Agent，并终止未完成的旧五类巡检。升级本机状态库时先停止本服务，执行 migrate、seed，再启动；seed 只注册技能，新版本默认禁用。

## 本机启动

要求 Python 3.11/3.12、PostgreSQL 16–18。以下命令从仓库根目录执行：

```powershell
python -m venv jsd-aird-agents/.venv
jsd-aird-agents/.venv/Scripts/python.exe -m pip install -r jsd-aird-agents/requirements.lock.txt
jsd-aird-agents/.venv/Scripts/python.exe -m pip install --no-deps -e ./jsd-aird-agents
./jsd-aird-agents/tools/setup-local-postgres.ps1
```

脚本在仓库 `.tmp/agents-pg/data` 创建独立 PostgreSQL 实例，默认端口 `55439`，只监听 loopback。
核对实际 `data_directory` 后才创建专属角色与数据库，不操作已有 PostgreSQL 服务：

| 用途 | 数据库 | 非超级用户角色 |
|---|---|---|
| 本地演示 | `jsd_aird_agents_dev` | `agents_dev` |
| 可重置测试 | `jsd_aird_agents_test` | `agents_test` |

两种运行角色无超级用户、建库和建角色权限。这个独立本地实例使用 loopback trust，只放模拟数据。
也可使用自行管理的 PostgreSQL，但必须为模块建立独立数据库与账号；程序拒绝连接名称不以
`jsd_aird_agents` 开头的数据库。数据库名称检查不替代账号权限隔离。

创建演示配置（随机凭证不打印，现有 `.env` 不会被覆盖）：

```powershell
Push-Location jsd-aird-agents
./.venv/Scripts/python.exe -m jsd_aird_agents demo-env --database-url postgresql://agents_dev@127.0.0.1:55439/jsd_aird_agents_dev
Pop-Location
./scripts/dev-agents.ps1 -Mode migrate
./scripts/dev-agents.ps1 -Mode seed
```

在三个 PowerShell 窗口分别运行（无需调度时可以只启动 API 和 Worker）：

```powershell
./scripts/dev-agents.ps1 -Mode api
./scripts/dev-agents.ps1 -Mode worker
./scripts/dev-agents.ps1 -Mode scheduler
```

- Swagger：`http://127.0.0.1:8091/internal/v1/agents/docs`
- 探活：`/internal/v1/agents/health/live`。
- 就绪：`/internal/v1/agents/health/ready`；缺少迁移或活跃 Worker 返回 503；模型未配置时 monitoring_ready=true、agent_ready=false，固定巡检仍可用。
- 业务 API 使用 `Authorization: Bearer <本地演示凭证>`；申请人与审批人凭证在模块 `.env`，
  身份由服务端映射，不能用 X-User-Id、组织 ID 或模型参数覆盖。
- 停止自己创建的隔离实例：`./jsd-aird-agents/tools/setup-local-postgres.ps1 -Stop`。

## 完整模拟闭环

API 与 Worker 启动后，从仓库根目录执行：

```powershell
jsd-aird-agents/.venv/Scripts/python.exe -X utf8 jsd-aird-agents/tools/demo.py
# 启动 Scheduler 后，验证质量 Pack、定时投递、Artifact 和 SSE 重放
jsd-aird-agents/.venv/Scripts/python.exe -X utf8 jsd-aird-agents/tools/demo_platform.py
```

演示通过真实本机 HTTP 完成：创建 Run → 断开一次 SSE 订阅 → 查询项目、任务与会议证据 →
分析延期 → 预览截止日期变更 → 独立审批人批准 → 修改模拟任务 → 读取验证 → 返回引用及执行回执。
任务版本实际递增，服务重启后仍保留。再次演示会创建新动作，不清空前次数据。

默认 `scripted` 是明确选择的离线演示模型，只覆盖夹具中的项目查询、延期与截止日期调整场景，
同时支持五类巡检规则报告，仍不代表通用模型能力。未选择项目时，同名“材料验证项目”会触发澄清；演示显式选择项目。

## 纯规则模式

设置 `JSD_AIRD_AGENTS_MODEL_BACKEND=disabled`，不需要模型地址、名称或密钥。
五类巡检默认 `explain_with_model=false`，直接执行规则、模板、证据和站内提醒，不初始化或使用 LangGraph。
需要模型解释时显式设置 true；复杂对话仍使用 Agent，缺模型明确报错。恢复、旧任务处理和接口详见 [EXECUTION.md](EXECUTION.md)。

API、Worker、Scheduler 启动后可运行 `tools/demo_monitoring.py` 验证五类零模型调用任务。
带 `--with-agent-action` 才额外创建待审批 Agent 建议，该选项需要模型或显式 scripted 配置。

## 真实模型

模块 `.env` 可配置：

```dotenv
JSD_AIRD_AGENTS_MODEL_BACKEND=openai_compatible
JSD_AIRD_AGENTS_MODEL_BASE_URL=https://your-provider.example/v1
JSD_AIRD_AGENTS_MODEL_API_KEY=<server-side-key>
JSD_AIRD_AGENTS_MODEL_NAME=<configured-model>
JSD_AIRD_AGENTS_MODEL_STRUCTURED_METHOD=function_calling
```

模块配置优先；根开发脚本还会读取现有 `JSD_AIRD_MODEL_BASE_URL/API_KEY/NAME`，不复制或打印密钥。
`JSD_AIRD_MODEL_RESPONSE_FORMAT` 可作为结构化方式的兼容配置，模块 `MODEL_STRUCTURED_METHOD` 优先。
服务商必须支持标准 Chat Completions 和选定的结构化输出方式，可显式选择 `json_schema`、`json_mode`。
固定 OpenAI SDK 2.x 与 HTTPX 0.28 的兼容契约，不默认支持 Responses 或供应商推理扩展。
Qwen 可显式设置 `JSD_AIRD_AGENTS_MODEL_ENABLE_THINKING=false`，通过 `extra_body` 发送供应商参数；
默认不发送该参数，不根据模型名称猜测。这与现有 Java 查询改写服务使用的显式开关相同。
参考 [供应商说明](https://www.alibabacloud.com/help/zh/model-studio/deep-thinking)。
当前仓库的 Qwen 配置已用 `MODEL_STRUCTURED_METHOD=json_mode` 与 `MODEL_ENABLE_THINKING=false`
跑通真实模型闭环；这些是显式的模块环境变量，不修改 Java 配置。生产模型仍需各自验收。
配置缺失时明确失败，不自动切换 scripted。凭证不进入 State、工具参数、审计正文或 SSE。
推理模型可能需要更大的输出预算；可设置 `JSD_AIRD_AGENTS_MODEL_MAX_OUTPUT_TOKENS=8192` 和
`JSD_AIRD_AGENTS_MODEL_TIMEOUT_SECONDS=90`。截断会明确失败；不改变协议或模型来静默重试。

默认上限：12 个步骤、16 次模型尝试、32 次工具调用、4 个并行读取、300 秒活动时间。
等待人工输入不计入活动时间；持久化的调用预算在恢复后继续生效。未知 usage 标记 `reported=false`。

## API 与执行契约

前缀 `/internal/v1/agents`，Run / Action ID 为 UUID：
请求、响应、中断、审批和 SSE 的详细约定见 [API.md](API.md)。

| 接口 | 行为 |
|---|---|
| `POST /runs` | Header `Idempotency-Key`；Body `{request, pack_ids?, resource_scopes?, workflow_id?, project_id?, conversation_id?, monitoring?}`；返回 202 |
| `GET /runs/{id}` | 返回计划、步骤、证据、中断、结果与错误，只允许该会话申请人读取 |
| `GET /runs/{id}/events` | 持久化 SSE，`Last-Event-ID` 必须属于该 Run |
| `POST /runs/{id}/resume` | 澄清 `{interrupt_id, answer}`；可重试失败 `{retry:true}`；不能批准动作 |
| `POST /runs/{id}/cancel` | 停止后续步骤、撤销未执行审批；已完成修改不回滚，回执可查询 |
| `GET /actions/{id}` | 申请人／有资格审批人查询精确提议、摘要、版本和回执 |
| `POST /actions/{id}/decisions` | 审批人提交 `{decision, request_sha256}`；决策 APPROVED、REJECTED 或 REVOKED |
| `GET /capabilities` | 模拟后端、模型配置、工具 Schema 和真实 Java 适配关闭状态 |

Run 状态为 QUEUED、RUNNING、WAITING_CLARIFICATION、WAITING_APPROVAL、SUCCEEDED、FAILED、CANCELLED。
同一会话活动／等待时拒绝新轮次；旧 Run 被新轮次取代后不能恢复。
动作绑定精确参数、版本、风险与真实模拟身份；缺少审批、过期、撤销、摘要或版本变化会拒绝执行。
成功重放只返回原回执；未知结果先查询账本，不能盲目再写。

步骤类型为 FACT_QUERY、RAG_QUERY、ANALYSIS、ACTION_PROPOSAL；DAG、工具、Schema 与结果引用由代码校验。
一轮处理一个明确项目中的多个依赖步骤，写动作逐项串行审批，跨项目编排保留为后续能力。

## 持久化、恢复与证据

- `agent_runtime` 保存模拟会话元数据、Run、步骤、调用审计、证据、事件与 Worker 状态。
  技能目录、版本、Run 绑定、已加载资源和审计也存放在此 Schema。
- `agent_checkpoint` 使用官方 AsyncPostgresSaver；只有迁移命令执行 setup，应用启动不做 DDL。
- `mock_domain` 保存模拟项目／阶段／任务／文档、质量数据集和审批执行账本。
- `agent_runtime` 同时保存 Schedule/occurrence/调度审计、模块契约版本和有摘要的报告产物。
- 每个步骤有 Checkpoint；会话 Thread 稳定，Run 每轮独立，临时字段显式重置。
- Worker 使用数据库领取、租约、会话 advisory lock 和执行方检查；进程失联后重新领取并恢复。
  中断或最终 Run 投影缺失可从 Checkpoint 重建，已完成调用按摘要复用。
- 模拟动作的审批消费、版本条件更新、验证与回执在同一事务内；动作账本闭合“已提交但未 checkpoint”窗口。
  未来 Java 必须提供等价的权威幂等保证，Python 不能代替业务端事务。
- 决策先持久化，Worker 自动对账并唤醒，避免决策提交后故障导致永远等待。
- SSE 带持久化序号；断开订阅不取消 Worker，重连补读、消费方按序号去重。
  同 Run 的事件序号按事务提交顺序分配，最终正文与终态在同一事务中发布。
  `answer.delta` 只发布通过引用校验的正文，进度和人工等待事件即时发布。
- Evidence 保留来源、资源版本、定位、内容及 SHA-256；校验 Run／组织／范围及快照一致性。
  历史快照可支撑“变更前”事实，不冒充当前值；ID 正确不代表语义蕴含正确，仍需黄金集评估。
- 当前独立会话仅用于模拟运行，不导入或同步既有 Java 会话；正式接入时再验收消息编辑／删除和权限生命周期。

## 测试

```powershell
# 仓库根目录：启动／复用专属原生 PostgreSQL 并执行检查
./jsd-aird-agents/tools/verify.ps1

# 模块目录：显式指定可重置测试库
$env:JSD_AIRD_AGENTS_TEST_DATABASE_URL='postgresql://agents_test@127.0.0.1:55439/jsd_aird_agents_test'
./.venv/Scripts/python.exe -m pytest -q
./.venv/Scripts/python.exe -m pytest -q -m 'not integration and not live'
./.venv/Scripts/python.exe -m pip check

# 显式启用真实模型冒烟，仅发送很小的合成证据
./.venv/Scripts/python.exe -m pytest -q tests/test_model_adapter.py --live-model
```

集成测试只接受名称以 `jsd_aird_agents` 开头且以 `_test` 结尾的明确配置数据库，每项测试重置
其中三个固定 Schema；不要传开发演示或生产库。缺少测试 DSN 会报错，不会悄悄跳过数据库测试。
真实模型测试默认明确跳过，需 `--live-model` 启用。真实命令与结果见 `TEST_RESULTS.md`。
当前黄金集包含三个可复现的脚本模型基线案例；这不是通用模型的语义质量认证。

模型计划校验只证明结构、工具参数与范围合法，不证明已完整覆盖用户的所有意图。
真实模型上线前还应扩展需求覆盖率与证据蕴含评测；不得把 `SUCCEEDED` 等同于业务需求全部满足。

示例中第二个同名项目也有独立任务，可避免重复演示已完成的相同变更：

```powershell
./.venv/Scripts/python.exe -X utf8 tools/demo.py --project-id 30000000-0000-0000-0000-000000000002 --deadline 2026-11-15
```

## 未来 Java 接入

`app/platform/ports.py` 与 `adapters/ports.py` 定义模块、领域、授权、Action、模型契约；Graph／Harness 通过注入调用。
当前 `domain_backend=java` 拒绝启动。Java 身份委托、数据范围和 Action 接口通过验收后，再增加 HTTP 适配器和
相同契约测试，替换模拟身份、读取、预览、审批通知、执行和回执适配。不得用服务超级账号或 Python 自行批准
填补 Java 权限缺口。正式业务写入需重新验收 Java 授权、审批、版本、幂等与审计，现有助手和 RAG 保持原入口。
