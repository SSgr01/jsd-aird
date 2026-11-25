# 映射计划与执行

计划保存到 `.dsh/cache/jsd-experiment-converter/plans/`，不能只留在会话上下文。服务器文件夹模式只使用当前绑定文件的独立计划；XLS/DOC 的 `source`、源坐标及 `source_sha256` 绑定后台提供的规范化工作副本，服务器原件另以 `original_sha256` 追溯，不要混用。最小格式：

```json
{
  "version": 1,
  "source": "原始实验/测试实验/文件.xlsx",
  "kind": "test",
  "source_sha256": "原件SHA256",
  "template_sha256": "标准模板/test.xlsx的SHA256",
  "sheets": [{
    "source_sheet": "Sheet1",
    "groups": ["D16", "E16"],
    "recipe": {
      "names": ["C17", "C18"],
      "amounts": [["D17", "E17"], ["D18", "E18"]],
      "totals": ["D19", "E19"]
    },
    "writes": [{"src": "D10", "dst": "C21"}],
    "structural": [{"src": "C16", "reason": "实验编号字段标签"}],
    "unplaced": [{"src": "D33", "reason": "额外时间点，模板无时间轴", "suggested_field": "翘曲度-12小时"}]
  }],
  "skipped": [{"source_sheet": "汇总", "reason": "非分组实验汇总"}]
}
```

- `source` 是相对工作区的精确路径；SHA256 可用 `workflow.digest(path)`。所有非空源表都必须进入 sheets 或有原因的 skipped；真正空表无需写。
- `groups`、`names`、`amounts`、`totals` 都是**源单元格地址**，不用复制一遍测量数据。amounts 外层是物料、内层是组。源无合计时可不提供 totals。
- Word配方数字文本可显式声明`recipe.amount_transform="numeric_text"`；保留转前原值及转换明细，拒绝异常数字、单位文本及百分比。每组各自列物料名/用量时，可用与groups等长的`recipe.entries`（每组一份`{"name":"源名称格","amount":"源用量格"}`列表）代替names/amounts。缺项保持空白，不造来源坐标；单组同名重复投料不能自动合并。相同样品可参与多次实验，sample_groups允许重复，groups仍需唯一。
- `writes.dst` 使用**扩容前的空模板坐标**，执行器负责把合计行及后面的目标行往下移。配方和组编号由专用字段写，别再在 writes 重复写它们。模板标签不可覆盖。
- 同一源可以写到多处，例如实验编号需同步两行；同一目标不能被多个源覆盖。
- `writes` 可显式使用 `{"srcs":["C35","C36","C37"],"join":"\n","dst":"B53"}` 按原顺序合并多段纯文字；不能同时声明src/transform，不用于折叠数值观测或重复投料。每个源格均参与覆盖核验。
- 本地无隐藏绑定的模板可显式声明 `group_identity_role`：默认 `EXPERIMENT`；合成的 `PLAN` 将方案身份写入实验内容，测试的 `PLAN` 将方案身份写入第7行方案/样品字段，测试的 `SAMPLE` 将样品身份写入样品/树脂字段，不补造实验编号。测试 `PLAN` 不将浓度或施工条件填作树脂。无实际配方时合计保持空白。系统发布绑定模板不接受这些身份替代。
- `structural` 逐个列源标签并说明；不要把未读懂的数字或观察放进它。`unplaced` 保留原值及原因；未交代的非空源单元格会报错。
- 输入含公式时使用原件缓存值；缺缓存会阻塞，不能改读公式字符串假装数值。
- 配方用量接受数值、空白、`/`，其他单位文本需要明确的转换适配。写入的普通源文本即使以 `=` 开头，也按文本保存。
- 写入保持目标模板格式，源百分比数值通常不需要乘100；只有明确单位转换才使用 `percent_to_points`。
- 源为普通数值格式的百分数点（如固含标签下的97.8），经语义核对后写入百分比格式目标时，可显式使用 `percent_points_to_fraction` 得到0.978；原值和采用模板默认百分比单位的依据仍须保留，不能仅凭数值大小自动判断。
- 已提供 `remove_remark_prefix` 转换，以及 `{ "pattern": "UVA能量：([0-9.]+)", "numeric": true }` 正则提取。提取需唯一命中；可用 value_map 显式声明等价编码。不要用正则决定两个不同测试项是否同义。

## 当前文件的计划

运行资源不包含历史业务样例计划。先检查当前源文件，为它独立生成映射计划，并核对源和模板指纹。不要按文件名或相似版式复用其他文件的数据与映射。

```text
python -X utf8 scripts/execute_plan.py --root ROOT --plan ROOT/.dsh/cache/jsd-experiment-converter/plans/current.json --output ROOT/_work/current-review.xlsx
```

执行器写入后重开逐格核对，校验缓存与配方；完整结构检查见 qa.md。状态保存到源指纹目录 status.json。仅在计划、模板、执行器和输出指纹均相同时复用结果，已有不同结果不覆盖。

## 加新格式时

### 本地来源与报告扩展

- 同一样品或方案、不同实验条件可在测试页显式声明`group_identity_role="SAMPLE"`或`"PLAN"`及与groups等长的`group_condition_refs`（每项为来源坐标列表）。仅当身份与条件组合唯一、全部区分条件同时由writes写入标准页时接受；不能只在内部去重而让交付表丢条件。同表多组本身不是跳过理由。
- 独立实验区块仍有多个正常平行组时，合成XLSX的`source_partition`可额外声明`mode="EXPERIMENT_BLOCK"`，保持该真实区块多组同页。须显式列全部引用坐标（包括本区块配方空格），分区合并仍须覆盖全源，不按容量拆组。
- 本地无绑定模板的元数据来源表可在顶层`context_sheets`中声明，如`[{"source_sheet":"table-1","structural":[],"unplaced":[]}]`。writes使用`table-1!A1`读取真实来源；上下文表不生成空的业务页，全部非空格仍须通过写入或逐项处置覆盖。上下文表不能同时选择/跳过，不能跨越显式源分区，也不改变系统发布模板绑定。
- DOCX正文可选`source_sheet="paragraphs"`，直接读取原段落A1、A2等；全部`docx_paragraphs`仍逐段匹配。空段落不作实验数据，不创建模型填值的中间工作簿。
- 长文组列可显式`group_column_width=38`（本地无绑定，20至60）；保留模板标签和字体，不缩小文字。写后重开及预览检查仍必需。
- 百分比浓度可显式用`writes.transform="percent_to_text"`保留为带百分号的条件文字；执行器要求该Excel源格本身有百分比数值格式，拒绝把普通小数猜成百分比。以浓度本身区分方案时可用`group_identity_role="PLAN"`及`group_transform="percent_to_text"`，不造实验编号；源数值和数值格式仍留原文对照。
- `recipe.amount_transforms`按本配方真实用量格指定显式转换，例如`{"V11":{"pattern":"^F-1834/([0-9.]+)$","numeric":true}}`。原用量全文及转换步骤保留报告；层别、浓度等含义仍须另写入对应方案，不能只提取数字而丢条件。
- `recipe.name_transforms`可逐格显式提取同格中的原物料名，适用于原文明确写成“XZ545    12”这类物料和投料共格的情况；与该格`amount_transforms`分别声明唯一正则，报告分别保留两种提取的完整原文。只允许本配方名称来源，不改写工作副本、不构造物料名，也不靠截断删除浓度或组成。
- 一格正文含多项配方时，`recipe.entries`的每项可声明`name_transform`和`amount_transform`，分别从该项`name`、`amount`指向的真实原格唯一提取名称和用量。不同物料可以引用同一正文格并使用各自正则；每项提取均保留完整原文及提取规则。仍不得填字面值、改写工作副本、合并重复投料或省略浓度；逐组用量及合计照常核验。
- 原记录是百分含量或混配比例，不能写入g配方时，可用`writes: [{"dst":"C20","composition_parts":[{"name":"A6","amount":"C6"},{"name":"B7","amount":"C7"}]}]`逐项生成该组“组成说明”。名称和加入数全部重读真实来源；有Excel百分格式才显示百分号，其余按原加入数显示，不猜单位、不生成g合计。仅允许测试模板各组第20行，拒绝字面值或作为检测结果合并入口，明细及原数值格式保留核验。
- 每次执行的核验回执位于源指纹目录的`verifications/<plan_sha>-<output_sha>.json`，result.verification引用该独立报告。通用verification.json只是最新缓存，不能将其当所有历史产物的报告；复用时检查源、模板、计划、输出和对应报告指纹。

### 已合入主 skill 的显式能力

- `source_partition: {"id": "唯一分区名", "cells": ["B6", "C6", "..."]}`：本地无隐藏绑定的合成XLSX可把同一源表显式分成单组标准页，保留每组独立日期、方案与逐次投料。每个分区仍直接读取原件坐标和公式缓存；所有引用必须在该分区的单元格清单中，所有分区的已处置单元格合并后必须覆盖整张源表的全部非空单元格。公共标签、工艺可跨分区共享引用；不能用分区省略其他组或额外事实。常规多组矩阵无需分区，共享物料列的重复投料可设置`preserve_repeated_lines=true`并保持多组，各组用量须独立且名称/用量同行、按原行顺序保留。不支持docx、测试模板或系统发布绑定模板的分区。

- `sample_groups`：与 `groups` 等长的源坐标列表。`groups`仍是实验编号，`sample_groups`写样品/方案编号；缺省保持旧的同步行为。
- `client_template_additions`：仅供已人工确认的客户标准模板追加项。每项提供 `label_src`、`after_template`（空模板B列前驱坐标）、`after_label`（精确标签）、`values`（每组源坐标）及 `reason`。仅支持配方合计后的无下游公式、无表格/图表/图片/条件格式模板；自动迁移纵向块合并、后续行坐标、验证、冻结及打印范围。标签直接取源原文，不自动判定语义资格。
- 多阶段工艺可使用 `writes.process_conditions`，每阶段为 `{operation:原操作标签坐标, parameters:[{label:原参数标签坐标,value:原值坐标}]}`，或 `{operation:原操作标签坐标,value:原条件坐标}`。可附 `source_texts` 引用原工艺文字；仅写本组测试方案49行/合成工艺简介36行。执行器逐项读取、保留单位标签并记录来源，不允许常量参数或用于折叠检测观测。
- 未标明指标、含义尚未确认的数列完整保留在客户核对附件，逐对关联原条件和来源；标准实验Excel仅写含义明确、映射完成的数据。不得以“原数列第几对”等位置标签造实验指标，不把来源坐标或待确认问题写进业务字段来通过导入；该部分继续列为未落位/待确认，不计适配完成。
- `recipe.source_total_policy`缺省`strict`，不一致仍报错。只有显式`report_conflicts`且输出位于工作区`_work/`时才允许生成审阅文件；实际用量合计仍真实计算，源合计冲突进入verification和unplaced，状态为`review_required`，不得称为validated正式结果。
- `single_group_test`：源只有一组结果、无配方也无平行样时（例如单一来样测试报告），每张表写 `"single_group_test": {}`，值统一使用同级 `"writes": [{"src": "B3", "dst": "C74"}]` 从当前源表读取；可复用上述显式 `transform`。不再接受旧式 `values` 常量，不能把模型抄写的数值作为原件核对依据。目标仍是**空模板坐标**，只允许 B 列空白标签格、首组 C 列和指定表头 D2/F2/H2，其他组列禁止写入；已有标签和公式不可覆盖。备注区 C138 会落到合并锚点 B138，合并后的重复目标也会拒绝。执行器不扩行、不扩列、不生成合计，其余组列留空；打印范围保留完整模板宽度，覆盖表头和合并区域。配方填 `{"names": [], "amounts": [], "totals": []}`，不能与 `groups/sample_groups` 同时使用。结构标签和未落位项仍通过 `structural/unplaced` 逐项交代。
- docx 原件：`source` 可为 `.docx`，`source_sheet` 用 `table-N`。地址按**真实表格网格**推导——`w:gridSpan` 跨列、`w:vMerge` 纵向合并、嵌套表都会改变列号，“第 3 个单元格”不一定是 C 列。先用执行器的 `_docx_table_views(path)` 打印带地址的视图再写计划。附录段落必须在 `docx_paragraphs` 中逐段交代（`text` 与原文完全一致，`handling` 说明去向，空白段也要列出），否则拒绝执行。

先提取一个代表表，保存字段级决策计划。未知测试项语义判断仍需模型完成，无法只靠执行器自动推出。同一前驱的多项插行、插行下方有公式、带图表/条件格式模板扩容、旧格式原件或通用公式求值仍需适配；明确记录阻塞并只扩展需要的能力。

旧 `merge_sheets.py`、`extend_rows.py` 是历史低层工具，**不作为默认执行路径**；它们不提供完整引用/样式迁移保证。`is_template_copy.py` 仅作结构线索，去括号后的标签相等不能证明测量条件相同。
### 本地测试表的独立重复观测

同一样品和相同条件在原表确有多处观测时，保留原样品名及条件，不按数值去重或编造编号。无系统绑定的本地测试计划可在 `group_identity_role=SAMPLE/PLAN`、`group_condition_refs` 基础上声明 `group_observation_refs`，按组列出独立检测来源坐标。各来源必须非空、彼此不重用，不能引用身份或条件格，并通过直接 `src` 写入本组标准检测区（空模板行62–135）。默认重复校验保持不变；此声明不授予人工审核状态。
