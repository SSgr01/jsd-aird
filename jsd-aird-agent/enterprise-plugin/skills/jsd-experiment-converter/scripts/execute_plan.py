"""Execute a reviewed cell mapping, verify it, and save a restart checkpoint.

Coordinates in writes refer to the pristine template; row expansion is automatic.
All populated source cells must be mapped, explicitly structural, or unplaced.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import time
from pathlib import Path

from workflow import (digest, read_json, write_json, source_path, state_dir, workspace, record_failure,
                      clear_failure, extract_docx, relposix, is_within)

VERSION = 1


def same(a, b):
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return math.isclose(a, b, rel_tol=1e-10, abs_tol=1e-9)
    return a == b


def check_inline_lists(ws):
    for dv in ws.data_validations.dataValidation:
        if dv.type != "list":
            continue
        text = str(dv.formula1 or "")
        if len(text) < 2 or not (text.startswith('"') and text.endswith('"')):
            raise ValueError("下拉来源不是内联列表，需要适配校验：" + str(dv.sqref))
        allowed = text[1:-1].replace('""', '"').split(",")
        for area in dv.ranges.ranges:
            for row in range(area.min_row, area.max_row+1):
                for col in range(area.min_col, area.max_col+1):
                    cell = ws.cell(row, col)
                    if cell.value not in (None, "") and str(cell.value) not in allowed:
                        raise ValueError(f"下拉值不在模板选项中：{ws.title}!{cell.coordinate}={cell.value!r}；允许 {allowed}")


def convert_value(value, transform):
    if not transform:
        return value
    if isinstance(transform, dict):
        if not isinstance(value, str):
            raise ValueError("正则提取需要源文本")
        matches = list(re.finditer(transform["pattern"], value))
        if len(matches) != 1:
            raise ValueError("源文本提取不唯一/未命中：" + transform["pattern"])
        result = matches[0].group(transform.get("group", 1))
        if "value_map" in transform:
            result = transform["value_map"][result]
        return float(result) if transform.get("numeric") else result
    if transform == "remove_remark_prefix":
        return re.sub(r"^备注[：:]\s*", "", value) if isinstance(value, str) else value
    if transform == "percent_to_points":
        if not isinstance(value, (int, float)):
            raise ValueError("percent_to_points 只接受源数值")
        return value * 100
    if transform == "numeric_text":
        if value is None or value == "/":
            return value
        if isinstance(value, str) and not value.strip():
            return None
        if isinstance(value, bool) or not re.fullmatch(r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?", str(value).strip()):
            raise ValueError("配方数字文本不明确，不能推断单位或修正原值：" + repr(value))
        number = float(value)
        if not math.isfinite(number):
            raise ValueError("配方数字必须为有限值")
        return number
    if transform == "customer_charge_g":
        # Explicit opt-in to this customer's confirmed no-charge notation.
        if value is None or str(value).strip() in ('', '/', '-', '－'):
            return 0
        number = convert_value(value, 'numeric_text')
        if number < 0:
            raise ValueError('投料量不能为负数')
        return number
    raise ValueError("不支持的显式转换：" + transform)


def docx_display_addresses(ns, table_element, rows):
    """Rebuild visible A1 addresses of one docx table from its real row grid.

    ``rows`` is workflow.extract_docx's per-row list of cell texts (one entry per
    ``w:tc``).  A physical row may start with a gridSpan-merged cell, may contain
    vertical-merge continuations, and may hold inner tables, so the n-th cell of a
    row is not column n.  Every cell of one table shares the same grid, so the
    widest row defines the stride and merged cells count their span.
    """
    from openpyxl.utils import get_column_letter

    W = "{%s}" % ns["w"]
    trs = table_element.findall("w:tr", ns)
    if len(trs) != len(rows):
        raise ValueError("docx 行数不一致，无法确定单元格地址")
    grid = []
    for tr, cells in zip(trs, rows):
        tcs = tr.findall("w:tc", ns)
        if len(tcs) != len(cells):
            raise ValueError("docx 单元格数不一致，无法确定单元格地址")
        row = []
        for tc, text in zip(tcs, cells):
            span = tc.find("w:tcPr/w:gridSpan", ns)
            n = 1 if tc.findall(".//w:tbl", ns) else (int(span.get(W + "val")) if span is not None else 1)
            vm = tc.find("w:tcPr/w:vMerge", ns)
            continued = vm is not None and vm.get(W + "val") != "restart"
            row.append((n, continued, text))
        grid.append(row)
    stride = max(sum(n for n, _, _ in row) for row in grid)
    addresses = {}
    for r, row in enumerate(grid, 1):
        g = 0
        for n, continued, text in row:
            for offset in range(n):
                addresses[f"{get_column_letter(g+offset+1)}{r}"] = None if continued else text
            g += n
        if g > stride:
            raise ValueError("docx 网格宽度不一致")
    return addresses




def docx_sheet_view(table, addresses):
    """Read-only cell view of one docx table for plans to reference by address."""
    from openpyxl.utils.cell import coordinate_from_string, column_index_from_string, get_column_letter

    class Cell:
        data_type = "s"

        def __init__(self, value, column, row):
            self.value, self.column, self.row = value, column, row
            self.coordinate = f"{get_column_letter(column)}{row}"

    class Sheet:
        title = "table-" + str(table["table"])

        def __init__(self):
            by_row = {}
            for coord, value in addresses.items():
                col, row = coordinate_from_string(coord)
                by_row.setdefault(row, {})[column_index_from_string(col)] = value
            height = max(by_row, default=0)
            width = max((max(v) for v in by_row.values()), default=0)
            self._rows = [[Cell(by_row.get(r, {}).get(c), c, r) for c in range(1, width+1)]
                          for r in range(1, height+1)]

        def __getitem__(self, coord):
            col, row = coordinate_from_string(coord)
            if row < 1 or column_index_from_string(col) < 1:
                raise ValueError("坐标无效：" + coord)
            row_cells = self._rows[row-1] if row <= len(self._rows) else []
            idx = column_index_from_string(col)-1
            if idx < len(row_cells):
                return row_cells[idx]
            return Cell(None, idx+1, row)

        def __iter__(self):
            return iter(self._rows)

    return Sheet()


def _docx_table_views(path):
    """Addressed read-only views of every docx table (nested included) + paragraphs."""
    import zipfile
    from xml.etree import ElementTree as ET

    ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    with zipfile.ZipFile(path) as z:
        document = ET.fromstring(z.read("word/document.xml"))
    extracted = extract_docx(path)
    elements = document.findall(".//w:tbl", ns)
    if len(elements) != len(extracted["tables"]):
        raise ValueError("docx 表格数与提取结果不一致，需要一次适配")
    views = {}
    for element, table in zip(elements, extracted["tables"]):
        key = "table-" + str(table["table"])
        views[key] = docx_sheet_view(table, docx_display_addresses(ns, element, table["rows"]))
    return views, extracted["paragraphs"]


def execute(root, plan_path, output=None):
    plan = read_json(plan_path)
    if plan.get("version") != VERSION:
        raise ValueError("不支持的映射计划版本")
    source = source_path(root, plan["source"])
    kind = plan["kind"]
    if kind not in ("test", "synthesis"):
        raise ValueError("kind 只能是 test/synthesis")
    category = "测试实验" if kind == "test" else "合成实验"
    if relposix(source, root / "原始实验").split("/")[0] != category:
        raise ValueError("计划分类与源目录不一致")
    docx = source.suffix.lower() == ".docx"
    if source.suffix.lower() not in (".xlsx", ".docx"):
        raise ValueError("执行器接受 xlsx 或 docx；.doc 等旧格式先转换并验证副本，不能只改扩展名")
    template = root / "标准模板" / (kind + ".xlsx")
    sha, template_sha = digest(source), digest(template)
    if sha != plan["source_sha256"] or template_sha != plan["template_sha256"]:
        raise ValueError("源文件/模板指纹已变，旧映射不能直接重放；重新提取并核对计划")
    output = Path(output).resolve() if output else root / "转换结果" / category / (source.stem + "-标准化.xlsx")
    # Testing may use _work; production uses the prescribed output category.
    if not any(is_within(output, p) for p in (root / "转换结果" / category, root / "_work")):
        raise ValueError("输出必须位于对应 转换结果/分类/ 或 _work/ 试跑目录")
    job = state_dir(root) / sha
    status_path = job / "status.json"
    plan_sha = digest(plan_path)
    engine_sha = digest(__file__) + digest(Path(__file__).with_name("template_ops.py"))
    status = read_json(status_path) if status_path.exists() else {}
    if (status.get("stage") in ("validated", "review_required") and status.get("plan_sha256") == plan_sha
        and status.get("engine_sha256") == engine_sha and status.get("template_sha256") == template_sha
        and output.is_file() and status.get("output") == relposix(output, root)
        and status.get("output_sha256") == digest(output)):
        return {"cache_hit": True, **status}
    if output.exists():
        raise ValueError("目标已存在且不是可验证的本计划产物；指定新的 --output，不自动覆盖")
    # A resumed job should not pay the spreadsheet dependency import cost again.
    from openpyxl import load_workbook
    from openpyxl.utils.cell import coordinate_to_tuple, get_column_letter
    from openpyxl.workbook.properties import CalcProperties
    from template_ops import (pristine_sheet, expand_recipe, expand_groups, insert_client_test_rows,
                              append_single_group_test, total_formula_and_value, cache_totals, layout)
    wf = None if docx else load_workbook(source, data_only=False)
    wv = None if docx else load_workbook(source, data_only=True)
    wb = load_workbook(template)
    try:
        blank = wb.active
        metadata_sheets = [s for s in wb if s is not blank]
        if blank.sheet_state != 'visible' or any(
            s.title not in ('_JSD_LAB_META', '_JSD_EXPORT_META') or s.sheet_state == 'visible'
            for s in metadata_sheets
        ):
            raise ValueError("模板包含未适配的业务表；仅支持一张业务表及系统隐藏元数据表")
        metadata_values = {s.title: [(c.coordinate, c.value, c.data_type)
                           for row in s for c in row if c.value is not None]
                           for s in metadata_sheets}
        prefix = category
        blank.title = "_pristine"
        selected = [p["source_sheet"] for p in plan["sheets"]]
        if metadata_sheets and len(selected) != 1:
            raise ValueError("系统发布模板的隐藏绑定仅支持一张业务表；多实验请分别输出文件")
        skipped = {s["source_sheet"]: s["reason"] for s in plan.get("skipped", [])}
        if any(not reason for reason in skipped.values()):
            raise ValueError("跳过的源表必须说明原因")
        if docx:
            tables, paragraphs = _docx_table_views(source)
            meaningful = [k for k, s in tables.items() if any(c.value is not None for row in s for c in row)]
        else:
            meaningful = [s.title for s in wf if any(c.value is not None for row in s for c in row)]
        if len(set(selected)) != len(selected) or set(selected) & set(skipped):
            raise ValueError("工作表重复或同时出现在转换/跳过清单")
        if set(selected) | set(skipped) != set(meaningful):
            raise ValueError("每张非空源表必须在 sheets 或 skipped 中明确处置")
        if not selected:
            raise ValueError("没有选择任何待转换工作表")
        if docx:
            declared = plan.get("docx_paragraphs")
            if not isinstance(declared, list) or len(declared) != len(paragraphs):
                raise ValueError("docx 附件段落必须在计划 docx_paragraphs 中逐段交代（含空白段）")
            for item, text in zip(declared, paragraphs):
                if item.get("text") != text or not item.get("handling"):
                    raise ValueError("docx 段落处置与原文不一致：" + text[:30])
        expected, protected, caches, details, unplaced, coverage = {}, {}, {}, [], [], []
        source_conflicts, recipe_conversions, derived_fields = [], [], []
        for index, spec in enumerate(plan["sheets"]):
            name = spec["source_sheet"]
            src = tables[name] if docx else wf[name]
            cached = None if docx else wv[name]
            title = prefix if index == 0 else prefix + "-" + str(index+1)
            ws = pristine_sheet(wb, blank, title)
            used, destinations = set(), set()

            def get(coord):
                if coord == '@source.parent':
                    derived_fields.append({'source': '@source.parent', 'rawValue': source.parent.name,
                        'sourcePath': relposix(source, root), 'status': 'PATH_DERIVED_NOT_SOURCE_SIGNATURE'})
                    return source.parent.name
                used.add(coord)
                cell = src[coord]
                if cell.data_type == "f" and cached[coord].value is None:
                    raise ValueError(f"源公式缺缓存：{name}!{coord}，需要一次重算")
                return cached[coord].value if cell.data_type == "f" else cell.value

            single = spec.get("single_group_test")
            recipe = spec["recipe"]
            if single is not None:
                # No formulation and no parallel samples: keep one group column
                # of the pristine test template; nothing is expanded.
                if kind != "test" or not isinstance(single, dict):
                    raise ValueError("single_group_test 只用于测试模板，使用空对象声明")
                if single or spec.get("groups") or spec.get("sample_groups"):
                    raise ValueError("single_group_test 不接受直接填写的 values 或分组；请使用 writes 的 src/dst 引用原件")
                if recipe.get("names") or recipe.get("amounts") or recipe.get("totals") or recipe.get('entries'):
                    raise ValueError("single_group_test 与配方不能同时使用")
                writes = spec.get("writes")
                if not isinstance(writes, list) or not writes:
                    raise ValueError("单组检测必须通过 writes 的 src/dst 引用原件")
                values = {}
                for item in writes:
                    if (not isinstance(item, dict) or not {"src", "dst"} <= item.keys()
                            or item.keys() - {"src", "dst", "transform"}):
                        raise ValueError("单组 writes 只接受 src、dst 和可选 transform，不接受直接填写的值")
                    row, col = coordinate_to_tuple(item["dst"])
                    coord = f"{get_column_letter(col)}{row}"
                    if coord in values:
                        raise ValueError("重复写目标：" + coord)
                    values[coord] = convert_value(get(item["src"]), item.get("transform"))
                placed = append_single_group_test(ws, values)
                expected[title] = dict(placed)
                protected[title] = {c.coordinate: c.value for row in blank for c in row
                                    if c.value is not None and c.data_type != "f"}
                for item in spec.get("structural", []):
                    if not item.get("reason"):
                        raise ValueError("结构性字段需要 reason")
                    get(item["src"])
                for item in spec.get("unplaced", []):
                    if not item.get("reason"):
                        raise ValueError("未落位字段需要 reason")
                    unplaced.append({"sheet": name, **item, "value": get(item["src"])})
                populated = {c.coordinate for row in src for c in row if c.value is not None}
                missing = sorted(populated-used)
                if missing:
                    raise ValueError(f"{name} 尚有 {len(missing)} 个非空源单元格未处置：{missing[:20]}")
                coverage.append({"sheet": name, "nonempty": len(populated), "accounted": len(populated & used)})
                details.append({**layout(ws, kind), "sheet": title, "source_sheet": name, "groups": 1,
                                "materials": 0, "added_test_items": [], "mode": "single_group_test",
                                "output_last_col": ws.max_column})
                continue
            groups = spec["groups"]
            total_policy = recipe.get('source_total_policy', 'strict')
            if total_policy not in ('strict', 'report_conflicts'):
                raise ValueError('源合计策略只能是strict或report_conflicts')
            if total_policy == 'report_conflicts' and not is_within(output, root / '_work'):
                raise ValueError('源合计冲突的审阅模式只能输出到工作区_work，不能作为正式validated结果')
            # Entries explicitly describe each group's material/value pairs. A
            # missing material stays blank; it is never read from a fake cell.
            if 'entries' in recipe:
                if recipe.get('names') or recipe.get('amounts') or len(recipe['entries']) != len(groups):
                    raise ValueError('逐组entries须对应全部组，不能与names/amounts混用')
                names, labels, amount_maps = [], [], []
                for entries in recipe['entries']:
                    mapping = {}
                    for entry in entries:
                        label = get(entry['name'])
                        if not isinstance(label, str) or not label.strip() or label in mapping:
                            raise ValueError('逐组物料名缺失或重复；分阶段重复投料应保留在历史结构化数据中')
                        mapping[label] = entry['amount']
                        if label not in labels:
                            labels.append(label)
                            names.append(entry['name'])
                    amount_maps.append(mapping)
                amount_refs = [[mapping.get(label) for mapping in amount_maps] for label in labels]
            else:
                names, amount_refs = recipe['names'], recipe['amounts']
                if any(ref is None for row in amount_refs for ref in row):
                    raise ValueError('固定矩阵用量须有真实来源；结构缺项仅由逐组entries表示')
            if not groups or len(amount_refs) != len(names) or any(len(row) != len(groups) for row in amount_refs):
                raise ValueError("配方二维矩阵与组数/物料数不匹配")
            if metadata_sheets:
                published_layout = layout(ws, kind)
                if (len(names) > published_layout['last']-published_layout['first']+1
                    or len(groups) > published_layout['last_col']-published_layout['first_col']+1):
                    raise ValueError("系统发布模板超出固定绑定区域；不能扩容后沿用旧隐藏映射")
            info, map_row = expand_recipe(ws, len(names), kind)
            expand_groups(ws, info, len(groups))
            additions = spec.get('client_template_additions', [])
            if additions and kind != 'test':
                raise ValueError('客户新增测试项仅支持测试模板')
            map_row, added_rows = insert_client_test_rows(ws, info, map_row, additions)
            expected[title] = {}
            protected[title] = {f"{get_column_letter(c.column)}{map_row(c.row)}": c.value
                                for row in blank for c in row if c.value is not None and c.data_type != "f"}

            def put(coord, value, allow_label=False):
                if coord in destinations:
                    raise ValueError(f"重复写目标：{title}!{coord}")
                destinations.add(coord)
                if ws[coord].value is not None and ws[coord].data_type != "f" and not allow_label:
                    raise ValueError(f"拒绝覆盖模板标签：{title}!{coord}")
                # A string starting with '=' in a source text cell stays text.
                ws[coord] = value
                if isinstance(value, str) and value.startswith("="):
                    ws[coord].data_type = "s"
                expected[title][coord] = value

            group_values = [get(c) for c in groups]
            if any(v is None for v in group_values) or len(set(map(str, group_values))) != len(group_values):
                raise ValueError("组编号缺失或重复；需要一次人工判断")
            sample_refs = spec.get('sample_groups', groups)
            if len(sample_refs) != len(groups):
                raise ValueError('样品/方案编号数量与实验分组数量不一致')
            sample_values = [get(c) for c in sample_refs]
            if any(v is None for v in sample_values):
                raise ValueError('样品/方案编号缺失，需要明确分组')
            for i, value in enumerate(group_values):
                col = get_column_letter(info["first_col"]+i)
                put(f"{col}{info['id_row']}", sample_values[i])
                if info["recipe_id_row"] != info["id_row"]:
                    put(f"{col}{info['recipe_id_row']}", value)
                elif sample_values[i] != value:
                    raise ValueError('此模板只有一个编号区，无法表示不同的样品与实验编号')
            for item in added_rows:
                if len(item['values']) != len(groups):
                    raise ValueError('客户追加项的值数与组数不一致')
                label = get(item['label_src'])
                if not isinstance(label, str) or not label.strip():
                    raise ValueError('客户追加项原标签为空或不是文本')
                put(f"B{item['target_row']}", label)
                for i, ref in enumerate(item['values']):
                    put(f"{get_column_letter(info['first_col']+i)}{item['target_row']}", get(ref))
            material_names = [get(c) for c in names]
            if any(v is None for v in material_names):
                raise ValueError("物料名称缺失，需要明确来源")
            if len(set(map(str, material_names))) != len(material_names):
                if not recipe.get('preserve_repeated_lines') or len(groups) != 1 or 'entries' in recipe:
                    raise ValueError("物料名称重复，需单组显式preserve_repeated_lines保留源行，不能自动合并")
                pairs = [(coordinate_to_tuple(n), coordinate_to_tuple(a[0])) for n, a in zip(names, amount_refs)]
                if (any(nr != ar or nc >= ac for (nr, nc), (ar, ac) in pairs)
                    or len({a[1] for _, a in pairs}) != 1
                    or [n[0] for n, _ in pairs] != sorted({n[0] for n, _ in pairs})):
                    raise ValueError('重复投料必须来自同一源列、同一行名称/用量，并保持严格源行顺序')
            for i, label in enumerate(material_names):
                row = info["first"]+i
                put(f"B{row}", label)
                for j, coord in enumerate(amount_refs[i]):
                    raw_value = get(coord) if coord is not None else None
                    value = convert_value(raw_value, recipe.get('amount_transform'))
                    target = f"{get_column_letter(info['first_col']+j)}{row}"
                    put(target, value)
                    if value != raw_value or type(value) is not type(raw_value):
                        recipe_conversions.append({'sheet':name,'src':coord,'raw_value':raw_value,
                            'value':value,'target':target,'transform':recipe.get('amount_transform')})
            for item in spec.get("writes", []):
                r, c = coordinate_to_tuple(item["dst"])
                dst = f"{get_column_letter(c)}{map_row(r)}"
                value = convert_value(get(item["src"]), item.get("transform"))
                put(dst, value)
            for item in spec.get("structural", []):
                if not item.get("reason"):
                    raise ValueError("结构性字段需要 reason")
                get(item["src"])
            for item in spec.get("unplaced", []):
                if not item.get("reason"):
                    raise ValueError("未落位字段需要 reason")
                unplaced.append({"sheet": name, **item, "value": get(item["src"])})
            total_cache = {}
            for col in range(info["first_col"], info["output_last_col"]+1):
                formula, value = total_formula_and_value(ws, col, info["first"], info["last"])
                coord = f"{get_column_letter(col)}{info['total']}"
                ws[coord] = formula
                expected[title][coord] = formula
                total_cache[coord] = value
                offset = col-info["first_col"]
                if offset < len(groups) and recipe.get("totals"):
                    raw_source_total = get(recipe["totals"][offset])
                    source_total = convert_value(raw_source_total, recipe.get('amount_transform'))
                    if source_total is not None and not same(source_total, value):
                        message = f"源合计与配方重算不一致：{name} 组{group_values[offset]}: {source_total} != {value}"
                        if total_policy == 'strict':
                            raise ValueError(message)
                        ref = recipe['totals'][offset]
                        source_conflicts.append({'sheet': name, 'group': group_values[offset], 'src': ref,
                            'source_total': source_total, 'raw_source_total': raw_source_total, 'computed_total': value, 'output_cell': coord,
                            'amount_sources': [row[offset] for row in amount_refs], 'reason': message})
                        unplaced.append({'sheet': name, 'src': ref, 'value': raw_source_total,
                            'reason': message+'；原值不覆盖真实用量合计，需核对原始记录',
                            'suggested_field': '源报告合计-待核对'})
            populated = {c.coordinate for row in src for c in row if c.value is not None}
            missing = sorted(populated-used)
            if missing:
                raise ValueError(f"{name} 尚有 {len(missing)} 个非空源单元格未处置：{missing[:20]}")
            caches[f"xl/worksheets/sheet{index+1}.xml"] = total_cache
            coverage.append({"sheet": name, "nonempty": len(populated), "accounted": len(populated & used)})
            details.append({"sheet": title, "source_sheet": name, "groups": len(groups), "materials": len(names),
                            'added_test_items': added_rows, **info})
        wb.remove(blank)
        # Keep business sheets first: total-cache XML addresses are positional.
        # Published template identity sheets remain unchanged and hidden.
        for metadata_sheet in metadata_sheets:
            wb.move_sheet(metadata_sheet, offset=len(wb.worksheets)-1-wb.index(metadata_sheet))
        wb.active = 0
        wb.calculation = CalcProperties(calcId=0, fullCalcOnLoad=True)
        output.parent.mkdir(parents=True, exist_ok=True)
        candidate = job / "candidate.xlsx"
        candidate.parent.mkdir(parents=True, exist_ok=True)
        wb.save(candidate)
        cache_totals(candidate, caches)
        check = load_workbook(candidate, data_only=False)
        check_values = load_workbook(candidate, data_only=True)
        try:
            for title, cells in metadata_values.items():
                if check[title].sheet_state == 'visible' or cells != [
                    (c.coordinate, c.value, c.data_type)
                    for row in check[title] for c in row if c.value is not None
                ]:
                    raise ValueError("系统隐藏元数据保存后不一致：" + title)
            for title, cells in expected.items():
                for coord, value in cells.items():
                    if not same(check[title][coord].value, value):
                        raise ValueError(f"保存后逐格比对失败：{title}!{coord}")
            for title, cells in protected.items():
                for coord, value in cells.items():
                    if check[title][coord].value != value:
                        raise ValueError(f"静态模板标签改变：{title}!{coord}")
            for i, detail in enumerate(details):
                for coord, value in caches.get(f"xl/worksheets/sheet{i+1}.xml", {}).items():
                    if not same(check_values[detail["sheet"]][coord].value, value):
                        raise ValueError("合计缓存保存后比对失败")
                ws = check[detail["sheet"]]
                check_inline_lists(ws)
                if detail.get("mode") == "single_group_test":
                    from openpyxl.worksheet.cell_range import CellRange
                    from openpyxl.worksheet.print_settings import PrintArea
                    areas = list(PrintArea.from_string(str(ws.print_area)).ranges)
                    if not areas or any(not any(coord in area for area in areas)
                                        for coord in expected[detail["sheet"]]):
                        raise ValueError("单组检测打印范围未覆盖全部写入内容")
                    if any(not any(CellRange(str(merged)).issubset(area) for area in areas)
                           for merged in ws.merged_cells.ranges):
                        raise ValueError("单组检测打印范围截断模板合并区域")
                    continue
                for col in range(detail["first_col"], detail["first_col"]+detail["groups"]):
                    for row in range(detail["first"], detail["last"]+1):
                        addr = f"{get_column_letter(col)}{row}"
                        if not any(addr in dv.sqref and dv.type == "custom" for dv in ws.data_validations.dataValidation):
                            raise ValueError("配方验证范围缺失：" + addr)
        finally:
            check.close()
            check_values.close()
        report = {"coverage": coverage, "sheets": details, "unplaced": unplaced,
                  "status": "review_required" if source_conflicts else "validated",
                  "source_sha256": sha, "template_sha256": template_sha,
                  "plan_sha256": plan_sha, "output_sha256": digest(candidate),
                  "human_review": "pending",
                  'source_conflicts': source_conflicts, 'recipe_conversions': recipe_conversions,
                  'derived_fields': derived_fields,
                  "limitations": "逐格核对验证计划的执行，不证明人工语义映射正确；仅对生成的数字 SUM 合计构建缓存，不是通用公式引擎。"}
        write_json(job / "verification.json", report)
        os.replace(candidate, output)
        result = {"stage": "review_required" if source_conflicts else "validated", "source": relposix(source, root),
                  "source_sha256": sha, "template_sha256": template_sha, "plan_sha256": plan_sha,
                  "engine_sha256": engine_sha, "output": relposix(output, root),
                  "output_sha256": digest(output), "sheets": len(details), "unplaced_cells": len(unplaced),
                  'source_conflicts': len(source_conflicts),
                  "verification": relposix(job / "verification.json", root)}
        write_json(status_path, result)
        clear_failure(root, plan["source"], "execute")
        return {"cache_hit": False, **result}
    finally:
        if wf is not None:
            wf.close()
        if wv is not None:
            wv.close()
        wb.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root")
    parser.add_argument("--plan", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()
    started = time.perf_counter()
    try:
        result = execute(workspace(args.root), args.plan, args.output)
        result["elapsed_seconds"] = round(time.perf_counter()-started, 3)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except Exception as e:
        try:
            record_failure(workspace(args.root), read_json(args.plan)["source"], "execute", e)
        except Exception:
            pass
        print(json.dumps({"ok": False, "error": str(e), "action": "修正计划或记录阻塞；不要反复重建转换器"}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
