"""File tool catalog; generation and registration do not require literature research."""

from aird_agent.capabilities.schema import EVIDENCE_ID, INTEGER, QUOTE_ID, STRING, tool

FILE_TOOLS = [
    tool("workspace_list", "查看此次工作区中的材料、脚本和输出文件。"),
    tool("workspace_read", "分段读取工作区文件。", {"path": STRING, "offset": INTEGER}, ["path"]),
    tool(
        "workspace_write",
        "写入 scratch 或 output；input 为只读。",
        {"path": STRING, "content": STRING},
        ["path", "content"],
    ),
    tool(
        "sandbox_prepare",
        "校验显式输入文件并返回大小、哈希、容器路径。只选择本次执行需要的文件，不传整个工作区。",
        {"input_paths": {"type": "array", "items": STRING, "uniqueItems": True}},
        ["input_paths"],
    ),
    tool(
        "sandbox_execute",
        "在无网络容器执行，必须显式input_paths，可为空；工作目录/work，使用input/、scratch/、output/相对路径，禁止/workspace/output。输出到output/后artifact_save登记。",
        {
            "input_paths": {"type": "array", "items": STRING, "uniqueItems": True},
            "script": {"type": "string", "maxLength": 65536},
            "language": {"type": "string", "enum": ["python", "shell"]},
        },
        ["script", "language", "input_paths"],
    ),
    tool(
        "artifact_save",
        "登记output/中的可下载文件，冻结实际来源及匹配的执行记录。source_quotes仅在使用原文时提供真实引用对；格式和来源检查失败时修复后重新生成，不修改执行产物绕过哈希。",
        {
            "path": STRING,
            "source_quotes": {
                "type": "array",
                "maxItems": 50,
                "items": {
                    "type": "object",
                    "properties": {"evidence_id": EVIDENCE_ID, "quote_id": QUOTE_ID},
                    "required": ["evidence_id", "quote_id"],
                    "additionalProperties": False,
                },
            },
        },
        ["path"],
    ),
]

FILE_INSTRUCTIONS = """按用户要求和用途生成文件，不固定格式或强制附加报告。简单文本可workspace_write，代码执行、计算和复杂格式生成只能通过隔离沙箱。
已注册工具不在激活列表时先tool_search发现，不据此声称不可用。沙箱可用性以平台标记和工具响应为准。
计算只传明确选中的input_paths；工作目录/work，input只读，scratch/output可写。成果输出到output/并通过artifact_save登记后才算可下载。
来源与执行记录按实际使用的输入登记；直接写出的文件不能冒充沙箱计算结果。执行产物不能在执行后修改，需修复脚本重新执行。
用户要求的文件在plan_update.file_names逐个列出，不省略未交付文件，不额外强制其他格式；普通文件无需研究主题或文献报告核查。
普通文件任务的plan_update使用task_type=GENERAL，省略presentation；不为文件生成填写研究资料范围或虚构研究步骤。
finish_task的checks.evidence_ids仅填写本任务实际使用的原文证据句柄；纯用户数据文件用空数组，文件名、文件ID和执行ID不能当作证据ID。
结果未知时先检查执行结果，不重复执行或登记。文件格式可解析不代表专业内容已验证。
生成Word、Excel或PDF前用skill_read(name="files")读取格式指南。沙箱提供python-docx、openpyxl、ReportLab、中文字体、LibreOffice及aird_files检查/转换帮助函数；实际可用性以执行结果为准。不得把写入公式当成已计算结果，不得只改扩展名冒充文件格式。"""
