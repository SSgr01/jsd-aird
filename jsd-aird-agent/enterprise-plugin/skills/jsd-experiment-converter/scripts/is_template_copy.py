# -*- coding: utf-8 -*-
"""判断源工作表是否为标准模板的填写稿，并找出客户自行追加的测试项。

规则依据：decisions.md 规则 25

两个判据，缺一不可
------------------
1. **区块指纹**：区块标题按模板顺序出现（表头 → 树脂物性 → 实验配方 →
   涂层性能 → 实验结论）。允许压缩、允许块内乱序、允许缺块。
   不要用"整表标签吻合率"——真实客户文件即使是从模板填的，吻合率也只有
   6–31%（各版本模板差异），用它会把要判的文件全判成自制表。

2. **客户追加行**：源表有、模板没有的行，且必须有数据、落在测试项区域内。
   匹配必须"精确优先、去括号后相等其次"，**绝不能用子串包含**：
   `自由基体系水接触角` 包含 `水接触角`，用包含判定会把它误当成模板已有，
   而那恰恰是要追加的那一行。

用法
----
    from is_template_copy import is_template_copy, find_customer_added_rows
    ok, blocks = is_template_copy(ws)
    if ok:
        added = find_customer_added_rows(ws, tpl_ws, n_groups=11)

    # added = [(row, label, n_values, block_title_or_None), ...]
"""
import re

# 模板的区块顺序（相对顺序即可，允许缺块）
BLOCK_SEQ = ["实验题目", "实验条件", "树脂物性", "实验方案", "实验配方",
             "涂料物性", "涂层性能", "实验结论", "备注"]

# 区块标题本身不是测试项
BLOCK_TITLES = {"实验条件", "树脂物性", "实验方案", "实验配方",
                "涂料物性", "涂层性能", "实验结论", "备注"}

# 配方表内的行不是测试项
RECIPE_ANCHORS = ("实验编号", "样品/方案编号")
TOTAL_PREFIXES = ("用量合计", "合计", "质量合计")


def norm(v):
    if not isinstance(v, str):
        return ""
    return re.sub(r"\s+", "", v)


def strip_paren(v):
    """去掉括号补充，"溶解性（S-5稀释至10%)" -> "溶解性"。

    括号里通常是稀释比例、单位、口径说明，不构成不同的测试项；
    但基名不同（自由基体系水接触角 vs 水接触角）必须区分，所以只去括号。
    """
    return re.sub(r"[（(][^）)]*[）)]", "", norm(v))


def _blocks_present(ws):
    """按出现顺序返回区块标题列表。"""
    found = []
    for r in range(1, ws.max_row + 1):
        for c in (1, 2):
            t = norm(ws.cell(row=r, column=c).value)
            if not t:
                continue
            for kw in BLOCK_SEQ:
                if kw in t and not any(f == kw for f in found):
                    found.append(kw)
    return found


def is_template_copy(ws, min_blocks=4):
    """区块指纹判定。返回 (是否模板稿, 命中的区块列表)。"""
    got = _blocks_present(ws)
    idx = 0
    matched = []
    for kw in got:
        if kw in BLOCK_SEQ[idx:]:
            idx = BLOCK_SEQ.index(kw, idx) + 1
            matched.append(kw)
    return len(matched) >= min_blocks, matched


def _recipe_range(ws, group_row_hint=None):
    """找配方区 (首行, 末行)；找不到返回 (None, None)。"""
    idr = tot = None
    for r in range(1, ws.max_row + 1):
        t = norm(ws.cell(row=r, column=2).value) or norm(ws.cell(row=r, column=3).value)
        if t in RECIPE_ANCHORS:
            idr = r
        if any(t.startswith(p) for p in TOTAL_PREFIXES):
            tot = r if idr else tot
    return idr, tot


def find_customer_added_rows(ws, tpl_ws, n_groups, data_start_col=3):
    """找出客户在模板里自行追加的测试项行。

    ws          源工作表（已判定为模板稿）
    tpl_ws      标准模板同类型的工作表
    n_groups    源表组数，用于判断"该行是否有数据"
    data_start_col  数据起始列（测试模板一般 C=3）

    返回 [(row, label, n_values, block_title)]；block_title 为该行上方最近的区块标题。
    """
    tpl_exact, tpl_core = set(), set()
    for r in range(1, tpl_ws.max_row + 1):
        t = norm(tpl_ws.cell(row=r, column=2).value)
        if t:
            tpl_exact.add(t)
            tpl_core.add(strip_paren(t))

    idr, tot = _recipe_range(ws)
    concl = None
    for r in range(1, ws.max_row + 1):
        if norm(ws.cell(row=r, column=1).value) == "实验结论":
            concl = r
            break

    out = []
    cur_block = None
    for r in range(1, ws.max_row + 1):
        a = norm(ws.cell(row=r, column=1).value)
        b = norm(ws.cell(row=r, column=2).value)

        if a in BLOCK_SEQ:
            cur_block = a

        if not b:
            continue
        if b in BLOCK_TITLES:
            continue
        # 表头区（配方标题之前）不视为测试项
        if idr and r < idr:
            continue
        # 配方物料行
        if idr and tot and idr < r < tot:
            continue
        # 结论/备注区
        if concl and r >= concl:
            continue

        if b in tpl_exact:
            continue
        if strip_paren(b) in tpl_core:
            continue

        nv = sum(1 for c in range(data_start_col, data_start_col + n_groups)
                 if ws.cell(row=r, column=c).value is not None)
        if nv == 0:
            continue
        out.append((r, b, nv, cur_block))
    return out
