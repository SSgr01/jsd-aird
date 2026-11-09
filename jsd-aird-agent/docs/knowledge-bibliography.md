# 知识库书目补全与目录导入

书目信息按组织、文档和文件版本保存，与目录分类、可见范围、文档标题独立。自动补全不改写发布快照；已可靠匹配时以书目 API 返回的实际字段为准，没有提供的字段保留本地已有值。每次写入追加修订；新知识库证据的 anchor 可带 `bibliography_revision`，验证时读取该修订。旧引用继续按原来的标题和定位验证，权限撤销后仍拒绝读取。

## 部署

本地保持 Flyway 关闭，检查数据库实际结构后手动执行 `jsd-aird-api/src/main/resources/db/migration/V99__knowledge_bibliography.sql`。不要改历史迁移编号或执行 repair。API 与 `local,worker` 进程均需更新；异步处理使用现有 PostgreSQL Worker，不另建 Agent 循环。Java 进程读取 `JSD_AIRD_OPENALEX_API_KEY`。

## 提取与核对

- 优先复用成功解析的原文节点和页码，缺少节点时读取已保存的 MinerU Markdown 或结果 ZIP；没有可用内容记录为“缺少解析结果”，不重新 OCR。
- 首页及前三页的文本用于保守识别，参考文献之后的 DOI 不用于匹配。识别为公开论文且有外发权限后，才调用共享书目服务。客户、内部和未知类型仅做本地提取。
- DOI 与标题一致，或规范化标题唯一精确匹配且作者或出版年份吻合，才自动接受外部候选。撤稿、多个匹配或近似标题不能直接通过；没有 DOI 时明确作者冲突仍拒绝匹配。日期和年份差异不否决已经由题名与 DOI／作者确认的匹配；没有 DOI、作者佐证时仍要求年份吻合。外部匹配不等于原文科学结论已通过核查。
- 已确认匹配后，日期、作者、期刊、卷期页码等字段自动采用 API 实际返回值，不因它们与 OCR 或旧值不同而要求人工确认；API 未提供时保留已有值及来源。缺少日或月份时不补造原文日期。
- 已确认公开论文但解析 DOI 缺失／存在多个读法，或 DOI 找到候选而解析题名不匹配时，读取同一授权文件版本 PDF 的前三页文字层再次精确核对；原件 DOI、题名及可用作者须相互印证。成功记录原件文件版本、哈希和页码，不修改 OCR 正文，不增加模型调用，也不把 PDF 发送给书目服务。无文字层、仍不一致或多个匹配时仅保留可确认的本地信息，不按特定论文或字符替换规则放行。
- 专利只提取明确 INID/标签对应的原文字段，不推断法律状态。不确定的类型、标题、字段与候选由详情页“书目信息”核对。没有摘要标题的论文可依据题名、DOI 与出版印记识别；图片首页、跨行字段或缺少出版信息的版式仍可能需要人工核对。
- 可选编辑须携带当前修订号；修订冲突返回 409。编辑后立即保存，无需选择候选或再确认；后续显式重新补全时，可靠匹配的 API 字段仍优先。没有可靠匹配时本地自动提取不覆盖用户编辑值。修改进入现有审计日志，历史修订继续可查。
- 免费额度耗尽时保存等待状态和恢复时间，延后入现有任务队列。暂时服务故障有限重试，确定性错误独立记录；正文发布不因书目失败而回滚。

## 使用

正文解析成功后由后台自动补全，不在知识库列表展示补全任务入口、技术说明或批次统计。详情页按当前选中的文件版本直接展示已接受信息，保留按权限使用的“编辑”，不提供填入候选、保存核对等必经流程。确实无法确认的外部候选不冒充已接受信息，仅提示部分信息暂未补全；运维追踪、修订和历史兼容接口保留。

批量目录导入沿用 `scripts/local/import_knowledge_directory.py`。清单分别记录 `bodyImportStatus`、`metadataStatus`、`metadataRevision`、`metadataTaskId`、`vectorIndexStatus` 和失败信息。已入库文件续跑时关联原文档，已发布授权的不重新解析或发布；补全任务也使用稳定请求标识。
筛选部分文件时，使用重复的 `--include 相对路径`；脚本不拷贝或改动来源 PDF，也不导入未选文件。

存量补全不需要原始 PDF 目录：

```powershell
python scripts/local/import_knowledge_directory.py --enrich-existing --base-url http://127.0.0.1:8180 --dry-run
python scripts/local/import_knowledge_directory.py --enrich-existing --base-url http://127.0.0.1:8180 --request-key backfill-20261104
```

用户名和密码通过 `JSD_AIRD_USERNAME`、`JSD_AIRD_PASSWORD` 提供。可加 `--category-id` 限定分类，`--metadata-wait-seconds` 等候结果；同一请求标识不能用于不同资料范围或版本。目录导入默认提交补全后记录当前状态即退出，可续跑查询。发布成功不代表书目已核对；待核对、缺少解析、补全失败和向量不完整分别汇总。

批量补全先持久化目标，再按现有异步队列的剩余容量分批调度；任务游标和幂等标识持久化，不一次提交所有文件占满组织队列。队列满时沿现有 Worker 延后执行，失败独立显示并支持重试。

向量状态来自当前发布子切片实际存储情况：`PRESENT`、`PARTIAL`、`MISSING`、`NOT_INDEXED`。它不表示已与新模型配置兼容。本轮不选择或部署向量模型，也不启动存量向量迁移。

## 接口

`/api/v1/knowledge` 下新增：

- `POST /bibliography-tasks/preview`：本地提取预览，不调用外部服务。
- `POST /bibliography-tasks`：`documentIds` 或 `categoryId`，均不传表示全部；必须传 `requestKey`。全部范围使用稳定 UUID 游标，不限 1000 份。
- `GET /bibliography-tasks`、`GET /bibliography-tasks/{id}?cursor=...&limit=...`：本人任务、计数和分页结果，逐资料复核读取权限。
- `POST /bibliography-tasks/{id}/items/{item}/retry`：续跑已失败、缺解析、版本变化、待核对或无匹配的项；等待中的项不重复执行。
- `GET/PUT /documents/{doc}/versions/{version}/bibliography`：读取或携修订号核对，读取可指定历史 `revision`。
- `GET /documents/{doc}/versions/{version}/vector-status`：真实存储状态，不触发向量生成。

解析公共成功路径仅投递补全任务。调度失败单独记日志，可重提交存量补全恢复；不会将已成功的解析改为失败。
