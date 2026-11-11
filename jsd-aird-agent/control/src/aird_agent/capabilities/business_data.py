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
    "SPECTRUM", "SPECTRUM_ANALYSIS", "RAW_MATERIAL", "RAW_MATERIAL_SOURCE",
]
COMPUTATIONS = {"run_prediction", "run_formula_design", "run_experiment_optimization", "run_model_training", "activate_trained_model", "continue_experiment_optimization"}
KINDS = {"type": "string", "enum": ["PREDICTION", "FORMULA_DESIGN", "EXPERIMENT_OPTIMIZATION", "MODEL_TRAINING"]}
REFERENCE = {"type": "object", "properties": {
    "type": {"type": "string", "enum": READ_TYPES}, "id": STRING, "revision": STRING,
    "version_id": STRING, "version_hash": STRING, "project_id": STRING, "customer_id": STRING,
    "import_job_id": STRING, "scope": {"type": "string", "enum": ["RND", "PRODUCTION"]},
    "file_name": STRING, "record_key": STRING, "source_node_key": STRING, "content_hash": STRING, "field": STRING,
}, "required": ["type", "id"], "additionalProperties": False}
PAGE = {"offset": {"type": "integer", "minimum": 0, "maximum": 100000},
        "limit": {"type": "integer", "minimum": 1, "maximum": 40}}
FILTERS = {"type": "object", "additionalProperties": True}
EVIDENCE_REF = {"type": "object", "properties": {"evidence_id": STRING},
                "required": ["evidence_id"], "additionalProperties": False}
RESEARCH_BASIS = {"type": "array", "maxItems": 50, "items": {"type": "object", "properties": {
    "source_ref": EVIDENCE_REF,
    **{key: {"type": "array", "maxItems": 30, "items": STRING}
       for key in ("variables", "conditions", "metrics", "pending_measurements")},
    **{key: STRING for key in ("conclusion", "applicability", "project_differences")},
}, "required": ["source_ref", "variables", "conditions", "metrics", "pending_measurements",
                "conclusion", "applicability", "project_differences"], "additionalProperties": False}}
INSTRUCTIONS = """业务读取按查找→完整读取→真实关联展开。business_search不传type只返回可用类型及筛选字段，不执行名称查询；按required_filters补齐筛选。MATERIAL是项目资料，须先取得项目ID并传filters.project_id，不是按型号搜索的物料库。已标明不可用的模块不逐项重试，不绕过权限读取。
沿搜索返回的ref读取具体修订；有next_offset时继续分页。关联只采用business_links返回的已确认关系，同名及相似批号只能作线索。
实验目录不是正文。判断配方、方法或结果是否缺失前先读固定版本概览中的共用字段、背景与方案；样品数为零不代表没有配方说明。项目资料数不是配方物料数。背景引用历史实验时沿明确的实验与版本回读，区分历史值、拟定条件和本次记录。
精确统计使用business_query；大数据用output=file取得原生分析文件，再用原生文件和脚本能力分析。缺失、未测、零值、单位及条件保持区分。
计算先读取get_compute_context，沿实际输入、模型版本及适用域填写请求。只有用户要求时运行预测、配方搜索或实验优化；预测和候选不是实测。
计算提交后保留真实id，使用get_compute_result查询；停止会话不代表后台计算已取消。结果未知先对账，不重建操作身份或重复提交。
化学原料使用RAW_MATERIAL按内部编号、牌号、供应商查询，读取指定version_id；来源原文沿RAW_MATERIAL_SOURCE关系读取。MATERIAL仍是项目资料。解释配方前查清编号对应的牌号、类别、作用、物性、官能团、组成及范围，逐条保留单位、适用条件和资料版本位置；未披露组分保持未知。INFERRED机理只能作为推断，GENERATED_REFERENCE为研发参考资料，不可声称供应商正式披露。
建模先用MODEL_TRAINING上下文选择授权样品和X/Y。调用run_model_training后解释样本覆盖、效果和适用域；只有用户明确说启用具体建模结果时，才调用activate_trained_model，并带上该原话。训练完成不会自动启用。
get_compute_context按offset/limit分页返回样品，按next_offset继续；rowCount为总行数，rows只是本页。可用column_codes只取所需X/Y值。原料概览只含身份与用途，物性、组成及出处按RAW_MATERIAL读取，不依赖本机命令读取落盘输出。
用户明确要求沿用已完成建模中的样品与X/Y进行推荐时，get_compute_context(kind=EXPERIMENT_OPTIMIZATION,training_run_id=真实建模运行身份)返回含trainingRunId的request_draft；直接据此提交并补充方向、bounds、formulaTotal。平台恢复选中行和X/Y，无须复制样品身份或重读全部样品。不重新训练。旧选择失效时须重新选择。
用get_experiment_loop读取推荐、实际实验版本、偏差和下一步。下一轮只调用continue_experiment_optimization，使用已完成关联样品的实际X/Y；不把候选手填值或预测值当作正式读数。
写复盘时，按实验、版本和样品对齐。以observations中COMPARABLE记录的actualInputs、value和metrics.residual列正式读数与偏差；metrics.inputChanges区分原推荐和实际投料。候选明细只用于说明原推荐或后续计划。用frozenData.rowCount写本轮实际学习条数，区分起始选择数。只统计已有正式读数的轮次，不用推荐轮数代替制样轮数。
结合文献与原料资料提出建议时，明确变量、条件、指标、适用边界、本项目差异与待实测事项。source_refs传已读取结果的[{evidence_id:真实证据身份}]，平台恢复原始出处。不可用business_read的{type,id}代替或虚构STRUCTURED_DATA引用。数据集与实验行由计算服务自行冻结；没有额外出处时省略source_refs。项目已明确时传project_id。
research_basis.source_ref使用source_refs中相同的{evidence_id:真实证据身份}，列明variables、conditions、metrics、结论conclusion、适用范围applicability、本项目差异project_differences和pending_measurements；该依据随运行和后续轮次保留。
本业务链正文、提示、工具说明和报告采用“实测、测定、评估、研发参考”等业务用词，避免“验收、测试、模拟、验证”；交叉折叠指标称“交叉评估”。仅两份标准模板的原名及原始证据保留原文。日报、周报和复盘全文留在当前对话，不另存项目文档。"""


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
            def source(ref):
                if not isinstance(ref, dict) or "evidence_id" not in ref:
                    return ref
                candidate = context.run.get("evidence", {}).get(ref["evidence_id"])
                if set(ref) != {"evidence_id"} or not candidate or not candidate.get("source"):
                    raise HTTPException(422, "RESEARCH_SOURCE_NOT_READ: use a retrieved evidence_id")
                return candidate["source"]
            if "source_refs" in payload:
                payload["source_refs"] = [source(ref) for ref in payload["source_refs"]]
            if "research_basis" in payload:
                payload["research_basis"] = [
                    {**item, "source_ref": source(item["source_ref"])} for item in payload["research_basis"]
                ]
                if any(item["source_ref"] not in payload.get("source_refs", []) for item in payload["research_basis"]):
                    raise HTTPException(422, "RESEARCH_BASIS_SOURCE_NOT_LINKED: include the same retrieved evidence_id in source_refs")
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
        tool("get_compute_context", "读取建模、预测、配方与优化上下文，包括授权样品、X/Y、原料档案和模型版本。", {
            "kind": KINDS, "target_ids": {"type": "array", "maxItems": 20, "items": STRING},
            "dataset_id": STRING, "query": STRING, "training_run_id": STRING,
            "offset": {"type": "integer", "minimum": 0}, "limit": {"type": "integer", "minimum": 1, "maximum": 40},
            "column_codes": {"type": "array", "maxItems": 100, "items": STRING},
        }, ["kind"]),
        *(tool(name, description, {"request": {"type": "object", "additionalProperties": True},
            "research_basis": RESEARCH_BASIS,
            "project_id": STRING, "source_refs": {"type": "array", "maxItems": 100, "items": EVIDENCE_REF}}, ["request"])
          for name, description in (
              ("run_prediction", "按get_compute_context的当前契约执行真实预测；不训练或发布模型。"),
              ("run_formula_design", "按用户目标调用现有配方搜索；返回权威运行id后查询结果。"),
              ("run_experiment_optimization", "调用已有HISTORICAL_GP_BO；需明确数据集、选中行、X/Y和边界。"),
              ("run_model_training", "按用户选择的授权样品、X/Y发起建模，返回训练任务；启用需另有明确指令。"),
          )),
        tool("activate_trained_model", "收到用户明确启用指令后，注册并启用指定的成功建模结果。", {
            "run_id": STRING, "activate": {"const": True}, "user_instruction": STRING,
        }, ["run_id", "activate", "user_instruction"]),
        tool("continue_experiment_optimization", "以已完成关联样品的实际配方、工艺和读数继续下一轮，冻结来源并去重。", {
            "run_id": STRING,
        }, ["run_id"]),
        tool("get_experiment_loop", "读取当前轮次、推荐方案、关联样品、正式结果、预测偏差及下一步，支持按项目汇总。", {
            "run_id": STRING, "project_id": STRING,
        }),
        tool("get_compute_result", "按真实运行id读取状态、候选和模型绑定；结果未知时先查原操作回执。", {
            "kind": KINDS, "run_id": STRING,
        }, ["kind", "run_id"]),
    )
    return CapabilityPack("business-data", "3", INSTRUCTIONS, specs, ("business_search",),
                          {spec["name"]: handler(spec["name"]) for spec in specs}, mutations=frozenset(COMPUTATIONS))
