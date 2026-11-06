"""Trusted platform capability catalog. Tool discovery does not grant permission."""


from aird_agent.capabilities.files.definitions import FILE_TOOLS
from aird_agent.capabilities.schema import EVIDENCE_ID, INTEGER, QUOTE_ID, QUOTE_REF, STRING, schema, tool

TOOLS = [
    tool("tool_search", "按关键词发现可用工具；返回工具说明并启用相关能力。", {"query": STRING}, ["query"]),
    tool(
        "knowledge_search",
        "默认检索当前权限知识库；用户明确只看某些文献时用documents传固定发布版本，仅本次查询生效。服务端本轮限制不可扩大。最多8个有效词、256字符；用match_mode=any/all/phrase，不支持OR/AND语法。next_offset翻排序结果，next_cursor翻库窗口，node_offset用于阅读。",
        {
            "query": {"type": "string", "maxLength": 256},
            "match_mode": {"type": "string", "enum": ["auto", "phrase", "all", "any"]},
            "offset": INTEGER,
            "cursor": {"type": "string", "maxLength": 16},
            "documents": {"type": "array", "minItems": 1, "maxItems": 100, "items": {
                "type": "object", "additionalProperties": False,
                "properties": {"document_id": STRING, "publication_id": STRING},
                "required": ["document_id", "publication_id"],
            }},
        },
        ["query"],
    ),
    tool(
        "document_outline",
        "读取知识库固定发布版本的页码及候选章节目录，返回节点范围；先定位结果/方法再document_read。不计全文覆盖。",
        {"document_id": STRING, "publication_id": STRING, "offset": INTEGER},
        ["document_id", "publication_id"],
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
        "evidence_context",
        "读取同一固定版本的上下文。relation默认neighbors；parent读取真实父块，cursor按Unicode码点续读。均不计全文覆盖，用evidence_read获取精确原文。",
        {
            "evidence_id": EVIDENCE_ID,
            "relation": {"type": "string", "enum": ["neighbors", "parent"]},
            "before": {"type": "integer", "minimum": 0, "maximum": 2},
            "after": {"type": "integer", "minimum": 0, "maximum": 2},
            "cursor": INTEGER,
        },
        ["evidence_id"],
    ),
    tool(
        "evidence_read",
        "回读固定原文跨度及quote_id；用返回的evidence_id+quote_id保存卡和证据矩阵，无须抄写数字公式。table_context附同一原表的真实表头，按列位置核对对象、指标和单位，不混用同一行左右样品。按next_quote_offset翻页。仍缺表头、其他行或试验条件时用evidence_context，重复读取同一跨度不会补出上下文；new_quote_count=0时复用已有引文，不连续重读。句柄不代表事实已核对。",
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
        "按单个研究子问题保存/更新证据矩阵。同一question_id会替换该问题全部claims，增补时保留仍适用的已有claim及quotes。先evidence_read获取quote_id，claims关联原文句柄。claim是待核对解释，不是原文事实；组织时连同对象、统计口径和测试条件核对，不把候选措辞代替原文。没有证据时写gap_note仅表示本次缺口，不代表全文未报告。question_id的目标与文献范围固定。",
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
        "保存前独立原文事实核查：先matrix_record采用原文；每个事实段落及列表项就地写[E:evidence_id]，上一段引用不自动覆盖下一段。quote_id只用于矩阵。检查全部事实和子问题覆盖，不用RCS分数充当正确性。单份最多80个核查单元、每单元3000字符；超限时减少非必要断言，拆段不能减少单元数。未通过时按具体原句补引用或删改，不重查已有原文、重建矩阵或反复提交同稿。",
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
        "bibliography_lookup",
        "通过OpenAlex核对题名、DOI或论文ID；无匹配时补查Crossref。只返回书目信息，不代表已读原文；需要外部检索确认。",
        {"query": STRING},
        ["query"],
    ),
    tool(
        "literature_search",
        "通过OpenAlex发现公开论文。query只含获准外发的检索词，不发送私有正文；支持关键词或语义、年份及开放获取筛选。返回元数据，不能冒充原文证据；按next_cursor分页。需要外部检索确认。",
        {
            "query": {"type": "string", "minLength": 1, "maxLength": 2000},
            "mode": {"type": "string", "enum": ["keyword", "semantic"]},
            "from_year": {"type": "integer", "minimum": 1600, "maximum": 2200},
            "to_year": {"type": "integer", "minimum": 1600, "maximum": 2200},
            "open_access": {"type": "boolean"},
            "type": {"type": "string", "enum": ["article", "review", "preprint", "book-chapter", "dissertation", "report"]},
            "limit": {"type": "integer", "minimum": 1, "maximum": 20},
            "cursor": {"type": "string", "maxLength": 2048},
        },
        ["query"],
    ),
    tool(
        "literature_expand",
        "按OpenAlex论文ID追踪references（它引用的论文）或citations（引用它的论文），按next_cursor分页。引用次数和引用关系不是科学结论证据；需要外部检索确认。",
        {
            "work_id": {"type": "string", "pattern": "^W[1-9][0-9]{0,20}$"},
            "direction": {"type": "string", "enum": ["references", "citations"]},
            "limit": {"type": "integer", "minimum": 1, "maximum": 20},
            "cursor": {"type": "string", "maxLength": 2048},
        },
        ["work_id"],
    ),
    tool(
        "literature_acquire",
        "获取指定OpenAlex论文的许可明确开放全文并解析到当前会话；不会发布知识库。需联网确认。返回ACQUIRING/PARSING时用literature_acquire_status检查，不重复获取；READY后通过私有文献工具读取并采用真实原文。",
        {"work_id": {"type": "string", "pattern": "^W[1-9][0-9]{0,20}$"}},
        ["work_id"],
    ),
    tool(
        "literature_acquire_status",
        "检查已提交论文的获取和解析结果，不发起下载或重试。READY后自动加入本次获准资料，可通过private_document_outline/search/read读取。UNKNOWN时保留结果未知，不重新提交。",
        {"work_id": {"type": "string", "pattern": "^W[1-9][0-9]{0,20}$"}},
        ["work_id"],
    ),
]


TOOLS.extend(FILE_TOOLS)

INITIAL_TOOLS = [
    "tool_search",
    "knowledge_search",
    "document_read",
]
