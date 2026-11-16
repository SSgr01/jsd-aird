# -*- coding: utf-8 -*-
"""通用能力：把客户在模板里自行追加的测试项，忠实还原到产物。

**前置条件（本模块不判断，调用方必须判断）**
--------------------------------------------
只有**源工作表本身就是标准模板的填写稿**，且客户在该模板里自己追加了这一行，
才允许追加。此时追加是"转录客户的既有决定"。
从自制表里冒出来的、模板没有的测试项**不应**用本模块追加——那是我们在改模板，
不是转换，应进未落位数据清单。判据见 decisions.md 规则 25 的 gate 1。

设计要点
--------
1. 位置跟随客户：还原源表里的相对位置（同一个前驱行之后）。
   客户的原位放不下时才退回到区块最后一行。
2. 区块按 A 列标签查找，不写死坐标（配方行扩展会让整个区块下移）。
3. 新行继承上一行的样式与行高，保证打印观感一致。
4. 插入行会让其后所有行号 +1，因此本模块在"填充数据之前"执行，
   返回 old_row -> new_row 的映射供后续落位使用。

用法：
    from extend_rows import find_block_bounds, last_labelled_row, insert_test_row
    bf, bl = find_block_bounds(ws, block_title_row)
    new_row = insert_test_row(ws, after_row, bf, bl, label)
"""
import copy
import re

from openpyxl.utils import get_column_letter


def _merge_list(ws):
    return [str(m) for m in ws.merged_cells.ranges]


def _row_label(ws, r, col=2):
    v = ws.cell(row=r, column=col).value
    if isinstance(v, str):
        return re.sub(r"\s+", "", v)
    return ""


def find_block_bounds(ws, anchor_row, anchor_col=1):
    """返回 anchor_row 所在 A 列的区块行范围。

    模板的分区标题（涂层性能、树脂物性…）是 A 列的**纵向单列合并**，
    形如 `A64:A135`，即 min_col == max_col == 1。早期误写成 `max_col > 1`
    会把这些合并区全部排除，导致区块末行被当成标题行本身。
    """
    for m in ws.merged_cells.ranges:
        if m.min_col == anchor_col and m.max_col == anchor_col and m.min_row <= anchor_row <= m.max_row:
            return m.min_row, m.max_row
    return anchor_row, anchor_row


def last_labelled_row(ws, r1, r2, col=2):
    """区块内最后一个 B 列有标签的行。"""
    last = r1
    for r in range(r1, r2 + 1):
        if _row_label(ws, r, col):
            last = r
    return last


def insert_test_row(ws, after_row, block_first, block_last, label, template_row=None):
    """在 after_row 之后插入一行测试项，标签写入 B 列。

    template_row: 用作样式/行高来源的行号；默认为 after_row。
    返回插入后的行号。
    """
    src = template_row if template_row is not None else after_row
    new_row = after_row + 1
    merges = _merge_list(ws)
    for m in merges:
        ws.unmerge_cells(m)

    ws.insert_rows(new_row, 1)

    max_col = ws.max_column
    for ci in range(1, max_col + 1):
        s = ws.cell(row=src, column=ci)
        d = ws.cell(row=new_row, column=ci)
        d._style = copy.copy(s._style)
    # 行高
    h = ws.row_dimensions[src].height
    if h is not None:
        ws.row_dimensions[new_row].height = h

    # 重建合并：行号 >= new_row 的下移 1；A 列区块合并区扩一行
    for m in merges:
        mm = re.match(r"([A-Z]+)(\d+):([A-Z]+)(\d+)$", m)
        c1, r1, c2, r2 = mm.group(1), int(mm.group(2)), mm.group(3), int(mm.group(4))
        if c1 == "A" and r1 == block_first and r2 == block_last:
            # 涂层性能等区块：末行 +1，把新行纳入
            ws.merge_cells("%s%d:%s%d" % (c1, r1, c2, r2 + 1))
            continue
        if r1 >= new_row:
            r1 += 1
        if r2 >= new_row:
            r2 += 1
        ws.merge_cells("%s%d:%s%d" % (c1, r1, c2, r2))

    ws.cell(row=new_row, column=2).value = label
    return new_row


def plan_and_insert(ws, extra_labels, block_anchor_row, label_col=2, start_after=None):
    """批量插入新测试项，返回 {原行号: 新行号} 映射与新增项清单。

    extra_labels: [(label, preferred_after_row_or_None), ...]
    全部追加到区块最后一个已有标签行之后。
    """
    block_first, block_last = find_block_bounds(ws, block_anchor_row)
    if start_after is None:
        start_after = last_labelled_row(ws, block_first, block_last, label_col)

    mapping = {}
    added = []
    cursor = start_after
    for label, _pref in extra_labels:
        new_row = insert_test_row(ws, cursor, block_first, block_last, label)
        added.append((label, new_row))
        # 后续行号整体下移
        for k in list(mapping):
            if mapping[k] >= new_row:
                mapping[k] += 1
        cursor = new_row
    return mapping, added


def remap(mapping, old_row):
    return mapping.get(old_row, old_row)
