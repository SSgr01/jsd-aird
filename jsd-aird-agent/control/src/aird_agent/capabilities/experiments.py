"""Experiment authority remains in Java; the model supplies business facts only."""

from fastapi import HTTPException

from aird_agent.capabilities.definitions import INTEGER, STRING, tool
from aird_agent.core.capabilities import CapabilityPack
from aird_agent.core.contracts import digest

UUID = {"type": ["string", "null"], "pattern": r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"}
REQUIRED_UUID = {**UUID, "type": "string"}

INSTRUCTIONS = """实验查询先解析真实对象；同名实验有歧义时询问用户，不猜编号。
按返回的具体版本比较，保留样品、配方、工艺、测定方法、条件和结果的对应关系；缺失事实标明缺失。
查询返回有界概览、available_metrics及section_counts；用get_experiment_record的section、sample_key、field_codes读取所需业务记录，按next_offset继续，不能把概览当作完整结果。
目前主要使用两份标准模板(SYNTHESIS与TEST)，读取以实际模板字段定义为准，不根据模板名称猜内容。formulaItems、processSteps、testResults按指标/配方行分组，行头给中文名称、阶段、单位和方法，values给各样品的值及原单元格，sample_columns给可读样品编号及组合身份。
value是规范化值，rawValue是原单元格值（例如百分比0.446规范化为44.6%），不重复乘100；NOT_MEASURED不是零，空方法不能当成方法相同。先读processSteps核对条件，再按available_metrics读取相关结果；合成配方与设计参数、实测结果分别判断。
样品身份使用experiment_id + version_id + sample_key；不同实验可有相同sample_key，禁止据此合并。
实验来源可独立于项目，不要求用户先创建项目或研究主题。
只有用户要求创建草稿时才调用创建工具。缺少名称、分类、计划日期或方案时询问；
不补造日期、配方、条件、结果，不冒充正式提交、审核或发布。返回真实草稿入口。
方案正文写入edit_model.plan，可选edit_model.purpose。无项目、阶段、任务时省略字段或传null，禁止传空字符串。
从search_experiments返回的draft_templates选择真实已发布且支持实验数据的模板，传入template_version_id；不复制已有实验结果。平台建立空白可编辑文档并写入用户方案。模板无法映射时说明限制并转入原实验页面，不猜测填写。
来源是推荐方案时，必须在create_experiment_draft工具参数中传recommendation.run_id与candidate_ids，不能只在plan正文写候选名称。服务端填入各样品列的配方、工艺与目标，实测区域留空。先读get_experiment_loop取得draftDefaults、draftCategories与真实候选身份；创建后核对experiments中的candidateId、experimentId和sampleKey。
用户给出读数后，先读get_experiment_loop或当前实验版本；按version_id、revision、sample_key、measurementFields.fieldCode调用save_experiment_measurements，只保存可编辑草稿。不得从预测值补写读数；提交、审核、完成沿原实验页面进行。
读数可附用户明确给出的method与conditions，记录在当前样品版本；不改标准模板、不推测缺失方法。沿用的预定方法与实际执行不同时填写实际方法，条件不同的结果分别比较。
创建结果未知时先查询回执并对账，不换参数、调用身份或新任务重复创建。"""


def handler(name):
    async def invoke(context, args):
        payload = dict(args)
        if name in {"create_experiment_draft", "save_experiment_measurements"}:
            if not context.call_id:
                raise HTTPException(409, "BUSINESS_CALL_ID_REQUIRED")
            payload["operation_key"] = digest([context.run["id"], context.call_id])
        return await context.host(context.principal, name, payload, context.run["id"])
    return invoke


def experiment_pack():
    specs = (
        tool("search_experiments", "按当前权限查询实验并返回可用分类，分页不泄露未授权记录。",
             {"query": STRING, "offset": INTEGER, "project_id": UUID}),
        tool("get_experiment_record", "读取指定版本的业务概览或分页记录，保留样品和测定条件。默认overview；业务记录在对应section中，按next_offset继续。",
             {"experiment_id": REQUIRED_UUID, "version_id": UUID,
              "section": {"type": "string", "enum": ["overview", "samples", "formulaItems", "processSteps", "testResults", "tables", "events"]},
              "offset": {"type": "integer", "minimum": 0, "maximum": 100000},
              "limit": {"type": "integer", "minimum": 1, "maximum": 40},
              "sample_key": STRING,
              "field_codes": {"type": "array", "maxItems": 8, "items": STRING}}, ["experiment_id"]),
        tool("create_experiment_draft", "按用户要求创建可编辑草稿；必须提供真实分类及用户给定的计划日期，不执行提交或审核。",
             {"title": STRING, "category_id": REQUIRED_UUID,
              "planned_date": {"type": "string", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
              "edit_model": {"type": "object", "properties": {"plan": {"type": "string", "minLength": 1}, "purpose": STRING},
                             "required": ["plan"], "additionalProperties": False},
              "project_id": UUID, "stage_id": UUID, "task_id": UUID, "template_version_id": REQUIRED_UUID,
              "recommendation": {"type": "object", "properties": {"run_id": REQUIRED_UUID,
                  "candidate_ids": {"type": "array", "minItems": 1, "maxItems": 6, "items": REQUIRED_UUID}},
                  "required": ["run_id", "candidate_ids"], "additionalProperties": False}},
             ["title", "category_id", "planned_date", "edit_model", "template_version_id"]),
        tool("save_experiment_measurements", "按用户提供的读数保存实测草稿，保留样品身份、单位与修订冲突；完成流程在实验页面办理。", {
            "experiment_id": REQUIRED_UUID, "version_id": REQUIRED_UUID, "revision": {"type": "integer", "minimum": 0},
            "measurements": {"type": "array", "minItems": 1, "maxItems": 200, "items": {"type": "object", "properties": {
                "sample_key": STRING, "field_code": STRING, "value": {"type": ["number", "string"]}, "unit": STRING,
                "method": {"type": "string", "minLength": 1, "maxLength": 500}, "conditions": {"type": "object"}},
                "required": ["sample_key", "field_code", "value"], "additionalProperties": False}},
        }, ["experiment_id", "version_id", "revision", "measurements"]),
    )
    return CapabilityPack("experiments", "1", INSTRUCTIONS, specs, ("search_experiments",),
                          {spec["name"]: handler(spec["name"]) for spec in specs},
                          mutations=frozenset({"create_experiment_draft", "save_experiment_measurements"}))
