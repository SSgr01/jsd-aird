"""Deterministic operations for the supplied JSD templates; no semantic mapping."""
from copy import copy, deepcopy
from decimal import Decimal
from math import fsum
from pathlib import Path
import os
import zipfile
from xml.etree import ElementTree as ET

from openpyxl.worksheet.cell_range import CellRange
from openpyxl.utils import get_column_letter
from openpyxl.utils.cell import coordinate_to_tuple


def norm(value):
    return "".join(str(value or "").split())


def find_label(ws, text, col=None):
    hits = [c for row in ws for c in row if (col is None or c.column == col) and norm(c.value) == norm(text)]
    if len(hits) != 1:
        raise ValueError(f"模板标签不唯一/不存在：{text}: {[c.coordinate for c in hits]}")
    return hits[0]


def layout(ws, kind):
    total_label = "用量合计（g）" if kind == "test" else "质量合计（g）"
    total = find_label(ws, total_label, 2).row
    spans = [r for dv in ws.data_validations.dataValidation if dv.type == "custom"
             for r in dv.ranges.ranges if r.max_row == total-1]
    if len(spans) != 1:
        raise ValueError("配方验证区域与合计行不匹配；模板已改变，需要一次适配")
    area = spans[0]
    return {"first": area.min_row, "last": area.max_row, "total": total,
            "first_col": area.min_col, "last_col": area.max_col,
            "id_row": find_label(ws, "样品/方案编号" if kind == "test" else "实验编号").row,
            "recipe_id_row": find_label(ws, "实验编号").row}


def pristine_sheet(wb, blank, title):
    # The donor remains blank for the whole job; every output starts from it.
    ws = wb.copy_worksheet(blank)
    ws.title = title
    for attr in ("data_validations", "conditional_formatting", "sheet_properties", "sheet_format",
                 "page_setup", "page_margins", "print_options"):
        setattr(ws, attr, deepcopy(getattr(blank, attr)))
    ws.views = deepcopy(blank.views)
    ws.freeze_panes = blank.freeze_panes
    ws.print_area = str(blank.print_area).split("!", 1)[-1] if blank.print_area else None
    ws.print_title_rows = blank.print_title_rows
    ws.print_title_cols = blank.print_title_cols
    return ws


def expand_recipe(ws, count, kind):
    info = layout(ws, kind)
    extra = max(0, count - (info["last"] - info["first"] + 1))
    at = info["total"]
    if not extra:
        return info, lambda row: row
    if ws.tables or ws._charts or ws._images or len(ws.conditional_formatting):
        raise ValueError("模板含表格/图表/图片/条件格式，需要专门的扩容适配")
    merges = [CellRange(str(r)) for r in ws.merged_cells.ranges]
    for r in list(ws.merged_cells.ranges):
        ws.unmerge_cells(str(r))
    heights = {r: copy(dim) for r, dim in ws.row_dimensions.items()}
    ws.insert_rows(at, extra)
    for r in list(ws.row_dimensions):
        del ws.row_dimensions[r]
    for r, dim in heights.items():
        nr = r+extra if r >= at else r
        dim.index = nr
        ws.row_dimensions[nr] = dim
    for r in range(at, at+extra):
        ws.row_dimensions[r] = copy(ws.row_dimensions[at-1])
        ws.row_dimensions[r].index = r
        for c in range(1, ws.max_column+1):
            ws.cell(r, c)._style = copy(ws.cell(at-1, c)._style)
    for rg in merges:
        if rg.min_row >= at:
            rg.shift(row_shift=extra)
        elif rg.max_row >= at-1:
            # Includes synthesis's A10:A23 recipe block, ending just before total.
            rg.max_row += extra
        ws.merge_cells(str(rg))
    for dv in ws.data_validations.dataValidation:
        updated = []
        for rg in dv.ranges.ranges:
            rg = CellRange(str(rg))
            if rg.min_row >= at:
                rg.shift(row_shift=extra)
            elif rg.max_row >= at-1:
                rg.max_row += extra
            updated.append(str(rg))
        dv.sqref = " ".join(updated)
    # Only the recipe totals are formula-bearing in the supported templates.
    if any(c.data_type == "f" and c.row != at+extra for row in ws for c in row):
        raise ValueError("模板新增了配方合计以外的公式；拒绝猜测行引用")
    if ws.freeze_panes:
        p = ws[ws.freeze_panes]
        if p.row >= at:
            ws.freeze_panes = f"{get_column_letter(p.column)}{p.row+extra}"
    info.update(last=info["last"]+extra, total=at+extra)
    return info, lambda row: row+extra if row >= at else row


def expand_groups(ws, info, count):
    end = info["first_col"] + count - 1
    old = info["last_col"]
    if end > old:
        for c in range(old+1, end+1):
            letter = get_column_letter(c)
            ws.column_dimensions[letter] = copy(ws.column_dimensions[get_column_letter(old)])
            ws.column_dimensions[letter].index = letter
            for r in range(1, ws.max_row+1):
                ws.cell(r, c)._style = copy(ws.cell(r, old)._style)
        merges = [CellRange(str(r)) for r in ws.merged_cells.ranges]
        for rg in merges:
            if rg.max_col == old and rg.min_col < old:
                ws.unmerge_cells(str(rg))
                rg.max_col = end
                ws.merge_cells(str(rg))
        for dv in ws.data_validations.dataValidation:
            updated = []
            for rg in dv.ranges.ranges:
                rg = CellRange(str(rg))
                if rg.max_col == old:
                    rg.max_col = end
                updated.append(str(rg))
            dv.sqref = " ".join(updated)
    info["output_last_col"] = max(end, old)
    ws.print_area = f"A1:{get_column_letter(info['output_last_col'])}{ws.max_row}"


def insert_client_test_rows(ws, info, base_map, items):
    """Insert explicitly reviewed client additions after pristine-template labels.

    Limited to rows below the recipe totals and formula-free following content.
    Semantic eligibility belongs to the reviewed plan, never label guessing.
    """
    if not items:
        return base_map, []
    if ws.tables or ws._charts or ws._images or len(ws.conditional_formatting):
        raise ValueError("客户测试项插行不支持含表格/图表/图片/条件格式的模板")
    anchors = []
    for item in items:
        row, col = coordinate_to_tuple(item['after_template'])
        if col != 2 or not item.get('reason') or not item.get('after_label'):
            raise ValueError("客户新增测试项需B列前驱坐标、原标签和明确理由")
        if base_map(row) < info['total'] or ws.cell(base_map(row), 2).value != item['after_label']:
            raise ValueError("客户新增项前驱必须是配方后已核对的模板标签")
        anchors.append(row)
    if len(set(anchors)) != len(anchors):
        raise ValueError("同一前驱追加多项暂需明确适配")
    placements = []
    for offset, (anchor, item) in enumerate(sorted(zip(anchors, items), key=lambda x: x[0])):
        at = base_map(anchor)+offset+1
        if any(c.data_type == 'f' and c.row >= at for row in ws for c in row):
            raise ValueError("客户测试项插行下方有公式，需要专门引用迁移")
        merges = [CellRange(str(r)) for r in ws.merged_cells.ranges]
        for rg in list(ws.merged_cells.ranges):
            ws.unmerge_cells(str(rg))
        heights = {r: copy(dim) for r, dim in ws.row_dimensions.items()}
        ws.insert_rows(at)
        for row in list(ws.row_dimensions):
            del ws.row_dimensions[row]
        for row, dim in heights.items():
            target = row+1 if row >= at else row
            dim.index = target
            ws.row_dimensions[target] = dim
        ws.row_dimensions[at] = copy(ws.row_dimensions[at-1])
        ws.row_dimensions[at].index = at
        for col in range(1, ws.max_column+1):
            ws.cell(at, col)._style = copy(ws.cell(at-1, col)._style)
        for rg in merges:
            if rg.min_row >= at:
                rg.shift(row_shift=1)
            elif rg.max_row >= at:
                rg.max_row += 1
            ws.merge_cells(str(rg))
        for dv in ws.data_validations.dataValidation:
            ranges = []
            for rg in dv.ranges.ranges:
                rg = CellRange(str(rg))
                if rg.min_row >= at:
                    rg.shift(row_shift=1)
                elif rg.max_row >= at:
                    rg.max_row += 1
                ranges.append(str(rg))
            dv.sqref = ' '.join(ranges)
        if ws.freeze_panes and ws[ws.freeze_panes].row >= at:
            pane = ws[ws.freeze_panes]
            ws.freeze_panes = f'{get_column_letter(pane.column)}{pane.row+1}'
        placements.append(dict(item, target_row=at))
    ws.print_area = f"A1:{get_column_letter(info['output_last_col'])}{ws.max_row}"
    return lambda row: base_map(row)+sum(anchor < row for anchor in anchors), placements


def append_single_group_test(ws, values):
    """Keep one group column of an otherwise untouched test template.

    For test records that have results but no formulation and no parallel
    samples (e.g. a single client sample).  The template spans six group
    columns; only the first one carries data.  Removing columns is not
    supported by openpyxl's merge tracking, so the other group columns are
    kept blank and the print area is narrowed to the used column.

    ``values`` maps pristine-template coordinates to values.  Labels live in
    column B and group values in the group columns; header item fields
    (D2/F2/H2) may be written too.  Formula cells are left alone.
    Returns {coordinate: value} actually written.
    """
    info = layout(ws, "test")
    first = info["first_col"]
    remap = {"C138": "B138"}  # merged remarks block anchored at B138
    allowed = {2} | set(range(first, ws.max_column+1))
    written = {}
    for coord, value in values.items():
        row, col = coordinate_to_tuple(coord)
        if col not in allowed:
            raise ValueError("单组测试记录只能写入标签列与分组列：" + coord)
        if not 1 <= row <= ws.max_row:
            raise ValueError("目标行超出模板：" + coord)
        cell = ws[coord]
        if cell.data_type == "f":
            raise ValueError("拒绝覆盖模板公式：" + coord)
        if cell.value is not None:
            raise ValueError("拒绝覆盖模板标签：" + coord)
        if value is None:
            continue
        coord = remap.get(coord, coord)
        cell = ws[coord]
        if cell.data_type == "f":
            raise ValueError("拒绝覆盖模板公式：" + coord)
        if cell.value is not None:
            raise ValueError("拒绝覆盖模板标签：" + coord)
        cell.value = value
        if isinstance(value, str) and value.startswith("="):
            cell.data_type = "s"
        written[coord] = value
    ws.print_area = f"A1:{get_column_letter(first)}{ws.max_row}"
    return written


def total_formula_and_value(ws, col, first, last):
    values = [ws.cell(r, col).value for r in range(first, last+1)]
    for v in values:
        if v is not None and v != "/" and (isinstance(v, bool) or not isinstance(v, (int, float))):
            raise ValueError(f"配方用量须为数字、空白或 /；发现 {v!r}；需在计划中明确单位转换")
        if isinstance(v, (int, float)) and v < 0:
            raise ValueError("配方用量不能为负")
    span = f"{get_column_letter(col)}{first}:{get_column_letter(col)}{last}"
    formula = f'=IF(COUNTA({span})=0,"",SUM({span}))'
    if all(v is None for v in values):
        return formula, None
    nums = [v for v in values if isinstance(v, (int, float))]
    # Separate Decimal and binary-float computations guard cache construction.
    value = float(sum((Decimal(str(v)) for v in nums), Decimal(0)))
    if abs(value-fsum(nums)) > 1e-8:
        raise ValueError("合计独立重算不一致")
    return formula, value


def cache_totals(path, caches):
    ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    path = Path(path)
    tmp = path.with_suffix(".cache-tmp.xlsx")
    try:
        with zipfile.ZipFile(path) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
            for item in src.infolist():
                data = src.read(item.filename)
                if item.filename in caches:
                    xml = ET.fromstring(data)
                    for cell in xml.findall(f".//{{{ns}}}c"):
                        coord = cell.get("r")
                        if coord not in caches[item.filename]:
                            continue
                        if cell.find(f"{{{ns}}}f") is None:
                            raise ValueError("拒绝给非公式单元格注入缓存：" + coord)
                        value = caches[item.filename][coord]
                        node = cell.find(f"{{{ns}}}v")
                        if node is None:
                            node = ET.SubElement(cell, f"{{{ns}}}v")
                        node.text = None if value is None else str(value)
                    data = ET.tostring(xml, encoding="utf-8")
                dst.writestr(item, data)
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)
