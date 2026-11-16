"""Separate additive pack; existing file capability pins remain unchanged."""
from aird_agent.capabilities.schema import STRING, tool

FOLDER_TOOL = tool("folder_conversion", "服务器目录历史实验批量转换，仅保存文件夹，不入库。list列出获准别名；start按alias启动；status分页查看；pause停止派发；continue续跑；source读取当前文件并准备转换skill；export保存当前文件的标准Excel、映射及校验报告；fail记录阻塞。每份独立理解，不复用其他文件映射。", {
        "action": {"type":"string", "enum":["list","start","status","pause","continue","source","export","fail"]},
        "alias": STRING, "batch_id": STRING, "reason": {"type":"string","maxLength":2000},
        "offset": {"type":"integer","minimum":0}, "output_path": STRING, "plan_path": STRING, "report_path": STRING,
    }, ["action"])

FOLDER_INSTRUCTIONS = "服务器文件夹批量转换通过 folder_conversion 启动。每份独立判读，只将结果保存到文件夹，不维护人工核对状态。此模式交付使用 folder_conversion export，不调用 present、artifact_save 或实验导入。目录别名由部署管理员授权。"
