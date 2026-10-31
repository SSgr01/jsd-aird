"""Trusted platform capability catalog. Tool discovery does not grant permission."""


def schema(properties=None, required=None):
    return {
        "type": "object",
        "properties": properties or {},
        "required": required or [],
        "additionalProperties": False,
    }


STRING = {"type": "string", "maxLength": 12000}
INTEGER = {"type": "integer", "minimum": 0}
EVIDENCE_ID = {"type": "string", "pattern": "^e[0-9a-f]{24}$"}
QUOTE_ID = {"type": "string", "pattern": "^q[0-9a-f]{24}$"}
QUOTE_REF = schema({"evidence_id": EVIDENCE_ID, "quote_id": QUOTE_ID}, ["evidence_id", "quote_id"])


def tool(name, description, properties=None, required=None):
    return {"name": name, "description": description, "parameters": schema(properties, required)}


TOOLS = [
    tool("tool_search", "按关键词发现可用工具；返回工具说明并启用相关能力。", {"query": STRING}, ["query"]),
    tool("skill_read", "读取当前获准能力包的技能说明。", {"name": STRING}),
    tool(
        "knowledge_search",
        "混合检索：AUTOMATIC 任务搜索授权知识库，SELECTED 任务限指定文献。用中英文/同义词补查；next_offset 翻片段、next_cursor 翻知识库窗口。",
        {"query": STRING, "offset": INTEGER, "cursor": {"type": "string", "maxLength": 16}},
        ["query"],
    ),
    tool(
        "document_read",
        "按文档读取目录、原文节点和相邻上下文；全文总结必须分页覆盖。",
        {
            "document_id": STRING,
            "publication_id": STRING,
            "offset": INTEGER,
            "limit": {"type": "integer", "minimum": 1, "maximum": 20},
        },
        ["document_id", "publication_id"],
    ),
    tool(
        "table_read",
        "分页读取发布版本的原始表格。",
        {"document_id": STRING, "publication_id": STRING, "table_id": STRING, "offset": INTEGER},
        ["document_id", "publication_id", "table_id"],
    ),
    tool(
        "evidence_read",
        "回读固定原文跨度及quote_id；用返回的evidence_id+quote_id保存卡和证据矩阵，无须抄写数字公式。按next_quote_offset翻页，句柄不代表事实已核对。",
        {"evidence_id": EVIDENCE_ID, "quote_offset": INTEGER},
        ["evidence_id"],
    ),
    tool(
        "evidence_score",
        "RCS：对最多12条已召回证据进行问题相关评分和摘要；不会再次向量化。",
        {
            "question": STRING,
            "evidence_ids": {"type": "array", "items": STRING, "minItems": 1, "maxItems": 12},
        },
        ["question", "evidence_ids"],
    ),
    tool("workspace_list", "查看此次工作区中的材料、脚本和输出文件。"),
    tool("workspace_read", "分段读取工作区文件。", {"path": STRING, "offset": INTEGER}, ["path"]),
    tool(
        "workspace_write",
        "写入 scratch 或 output；input 为只读。",
        {"path": STRING, "content": STRING},
        ["path", "content"],
    ),
    tool(
        "sandbox_execute",
        "在无网络的隔离容器中执行 Python 或 Shell，返回结果和文件位置。",
        {
            "script": {"type": "string", "maxLength": 65536},
            "language": {"type": "string", "enum": ["python", "shell"]},
        },
        ["script", "language"],
    ),
    tool("note_save", "保存研究进度、已读范围、决定和未解决问题。", {"text": STRING}, ["text"]),
    tool(
        "evidence_save",
        "保存单篇固定版本实例卡。优先每字段提供evidence_id、name、quote_id，平台补齐真实原文和来源；亦兼容准确excerpt。不自行抄写公式或标记人工核对。",
        {
            "instance": STRING,
            "target": STRING,
            "fields": {
                "type": "array",
                "minItems": 1,
                "maxItems": 100,
                "items": schema(
                    {
                        "evidence_id": EVIDENCE_ID,
                        "quote_id": QUOTE_ID,
                        "name": {"type": "string", "minLength": 1, "maxLength": 100},
                        "original": {"type": "string", "minLength": 1, "maxLength": 6000},
                        "excerpt": {
                            "type": "string",
                            "minLength": 1,
                            "maxLength": 6000,
                            "description": "从evidence_read返回的text复制连续原文；不翻译、不改写公式或符号。空白差异由平台匹配到真实原文。",
                        },
                        "value": {
                            "type": ["string", "null"],
                            "maxLength": 6000,
                            "description": "标准化数值，数量级放在此值中。",
                        },
                        "unit": {
                            "type": ["string", "null"],
                            "maxLength": 100,
                            "description": "物理单位，不重复value已有的数量级，例如m²/s而非10⁻⁹m²/s。",
                        },
                        "qualifiers": {"type": "object", "additionalProperties": {"type": "string"}},
                    },
                    ["evidence_id", "name"],
                ),
            },
        },
        ["instance", "target", "fields"],
    ),
    tool(
        "matrix_record",
        "按单个研究子问题保存/更新证据矩阵。先evidence_read获取quote_id，claims关联原文句柄；没有证据时写gap_note仅表示本次缺口，不代表全文未报告。question_id的目标与文献范围固定。",
        {
            "question_id": {"type": "string", "pattern": "^[a-zA-Z0-9_-]{1,64}$"},
            "question": {"type": "string", "minLength": 1, "maxLength": 1000},
            "documents": {
                "type": "array",
                "minItems": 1,
                "maxItems": 10,
                "uniqueItems": True,
                "items": schema(
                    {"document_id": STRING, "publication_id": STRING}, ["document_id", "publication_id"]
                ),
            },
            "claims": {
                "type": "array",
                "maxItems": 16,
                "items": schema(
                    {
                        "claim": {"type": "string", "minLength": 1, "maxLength": 1000},
                        "quotes": {"type": "array", "minItems": 1, "maxItems": 8, "items": QUOTE_REF},
                        "qualifiers": {"type": "object", "additionalProperties": {"type": "string"}},
                    },
                    ["claim", "quotes"],
                ),
            },
            "gap_note": {"type": "string", "maxLength": 1000},
        },
        ["question_id", "question", "documents", "claims"],
    ),
    tool(
        "matrix_read", "读取本次持久化的子问题证据矩阵；question_id可只读一个问题。", {"question_id": STRING}
    ),
    tool(
        "report_check",
        "保存前独立原文事实核查：检查逐段/表格行的全部事实和子问题覆盖。只用矩阵采用的原文，不用RCS分数充当正确性。未通过时按问题回读修订，再检查。",
        {"title": STRING, "text": STRING},
        ["title", "text"],
    ),
    tool(
        "report_save",
        "保存与report_check通过的title/text完全相同的报告，并冻结矩阵与AI核查记录。修改报告或矩阵后须重新核查，仍不代表人工已核对。",
        {"title": STRING, "text": STRING},
        ["title", "text"],
    ),
    tool(
        "artifact_save",
        "把 output 中的分析文件登记到系统文件服务，登记后才能作为长期成果下载。",
        {"path": STRING},
        ["path"],
    ),
    tool(
        "external_search",
        "向外部发送已确认的检索词发现文献；未经确认将等待审批。",
        {"query": STRING},
        ["query"],
    ),
    tool(
        "bibliography_lookup",
        "通过 Crossref 查询书目信息或补全 DOI；只返回元数据，需要外部检索确认。",
        {"query": STRING},
        ["query"],
    ),
]

TOOLS.extend(
    [
        tool(
            "plan_update",
            "记录/调整用户可读的计划。研究任务用presentation=research_plan_v1、scope说明资料范围；步骤title写研究问题，description写查看和比较方法，不写工具调用清单。",
            {
                "goal": {"type": "string", "minLength": 1, "maxLength": 500},
                "summary": {"type": "string", "minLength": 1, "maxLength": 400},
                "task_type": {"type": "string", "enum": ["RESEARCH", "GENERAL"]},
                "presentation": {"type": "string", "enum": ["research_plan_v1"]},
                "scope": {"type": "string", "minLength": 1, "maxLength": 500},
                "fulltext_coverage_required": {"type": "boolean"},
                "fulltext_documents": {
                    "type": "array",
                    "maxItems": 100,
                    "uniqueItems": True,
                    "items": schema(
                        {
                            "document_id": {"type": "string", "maxLength": 100},
                            "publication_id": {"type": "string", "maxLength": 100},
                        },
                        ["document_id", "publication_id"],
                    ),
                },
                "deliverables": {
                    "type": "array",
                    "minItems": 1,
                    "maxItems": 4,
                    "uniqueItems": True,
                    "items": {"type": "string", "enum": ["answer", "report", "comparison", "analysis_file"]},
                },
                "steps": {
                    "type": "array",
                    "minItems": 1,
                    "maxItems": 12,
                    "items": schema(
                        {
                            "id": {"type": "string", "pattern": "^[a-zA-Z0-9_-]{1,40}$"},
                            "title": {"type": "string", "minLength": 1, "maxLength": 160},
                            "description": {"type": "string", "minLength": 1, "maxLength": 400},
                            "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]},
                        },
                        ["id", "title", "status"],
                    ),
                },
            },
            ["goal", "summary", "task_type", "deliverables", "steps"],
        ),
        tool(
            "finish_task",
            "交付前自检需求覆盖、来源和缺口。完整交付需计划完成且所需成果已登记；自检不等于科研复现/人工核验。",
            {
                "task_type": {"type": "string", "enum": ["RESEARCH", "GENERAL"]},
                "outcome": {"type": "string", "enum": ["COMPLETE", "PARTIAL", "NEEDS_INPUT"]},
                "summary": {"type": "string", "minLength": 1, "maxLength": 1000},
                "checks": {
                    "type": "array",
                    "maxItems": 20,
                    "items": schema(
                        {
                            "requirement": {"type": "string", "minLength": 1, "maxLength": 300},
                            "status": {"type": "string", "enum": ["covered", "missing", "not_applicable"]},
                            "evidence_ids": {
                                "type": "array",
                                "maxItems": 40,
                                "items": {"type": "string", "maxLength": 100},
                            },
                        },
                        ["requirement", "status"],
                    ),
                },
                "gaps": {"type": "array", "maxItems": 20, "items": {"type": "string", "maxLength": 500}},
            },
            ["outcome", "summary", "checks", "gaps"],
        ),
    ]
)

INITIAL_TOOLS = [
    "tool_search",
    "skill_read",
    "knowledge_search",
    "document_read",
    "plan_update",
    "finish_task",
]

SKILL = """# 材料文献研究
研究先建立子问题证据矩阵：每个问题检索、evidence_read取得quote_id、matrix_record保存原文证据/条件/本次缺口。压缩后matrix_read恢复，不靠摘要记忆拼接数值。question_ids由用户指定时必须全部覆盖；question_targets指定时question必须与该目标完全一致，不得偷换。
证据卡优先使用name/evidence_id/quote_id绑定原文，避免逐字符抄写。报告的每个科学段落和比较表行引用矩阵中采用的证据，保存前必须report_check，并修复CONTRADICTED/INSUFFICIENT/MISSING。核查通过后report_save冻结矩阵和AI核查记录，修改内容后须重新检查。
不要要求用户先建主题、选文献或逐步指导。AUTOMATIC 任务主动搜索授权知识库；SELECTED 任务严格限于指定资料。
用 plan_update 展示并调整计划。查不到时换同义词/中英文/更短查询、翻页及回读，不把首次无结果当成没有文献。
先明确目标和比较指标，查看主题中的文献集合及可用全文。
发现资料时区分元数据、摘要、已取得全文。全文总结先读目录和各章节；记录覆盖范围。
针对研究子问题检索，RCS评分后回读拟采用的原文和表格。每次评分只围绕一个明确子问题，不把两篇论文的全部主题拼成一个笼统评分问题。保留条件和反例。
比较单元为文献版本+具体实施例或步骤。目标、规模、测试方法和指标口径不一致时不得直接排名。
每个字段关联原文短摘录与来源；缺失信息保持缺失。AI 抽取默认待核对。
EXTRACTION_VERIFIED 只表示抽取与原文一致，不等于结论被独立复现。
同一实验的重复发表及同一专利族应归组，不能增加独立验证计数。
按子问题组织报告，引用 [E:证据ID]，列出未读范围、矛盾、待核对内容和信息缺口。
定期 note_save 保存研究进展、具体数值口径与对应真实证据ID，避免全文阅读后只剩概括。报告用 report_save、证据卡用 evidence_save、分析文件用 artifact_save 登记后才算交付；在对话中声称已保存不能替代实际调用。
交付前用 finish_task 核对用户要求；缺全文、预算不足或条件不一致时交付已有成果并明确 PARTIAL/NEEDS_INPUT，不伪称完成。"""
