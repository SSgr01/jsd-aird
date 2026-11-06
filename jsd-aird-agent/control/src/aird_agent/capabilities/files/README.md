# 通用文件能力

`files` 与文献、项目等能力并列。`core + files` 通过 dsh 原生 read/write/edit/apply_patch/bash/present 创建、读取、隔离执行和交付文件，不要求文献能力、研究主题、证据矩阵或报告。

- `definitions.py`：业务来源登记工具及平台说明；文件操作和格式、文件数量遵循原生工具与当前任务。
- `handler.py`：执行来源检查和成果登记；宿主存储与来源策略通过接口注入。原生工具的沙箱适配位于 `hosts/jsd/native_files.py`，不另外提供文件 CRUD 或脚本工具。
- `quality.py`：CSV/TSV/JSON 结构及 Office/PDF 基本格式检查，不要求文献引用，也不修改原始字节。
- Office 指导通过固定上游的原生 `skill-office` 提供，企业扩展只替换 Linux 沙箱中的运行路径。
- `completion.py`：检查任务承诺的文件是否已登记。

JSD 宿主通过 `evidence.exports` 从显式引用及实际选中执行输入解析来源，并调用文献能力的 `literature.export` 检查文献数据引用。任务曾经检索过的其他资料不会自动附到文件上。专业校验不能被用作通用格式限制。

沙箱输出按执行记录和内容哈希登记。修改输出或选中输入后必须重新执行；结果未知时先对账。直接创建的普通文件没有执行回执，不可当作已经计算验证。

Word、Excel、PDF 在沙箱生成，并由 Runner 在导出前重新解析；不能通过直接写入假文件、改扩展名或伪造 PDF 文件头绕过检查。LibreOffice 提供转 PDF 与 Excel 重算，实际版式及公式兼容性仍需检查。

Java 的 `AgentArtifactService` 和 `AgentArtifactStore` 使用原有成果记录及文件存储。研究主题是可选关联；旧记录的主题、来源权限和下载地址继续生效。存储允许的格式保持原样；有格式校验不代表存在对应生成器，文件可下载不代表内容已通过专业验证。
