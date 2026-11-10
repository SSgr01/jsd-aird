"""Small business contracts on the native harness; authority remains with Java."""

from fastapi import HTTPException

from aird_agent.capabilities.definitions import STRING, tool
from aird_agent.core.capabilities import CapabilityPack
from aird_agent.core.contracts import digest

READ_TYPES = [
    "PROJECT", "STAGE", "TASK", "REQUIREMENT", "MATERIAL", "EXPERIMENT", "STRUCTURED_DATA",
    "DATA_IMPORT", "STRUCTURED_RECORD", "RESEARCH_TEST", "CUSTOMER", "CUSTOMER_CONTACT",
    "CUSTOMER_COMMUNICATION", "CUSTOMER_REQUIREMENT", "INVENTORY_BALANCE", "INVENTORY_TRANSACTION",
    "SAMPLE_DISPATCH", "SHIPMENT", "PRODUCTION_ORDER", "PRODUCTION_UPLOAD", "QUALITY_STANDARD",
    "QUALITY_RECORD", "QUALITY_COA", "QUALITY_DEFECT", "QUALITY_MANUAL", "QUALITY_MSDS", "QUALITY_LABEL",
    "SPECTRUM", "SPECTRUM_ANALYSIS",
]
COMPUTATIONS = {"run_prediction", "run_formula_design", "run_experiment_optimization"}
KINDS = {"type": "string", "enum": ["PREDICTION", "FORMULA_DESIGN", "EXPERIMENT_OPTIMIZATION"]}
REFERENCE = {"type": "object", "properties": {
    "type": {"type": "string", "enum": READ_TYPES}, "id": STRING, "revision": STRING,
    "version_id": STRING, "version_hash": STRING, "project_id": STRING, "customer_id": STRING,
    "import_job_id": STRING, "scope": {"type": "string", "enum": ["RND", "PRODUCTION"]},
    "file_name": STRING, "record_key": STRING, "source_node_key": STRING, "content_hash": STRING, "field": STRING,
}, "required": ["type", "id"], "additionalProperties": False}
PAGE = {"offset": {"type": "integer", "minimum": 0, "maximum": 100000},
        "limit": {"type": "integer", "minimum": 1, "maximum": 40}}
FILTERS = {"type": "object", "additionalProperties": True}
INSTRUCTIONS = """业务读取按查找→完整读取→真实关联展开。business_search不传type只返回可用类型及筛选字段，不执行名称查询；按required_filters补齐筛选。MATERIAL是项目资料，须先取得项目ID并传filters.project_id，不是按型号搜索的物料库。已标明不可用的模块不逐项重试，不绕过权限读取。
沿搜索返回的ref读取具体修订；有next_offset时继续分页。关联只采用business_links返回的已确认关系，同名及相似批号只能作线索。
实验目录不是正文。判断配方、方法或结果是否缺失前先读固定版本概览中的共用字段、背景与方案；样品数为零不代表没有配方说明。项目资料数不是配方物料数。背景引用历史实验时沿明确的实验与版本回读，区分历史值、拟定条件和本次记录。
精确统计使用business_query；大数据用output=file取得原生分析文件，再用原生文件和脚本能力分析。缺失、未测、零值、单位及条件保持区分。
计算先读取get_compute_context，沿实际输入、模型版本及适用域填写请求。只有用户要求时运行预测、配方搜索或实验优化；预测和候选不是实测。
计算提交后保留真实id，使用get_compute_result查询；停止会话不代表后台计算已取消。结果未知先对账，不重建操作身份或重复提交。"""


def handler(name):
    async def invoke(context, args):
        if name == "get_research_briefing_context":
            return await context.runtime.briefings.context(context, args)
        payload = dict(args)
        if name == "business_read":
            payload = context.runtime.briefings.read_request(context.run, payload)
        if name in COMPUTATIONS:
            if not context.call_id:
                raise HTTPException(409, "BUSINESS_CALL_ID_REQUIRED")
            payload["operation_key"] = digest([context.run["id"], context.call_id])
        return await context.host(context.principal, name, payload, context.run["id"])
    return invoke


def business_pack():
    specs = (
        tool("get_research_briefing_context", "读取项目晨报、研发周报、实验复盘的活动索引与固定版本。时间窗口由服务端确定，完整内容仍用business_read；按available_sections和next_offset读完。", {
            "task_type": {"type": "string", "enum": ["project-morning", "research-weekly", "experiment-review"]},
            "section": {"type": "string", "enum": ["PROJECT", "STAGE", "TASK", "EXPERIMENT"]},
            "project_refs": {"type": "array", "maxItems": 20, "items": {"type": "object", "properties": {
                "type": {"const": "PROJECT"}, "id": STRING}, "required": ["type", "id"], "additionalProperties": False}}, **PAGE,
        }, ["task_type"]),
        tool("business_search", "按权限查找业务对象。不传type返回可用类型和筛选字段；返回ref供后续读取。", {
            "type": {"type": "string", "enum": READ_TYPES}, "query": STRING, "filters": FILTERS, **PAGE,
        }),
        tool("business_read", "读取ref及固定修订。实验优先section=record，一次读取完整背景、方案、样品、配方、工艺与结果；较大时按overview的分区和next_offset继续分页，不能跳页。谱图include_file=true交给原生文件工具。", {
            "ref": REFERENCE, "section": STRING, "sample_key": STRING,
            "include_file": {"type": "boolean"},
            "field_codes": {"type": "array", "maxItems": 8, "items": STRING}, **PAGE,
        }, ["ref"]),
        tool("business_links", "沿真实业务关联读取；先查看available_relations，再传relation选择一类。关联目标分别授权。", {
            "ref": REFERENCE, "relation": STRING, **PAGE,
        }, ["ref"]),
        tool("business_query", "查询已接入业务表格。字段采用返回schema的名称；统计仅支持类型明确的数值。output=file将完整授权结果交给原生文件分析。", {
            "type": {"type": "string", "enum": [name for name in READ_TYPES if name not in {
                "PROJECT", "STAGE", "TASK", "REQUIREMENT", "MATERIAL", "EXPERIMENT", "STRUCTURED_DATA"}]},
            "filters": FILTERS, "fields": {"type": "array", "maxItems": 60, "items": STRING},
            "group_by": {"type": "array", "maxItems": 8, "items": STRING},
            "metrics": {"type": "array", "maxItems": 20, "items": {"type": "object", "properties": {
                "field": STRING, "op": {"type": "string", "enum": ["count", "sum", "avg", "min", "max"]},
                "as": STRING}, "required": ["op"], "additionalProperties": False}},
            "output": {"type": "string", "enum": ["rows", "file"]}, **PAGE,
        }, ["type"]),
        tool("get_compute_context", "读取实际可用预测/配方输入、模型版本或历史优化数据集。优化数据按实验版本授权。", {
            "kind": KINDS, "target_ids": {"type": "array", "maxItems": 20, "items": STRING},
            "dataset_id": STRING, "query": STRING,
        }, ["kind"]),
        *(tool(name, description, {"request": {"type": "object", "additionalProperties": True}}, ["request"])
          for name, description in (
              ("run_prediction", "按get_compute_context的当前契约执行真实预测；不训练或发布模型。"),
              ("run_formula_design", "按用户目标调用现有配方搜索；返回权威运行id后查询结果。"),
              ("run_experiment_optimization", "调用已有HISTORICAL_GP_BO；需明确数据集、选中行、X/Y和边界。"),
          )),
        tool("get_compute_result", "按真实运行id读取状态、候选和模型绑定；结果未知时先查原操作回执。", {
            "kind": KINDS, "run_id": STRING,
        }, ["kind", "run_id"]),
    )
    return CapabilityPack("business-data", "2", INSTRUCTIONS, specs, ("business_search",),
                          {spec["name"]: handler(spec["name"]) for spec in specs}, mutations=frozenset(COMPUTATIONS))
