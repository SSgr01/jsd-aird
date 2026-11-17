# Qwen3 Embedding 部署与查询指令评测

云端 API 与自部署服务使用同一个 OpenAI-compatible `/embeddings` 适配器。通过环境变量指定地址、服务实际暴露的模型名和维度；需要鉴权时填写 Key，无鉴权服务显式留空。API 与 Worker 都要使用同一组模型、维度配置。

```dotenv
JSD_AIRD_EMBEDDING_BASE_URL=http://127.0.0.1:8000/v1
JSD_AIRD_EMBEDDING_API_KEY=
JSD_AIRD_EMBEDDING_MODEL=Qwen/Qwen3-Embedding-4B
JSD_AIRD_EMBEDDING_DIMENSION=2560
JSD_AIRD_EMBEDDING_QUERY_PROFILE=none
JSD_AIRD_EMBEDDING_QUERY_VERSION=qwen3-instruct-v1
```

配置文件对应 `app.ai.embedding.query`。`profile` 支持 `none`（默认基线）、`generic`（官方通用检索指令）、`materials`（材料研发候选）。通用与材料指令位于 [共享预设](../jsd-aird-api/src/main/resources/ai/embedding-query-presets.json)，Java 服务和 Python 评测读取同一份预设。

仅查询向量使用 `Instruct: {instruction}\nQuery:{query}`。文档入库和重建向量保持原文，BM25 接收原始检索文本。操作手册在启用指令时使用独立的操作任务描述，`none` 时同样保持原始查询。知识库指令、手册指令可分别通过 `JSD_AIRD_EMBEDDING_KNOWLEDGE_INSTRUCTION`、`JSD_AIRD_EMBEDDING_MANUAL_INSTRUCTION` 覆盖。

修改指令时建议更新 `QUERY_VERSION`；指令正文、格式哈希及版本均进入检索策略指纹，即使忘记递增版本，正文变化也会使旧检索快照失效。配置在进程启动时读取，修改后需要重启服务。

## 切换模型与已有文档

切换模型必须重建文档向量。同为 1024 维也不能混搜；知识库检索在排序和 LIMIT 前按 `embedding_model` 精确匹配，未标记模型的向量也会排除。模型名称应对应固定权重版本，不能将同一服务别名悄悄指向新权重，否则模型字符串无法识别这次变化。

先部署更新后的 API 和 Worker，再使用已有导入脚本的向量重建模式。模型名、维度必须与服务配置一致：

```bash
python3 scripts/local/import_knowledge_directory.py --rebuild-vectors --embedding-model Qwen/Qwen3-Embedding-4B --embedding-dimension 2560 --dry-run
```

核对清单后移除 `--dry-run` 执行。脚本分页处理已发布且允许 AI 使用的文档，记录进度并支持续跑，保留发布版本、分块和词法索引。重建期间尚未迁移的文档仍可通过词法检索召回，向量通道只返回当前模型已建好的文档。操作手册已有模型和维度隔离，会按新模型建立自己的索引。

仅调整查询指令不需要重建文档向量。

## 三组对照

[独立评测脚本](../scripts/local/evaluate_embedding_instructions.py) 只调用 Embedding API，不写知识库。它对固定语料生成一次无指令的文档向量，分别用 `none / generic / materials` 生成查询向量，使用相同精确余弦排序、相同 K 和本地人工标注计算宏平均 Recall@K、nDCG@K。查询同分时按语料 ID 排序，报告保存输入、语料、预设哈希和每题命中情况。Key 从环境变量读取，不写入报告。

本地评测集格式如下。`corpus` 应使用固定发布版本的真实 CHILD 分块；`relevance` 的键是分块 ID，等级为 0～3 的整数，正数表示相关，每题至少标注一个相关分块。未标注分块按不相关计算，因此标注质量直接影响指标。示例仅说明格式，不能用于判断真实效果：

```json
{
  "corpus": [
    {"id": "chunk-a", "text": "固定版本的真实材料研究证据……"},
    {"id": "chunk-b", "text": "固定版本的其他证据……"}
  ],
  "queries": [
    {"id": "q1", "query": "光引发剂含量如何影响UV固化树脂性能？", "relevance": {"chunk-a": 3, "chunk-b": 0}}
  ]
}
```

脚本不自动加载 `.env`，运行前将所需变量导出至当前终端，或显式传入地址、模型、维度：

```bash
python3 scripts/local/evaluate_embedding_instructions.py --cases .runtime/qwen-eval/cases.json --output .runtime/qwen-eval/report.json --base-url http://127.0.0.1:8000/v1 --model Qwen/Qwen3-Embedding-4B --dimension 2560 --top-k 5,10,20
```

云端服务通过 `JSD_AIRD_EMBEDDING_API_KEY` 提供 Key。每个报告使用新文件名，脚本拒绝覆盖已有报告。语料、问题、结果应保存在忽略的 `.runtime` 目录。三组评测期间需固定模型权重、服务配置、问题、语料和标注；另保留未参与指令调优的验收题。

此报告衡量纯向量检索。选择候选后，还应使用现有 `evaluate_knowledge_quality.py` 验证完整检索流程，并人工检查最终证据能否支持回答；纯向量 Recall 或 nDCG 的提升不等于最终回答准确率提升。操作手册需要独立评测，不能用材料题的结果代替。

Qwen 官方提到合适指令在多数下游任务中通常有约 1%～5% 的收益，但这不是某个指标的百分点保证。参考 [官方说明](https://github.com/QwenLM/Qwen3-Embedding/blob/main/README.md) 与 [查询/文档用法](https://huggingface.co/Qwen/Qwen3-Embedding-4B#usage)。

## 共用数据库：云端 1024 与 Qwen 4B 2560 并存

升级至 V118 后，知识库向量独立保存在 kb.document_chunk_embedding，按分块、模型、维度分别存储。两种表达式 HNSW 索引只负责检索，不会触发模型调用。Linux 导入只调用 Qwen；1024 云端模型只按需补建。

本地继续保留现有 BASE_URL/API_KEY/MODEL=text-embedding-v4/DIMENSION=1024。服务器 MODEL=Qwen/Qwen3-Embedding-4B、DIMENSION=2560，BASE_URL 指向实际 /v1 地址，API_KEY 可显式留空。两边 API 和 Worker 均设置：

```dotenv
JSD_AIRD_EMBEDDING_AUTO_BUILD_MODEL=Qwen/Qwen3-Embedding-4B
JSD_AIRD_EMBEDDING_AUTO_BUILD_DIMENSION=2560
JSD_AIRD_EMBEDDING_EF_SEARCH=100
```

服务器 Worker 使用 Qwen 查询模型配置，本地 Worker 使用云端模型配置。数据库领取前匹配任务的 expectedEmbeddingModel/expectedEmbeddingDimension，不匹配不占用租约或重试次数。自动发布任务交给 Qwen Worker；本地 Worker 仅执行明确指向云端模型的补建任务。自动目标服务不可用时任务等待/失败，不自动改调另一模型。

上线前备份并暂停两边 API/Worker 写入，等待在途任务结束。执行新增迁移至 V118，统一更新两边代码后再启动。保留当前可用 pgvector 版本，不降级；先检查 halfvec、HNSW、迭代扫描能力。迁移会将已标记模型的旧向量转换成 FP16，不调用云端 API；旧列保留回溯，转换存在量化误差。未标记模型或不支持的旧向量须先清点，不能直接猜测来源。

旧待执行的知识库向量/发布任务必须补齐 expectedEmbeddingModel/expectedEmbeddingDimension：已有明确目标的重建任务保留原目标，自动发布/授权任务指定 Qwen/2560。不确定来源的任务暂不领取。不要让旧代码 Worker 与新代码同时运行。

服务器补建（先 --dry-run，核对后移除）：

```bash
python3 scripts/local/import_knowledge_directory.py --rebuild-vectors --embedding-model Qwen/Qwen3-Embedding-4B --embedding-dimension 2560 --manifest /srv/jsd-aird/import-state/qwen4b.jsonl --dry-run
```

本地按需补建时将脚本 API 地址指向本地 API，目标用 text-embedding-v4/1024，并使用独立 manifest。成功完整的目标模型向量可直接复用；另一套模型的向量不影响完成判定。撤销 AI 授权清除所有模型向量；重新切分内容会使旧分块向量失效。

批量导入和向量重建均可用 `--max-in-flight 4` 同时跟踪四份资料，支持 1–16，默认 8；导入还受组织队列容量限制。分类解析、文件上传和去重保持顺序，解析/发布等待并行回收，避免慢文件阻塞其他文件。重建继续复用原 manifest、已提交任务和完整向量，不要通过多个脚本实例或多个登录来增加并发。HTTP 401 会停止脚本，修复登录后原命令续跑。

Worker 新建、重建文档向量使用批量输入；`JSD_AIRD_EMBEDDING_BUILD_BATCH_SIZE` 默认 16，可设 1–64，并受每组 32000 字符和适配器请求条数上限约束。显存紧张时先设 4 或 8；不支持数组输入的服务设 1。变更后需要更新 Java JAR 并重启执行任务的 Worker，不能仅更新 Python。实际同时执行的任务数仍受 Worker 资源门控限制，脚本参数只是允许在途的文件数。

元数据补全由后端分发并受外部服务配额限制，不并发重复提交整批。只读诊断脚本 `scripts/local/diagnose_knowledge_metadata.py --task-id <任务UUID> --concurrency 4` 可并发读取明细，范围 1–16；不要和同账号的导入、重建脚本同时运行。

长书籍仍保持一个文件、版本和知识库记录。后端在结构化内容超过原单段 20,000 节点或 5 MiB 限制时自动按段落、列表项和表格行分段，保留原文来源、排除标记和顺序；前端按内容段显示和编辑，检索覆盖全部段。旧解析结果读取时同样分段，正常内容无需重新上传或 OCR。单个异常解析块、超过总容量的结果仍会明确报错，不能静默截断。此功能需要同时更新 Java API、Worker 和前端，没有新增数据库迁移。

超过 160 个 Unicode 字符的异常超长分词不写入关键词表，正文和向量文本不截断。知识库解析、发布索引、向量构建及重建任务有独立时限 `JSD_AIRD_WORKER_KNOWLEDGE_JOB_TIMEOUT=2h`，其他任务不受影响。两小时是执行上限，不是性能保证；仍需检查模型吞吐、硬件和解析结果。发布失败后用原导入命令、原 manifest 加 `--retry-failed` 续跑；只有解析本身失败时才会重新提交解析。

验收需要在隔离数据库检查两种向量并存、索引 EXPLAIN、权限和撤权、双 Worker 路由，并与精确余弦检索比较 Recall@10 与延迟。HNSW 是近似召回，不保证与精确排序一致。回滚前停止新 Worker 并恢复原云端配置，不能让旧服务以 Qwen 覆盖旧向量列。

停机迁移后，可执行 `psql -v auto_model='Qwen/Qwen3-Embedding-4B' -v auto_dimension=2560 -f scripts/local/migrate_embedding_jobs.sql` 为旧自动任务补目标；脚本保留已有目标，不修改已执行任务，列出仍不确定的重建任务及迁移遗漏向量。先核对参数与备份，再运行；这里的 SQL 不由应用启动自动执行。向量重建状态 GET 请求需带 `vectorsOnly=true`，与 POST 使用相同模式；幂等键按发布、模型、维度和模式区分。

### 本地隔离验证记录

本轮使用401条合成2560维向量、10条查询，在相同组织、授权、发布过滤条件下对比 HNSW 与关闭索引扫描的精确查询：Recall@10=1.0；10次查询合计 HNSW 4522ms、精确查询4571ms。该小样本主要验证类型、过滤、召回和调用链，不构成线上性能提升结论。索引可用性检查通过 EXPLAIN (ANALYZE, BUFFERS) 并关闭顺序扫描确认2560索引可选；真实数据量下的自然执行计划、冷热缓存延迟和业务问题召回仍需部署验收。
