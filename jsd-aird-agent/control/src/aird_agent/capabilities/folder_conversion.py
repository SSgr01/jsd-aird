"""Separate additive pack; existing file capability pins remain unchanged."""
from aird_agent.capabilities.schema import STRING, tool

FOLDER_TOOL = tool("folder_conversion", "服务器目录历史实验批量转换，仅保存文件夹，不入库。list列出获准别名及本人已有批次；start按alias增量扫描或接续活跃批次；status分页查看；pause停止派发；continue续跑，已结束则增量扫描；source读取当前文件并准备转换skill；export保存当前文件的标准Excel、映射及校验报告；fail记录阻塞。每份独立理解，不复用其他文件映射。", {
        "action": {"type":"string", "enum":["list","start","status","pause","continue","source","export","fail"]},
        "alias": STRING, "batch_id": STRING, "reason": {"type":"string","maxLength":2000},
        "offset": {"type":"integer","minimum":0}, "output_path": STRING, "plan_path": STRING, "report_path": STRING,
    }, ["action"])

FOLDER_INSTRUCTIONS = "服务器文件夹批量转换通过 folder_conversion 启动。每份独立判读，只将结果保存到文件夹，不维护人工核对状态。此模式交付使用 folder_conversion export，不调用 present、artifact_save 或实验导入。目录别名由部署管理员授权。当前或新会话都可先list找回本人获准目录的已有批次，无需用户记住批次ID。只有一个目录或目标明确时直接处理，多个目录无法判断时才询问。start复用扫描中或运行中批次；暂停批次用continue恢复。遇到FOLDER_ENGINE_CHANGED先pause旧批次，再start新批次，不绕过资源指纹检查；continue可不填batch_id，目录无歧义时自动选择，已结束批次启动增量扫描。selection_required表示需选择目录，不要猜。系统仅在原件、模板、转换资源与结果文件校验一致时沿用已有结果（SKIPPED），不代表人工核对通过；新增、变化、失败和结果丢失的文件重新排队。在当前对话直接汇报进度和结果路径，沿用原生聊天与工具展示，不增加页面或详情区。"
