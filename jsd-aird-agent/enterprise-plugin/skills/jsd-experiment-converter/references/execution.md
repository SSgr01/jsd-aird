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
- `writes.dst` 使用**扩容前的空模板坐标**，执行器负责把合计行及后面的目标行往下移。配方和组编号由专用字段写，别再在 writes 重复写它们。模板标签不可覆盖。
- 同一源可以写到多处，例如实验编号需同步两行；同一目标不能被多个源覆盖。
- `structural` 逐个列源标签并说明；不要把未读懂的数字或观察放进它。`unplaced` 保留原值及原因；未交代的非空源单元格会报错。
- 输入含公式时使用原件缓存值；缺缓存会阻塞，不能改读公式字符串假装数值。
- 配方用量接受数值、空白、`/`，其他单位文本需要明确的转换适配。写入的普通源文本即使以 `=` 开头，也按文本保存。
- 写入保持目标模板格式，源百分比数值通常不需要乘100；只有明确单位转换才使用 `percent_to_points`。
- 已提供 `remove_remark_prefix` 转换，以及 `{ "pattern": "UVA能量：([0-9.]+)", "numeric": true }` 正则提取。提取需唯一命中；可用 value_map 显式声明等价编码。不要用正则决定两个不同测试项是否同义。

## 当前文件的计划

运行资源不包含历史业务样例计划。先检查当前源文件，为它独立生成映射计划，并核对源和模板指纹。不要按文件名或相似版式复用其他文件的数据与映射。

```text
python -X utf8 scripts/execute_plan.py --root ROOT --plan ROOT/.dsh/cache/jsd-experiment-converter/plans/current.json --output ROOT/_work/current-review.xlsx
```

执行器写入后重开逐格核对，校验缓存与配方；完整结构检查见 qa.md。状态保存到源指纹目录 status.json。仅在计划、模板、执行器和输出指纹均相同时复用结果，已有不同结果不覆盖。

## 加新格式时

### 已合入主 skill 的显式能力

- `sample_groups`：与 `groups` 等长的源坐标列表。`groups`仍是实验编号，`sample_groups`写样品/方案编号；缺省保持旧的同步行为。
- `client_template_additions`：仅供已人工确认的客户标准模板追加项。每项提供 `label_src`、`after_template`（空模板B列前驱坐标）、`after_label`（精确标签）、`values`（每组源坐标）及 `reason`。仅支持配方合计后的无下游公式、无表格/图表/图片/条件格式模板；自动迁移纵向块合并、后续行坐标、验证、冻结及打印范围。标签直接取源原文，不自动判定语义资格。
- `recipe.source_total_policy`缺省`strict`，不一致仍报错。只有显式`report_conflicts`且输出位于工作区`_work/`时才允许生成审阅文件；实际用量合计仍真实计算，源合计冲突进入verification和unplaced，状态为`review_required`，不得称为validated正式结果。
- `single_group_test`：源只有一组结果、无配方也无平行样时（例如单一来样测试报告），每张表写 `"single_group_test": {"values": {"C74": "8H", ...}}`，坐标仍是**空模板坐标**；只允许写 B 列标签行、C 起的组值格和表头值区（D2/F2/H2）。备注区写 `C138` 会落到合并锚点 B138。执行器从空模板底稿只填一组，不扩行、不扩列、不生成合计，其余分组列留空，打印区收窄到 C 列；配方填 `{"names": [], "amounts": [], "totals": []}`，不能与 `groups` 同时使用。
- docx 原件：`source` 可为 `.docx`，`source_sheet` 用 `table-N`。地址按**真实表格网格**推导——`w:gridSpan` 跨列、`w:vMerge` 纵向合并、嵌套表都会改变列号，“第 3 个单元格”不一定是 C 列。先用执行器的 `_docx_table_views(path)` 打印带地址的视图再写计划。附录段落必须在 `docx_paragraphs` 中逐段交代（`text` 与原文完全一致，`handling` 说明去向，空白段也要列出），否则拒绝执行。

先提取一个代表表，保存字段级决策计划。未知测试项语义判断仍需模型完成，无法只靠执行器自动推出。同一前驱的多项插行、插行下方有公式、带图表/条件格式模板扩容、旧格式原件或通用公式求值仍需适配；明确记录阻塞并只扩展需要的能力。

旧 `merge_sheets.py`、`extend_rows.py` 是历史低层工具，**不作为默认执行路径**；它们不提供完整引用/样式迁移保证。`is_template_copy.py` 仅作结构线索，去括号后的标签相等不能证明测量条件相同。
