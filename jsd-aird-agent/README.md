# 企业助手与 DeepSeek Harness

DeepSeek 是唯一模型执行循环和网页工作台，上游固定为 `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159`（`0.2.2-rc.1`），使用独立 Node 24.11.1 与 pnpm 11.28.5。企业插件独立维护，原生循环仅补充等待入场的扩展点。统一入口在原生标准预设上组合授权业务工具和按需专业技能；简单回答与多步研究使用同一执行路径。

企业读取适配已扩展到结构化记录、客户、库存、两类生产单、品管、研发测试与谱图，并接入现有预测、配方搜索及历史实验优化。新增域等待记录权限实现，默认关闭；已有研发链路继续使用现有授权。各模块状态见[覆盖核对](docs/enterprise-data-coverage.md)，工具、原生数据文件及权限对接契约见[业务接入说明](docs/enterprise-business-data.md)。

| 目录 | 职责 |
| --- | --- |
| ../vendor/deepseek-harness | 原生循环、会话、Inbox、计划、压缩、恢复、附件与交付界面 |
| deepseek | 企业认证代理、按组织和用户隔离的 Host、RPC 与 WebSocket 门禁 |
| enterprise-plugin | 受控业务工具、原生 @ 资料入口、来源详情、文件与沙箱桥 |
| control/src/aird_agent/core | 业务操作账本、幂等与事件投影 |
| control/src/aird_agent/hosts/jsd | 当前业务权限、证据和专业交付规则 |
| control/src/aird_agent/capabilities | 检索、文献、文件登记、统一业务读取、实验草稿与现有计算服务工具 |
| control/src/aird_agent/sandbox | 独立 Broker、快照输入、取消与执行回执 |
| sandbox | Office、PDF、中文字体与 LibreOffice 镜像构建文件 |

DSH Workspace 管目录和会话，企业资料随任务通过原生附件或 `@` 输入。默认按当前权限检索，明确筛选只约束对应请求。没有持久的“资料范围”，也没有独立成果、队列或文件操作界面。模型状态以 dsh 日志为准，业务结果以权威服务回执为准。文件按用户要求生成，普通文件不要求研究主题或报告。

运行入场由现有 PostgreSQL 保存队列和租约：每用户默认同时运行 2 个任务，组织限额默认跟随现有全局上限。超额请求按组织、用户轮流自动启动，等待期间不领取原生输入、不解析工具、不计执行时间。10 秒续租、60 秒到期；取消、失败、重启和迟到回执按执行代次对账。状态区提供排队提示、取消及安全重试，原始诊断仅管理员可见。配置及恢复限制见 [运行入场说明](docs/run-admission.md)。

分支按钮显示不可用原因、创建进度及分阶段重试；同一请求复用原生持久分支。化学 Markdown 使用共享分子卡片，区分 RDKit 语法及价态有效、同组重复结构与原图核对。本次不核对或更正论文分子结构，也不追加更正历史回答。

长期记忆已接入自托管 Hindsight 0.8.6，提供本人私有偏好、新会话召回、来源核验、后台整理、更正遗忘及原生设置。Control 复用现有记录结构保存控制信息，正文由 Hindsight 保存；不导入启用前历史。默认配置保留部署开关，先隔离验收再启用企业入口。接入及开关见 [个人记忆一期](docs/personal-memory.md)，固定镜像及后台启动见 [记忆服务部署](../deployment/hindsight/README.md)，实际结果见 [验收记录](docs/personal-memory-acceptance.md)。

网页外观通过企业插件的 `src/theme.mjs` 和 `src/theme.css` 适配 UI-opz 系统设计，使用原生 `theme.overrideTokens` 与品牌、导航插槽；不复制旧问答页面或上游组件。浅色、深色、字号和自选字体继续由 dsh 原生设置管理，网关仅允许其显示字段。修改样式后执行 `node jsd-aird-agent/enterprise-plugin/build.mjs` 并重启网关及其 Host；生成的 `lib/client.js` 包含主题资源，不需要额外样式请求。

欢迎区保留 UI-opz 的“阅读文献、比较方法、整理数据”卡片，通过原生 Hero 与输入区插槽展示；点击仅追加到原生草稿并聚焦，保留已有文字、资料引用与附件，由用户确认发送。进入已有对话后不显示卡片。

页面品牌统一为“杰事达研发工作台”，包括浏览器标题、欢迎语、图标、安装清单、设置帮助与错误提示。网关 `src/branding.mjs` 仅适配固定版本的静态页面和客户端文案资源（包括原生 `/plugins/??` 合并脚本），保留原生组件、国际化键、模块与协议；API、会话正文、原文与交付文件字节不进入品牌适配。上游升级时需复核字典格式与静态资源路径。

“自动化任务”恢复原生 `ui-schedule`、Host Schedule 服务与 `schedule_create/list/view/edit` 工具；创建、编辑、暂停、立即执行和执行历史共用原生存储。新建会话的任务目标只能使用本用户的受管目录与企业预设，已有会话由原生用户隔离的注册表校验。每次自动投递重新检查企业身份和既有资料权限，指令进入与手动消息相同的业务绑定、模型预算和工具门禁。原生执行历史记录投递，实际业务成功与否仍以会话和业务回执为准。

首次进入工作台默认安装项目晨报、研发周报和实验复盘，使用北京时间及三个独立会话。网关启动恢复启用任务的用户 Host，并用独立、可撤销的后台授权换取短期执行票据；关闭浏览器后继续执行，不保存浏览器 Cookie。后台授权只允许这三项任务的研发读取与分析，原生任务、投递消息、会话及实际轮次共同绑定，普通对话和自建任务不能借用。账号、组织或授权版本失效后停止执行。详见 [内置研发简报](docs/research-briefings.md)。

Inbox 的编辑、取消、插话与自动发送使用原生实现；排序和纯文本放回输入框通过输入区扩展调用原生 Inbox 与草稿 API，仍只有一份队列与持久日志。用户主动停止的会话不会自动续跑；容量等待和非主动中断通过持久入场记录恢复。

会话永久删除和对话回退分别加载固定的 `@michengai/dsh-archive-manager@1.2.8`、`dsh-rewind-plugin@0.21.2`，界面和主体操作直接复用插件。网关依赖目录的 `pnpm-workspace.yaml` 固定补丁并关闭自动安装 peer，运行时共享固定上游 Host 的服务；安装使用 `pnpm install --frozen-lockfile`。补丁只适配当前持久化接口、企业删除/回退生命周期、沙箱文件路径和业务结果边界。

删除当前会话后通过原生 `uiWorkspace.startSession` 返回新会话。企业部署不显示插件附带的宿主 Explorer 和物理跨目录搬迁入口，对应服务端操作也保持关闭。

永久删除先停止原生 Agent、排空业务投影及缓存，再删除原生会话和对应回退快照、业务会话投影；业务写入回执与已登记成果保留，删除标记阻止迟到事件恢复会话。被自动化任务引用的会话需先解除该任务。工作区删除沿用原生语义：删除登记，保留目录与会话，会话转入未分组。对话回退只还原对话及插件已备份的文件，不撤销实验草稿、报告或成果登记。

浏览器与文件工具统一使用沙箱目录 `/work/scratch/native`，默认工作区显示“我的工作区”。网关对原生 HTTP/流式元数据中的工作目录、工作区和文件路径作显示映射，目录选择和错误提示使用同一命名空间；入站目录仍逐次执行所属用户、真实目录及符号链接检查。用户自定义名称、会话正文和下载文件字节不作替换。

输入框右下角的主按钮按状态切换：有文字或附件时保留原生发送箭头，空输入且运行中显示停止方块；手动停止、运行中断或输出截断后，空输入显示继续三角，在原会话发送续接消息。按钮读取原生轮次事件，刷新后仍可使用；同一暂停位置的请求复用原生请求 ID，避免连点或响应丢失后重复执行。正常完成与权限、策略拦截保留原生按钮行为。

浏览器打开 `/agent/` 时，未登录或登录过期会跳转同域系统登录页，登录成功后整页返回原工作台地址，保留会话参数和定位片段。接口及静态资源请求继续返回 HTTP 401；权限不足返回 403，身份服务故障返回 503，均不会误跳登录。网关日志记录状态、拒绝原因和路径，不记录 Cookie、票据或查询参数。

## 构建与启动

```powershell
git submodule update --init vendor/deepseek-harness
# 在独立 Node 24 环境中，使用上游 packageManager 固定的 pnpm。
cd vendor/deepseek-harness
pnpm install --frozen-lockfile
pnpm build
cd ../..
node jsd-aird-agent/enterprise-plugin/build.mjs
```

安装 Python 依赖使用 `control/requirements.lock.txt`；网关依赖使用 `deepseek/pnpm-lock.yaml`。从仓库根目录分别执行 `scripts/dev-harness.ps1 -Service gateway -EnvFile .env`，将 Service 换为 `control`、`api`、`web` 启动其他服务。沙箱独立执行 `scripts/dev-sandbox.ps1`。独立 Node 可通过 `JSD_AIRD_DSH_NODE` 指定；首次建立业务账本使用 `-Service migrate-control`，不执行业务库 Flyway，本地保持 `SPRING_FLYWAY_ENABLED=false`。网关默认 8092、Control 8093、Broker 8094，开发前端 5184、API 8180。

Host 冷启动默认等待 300 秒，可用 `JSD_AIRD_DSH_START_TIMEOUT_MS` 在 1000–600000 毫秒内调整。实例退出或身份版本变化后，同一用户的并发请求共享一次重启，避免重复启动和争用会话目录。

只在组织与用户的受管执行根目录中允许原生文件操作。原生 bash 的同一目录快照交给隔离沙箱，输出核验后回写，再由原生 present 展示已登记文件。宿主不执行模型命令，不开放模型安装插件。未知写入先查回执，禁止自动重跑。

命令链路为原生 `tool-bash` / Jobs → 原生 `LocalBashExecutor` → 企业 Subprocess 提供方 → Control 业务账本 → Broker → Docker。企业 Shell 只补充策略与沙箱事实；超时分类、取消、输出收集、溢出文件和后台交互由原生实现负责。企业提供方固定提交时的 Run、Turn 和调用 ID，后续对话不能改绑已运行的命令。

Broker 启动后预先准备空的隔离容器，收到命令时再复制本次输入并释放 stdin 执行门。每个容器只执行一次，不在命令或用户之间复用；准备阶段没有业务输入。镜像必须包含当前 `sandbox/runner.py`，支持 `--stdin` 的 ready 协议 v3（独立 stdout/stderr 字节流与最终结果帧）；更新代码时在仓库根目录执行 `docker build -t jsd-aird-analysis:1 jsd-aird-agent/sandbox` 重建镜像，再重启 Broker。协议不匹配会拒绝执行，不回退到宿主运行。保留无网络、非 root、只读宿主挂载和资源限制；联网获取仍使用网页或文献获取工具。

预启动数量由 `JSD_AIRD_SANDBOX_PARALLELISM` 控制，默认 4；单次准备上限 `JSD_AIRD_SANDBOX_STARTUP_SECONDS` 默认 45 秒，Docker 清理上限 `JSD_AIRD_SANDBOX_CLEANUP_SECONDS` 默认 10 秒。Broker 的资源上限包含等待可用容器及输入准备。回执记录容器预启动、等待、输入复制、脚本及导出耗时；预启动耗时发生在后台，不能与本次命令耗时相加。健康接口的 `ready` 表示当前可用空容器数。

原生 bash 使用上游前台等待配置：默认 120 秒，可通过 `timeoutMs` 指定，最高 600 秒；原生 `run_in_background` 和超时转后台已启用，使用原生 Jobs 列表、输出与停止交互。后台等待不受前台期限限制，Broker 的 `JSD_AIRD_SANDBOX_TIMEOUT_SECONDS` 和 Control 的 `JSD_AIRD_HARNESS_SANDBOX_TIMEOUT_SECONDS` 仍是资源上限，默认 600 秒。命令业务请求按资源上限加 15 秒设置传输期限，输出及取消请求使用独立短超时。模型回复结束而命令仍运行时，业务账本保留待完成状态，收到命令回执后才收尾；取消或失效任务不能被迟到的命令回执改成成功。

stdout 与 stderr 在执行期间按独立字节游标读取，使用上游 `OutputCollector` 保存尾部与完整溢出文件；不再静默截到 12,000 字符。Broker 日志在独立 `.streams` 目录持久保存，每个流默认保留最近 8 MiB（`JSD_AIRD_SANDBOX_OUTPUT_BYTES_PER_STREAM`，最高 64 MiB），不占成果导出配额。读取落后于保留位置时明确报告缺口，不提供伪完整日志。原生完整输出位于会话 `.dsh-process-output`，可用原生文件工具读取；它不会作为业务成果重新导出。命令输入快照仍有 50 文件 / 10 MiB 的传输限制（包括被带入容器的既有日志），超限明确拒绝，不静默丢文件。

取消先返回 `CANCELLING`，确认容器终止后才记录 `CANCELLED`；清理未确认时保留 `UNKNOWN`。停止响应丢失后回读同一执行回执，控制层在停止后有界对账，不重复提交命令。Broker 重启可读取已有日志及已完成回执，未完成操作保留为未知；带当前 Broker 工作区标签的遗留容器由清扫器处理，不删除会话、业务数据或其他部署的容器。原生 Jobs 注册表保存在运行实例内存中，网关或 Host 重启不支持在原生任务卡中继续运行旧命令；此时按业务回执对账，不能承诺跨重启续算。

原生工作目录及溢出正文统一使用沙箱可见的 `/work/scratch/native/...` 路径。溢出正文保存在当前会话目录的 `.dsh-spill/<会话摘要>/` 中，继续由原生 read、grep 和 bash 读取；不向模型提供 Windows 宿主临时目录。文件快照、回写、成果登记及下载仍核对原有哈希和回执。

测试、实际启动参数和固定客户任务见 [deepseek-migration.md](docs/deepseek-migration.md)。旧 pi 源码、启动与检查点读取已移除，旧会话不兼容。协议见 [evidence-protocol.md](docs/evidence-protocol.md)，检索与书目见 [retrieval-pipeline.md](docs/retrieval-pipeline.md)及 [knowledge-bibliography.md](docs/knowledge-bibliography.md)。

## OpenAlex 论文发现与全文

Java 的 `ScholarlyLiteratureFacade` 提供共享服务，通过官方 HTTP API 访问 OpenAlex。
在根 `.env` 配置 `JSD_AIRD_OPENALEX_API_KEY` 后重启 API；密钥仅由 Java 持有，以请求头发送。
Agent 沿现有 Registry 和 `tool_search` 按需发现工具，不增加 MCP、CLI 或另一套运行循环。

| 工具 | 用途 |
| --- | --- |
| `bibliography_lookup` | 按 DOI、论文 ID 或题名核对书目；无匹配或未配置时补查 Crossref |
| `literature_search` | 关键词或语义发现，支持年份、类型、开放获取过滤 |
| `literature_expand` | 查询参考文献或引用它的后续论文 |
| `literature_acquire` | 获取许可明确的 PDF，并异步解析为当前会话的私有资料 |
| `literature_acquire_status` | 只检查已有获取操作，完成后加入当前 Run 的资料范围 |

所有外部请求沿用本次参数的联网确认；拒绝后不重复询问。默认关键词检索，每页最多 20 条；
语义检索最多 50 条。分页使用返回的 `next_cursor`，语义分页需保持相同页大小。
元数据、摘要、引用次数及引用关系用于发现，不作为已读取的原文证据。

共享服务包含有界缓存、限流及失败重试；收费端点每次请求和重试前检查免费日额度，
即使账户有预付余额，免费余额不足仍停止。该检查约束本服务，不能为其他共用 Key 的程序预留额度。
单记录查询免费。额度不足返回等待信息，不自动切换付费路径。

全文仅使用官方 content 端点，限制 10 MiB，自动获取范围沿用 `CC BY`、`CC BY-SA`、`CC0`。
没有明确许可、撤稿、无全文或格式不符时拒绝获取，不绕过出版方限制。
获取前在现有私有资料表登记会话内唯一操作；重调只返回该操作，网络结果未知时不能重新下载。
现有“检查结果”能核对丢失的提交响应，确认已提交不等于全文已经解析完成。
解析成功后沿原有私有文献阅读、固定版本、哈希、页码和 quote 链路产生证据；不自动发布知识库或建立研究主题。

新工具会改变能力指纹，使用新会话验收；旧 Run 的指纹检查继续生效。问答 HTTP 接口和工作台统一调用同一个 dsh 执行路径。
本轮不包含存量元数据补全、导入脚本改造或向量迁移，也不新增数据库迁移。

### 工作区目录命名

每个组织、用户的执行存储独立映射为 `/workspaces`，新建默认目录为 `/workspaces/default`。目录选择器只允许此根目录及其子目录，拒绝跨用户路径和链接逃逸。同一用户选择同一目录的会话共享文件。现有目录与会话不迁移、不删除。浏览器元数据和原生文件工具使用同一命名空间；沙箱镜像通过 `/workspaces` 链接到受控工作副本，更新后需重建 `jsd-aird-analysis:1`。收藏使用插件原生 `args.input` 参数协议，网关继续校验会话标识和布尔状态。

### 原文链接展示

企业来源使用原生 Markdown 链接和 PDF 侧栏。标签优先取已记录的章节或内容名称，如“配方与固化工艺 · 第3页”，否则使用清理后的论文简称；缺少标题时显示“论文原文”。固定文献版本、证据标识、权限检查和页码地址保持不变。历史回复只在展示时规范链接，不改会话日志；孤立页码仅在当前文献入口明确时绑定，无法确定来源时标为“来源待确认”。推理与图片通过独立扩展席位复用原生组件，不增加引用组件或 PDF 阅读器。

文献工具结果在每次返回内合并重复来源声明，保留原文、页码及适用条件，最终模型投影再次检查读取预算。引用修复提供错误位置和已读证据候选，默认最多 2 次模型请求、2 次已有来源补读；预算和工具限制由服务端执行，耗尽后保留明确缺口或失败状态。配置及细节见 [检索流水线](docs/retrieval-pipeline.md)。
