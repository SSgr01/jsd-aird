# Hindsight 个人记忆一期

企业插件调用 Hindsight 官方 HTTP API，后端固定 `0.8.6`。dsh 固定 `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159`，继续管理会话、模型循环、压缩、分叉和历史查询。接入不修改上游、不安装全局 coding-agent 插件，也不导入启用前的历史。

## 数据和身份

每个组织、用户绑定稳定私有 bank；执行目录、模型和会话切换不改变归属。模型和浏览器不能传入 bank、用户或组织。授权版本用于运行身份校验、作业取消和上下文失效。

Control 复用 `agent_runtime.harness_records`，不增加业务表或数据库迁移：

| kind | 保存内容 |
| --- | --- |
| `memory-preferences` / `memory-binding` | 独立读取、自动整理开关；启用时间；授权版本；私有 bank |
| `memory-session` | 会话读取、参与整理开关及参与版本 |
| `memory-entry` | 文档 ID、内容修订、来源、权限引用、状态 |
| `memory-job` | 固定操作 ID、交付租约、重试、回执、实际用量 |
| `memory-exclusion` | 持久来源排除标记 |
| `memory-budget` / `memory-usage` | 每日预算、未结算预留、调用耗时及实际用量 |

正文由 Hindsight 文档保存。待交付作业暂存待写入片段，终态后删除该字段；Control 不维护第二份可检索正文。列表和扫描均使用绑定组织、用户和记录类型的游标分页，不能以 200 条截断。

## 事件与召回

原生轮次成功结束后，从该轮真实用户消息提取直接表达的稳定偏好、确认决定。排除推理、助手声明、记忆注入、内置简报、引用材料、凭据和临时指令；分叉只处理自有增量。工作完成经验只从企业操作账本中的成功回执生成，保存真实记录、固定版本及回执 ID。恢复会话时重放固定操作 ID，避免重复摄取。

新会话从独立偏好文档加载经过哈希验证的少量快照，避免冷向量检索阻塞首轮。原生追加文本包含来源，整体保守限制在 800 个 UTF-8 字节以内，满足不超过 800 Token 的要求；不声称每个模型的精确 Token 数。查询既往工作时默认 Recall，深层归纳才用 Reflect。同一内容修订和工具续接不重复追加。

检索前仅传入当前授权文档标签，返回后再次核对原始文档、来源、修订和排除标记。一期禁用 Hindsight 聚合 observation，排除知识页、mental model、directive；Reflect 必须解析全部依据为授权原始事实，缺少任何依据即跳过归纳。历史展开使用原生 `sessionQuery`，核对真实用户事件的哈希或真实工具回执，不能用助手复述定位。

记忆属于带来源的历史线索。当前实验、项目、库存等状态仍用既有业务工具重新读取。

## 作业、授权和失效

`personal-memory` 是独立后台授权，仅可换取本人运行身份及核对来源权限，不能执行业务变更或简报。每次新增提交或重试校验账号、组织、授权版本和自动整理授权；会话退出参与后再打开也不会重新授权旧作业。显式保存操作仅授权本次保存，自动整理开关独立控制后续自动处理。

Control 随服务启动恢复作业，以数据库租约和 `SKIP LOCKED` 领取。Hindsight 接收固定 `operation_id` 异步整理；响应丢失后先查询同一操作。工作台关闭不会终止已授权交付。预算耗尽时保留待处理，次日重试；未知响应标记“结果待核对”，不能声称保存失败或成功。成功摄取后若计量失败，仅重试计量，不重新提交正文。

更正和遗忘使用修订校验。先提交排除标记，使旧记忆立即停止使用，再等原摄取租约和 Hindsight 作业结束，删除文档并回读确认。禁用聚合输出、文档级删除派生事实和启用随文档删除 LLM trace，避免旧版本在后端派生内容或调用正文中残留。相关企业工具缓存、回执正文及保存参数也会清除，保留操作身份以便对账。来源会话删除先落删除围栏，再分页清理记忆。

每次模型调用前，包括工具续接，重新验证已经注入的记忆。失效后通过原生 surface 操作移除记忆及可能衍生的助手、工具和压缩内容，保留真实用户输入和系统规则。固定 dsh 的模型请求不可改写；若失效发生在请求已组装后，调用模型前停止该请求，并提示重新发送，由原生循环从有效日志重建。

## 接口和设置

浏览器沿 `/business/memory` → `/internal/v1/harness/dsh/memory`，只允许受控动作。`capture`、`validate` 仅企业插件内部调用。

| 动作 | 用途 |
| --- | --- |
| `initialize` / `settings` / `set-settings` | 初始化、读取、带修订修改个人设置 |
| `session-settings` / `set-session` | 会话读取、参与整理开关 |
| `list` | `limit` 1–100、`cursor` 分页 |
| `source` | 跳转前重新授权来源 |
| `search` | Recall、Reflect 或历史定位 |
| `remember` / `correct` / `forget` | 显式保存、更正和遗忘 |
| `status` | 查询操作回执 |

模型通过 `tool_search` 按需发现 `memory_search`、`memory_manage`。更正、遗忘必须提供实际 `entry_id` 和 `revision`；显式保存内容必须取自用户真实输入。回执包含 `operation_id`、`entry_id`、状态、用量及是否待对账，区分待处理、成功、失败、未知和已停止。

原生“工作台设置 → 记忆”提供列表、添加偏好、编辑、遗忘、来源跳转、读取和自动整理开关。会话顶部“更多操作 → 本会话记忆”或侧栏会话菜单可调整当前会话的读取与参与整理，输入框下方不显示记忆面板。设置导航与会话菜单使用同一个小笔记本图标。启用后默认开启个人读取和自动整理，首次进入工作台时用短提示说明用途并提供关闭入口。列表自动刷新保留已加载分页。

## 部署

先依照 [Hindsight 部署说明](../../deployment/hindsight/README.md)启动固定镜像，再配置 Control：

```dotenv
JSD_AIRD_HARNESS_MEMORY_ENABLED=true
JSD_AIRD_HARNESS_MEMORY_URL=http://127.0.0.1:8888
JSD_AIRD_HARNESS_MEMORY_API_TOKEN=
JSD_AIRD_HARNESS_MEMORY_RECALL_TIMEOUT_SECONDS=8
JSD_AIRD_HARNESS_MEMORY_REQUEST_TIMEOUT_SECONDS=15
JSD_AIRD_HARNESS_MEMORY_DAILY_CALLS=100
JSD_AIRD_HARNESS_MEMORY_DAILY_TOKENS=200000
JSD_AIRD_HARNESS_MEMORY_WORKERS=2
```

示例默认关闭部署入口；先在隔离 Control 数据库、用户和 bank 验收，再启用实际企业环境。用户两项默认开关与部署入口分开管理。构建企业客户端后重启 Java、Control、网关及用户 Host；无需更改固定 dsh 版本。

保留 `HINDSIGHT_API_LLM_TRACE_DELETE_WITH_DOCUMENT=true`。关闭入口前应让清理任务完成，避免尚未完成的物理删除无人对账。撤权不会阻止已经承诺的清理，不能用撤权恢复旧内容。

Hindsight retain 用量从完整分页的 LLM traces 取得，包含输入、输出和缓存；Reflect 使用实际响应。Recall 的 embedding / reranker 用量在 `0.8.6` 不暴露，保留未知；不以估计冒充实际。摄取耗时使用从提交到完成的持续时间，包含异步等待；偏好文档读取不调用生成模型。

## 验证

```powershell
# 只使用独立 agents 测试数据库。
$env:HARNESS_TEST_DATABASE_URL='postgresql://agents_test@127.0.0.1:55439/jsd_aird_agents_test'
cd jsd-aird-agent/control
.venv/Scripts/python.exe -m pytest harness_tests/test_memory.py -q
.venv/Scripts/python.exe -m ruff check src/aird_agent/memory harness_tests/test_memory*.py

# 可选：真实 Hindsight 往返，新建隔离 bank，finally 删除。
$env:HARNESS_MEMORY_LIVE='true'
$env:HARNESS_MEMORY_EVIDENCE='G:/Projects/jsd-aird/.runtime/hindsight/enterprise-acceptance.json'
.venv/Scripts/python.exe -m pytest harness_tests/test_memory_live.py -q
```

还需运行 DeepSeek Node 回归、Java `AgentAutomationDelegationTest`，并按 [一期验收记录](personal-memory-acceptance.md)完成真实工作台检查。实际环境、性能和回归基线限制在验收记录中单列。
