# Agent 平台

本项目以 pi 为唯一 Agent 执行循环。Java API 负责业务权限、知识库和成果存储。

快速问答与研究共用底座、证据协议和检索公共组件，保留独立策略与会话。
协议见 [evidence-protocol.md](docs/evidence-protocol.md)，
可配置检索与父块回取见 [retrieval-pipeline.md](docs/retrieval-pipeline.md)。

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
