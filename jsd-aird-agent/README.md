# 企业助手与 DeepSeek Harness

DeepSeek 是唯一模型执行循环和网页工作台，上游固定为 `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159`（`0.2.2-rc.1`），使用独立 Node 24.11.1 与 pnpm 11.28.5。企业插件独立维护，不修改上游循环。统一入口在原生标准预设上组合授权业务工具和按需专业技能；简单回答与多步研究使用同一执行路径。

| 目录 | 职责 |
| --- | --- |
| ../vendor/deepseek-harness | 原生循环、会话、Inbox、计划、压缩、恢复、附件与交付界面 |
| deepseek | 企业认证代理、按组织和用户隔离的 Host、RPC 与 WebSocket 门禁 |
| enterprise-plugin | 受控业务工具、原生 @ 资料入口、来源详情、文件与沙箱桥 |
| control/src/aird_agent/core | 业务操作账本、幂等与事件投影 |
| control/src/aird_agent/hosts/jsd | 当前业务权限、证据和专业交付规则 |
| control/src/aird_agent/capabilities | 检索、文献、文件登记、只读项目与实验基础工具 |
| control/src/aird_agent/sandbox | 独立 Broker、快照输入、取消与执行回执 |
| sandbox | Office、PDF、中文字体与 LibreOffice 镜像构建文件 |

DSH Workspace 管目录和会话，企业资料随任务通过原生附件或 `@` 输入。默认按当前权限检索，明确筛选只约束对应请求。没有持久的“资料范围”，也没有独立成果、队列或文件操作界面。模型状态以 dsh 日志为准，业务结果以权威服务回执为准。文件按用户要求生成，普通文件不要求研究主题或报告。

网页外观通过企业插件的 `src/theme.mjs` 和 `src/theme.css` 适配 UI-opz 系统设计，使用原生 `theme.overrideTokens` 与品牌、导航插槽；不复制旧问答页面或上游组件。浅色、深色、字号和自选字体继续由 dsh 原生设置管理，网关仅允许其显示字段。修改样式后执行 `node jsd-aird-agent/enterprise-plugin/build.mjs` 并重启网关及其 Host；生成的 `lib/client.js` 包含主题资源，不需要额外样式请求。

欢迎区保留 UI-opz 的“阅读文献、比较方法、整理数据”卡片，通过原生 Hero 与输入区插槽展示；点击仅追加到原生草稿并聚焦，保留已有文字、资料引用与附件，由用户确认发送。进入已有对话后不显示卡片。

页面品牌统一为“杰事达研发工作台”，包括浏览器标题、欢迎语、图标、安装清单、设置帮助与错误提示。网关 `src/branding.mjs` 仅适配固定版本的静态页面和客户端文案资源（包括原生 `/plugins/??` 合并脚本），保留原生组件、国际化键、模块与协议；API、会话正文、原文与交付文件字节不进入品牌适配。上游升级时需复核字典格式与静态资源路径。

“自动化任务”恢复原生 `ui-schedule`、Host Schedule 服务与 `schedule_create/list/view/edit` 工具；创建、编辑、暂停、立即执行和执行历史共用原生存储。新建会话的任务目标只能使用本用户的受管目录与企业预设，已有会话由原生用户隔离的注册表校验。每次自动投递重新检查企业身份和既有资料权限，指令进入与手动消息相同的业务绑定、模型预算和工具门禁。原生执行历史记录投递，实际业务成功与否仍以会话和业务回执为准。

自动执行依赖用户 Host 运行和有效企业授权；授权过期后需要重新登录，服务重启后的 Host 在再次访问工作台时启动。不延长身份有效期、不绕过撤权，也不另建调度器。需要长期无人值守部署时，应配置常驻 Host 与可撤销的自动化服务授权。

Inbox 的编辑、取消、插话与自动发送使用原生实现；排序和纯文本放回输入框通过输入区扩展调用原生 Inbox 与草稿 API，仍只有一份队列与持久日志。重启恢复不自动执行已停止会话的排队消息。

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
