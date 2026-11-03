# Agent 控制服务

当前包为 `aird_agent`，不再包含旧 LangGraph 应用。
架构和启动说明见 [项目入口](../README.md)。

```powershell
$env:PYTHONPATH='src'
./.venv/Scripts/python.exe -m aird_agent.server
./.venv/Scripts/python.exe -m pytest harness_tests --confcutdir=harness_tests
```

测试数据库通过 HARNESS_TEST_DATABASE_URL 和 HARNESS_FAULT_DATABASE_URL 显式配置；未提供时数据库测试跳过。
历史设计位于 ../docs/history/。
