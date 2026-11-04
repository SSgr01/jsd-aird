# Agent 平台

本项目以 pi 为唯一 Agent 执行循环。Java API 负责业务权限、知识库和成果存储。

快速问答与研究共用底座、证据协议和检索公共组件，保留独立策略与会话。
协议见 [evidence-protocol.md](docs/evidence-protocol.md)，
可配置检索与父块回取见 [retrieval-pipeline.md](docs/retrieval-pipeline.md)。
知识库书目信息自动补全、人工核对与目录续传见 [knowledge-bibliography.md](docs/knowledge-bibliography.md)。

文件生成与交付复用独立的 [files 能力包](control/src/aird_agent/capabilities/files/README.md)，
不要求文献研究主题。Word、Excel、PDF 的依赖、中文字体、转换与真实容器验收见
[沙箱文件环境](pi-worker/sandbox/README.md)。

| 目录 | 职责 |
| --- | --- |
| control/src/aird_agent/core | 宿主无关调度、预算、检查点、调用账本 |
| control/src/aird_agent/server | HTTP 服务、身份验证、配置与入口 |
| control/src/aird_agent/hosts/jsd | JSD 平台适配与交付策略 |
| control/src/aird_agent/capabilities | 问答、文献、文件、项目能力注册与处理 |
| control/src/aird_agent/evidence | 原文导航与引用 |
| control/src/aird_agent/sandbox | 独立 Broker、输入传输与执行回执 |
| pi-worker | Node 原生 pi 会话和模型循环 |
| pi-worker/sandbox | Docker 镜像构建文件 |
| control/harness_tests | 当前控制服务回归测试 |
| docs | 架构与协议；history 为历史设计资料 |

从仓库根目录使用 `scripts/dev-harness.ps1` 启动开发服务，
使用 `scripts/dev-sandbox.ps1` 启动沙箱 Broker。

Python 入口为 `python -m aird_agent.server`，迁移加 `--migrate`；
沙箱入口为 `python -m aird_agent.sandbox.broker`，均需设置 control/src 为 PYTHONPATH。
依赖安装使用 control/requirements.lock.txt。旧 LangGraph 应用不再包含在源码和发行包中。

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

新工具会改变能力指纹，使用新会话验收；旧 Run 的指纹检查继续生效。快速问答仍走固定检索和生成。
本轮不包含存量元数据补全、导入脚本改造或向量迁移，也不新增数据库迁移。
