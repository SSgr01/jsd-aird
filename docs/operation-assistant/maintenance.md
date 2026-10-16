# 操作助手维护说明

操作手册的正文和页面清单维护在同一个目录，随系统版本发布。浏览器会话只保存在当前标签页，不写入数据库。

## 文件职责

- `operation-manual.md`：给客户阅读和给模型检索的手册正文。当前为按现有系统实现整理的初稿。
- `pages.json`：操作助手用的页面目录和章节索引。它把手册章节映射到固定路由和权限，并保存检索关键词、推荐问法；前端据此显示可访问的入口，Maven 同时将清单和正文打包到 `operation-assistant/`。它不是客户数据，也不是向量库。
- `maintenance.md`：开发维护说明，不进入检索集合。

## 更新手册

1. 对照当前系统核对业务文档，不直接将旧版本操作流程发布到新系统。
2. 每个操作以 `<!-- section: chapter-id -->` 开始，下一行是与清单标题完全一致的二级标题。保持已有章节编号稳定。
3. 在清单的 `sections` 中登记章节，关联 `pageIds`，补充关键词和常见问法。`requiredPermissions` 是全部需要满足的操作权限；页面 `permissions` 是至少满足其中一个的访问权限，空列表代表登录后可用。
4. 操作正文说明入口、前置条件、步骤及结果确认。未经业务确认的规则继续标记“待业务核对”。收到正式文档后按章节合并，保留已经核对有效的页面映射。
5. 增加页面时只登记已有固定路由；需要记录 ID 的工作台以相应列表作为入口，`contextPatterns` 仅用于识别当前页面，不生成记录链接。
6. 调整清单的 `version`，完成测试并一起发布前后端。系统版本还包含清单与手册的 SHA-256，正文变化也会产生新索引版本。

## 向量与检索

复用 `JSD_AIRD_EMBEDDING_BASE_URL`、`JSD_AIRD_EMBEDDING_API_KEY`、`JSD_AIRD_EMBEDDING_MODEL` 和 `JSD_AIRD_EMBEDDING_DIMENSION`。默认维度为 1024，实际值必须与提供方一致，不在浏览器配置模型密钥。

V77 迁移创建 `ai.operation_manual_index` 和 `ai.operation_manual_chunk`。这两张表只存放打包发布的公共操作手册及派生向量，权限在每次检索时筛选。首版手册较小，向量检索采用精确余弦距离排序。

应用就绪后在后台同步索引，数据库事务锁避免多实例重复构建。同模型、同维度、同内容哈希的向量复用；所有片段生成成功后事务提交并标记 READY。启动时失败最多尝试 3 次，间隔 30 秒；修复配置后重启会继续同步。

每次查询仅使用匹配当前手册版本、模型和维度的 READY 索引。关键词与向量分别最多 20 个候选，等权 RRF（常数 60）合并后保留最多 6 个章节。`JSD_AIRD_OPERATION_ASSISTANT_EMBEDDING_QUERY_TIMEOUT` 默认 3 秒，超时或服务失败退回当前版本的关键词检索。模型生成失败时展示相关授权手册供参考并允许重试。

模型正文链接不会成为页面入口；后端根据有效章节引用生成链接，前端再次校验固定路由和当前权限。不要将手册接入现有研发资料检索范围，否则会混淆使用范围和知识资料权限。

## 验证与排查

本仓库完整前端构建包含较大的表格与文档编辑依赖。本次构建在 6 GB Node 堆上限下通过；Windows 可先执行 `$env:NODE_OPTIONS = '--max-old-space-size=6144'`，再执行 `npm.cmd run build`。

- 前端：`npm run typecheck`、`npm run lint`、操作助手相关 Vitest 测试和 `npm run build`。
- 浏览器实测：先运行前端开发服务，再执行 `npm run verify:operation-assistant`。该脚本使用独立浏览器和测试接口响应，截图及报告保存到 `artifacts/operation-assistant/`，不读取或写入客户数据。
- 后端：`OperationManualTest`、`PermissionRouteFilterTest`、架构及模块边界检查。
- PostgreSQL 实测：设置 `JSD_AIRD_OPERATION_MANUAL_PG_TEST=true`，使用 `JSD_AIRD_DATASOURCE_*` 连接本机测试数据库，执行 `OperationManualIndexPostgresTest`。测试创建随机临时 schema 并清理，不改应用表。
- 日志：`operation_manual_index` 查看索引状态，`operation_manual_retrieval` 查看候选和降级原因，`operation_assistant` 按请求编号查看回答状态及耗时。
- 检查未找到依据、表达歧义、账号权限变化、断流重试和退出登录的会话清理。
- 多种问法和预期行为见 `question-test-cases.md`。

模型和向量服务未配置时，业务系统仍可使用，操作助手会显示相应状态并提供已授权手册参考。
