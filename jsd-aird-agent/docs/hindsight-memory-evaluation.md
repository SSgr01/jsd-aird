# Hindsight 原生 dsh 记忆集成评估

核对日期：2026-11-09。Hindsight 已被用户选定，部署复用现有 PostgreSQL 实例并使用独立数据库及专用账号。服务配置、准备脚本和启动方式见 [部署说明](../../deployment/hindsight/README.md)。下文保留集成评估；服务部署与企业长期记忆启用分别记录，不能把后者视为已完成。

评估的上游源码固定至 `vectorize-io/hindsight@0bd55015393d224deec1073d63b24bcc0faad164`，其中 `@vectorize-io/hindsight-coding-agents` 包版本为 `0.8.6`。插件包版本与后端服务版本需要分别固定。

## 1. 结论与选型调整

**采用自托管 Hindsight，不再继续比较其他记忆后端。** 记忆提炼、检索、归纳与知识页更新复用 Hindsight；企业侧只维护身份、来源映射、授权、用量、偏好开关、作业回执和遗忘排除记录。现有业务数据库及 Java Flyway 不变。

该包当前主要面向编程仓库。直接安装的默认体验不能当作企业研发记忆交付：仓库身份、编程提示词、模型归纳文本、用户私有配置与企业业务权限尚未对接。优先通过配置和上游扩展点适配；缺少的输入筛选、结构化来源与管理能力由薄适配补齐，不修改 dsh 执行循环。

若上游插件没有足够扩展点，企业插件可调用 Hindsight 的官方客户端/API，并沿用其原生事件接入方式。仍只维护一个记忆后端，不再实现提炼、向量检索、知识归纳或第二套模型会话循环。

## 2. 它实际提供什么

### 原生 dsh 接入

不是只能通过 MCP 暴露几个工具。包直接导出 `./dsh`，通过 `cordis.patch.yml` 注册 Host 层插件：

| dsh 原生接口 | Hindsight 的行为 |
|---|---|
| `agent/session-start` | 初始化工作区运行实例，后台进行冷启动检查及资料摄取 |
| `agent/pre-step` | 有新的真实用户消息才执行召回；工具续接步骤不重复召回 |
| `agent/turn-stopping` | 读取原生事件，写回新增对话 |
| `ctx.tools` | 注册八个 `hindsight_*` 工具，执行时按调用者会话解析记忆库 |
| 原生上下文消息 | 以 `source.kind=plugin:hindsight`、`form=recall` 追加记忆；相同文本不重复注入 |

它支持 `snapshotEvents()`，也处理分叉会话的继承事件，避免把父会话重复摄取。原生日志仍是 dsh 保存，记忆是独立的派生层。

### Hindsight 后端

- **Retain**：提炼事实、时间、实体和关系，建立检索表示。
- **Recall**：查询记忆；支持来源与范围等过滤。没有额外生成式归纳步骤，但仍有检索、embedding、重排及可能的决策模型开销。
- **Reflect**：由后端模型循环检索并归纳记忆，适合追问既往决定的原因；会产生额外模型调用和耗时。
- **Knowledge Pages / Mental Models**：持续更新的主题知识页，读取已生成正文无需重新做一次归纳。
- **Documents / Operations**：文档身份、增量摄取、异步操作、历史与删除等基础。删除文档可以删除其提炼的记忆。

这比只存一份 `MEMORY.md` 更完整，适合承载长期任务经验。它仍不能自行判断企业记录是否真实、当前用户是否有权读取，或把模型完成声明等同于业务回执。

## 3. 默认行为与本系统的差异

| 默认行为 / 源码事实 | 企业接入处理 |
|---|---|
| 默认 `bankIdTemplate=coding-agent::{gitProject}`，按会话 `cwd` / Git 项目派生 | 服务端按组织与用户确定私有 bank；业务项目使用真实资源引用，目录不参与业务身份 |
| 默认读取 `~/.hindsight/coding-agent.json`，配置文件可覆盖环境变量 | 每个用户 Host 使用受控 `HINDSIGHT_CONFIG`；配置不能被模型改写，缓存与日志路径也需核对隔离 |
| 默认 `autoSeed=true`、`gitIngest=message`、`retainSessions=true` | 关闭 Git 和目录历史摄取；只写入允许的用户声明、决策与带来源的工作片段 |
| 默认 `autoInject=reflect`，首个用户请求可能等待后端归纳 | 优先有预算的 Recall 或知识页检索，Reflect 按需使用；故障和超时不阻断主任务 |
| 默认五类页是组件、概念、编程惯例、技术决策、功能计划 | 改为用户偏好、课题决策、已确认方法、待跟进事项等；不复制实时实验和库存库 |
| 八个工具全部注册，包含诊断、状态及资料摄取 | 接入现有能力授权和按需发现，避免每次向模型暴露完整工具组 |
| 提示词要求显示 “From Hindsight memory”，含写代码、测试、提交等触发规则；`toolGuideExtra` 只能追加 | 企业界面显示业务来源；使用简短研发指引。此处需要扩展点或局部适配，不能仅添加一段中文提示词声称已替换 |
| 默认每个用户轮次刷新工具指引；dsh 层会跳过完全相同的注入 | 沿用追加消息及去重，限制变化和注入预算；实测缓存及 Token，不能把“每轮刷新”直接等同于每轮重复注入或一定破坏缓存 |
| 默认有运行时自动更新；部署模式默认 Cloud | 使用受控自托管服务，固定包及服务版本，关闭自动更新；不使用全局 `install all` |

只关闭 `autoSeed` 不等于关闭全部记忆行为；只关闭 `retainSessions` 也不会关闭召回、工具写入和 Git 摄取。企业开关需要明确覆盖对应链路。

## 4. 必须补齐的四条链路

### 4.1 记忆库身份和资源授权

Hindsight 官方也将 bank 作为隔离边界，tag 用于边界内分类。建议一期建立每个“组织 + 用户”的私有 bank，ID 与访问权由服务端确定；模型不能提交任意 bank 或借用服务端管理令牌。

项目关联用真实 ID/tag/metadata，不用项目名或 cwd。团队共享等后续通过企业权限明确授权，不能去掉用户条件使个人记忆自动共享。

同一私有 bank 内仍有资源撤权问题：原始片段、合并后的 observation 和知识页都可能包含失效业务信息。必须在检索/归纳前限制允许的来源，并在输出前重检；无法确认来源的混合摘要先停用。不能先对全 bank 做 Reflect，拿到一段文本后再尝试删掉无权限句子。

现有企业 `tools.guard` 会拒绝未授权的 Hindsight 工具；生命周期里的自动 HTTP 调用也没有自动经过现有业务门禁。安装成功不代表授权、预算与工具调用已经接通，接入时分别绑定。

### 4.2 真实业务依据

当前 `readDshEvents()` 保留真实用户文本、助手正文和简短工具调用动作，主动排除了 `tool/result`、推理、图片和插件上下文。这样能减少噪音及“记忆再次摄取自己”，但也意味着业务读取的原始回执、版本与来源不是天然完整保留下来的。

应由现有业务账本给允许摄取的片段补充：Session、Turn、事件位置、resource ref、固定版本/哈希、用户是否确认、真实操作状态。助手说“已经保存”只作为声明，只有成功回执才支持完成事实。

此外，当前 coding-agents 客户端的 `reflect()` 只发送 `query` 和 `budget`，返回 `data.text`；`recallObservations()` 也只返回文本。Hindsight 后端本身支持 `include.facts` / `based_on`，因此应保留这些结构化依据，并回映射到现有引用入口。不要只把这两个字符串接口当作企业证据协议。

### 4.3 更正和遗忘

该插件八个工具没有直接提供记忆删除工具。它主要引导模型写入 `Correction: ...` 文档，这能表达新事实，但不等于删除旧来源，也不能承诺旧内容绝不会再出现。

复用后端的文档/事实管理能力实现查看、更正和删除，维护“企业记忆条目/来源片段 ↔ Hindsight document/fact”的映射。显式偏好宜使用独立文档，避免删除一句偏好时误删整段会话。

遗忘时检查相关 observation、知识页及缓存的失效与重建；企业侧保存来源排除标记，阻止晚到或重放的摄取再次添加。不能只删除一个提炼结果，而让原始会话下一轮又生出相同结果。具体删除后的派生数据行为需真实检查，不能仅凭 API 文档作完成承诺。

### 4.4 运行成本、模型与部署

Hindsight 是独立服务，有自己的 LLM、embedding、重排和后台知识页刷新配置，不会自动继承 dsh 的 Provider、预算或用量账本。复用现有企业模型入口做授权和费用归属；若要求调用 dsh 模型服务，则需验证桥接能力。不能为此再写一套模型管理界面。

它的 PostgreSQL 存储包含 pgvector 等结构，并有自己的官方 schema 初始化/升级。这可以部署在独立数据库，保持现有业务库、Flyway 和迁移文件不动；但必须如实记录新增服务与存储依赖，不能称为“完全无需数据库初始化”。

插件的首请求默认 Reflect 超时、显式 Reflect 工具超时及知识页刷新频率都有独立配置。企业部署应限制召回、提炼与刷新预算，分别计量；主任务不因记忆服务不可用而失败，写入未完成则显示真实待处理状态。

## 5. 调整后的职责

| 职责 | 承担方 |
|---|---|
| 模型执行、文件、附件、Inbox、会话、压缩、恢复 | dsh 原生 |
| 记忆提炼、检索、归纳、知识页与其索引 | Hindsight |
| 组织用户身份、来源版本、权限、预算及工具门禁 | 现有企业网关与业务服务 |
| 记忆开关、bank/来源映射、排除与回执 | 现有 JSONB 账本，只存控制数据 |
| 最新项目、实验、客户、库存和正式业务结果 | Java 原业务模块，通过原有工具读取 |

不在 JSONB 和 Hindsight 中各维护一份权威摘要；原生日志、派生记忆、业务事实各有明确来源。

## 6. 下一步范围与判据

在固定 dsh 提交上，用隔离用户接自托管 Hindsight，完成最小的“记住 → 新会话召回 → 更正 → 删除 → 重启后仍有效”。再增加有真实版本引用的课题续接、跨组织隔离、撤权和晚到摄取检查。不重新展开多后端选型。

同时比较无记忆与有记忆时的中文材料别名、用户偏好、项目决策召回质量，记录误召回、来源完整性、附加费用、首字耗时和缓存。README 的公开效果来自编程任务，不能直接作为研发问答、实验分析的收益或完成证据。

当前静态核对显示，插件使用的 `agent/pre-step`、`agent/turn-stopping`、`snapshotEvents()` 与本地固定 dsh 的相关接口一致；上游端到端脚本的 dsh 安装默认使用 `latest`，因此仍需固定版本的真实运行，不能据此宣称开箱即用。

上述企业接入链路仍需单独交付；部署脚本不自动采集现有会话和业务数据。

## 7. 依据

以下链接固定到本次核对提交：

- [集成 README](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-integrations/coding-agents/README.md)
- [dsh 原生插件](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-integrations/coding-agents/src/dsh.ts)
- [配置与默认值](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-integrations/coding-agents/src/core/config.ts)
- [对话提取](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-integrations/coding-agents/src/core/transcript-dsh.ts)
- [客户端 Reflect / Recall](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-integrations/coding-agents/src/core/hindsight.ts#L810)
- [工具集合](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-integrations/coding-agents/src/core/knowledge-tools.ts)
- [知识注入与展示规则](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-integrations/coding-agents/src/core/knowledge-injection.ts)
- [后端归纳及依据](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-docs/docs/developer/api/reflect.mdx)
- [文档管理与删除](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-docs/docs/developer/api/documents.mdx)
- [官方多用户隔离设计](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-docs/blog/2026-08-04-per-user-multi-tenant-agent-memory.md)
- [存储要求](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-docs/docs/developer/storage.md)
- [模型服务配置](https://github.com/vectorize-io/hindsight/blob/0bd55015393d224deec1073d63b24bcc0faad164/hindsight-docs/docs/developer/models.mdx)

原始材料保存在忽略目录 `.runtime/memory-research/hindsight/`，未提交或推送。
