# 企业业务服务

`aird_agent` 只负责权限、业务工具、证据、专业核查、成果登记和持久操作回执。模型循环、计划、消息队列、会话日志、压缩与恢复由原生 DeepSeek Harness 管理。

报告核查和可选证据评分使用当前会话的原生模型服务，沿统一的权限、预算和用量账本执行；无需独立配置 PaperQA 或模型密钥。检索直接消费任务查询，结构化读取返回真实字段与条件。普通工具结果由 dsh 整理，业务读取按需分页。

```powershell
$env:PYTHONPATH='src'
./.venv/Scripts/python.exe -m aird_agent.server
```

回归必须显式指定独立 `jsd_aird_agents*_test` 数据库，不读取业务 .env，不得对业务库运行测试：

```powershell
$env:HARNESS_TEST_DATABASE_URL='postgresql://agents_dsh_test@127.0.0.1:55449/jsd_aird_agents_dsh_test'
./.venv/Scripts/python.exe -m pytest harness_tests --confcutdir=harness_tests
```

进程重建测试验证原生 Session/Turn 映射、重复事件、撤权及未知操作禁止重试，不复制或读取旧引擎检查点。Office 镜像和真实模型任务需额外环境，未运行项单独记录。

架构、构建和启动见 [项目入口](../README.md)。
