"""File tool catalog; generation and registration do not require literature research."""

from aird_agent.capabilities.schema import EVIDENCE_ID, QUOTE_ID, STRING, tool

FILE_TOOLS = [
    tool(
        "artifact_save",
        "登记原生工作目录中的文件，冻结实际来源及匹配的执行记录；通常由原生 present 自动登记。source_quotes仅在使用原文时提供真实引用对；格式和来源检查失败时修复后重新生成，不修改执行产物绕过哈希。",
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

FILE_INSTRUCTIONS = """文件、附件、脚本与交付使用 dsh 原生工具和 Office 技能，命令在已有无网络 Linux 沙箱中运行。
原生 present 自动登记成果；需要指定原文引用对时先 artifact_save(path,source_quotes)。原生编辑后的哈希变化由 present 自动冻结当前工作区并登记，不需要反复手工执行命令。
历史文件的引用句柄不自动属于本轮。重新读取并替换成确实支持该事实的本轮引用；更正说明中也不要保留失效句柄，改用普通文字描述。引用错误会返回文件位置，修正指定位置后再 present。
未知执行或登记结果先对账，不盲目重放；文件格式检查不代替专业内容验证。"""
