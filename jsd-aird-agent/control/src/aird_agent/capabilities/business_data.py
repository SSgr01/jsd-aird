"""Small business contracts on the native harness; authority remains with Java."""

import asyncio
import json

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
    "SPECTRUM", "RAW_MATERIAL", "RAW_MATERIAL_SOURCE",
    "MEETING_MINUTES", "PROJECT_DOCUMENT", "PROJECT_REFERENCE", "QUALITY_UPLOAD", "RESEARCH_TEST_UPLOAD",
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
READ_PARAMETERS = {
    "ref": REFERENCE, "section": STRING, "sample_key": STRING,
    "page": {"type": "integer", "minimum": 1, "description": "图谱原始页码，配合section=page和include_file=true读取原图。"},
    "include_file": {"type": "boolean"}, "file_id": STRING,
    "field_codes": {"type": "array", "maxItems": 8, "items": STRING,
        "description": "仅使用原记录返回的字段代码；实验record/content不传此项，登记日期和完整编号在current_registration。"}, **PAGE,
    "refresh": {"type": "boolean", "description": "明确需要刷新本轮已读记录时为true。"},
}
EVIDENCE_REF = {"type": "object", "properties": {"evidence_id": STRING},
                "required": ["evidence_id"], "additionalProperties": False}
RESEARCH_BASIS = {"type": "array", "maxItems": 50, "items": {"type": "object", "properties": {
    "source_ref": EVIDENCE_REF,
    **{key: {"type": "array", "maxItems": 30, "items": STRING}
       for key in ("variables", "conditions", "metrics", "pending_measurements")},
    **{key: STRING for key in ("conclusion", "applicability", "project_differences")},
}, "required": ["source_ref", "variables", "conditions", "metrics", "pending_measurements",
                "conclusion", "applicability", "project_differences"], "additionalProperties": False}}
INSTRUCTIONS = """业务读取按查找→完整读取→真实关联展开。business_search不传type只返回可用类型及筛选字段，不执行名称查询；按required_filters补齐筛选。目录不含正文，不能把取得目录说成查到了记录。产品型号不一定是原料编号：先并行查EXPERIMENT、RESEARCH_TEST与QUALITY_STANDARD，必要时查STRUCTURED_DATA及库存；RAW_MATERIAL零命中不能据此断言系统没有产品或实验资料。MATERIAL是项目资料，须先取得项目ID并传filters.project_id，不是按型号搜索的物料库。已标明不可用的模块不逐项重试，不绕过权限读取。
查找命中不超过4条时会直接补齐授权正文；candidates中已有reading的正文直接使用。多条明确记录使用business_read_many批量传搜索返回的ref，不再用名称反复搜索。每项错误独立返回，失败不表示没有记录。读取相同固定版本或相同文献分页时复用本轮结果；需要最新状态时用refresh=true。
参数错误按repair或公开schema修正一次；相同参数的明确拒绝不能原样重试。工具不存在时立即用tool_search按准确工具名重新发现一次，仍不可用就说明具体阻断，不改用反复搜索代替正文读取。权限拒绝、版本冲突、未知执行回执分别处理；用户确认不能替代系统回执。生成文件后直接present交付，成功登记前不能声称已有下载入口。
query按连续文本匹配，使用一个明确编号或关键词，不把客户、型号和资料名称拼成一串。filters.business_no是单据编号的精确匹配，不是产品型号：查FR-318等产品型号用query，取得JC/COA/STD等完整业务编号后再精确匹配。按型号对相关类型查询均无资料时说明查过的范围与缺口，不继续拼接同义词、空格或更长词组尝试碰撞。搜索或关联返回files目录后，可沿ref用business_read获取指定file_id的原件；未请求原件时不能据此声称没有原件。
业务任务先并行查直接相关类型；取得明确关联后沿ID或business_links读取，不再拼接多个名称反复搜索。生产单既有PRODUCTION_ORDER在线单据，也有PRODUCTION_UPLOAD上传单据，两类应一并查询。质量判定以已保存规格、结果和审核状态为依据；未取得的放行、签字、补测与物流时长保持未知，不编排半天、一天等承诺。
沿搜索返回的ref读取具体修订；有next_offset时继续分页。关联只采用business_links返回的已确认关系，同名及相似批号只能作线索。
品管、生产和研发资料的content为页面已保存正文，登记字段不能代替正文。大正文使用section=content，按reading中的offset、returned、total与next_offset读完；单元格原值、公式与数字格式保持区分。files仅为原文件目录，include_file=true按file_id下载原上传内容；有business_file.reading_path时用原生read阅读该文本投影，图片用read_image。文本投影不能代替内嵌图片和版式阅读。原件可能早于页面保存正文，不能相互替代。没有保存正文时按content_notice说明缺失，并按需要读取原件。
项目business_links可发现会议纪要、项目文档、资料参考及关联业务资料。日报周报先读完RELATED_ACTIVITY及全部索引分页，再按报告需要展开正文、原文件和附件；发现目录、读取正文、下载原件与实际阅读附件是不同动作。不要求逐个附件全部读取。后台可阅读任务目录中的文件与大结果，不能运行脚本、修改文件或交付文件。
实验目录不是正文。判断配方、方法或结果是否缺失前先读固定版本概览中的共用字段、背景与方案；样品数为零不代表没有配方说明。项目资料数不是配方物料数。背景引用历史实验时沿明确的实验与版本回读，区分历史值、拟定条件和本次记录。
实验section=record包含结构化分区及页面保存正文；过大时按section_counts逐分区读取，其中content保留未绑定字段的备注、额外工作表及Word文字。实验原上传文件也通过files及include_file/file_id按需读取，历史版本仅列该版本来源；附加文件仅在当前版本目录列出。没有上传文件的在线实验不生成原件。
精确统计使用business_query；大数据用output=file取得原生分析文件，再用原生文件和脚本能力分析。缺失、未测、零值、单位及条件保持区分。
同一任务中，已完整读取的同版本、同分区和同分页结果直接复用，不为整理结论或更换输出格式重新搜索、重读。导出表格优先使用已取得的rows或dataset.path，通过原生文件和脚本能力转换；文件路径不等于已读取。仅在缺少字段或分页、来源版本变化、用户要求刷新当前状态时补充查询；沿next_offset读取未读页，不重复从第一页开始。历史结果已被压缩且必要数据或出处无法恢复时允许重读，不凭摘要补造数据。
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


INSTRUCTIONS += """\n系统记录整理为Excel时，找到ref后直接调用business_export_records，把所有相关固定ref一次传入。该工具生成保留原值的表格并重新打开逐值核对；收到workbook.path后present交付。不要手写source_rows或转换脚本，不调用历史上传文件转换流程。
空白或null只能说明未填写或缺失，除非原文明确记录，不能推断未测定、未执行或零值。没有重复性、允差或统计依据时，只说明数值差异，不能称合理波动、稳定或合格。日期字段保留原名称，登记日期不能改称测定日期。"""


def report_project_ids(snapshot):
    """Current context may belong to an indexed experiment without a project update."""
    projects = set()
    for entries in snapshot.get("sections", {}).values():
        for item in entries:
            ref = item.get("ref", {})
            if ref.get("type") == "PROJECT":
                projects.add(ref["id"])
            if ref.get("project_id"):
                projects.add(ref["project_id"])
    return sorted(projects)


def reading_size(result):
    from aird_agent.evidence.presentation import model_result
    return len(json.dumps(model_result(result), ensure_ascii=False, separators=(",", ":")))


async def read_record(context, args, *, paginate=True):
    payload = {key: value for key, value in args.items() if key != "refresh"}
    if isinstance(payload.get("sample_key"), str):
        payload["sample_key"] = payload["sample_key"].strip()
    if not payload.get("sample_key"):
        payload.pop("sample_key", None)
    if not payload.get("field_codes"):
        payload.pop("field_codes", None)
    if payload.get("ref", {}).get("type") == "EXPERIMENT":
        payload.setdefault("section", "record")
    runtime = context.runtime
    if runtime is not None and hasattr(runtime, "briefings"):
        payload = runtime.briefings.read_request(context.run, payload)
    if context.run.get("automation"):
        snapshot = context.run.get("briefing_snapshot", {})
        if not snapshot.get("frozen"):
            raise HTTPException(409, "BACKGROUND_REPORT_SCOPE_REQUIRED: read get_research_briefing_context first")
        payload["report_project_ids"] = report_project_ids(snapshot)
    operation = lambda: context.host(context.principal, "business_read", payload, context.run["id"])
    if runtime is not None and hasattr(runtime, "read_cache"):
        result = await runtime.read_cache.read(context.principal, context.run, "business_read",
            {**payload, **({"refresh": True} if args.get("refresh") else {})}, operation,
            (lambda result: runtime.validate_reused_read(context.principal, context.run, result))
            if hasattr(runtime, "validate_reused_read") else None)
    else:
        result = await operation()
    if (paginate and payload.get("section") == "record" and payload.get("ref", {}).get("type") == "EXPERIMENT"
            and reading_size(result) > 32000):
        # Do not deliver a head/tail excerpt as a complete record. Its fixed
        # overview supplies real section counts and subsequent page cursors.
        return {**await read_record(context, {**args, "section": "overview"}, paginate=False),
                "record_requires_paging": True,
                "reading_notice": "完整记录超过本次正文容量，已返回同版本概览；按section_counts读取非空分区及分页，不能把概览当作读完正文。"}
    return result


async def read_many(context, requests, *, bounded=True):
    limit = asyncio.Semaphore(4)
    async def one(index, request):
        async with limit:
            try:
                result = await read_record(context, request, paginate=bounded)
                return {"index": index, "ref": request["ref"], "request": request, "result": result}
            except HTTPException as error:
                return {"index": index, "ref": request["ref"], "request": request, "error": str(error.detail),
                        "status": error.status_code, **getattr(error, "tool_error", {})}
    items = await asyncio.gather(*(one(i, req) for i, req in enumerate(requests)))
    candidates, delivered, deferred = [], [], []
    for position, item in enumerate(items):
        result = item.pop("result", None)
        if result is not None:
            start = len(candidates)
            item.update({key: value for key, value in result.items() if key != "candidates"})
            proposed = [*candidates, *result.get("candidates", [])]
            item["candidate_indexes"] = list(range(start, len(proposed)))
            if bounded and reading_size({"items": [*delivered, item], "candidates": proposed}) > 36000:
                if not delivered:
                    delivered.append({"index": item["index"], "ref": item["ref"], "request": item["request"],
                                      "error": "BUSINESS_PAGE_TOO_LARGE: 缩小分区或分页后读取正文。", "status": 422})
                    deferred = requests[position+1:]
                else:
                    deferred = requests[position:]
                break
            candidates = proposed
        delivered.append(item)
    return {"items": delivered, "candidates": candidates,
            "complete": not deferred and all("error" not in item for item in delivered),
            **({"remaining_requests": deferred,
                "reading_notice": "本批只包含已完整送达的正文，剩余请求未计入读取；继续remaining_requests，后台可直接business_read_many({})。"} if deferred else {})}


def handler(name):
    async def invoke(context, args):
        if name == "business_search" and args.get("types"):
            if args.get("type"):
                raise HTTPException(422, "BUSINESS_SEARCH_TYPE_CONFLICT: type与types只传一种。")
            slots = asyncio.Semaphore(4)
            async def search_one(kind):
                request = {key: value for key, value in args.items() if key != "types"}
                request.update(type=kind, read_records=False)
                async with slots:
                    try:
                        return kind, request, await invoke(context, request)
                    except HTTPException as error:
                        return kind, request, {"error": str(error.detail), "status": error.status_code,
                                              **getattr(error, "tool_error", {})}
            searched = await asyncio.gather(*(search_one(kind) for kind in args["types"]))
            candidates, searches, remaining = [], [], []
            for kind, request, result in searched:
                searches.append({"type": kind, "returned": len(result.get("candidates", [])),
                    **{key: result[key] for key in ("total", "error", "error_code", "status", "repair", "resolution") if key in result},
                    **({"next_request": {**request, "offset": result["next_offset"]}} if result.get("next_offset") is not None else {})})
                for candidate in result.get("candidates", []):
                    if reading_size({"candidates": [*candidates, candidate]}) <= 30000:
                        candidates.append(candidate)
                    elif candidate.get("ref"):
                        remaining.append(candidate["ref"])
            result = {"candidates": candidates, "searches": searches, "query_executed": True,
                "searched_types": list(args["types"]), "lookup_complete": all(not result.get("error") for _, _, result in searched),
                "records_complete": not remaining and all(not value.get("error") and value.get("next_offset") is None for _, _, value in searched),
                "reading_notice": "联查结果逐类保留实际查询范围、命中和错误。空命中只代表这些范围没有匹配；受限和待分页不代表没有记录。不再换长词组重复查同一型号；未取得的企业信息列为资料缺口，先给有边界的分析。",
                **({"remaining_refs": remaining} if remaining else {})}
            refs = [candidate["ref"] for candidate in candidates if candidate.get("ref")]
            if args.get("read_records", True) and 0 < len(refs) <= 4 and not remaining:
                read = await read_many(context, [{"ref": ref, **({"refresh": True} if args.get("refresh") else {})} for ref in refs])
                delivered = {item["index"] for item in read["items"] if "error" not in item}
                result.update(candidates=[*read["candidates"], *[item for i, item in enumerate(candidates) if i not in delivered]],
                    reads=read["items"], records_complete=result["records_complete"] and read["complete"]
                        and not any(item.get("record_requires_paging") for item in read["items"]),
                    **({"remaining_requests": read["remaining_requests"]} if read.get("remaining_requests") else {}))
            return result
        if name == "get_research_briefing_context":
            return await context.runtime.briefings.context(context, args)
        if name == "business_read_many":
            requests = args.get("requests")
            if requests is None:
                if not context.run.get("automation"):
                    raise HTTPException(422, "BUSINESS_READ_REQUESTS_REQUIRED")
                current = await context.runtime.store.get(context.principal, "run", context.run["id"])
                requests = context.runtime.briefings.pending_reads(current)
            result = await read_many(context, requests)
            if context.run.get("automation"):
                result["next_action"] = "本批正文已返回。继续调用business_read_many({})由服务端选择下一批未读固定记录；无requests仅用于后台继续本期读取。逐项错误先处理，不原样重试拒绝项。无剩余记录时整理正文。"
            return result
        if name == "business_export_records":
            if context.run.get("automation") or "files" not in context.run.get("pack_ids", []):
                raise HTTPException(403, "CAPABILITY_NOT_AUTHORIZED")
            result = await read_many(context, [{"ref": ref} for ref in args["refs"]], bounded=False)
            if not result["complete"]:
                return {**result, "error": "EXPORT_SOURCE_INCOMPLETE", "retryable": False}
            return {**result, "business_export": {"name": "业务记录.xlsx"}}
        if name == "business_read":
            return await read_record(context, args)
        payload = {key: value for key, value in args.items() if key not in {"refresh", "read_records"}}
        if context.run.get("automation") and name in {"business_read", "business_search", "business_links"}:
            kind = args.get("type", args.get("ref", {}).get("type"))
            scoped = {"MEETING_MINUTES", "PROJECT_DOCUMENT", "PROJECT_REFERENCE", "QUALITY_UPLOAD", "PRODUCTION_UPLOAD", "RESEARCH_TEST", "RESEARCH_TEST_UPLOAD",
                "QUALITY_STANDARD", "QUALITY_RECORD", "QUALITY_COA", "QUALITY_DEFECT", "QUALITY_MANUAL", "QUALITY_MSDS", "QUALITY_LABEL", "DATA_IMPORT", "SPECTRUM", "CUSTOMER_REQUIREMENT", "PROJECT"}
            if kind in scoped:
                snapshot = context.run.get("briefing_snapshot", {})
                if not snapshot.get("frozen"):
                    raise HTTPException(409, "BACKGROUND_REPORT_SCOPE_REQUIRED: read get_research_briefing_context first")
                payload["report_project_ids"] = report_project_ids(snapshot)
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
        result = await context.host(context.principal, name, payload, context.run["id"])
        if name == "business_search" and not args.get("type"):
            query = args.get("query", args.get("filters", {}).get("query"))
            if query:
                result = {**result, "query_executed": False, "pending_query": query,
                    "next_action": "这只是能力目录，尚未执行名称查询。按resource_types选择具体type；产品型号可并行查询EXPERIMENT、RESEARCH_TEST、QUALITY_STANDARD、STRUCTURED_DATA，不能只查RAW_MATERIAL后判定无资料。"}
        if name == "business_search" and args.get("type") and args.get("read_records", True):
            refs = [item["ref"] for item in result.get("candidates", []) if item.get("ref")]
            if 0 < len(refs) <= 4 and not result.get("next_offset"):
                read = await read_many(context, [{"ref": ref, **({"refresh": True} if args.get("refresh") else {})} for ref in refs])
                returned = {item["index"] for item in read["items"] if "error" not in item}
                failed = set(range(len(refs))) - returned
                summaries = [item for i, item in enumerate(result.get("candidates", [])) if i in failed]
                result = {**result, "candidates": [*read["candidates"], *summaries], "reads": read["items"],
                          "records_complete": read["complete"] and not any(item.get("record_requires_paging") for item in read["items"]),
                          **({"remaining_requests": read["remaining_requests"]} if read.get("remaining_requests") else {})}
        return result
    return invoke


def business_pack():
    specs = (
        tool("experiment_conversion", "实验记录标准化转换：source取得本人上传原件并准备skill工作区；stage以present登记的三个真实成果ID保存转换结果、映射计划、校验报告，送待人工核对，不确认导入、不发布；fail记录具体文件阻塞。", {
            "action": {"type": "string", "enum": ["source", "stage", "fail"]},
            "conversion_id": STRING, "result_artifact_id": STRING, "plan_artifact_id": STRING,
            "report_artifact_id": STRING, "reason": {"type": "string", "maxLength": 2000},
        }, ["action", "conversion_id"]),
        tool("get_research_briefing_context", "读取项目晨报、研发周报、实验复盘的活动索引与固定版本。时间窗口由服务端确定，正文用business_read。以next_request或pending_index_requests中的完整参数继续调用；offset为页起点，不能反复读取offset=0。", {
            "task_type": {"type": "string", "enum": ["project-morning", "research-weekly", "experiment-review"]},
            "section": {"type": "string", "enum": ["PROJECT", "STAGE", "TASK", "EXPERIMENT", "RELATED_ACTIVITY"]},
            "project_refs": {"type": "array", "maxItems": 20, "items": {"type": "object", "properties": {
                "type": {"const": "PROJECT"}, "id": STRING}, "required": ["type", "id"], "additionalProperties": False}}, **PAGE,
        }, ["task_type"]),
        tool("business_search", "按权限查找业务对象。types可一次并行联查最多8类资料，逐类返回实际结果和错误；与type二选一。产品型号资料优先types=[EXPERIMENT,RESEARCH_TEST,QUALITY_STANDARD,RAW_MATERIAL,QUALITY_MANUAL,STRUCTURED_DATA]及准确query。不传type/types只返回能力目录。query是单个连续文本，不拆词。少量命中补齐固定正文。", {
            "type": {"type": "string", "enum": READ_TYPES},
            "types": {"type": "array", "minItems": 1, "maxItems": 8, "uniqueItems": True, "items": {"type": "string", "enum": READ_TYPES}},
            "query": STRING, "filters": FILTERS, **PAGE,
            "read_records": {"type": "boolean", "description": "默认补齐不超过4条命中的正文；仅需目录时为false。"},
            "refresh": {"type": "boolean"},
        }),
        tool("business_read", "读取已保存正文及登记字段。大表格用section=content及offset/limit按next_offset读完。files是原文件目录，include_file=true下载原件；多个文件需指定file_id。原件可能早于保存正文，下载成功不代表已阅读。实验优先section=record，较大时按分区分页。", {
            **READ_PARAMETERS,
        }, ["ref"]),
        tool("business_read_many", "按固定ref批量读取正文，最多8条；每条单独授权，错误和分页分别返回。实验默认完整record。后台简报可传{}，服务端从本期冻结索引选择下一批未读记录及分页，无须复制ID；前台必须传requests。", {
            "requests": {"type": "array", "minItems": 1, "maxItems": 8, "items": {
                "type": "object", "properties": {key: value for key, value in READ_PARAMETERS.items() if key != "include_file"},
                "required": ["ref"], "additionalProperties": False}},
        }),
        tool("business_export_records", "将已找到的固定实验记录直接整理为Excel，最多8条。平台批量读取正文、保留空白、零值、单位、方法、日期及出处，重新打开逐值核对后返回workbook.path；随后present交付。无需手写脚本或加载历史文件转换技能。", {
            "refs": {"type": "array", "minItems": 1, "maxItems": 8, "items": {**REFERENCE,
                "properties": {**REFERENCE["properties"], "type": {"const": "EXPERIMENT"}},
                "anyOf": [{"required": ["version_id"]}, {"required": ["revision"]}]}},
        }, ["refs"]),
        tool("business_links", "沿真实业务关联读取；复制记录中的available_relations名称作为relation，不猜英文关联名。未提供名称时先省略relation取得目录，再选一类。关联目标分别授权。", {
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
    return CapabilityPack("business-data", "9", INSTRUCTIONS, specs, ("business_search", "business_read", "business_read_many", "business_export_records", "business_links", "business_query"),
                          {spec["name"]: handler(spec["name"]) for spec in specs}, mutations=frozenset(COMPUTATIONS | {"experiment_conversion"}))
