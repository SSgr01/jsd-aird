# MinerU 接入、批量解析与历史资料修复

## 环境与数据流

本地 `local` profile 使用云端 MinerU API、Token 和原有模型配置。服务器的 API 与 Worker 都启用 `prod`，Worker 另外启用 `worker`。生产默认自部署 Tasks API、Advanced + OCR；不配置云端 Token 也能解析 PDF、图片和 PPT，失败不会自动发送到云端。PPT 使用原生解析参数。

网页和目录导入均使用系统后端：原文件进入 MinIO，Worker 解析并归档 ZIP、JSON、Markdown 和图片，结构化正文经过校对、审核和发布后进入分块及 Qwen3 Embedding。Java 后端不调用 `parse_file.py`。已完成的历史文件不会因默认配置变化而自动重新 OCR。

## 真正的多文件批量请求

目录脚本并行上传、创建系统文档和跟踪处理，每份文件仍是独立系统文档；Worker 将同一组织、相同档位、OCR 模式及原生/视觉解析方式的文件汇集到一批，通过 **一个** `/v1/tasks` 请求的 `files` 数组提交多份文件。云端对应一个 `/api/v4/file-urls/batch` 请求。每份文件保留独立版本、远程文件标识、上传状态、解析结果和错误。结果通过文件标识匹配，支持同名文件及响应顺序变化。

默认每批最多 8 份、两批同时在途，每批源文件合计最多 1 GiB。未满批次在 500 ms 汇集窗口结束后提交，单份网页上传也能及时处理。已有远程任务优先恢复；只有明确失败或 404/410 失效的文件进入新批次。创建请求响应丢失时保存不明确状态，不盲目重复 POST。某个文件失败不会丢弃同批其他成功结果。

每次提交不会预先把所有文件拼成一个 PDF。大 PDF 的分片同样进入批量协调器，保留原页偏移及重叠页去重。

## 服务器容量配置

[生产环境变量示例](../deployment/mineru-server.env.example) 须同时提供给 API 和 Worker。不要从旧环境继续带入 `JSD_AIRD_MINERU_DEFAULT_OCR_MODE=AUTO` 或云端 URL。

| 层级 | 生产默认值 | 配置 |
| --- | --- | --- |
| 每个 MinerU 请求的文件数 | 8 | `JSD_AIRD_MINERU_BATCH_SIZE` |
| 同时在途的 MinerU 批次 | 2 | `JSD_AIRD_MINERU_BATCH_CONCURRENCY` |
| 每批源文件总字节数 | 1 GiB | `JSD_AIRD_MINERU_BATCH_MAX_BYTES` |
| Worker 每轮最多领取 | 16 | `JSD_AIRD_WORKER_CLAIMS_PER_POLL` |
| 同实例重任务执行槽 | 20 | `JSD_AIRD_CAPACITY_HEAVY` |
| 本地转换子进程 | 4 | `JSD_AIRD_WORKER_DOCUMENT_PROCESSES` |
| 每个转换子进程堆 / Direct / Metaspace | 2048 / 256 / 256 MiB | `JSD_AIRD_WORKER_DOCUMENT_PROCESS_HEAP_MIB` / `DIRECT_MIB` / `METASPACE_MIB`（后两项使用相同前缀） |
| 每个转换子进程 CPU / 超时 | 2 / 5 分钟 | `JSD_AIRD_WORKER_DOCUMENT_PROCESS_CPUS` / `JSD_AIRD_WORKER_DOCUMENT_PROCESS_TIMEOUT` |
| 数据库连接池 | 每实例 32 | `JSD_AIRD_DB_MAXIMUM_POOL_SIZE` |
| 持久化任务队列 | 全局 2000 / 每组织 1000 | `JSD_AIRD_CAPACITY_PENDING_JOBS` / `JSD_AIRD_CAPACITY_ORGANIZATION_PENDING_JOBS` |
| 受控临时空间 | 32 GiB | `JSD_AIRD_CAPACITY_TEMPORARY_BYTES` |
| 目录导入在途文件数 | 16，可显式调至 128 | 脚本 `--max-in-flight` |

Worker 领取任务后异步监督执行，不再等前一份解析完成才领取下一份；本地转换运行时也继续领取其他任务。执行槽、超时、租约心跳和取消检查仍然有效。重任务默认 20 个槽包括解析、索引等工作，两个 MinerU 批次最多占用 16 个解析等待槽，其余工作可继续推进。

Office 转换原先硬编码为 512 MiB 堆、1 个 CPU、90 秒超时，生产改为每进程 2 GiB 堆、2 个 CPU、5 分钟超时，Direct 和 Metaspace 各 256 MiB；四个进程的这些上限合计约 10 GiB，另有 JVM 原生开销。进程容量与主 JVM 堆分开配置。

环境模板同时为每个 Java 实例设置 `-Xms2g -Xmx16g`。应用的工作内存预估额度为最大堆的一半；如果启动命令仍写着较小的 `-Xmx`，提高任务数后仍可能触发资源不足。移除启动命令中旧的较小堆限制，确保实际生效。两个实例最大堆合计 32 GiB，另外留出原生转换、Ollama、PostgreSQL、MinIO 和系统页缓存空间。

继续调大批次时配套增加执行槽，建议至少满足 `heavy >= batch-size × batch-concurrency + 4`，并核对 JVM 堆和数据库连接预算。提高脚本在途数只增加准备和队列窗口，不会绕过 GPU 服务并发限制。

这些值是面向 Xeon Gold 6230、128 GB RAM、RTX A4000 16 GB 的上线起始配置，尚未在该服务器进行吞吐压测。API + Worker 两个连接池最多合计 64 个连接，需要计入 PostgreSQL 总连接预算。不要将 Worker 的 20 个槽理解为 GPU 同时运行 20 份 OCR。

MinerU 容器的 `--concurrency` 与后端批次数分开配置。原来是 1，可先调到 **4**，保留已有模型挂载、端口及环境变量后重新创建容器。例如当前已确认的启动参数为：

```bash
docker stop mineru-api
docker rm mineru-api
docker run -d --name mineru-api --restart=unless-stopped --gpus device=0 \
  --ipc=host --ulimit memlock=-1 --ulimit stack=67108864 \
  --log-opt max-size=20m --log-opt max-file=3 \
  -p 127.0.0.1:8010:8000 -v /opt/mineru:/work:Z \
  -e MINERU_MODEL_SOURCE=local local/mineru:4.2.3-cn \
  api-server --host 0.0.0.0 --port 8000 --tier advanced --concurrency 4
```

如后来增加过挂载或环境变量，使用实际部署参数。无需修改容器内部源码。用不同文件组成批次，同时运行 Embedding，观察 `nvidia-smi`、系统 RAM、CPU、磁盘、数据库连接、OOM 日志及每分钟处理页数，再调整 GPU 并发和批次大小；空闲显存或单份缓存命中时间不能代表并行吞吐。

## 目录导入

```bash
export JSD_AIRD_URL=http://127.0.0.1:8080
export JSD_AIRD_USERNAME=admin
# 密码通过已有安全环境配置提供 JSD_AIRD_PASSWORD
python3.12 scripts/local/import_knowledge_directory.py /srv/kb/import \
  --max-in-flight 16 --manifest /srv/kb/import-progress.jsonl
```

一级目录选择/创建分类，更深目录成为标签；仍复用去重关联、断点续跑、元数据补全、审核发布和向量等待逻辑。默认启用已有自动审核发布选项，使用 `--no-auto-approve` 改成人工审核。失败后使用相同 manifest，必要时加 `--retry-failed`。

脚本不持有 MinerU Token、不直接请求 MinerU，也不单独维护档位或 OCR 默认值。清单增加解析来源、实际档位、OCR 模式、远程任务 ID、远程文件 ID、实际批次文件数和完整性告警。`--max-in-flight 32` 可进一步增加准备和跟踪窗口；真正的 GPU 并发仍由 MinerU 服务控制。同一文档的元数据和审核写入使用文档锁串行化；分类解析也串行化，文件上传和不同文档的处理并行。实际批次取决于相同参数的就绪文件数与汇集窗口，并非每次请求都必然满 8 份。

## JSON 正文与归档

优先使用有 `pages` 结构的 `middle_json.json`，其次 `structured_content.json`，兼容旧 `content_list.json` / `content_list_v2.json`。每个 ZIP 只选择一套正文来源，不将 Markdown 再重复拼进正文。支持 `markdown.md` 和 `full.md` 归档。

递归遍历 `list.content`，保留参考文献及阅读顺序。新版 0–1 与旧版 0–1000 坐标分别适配；保存页面尺寸、页码和分片偏移。保留图注、表格、公式及表格内部图片的 MinIO 引用。图表推读数字保存为 `extractedChartData` / `derivedFromChart`，图注参与检索，推读数值默认不混进实验正文 embedding。

恢复误标参考文献时，需要处于参考文献章节、编号连续、包含年份及文献特征；记录 `originalMineruType` 和 `recoveryReason`。真正的页眉页脚保持不可检索，编号缺口形成告警。

测试夹具由四份真实 ZIP 缩减而来，保留原始 JSON 和 Markdown，去除 PDF 和图片二进制；验证正文依据结构化 JSON，而不是 Markdown 行数。

## 历史修复候选

```bash
# 分页清点和预览，不提交修复
python3.12 scripts/local/import_knowledge_directory.py --repair-existing --dry-run

# 可加 --category-id 分类UUID 或 --scope INTERNAL
python3.12 scripts/local/import_knowledge_directory.py --repair-existing \
  --max-in-flight 16 --manifest /srv/kb/parse-repair.jsonl

# 重跑同一清单；终态失败加 --retry-failed
python3.12 scripts/local/import_knowledge_directory.py --repair-existing \
  --retry-failed --manifest /srv/kb/parse-repair.jsonl
```

每次请求最多 100 个明确版本，使用提交前写入 manifest 的请求键去重。优先适配已有 ZIP；缺失、分片不完整或无法恢复完整内容时才重新解析原文件。生产 PDF/图片重解析明确要求 Advanced + OCR；复用旧 Standard ZIP 保留原档位。

后端新增接口：

- `POST /api/v1/knowledge/parse-repairs/preview`：预览明确的 `files:[{documentId,versionId}]`。
- `POST /api/v1/knowledge/parse-repairs`：同一 `files`、持久化 `requestKey`，可传 `retryFailed`。
- `GET /api/v1/knowledge/parse-repairs/{id}`：查询候选正文、元数据、告警及错误。
- `POST /api/v1/knowledge/parse-repairs/{id}/adopt`：明确采用候选为新的待校对版本。

修复作业创建独立解析记录及候选，不更新人工正文、发布版本、元数据抽取来源或线上向量。采用时再次核对文件版本、发布快照及人工修订锁，期间有修改就拒绝覆盖。采用后仍需走现有校对、审核、发布流程。历史修复脚本从不自动调用采用或发布接口。

`--rebuild-vectors` 仍只重建已发布资料的向量，默认并发 8，不重新 OCR，也不能补回缺失正文。

## 发布顺序与验证

先执行 V123 数据库迁移，再部署 API / Worker 和一致的生产配置，最后更新目录脚本；迁移为旧任务补上协议字段，并新增独立修复候选表。不要在长任务执行中途直接终止 Worker；停机后使用原检查点续跑。

先验证一份实际文件，再用至少 16 份不同资料验证多文件请求、部分失败和重启恢复，最后跑目录导入。验收同时检查内容完整性、审核发布、MinIO 下载、向量状态及服务器资源。单份文件成功和本地协议测试不能替代服务器并行压测。
