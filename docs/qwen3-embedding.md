# Qwen3 Embedding 部署与查询指令评测

云端 API 与自部署服务使用同一个 OpenAI-compatible `/embeddings` 适配器。通过环境变量指定地址、服务实际暴露的模型名和维度；需要鉴权时填写 Key，无鉴权服务显式留空。API 与 Worker 都要使用同一组模型、维度配置。

```dotenv
JSD_AIRD_EMBEDDING_BASE_URL=http://127.0.0.1:8000/v1
JSD_AIRD_EMBEDDING_API_KEY=
JSD_AIRD_EMBEDDING_MODEL=Qwen/Qwen3-Embedding-0.6B
JSD_AIRD_EMBEDDING_DIMENSION=1024
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
python3 scripts/local/import_knowledge_directory.py --rebuild-vectors --embedding-model Qwen/Qwen3-Embedding-0.6B --embedding-dimension 1024 --dry-run
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
python3 scripts/local/evaluate_embedding_instructions.py --cases .runtime/qwen-eval/cases.json --output .runtime/qwen-eval/report.json --base-url http://127.0.0.1:8000/v1 --model Qwen/Qwen3-Embedding-0.6B --dimension 1024 --top-k 5,10,20
```

云端服务通过 `JSD_AIRD_EMBEDDING_API_KEY` 提供 Key。每个报告使用新文件名，脚本拒绝覆盖已有报告。语料、问题、结果应保存在忽略的 `.runtime` 目录。三组评测期间需固定模型权重、服务配置、问题、语料和标注；另保留未参与指令调优的验收题。

此报告衡量纯向量检索。选择候选后，还应使用现有 `evaluate_knowledge_quality.py` 验证完整检索流程，并人工检查最终证据能否支持回答；纯向量 Recall 或 nDCG 的提升不等于最终回答准确率提升。操作手册需要独立评测，不能用材料题的结果代替。

Qwen 官方提到合适指令在多数下游任务中通常有约 1%～5% 的收益，但这不是某个指标的百分点保证。参考 [官方说明](https://github.com/QwenLM/Qwen3-Embedding/blob/main/README.md) 与 [查询/文档用法](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B#usage)。
