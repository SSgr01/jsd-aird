#!/usr/bin/env python3
"""Structural audit for JSD standardized experiment Excel workbooks.

This checks structural invariants only. It does NOT validate field mappings or
completeness of all original measurements; those require independent data review.
"""

import argparse
import json
import re
import sys
from pathlib import Path

from openpyxl import load_workbook
from openpyxl.utils import get_column_letter, column_index_from_string

# Windows 控制台默认 GBK，报告里的中文表名/标签会乱码或被下游解析失败。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

TOTAL_LABELS = {
    'test': '\u7528\u91cf\u5408\u8ba1',        # 用量合计
    'synthesis': '\u8d28\u91cf\u5408\u8ba1',   # 质量合计
}
RECIPE_START = {'test': 52, 'synthesis': 10}
ID_ROW = {'test': 7, 'synthesis': 9}
# Worksheet titles must be standard-template copies only (Chinese names).
ALLOWED_SHEET_PREFIXES = {
    'test': ('\u6d4b\u8bd5\u5b9e\u9a8c',),          # 测试实验
    'synthesis': ('\u5408\u6210\u5b9e\u9a8c',),     # 合成实验
}
# Any sheet whose title starts with one of these is a forbidden helper/report sheet.
HELPER_TITLE_PREFIXES = (
    '\u660e\u7ec6',                 # 明细
    '\u5b57\u6bb5\u6765\u6e90',      # 字段来源
    '\u539f\u59cb\u8bb0\u5f55',      # 原始记录
    '\u7248\u672c',                 # 版本
    '\u6821\u9a8c',                 # 校验
    '\u8f6c\u6362\u8bf4\u660e',      # 转换说明
    '\u914d\u65b9\u660e\u7ec6',      # 配方明细
    '\u793a\u4f8b',                 # 示例
    'Sheet',                       # Sheet1 / Sheet2 helper leftovers
)


def find_total_row(ws, kind):
    label = TOTAL_LABELS[kind]
    for row in range(RECIPE_START[kind] + 1, ws.max_row + 1):
        val = ws.cell(row, 2).value
        if isinstance(val, str) and label in val:
            return row
    return None


def has_validation(ws, cell_address, dv_type=None):
    if not ws.data_validations:
        return False
    for dv in ws.data_validations.dataValidation:
        if dv_type and dv.type != dv_type:
            continue
        if cell_address in dv.sqref:
            return True
    return False


def print_covers(ws, final_col, final_row):
    text = str(ws.print_area or '')
    if not text:
        return False
    ends = re.findall(r':\$?([A-Z]{1,3})\$?(\d+)', text)
    if not ends:
        return False
    return any(column_index_from_string(col) >= final_col and int(row) >= final_row
               for col, row in ends)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=['test', 'synthesis'], required=True)
    parser.add_argument('--template', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expected-sheets', type=int, default=0)
    parser.add_argument('--min-groups', type=int, default=0)
    parser.add_argument('--min-recipe-lines', type=int, default=0)
    args = parser.parse_args()

    failures = []
    warnings = []
    details = []
    try:
        template_wb = load_workbook(args.template, data_only=False)
        output_wb = load_workbook(args.output, data_only=False)
        output_cached_wb = load_workbook(args.output, data_only=True)
    except Exception as exc:
        print(json.dumps({'passed': False, 'failures': [f'Cannot read workbook: {exc}']},
                         ensure_ascii=False, indent=2))
        return 2

    template = template_wb.active
    if args.expected_sheets and len(output_wb.worksheets) != args.expected_sheets:
        failures.append(f'Expected {args.expected_sheets} output sheets, found {len(output_wb.worksheets)}')

    if not output_wb.worksheets:
        failures.append('Output workbook has no sheets')

    for ws in output_wb.worksheets:
        item = {'sheet': ws.title, 'recipe_rows': 0, 'groups': 0, 'formula_count': 0}
        # 只允许标准模板副本；任何辅助/报表/示例表都视为失败
        allowed = ALLOWED_SHEET_PREFIXES[args.kind]
        if any(ws.title.startswith(p) for p in HELPER_TITLE_PREFIXES) or \
                not any(ws.title.startswith(p) for p in allowed):
            failures.append(
                f'{ws.title}: not a standard template sheet copy '
                f'(expected title starting with one of {allowed})')

        start = RECIPE_START[args.kind]
        total = find_total_row(ws, args.kind)
        if total is None:
            failures.append(f'{ws.title}: recipe total row not found')
            details.append(item)
            continue
        end = total - 1
        item['total_row'] = total
        item['recipe_rows'] = len([r for r in range(start, total)
                                  if ws.cell(r, 2).value not in (None, '')])
        if item['recipe_rows'] < args.min_recipe_lines:
            failures.append(f'{ws.title}: only {item["recipe_rows"]} labeled ingredients; expected at least {args.min_recipe_lines}')

        ids = []
        for col in range(3, ws.max_column + 1):
            value = ws.cell(ID_ROW[args.kind], col).value
            if value is not None and str(value).strip():
                ids.append((col, str(value).strip()))
        item['groups'] = len(ids)
        if len(ids) < args.min_groups:
            failures.append(f'{ws.title}: only {len(ids)} labeled groups; expected at least {args.min_groups}')
        if len(set(value for _, value in ids)) != len(ids):
            failures.append(f'{ws.title}: repeated group identifiers within one output sheet')

        # Check that formulas cover every used group and include *all* recipe rows.
        for col, group_id in ids:
            letter = get_column_letter(col)
            formula = ws.cell(total, col).value
            if ws.cell(total, col).data_type != 'f' or not isinstance(formula, str):
                failures.append(f'{ws.title}:{letter}{total}: missing formula for group {group_id}')
            else:
                item['formula_count'] += 1
                ref = f'{letter}{start}:{letter}{end}'
                if ref not in formula.upper().replace('$', ''):
                    failures.append(f'{ws.title}:{letter}{total}: formula does not cover {ref}')

            # Extended group columns and extended recipe rows must remain validated.
            for row in range(start, total):
                address = f'{letter}{row}'
                if not has_validation(ws, address, 'custom'):
                    failures.append(f'{ws.title}:{address}: missing recipe validation')
                    break
            if args.kind == 'test' and not has_validation(ws, f'{letter}10', 'list'):
                failures.append(f'{ws.title}:{letter}10: missing curing-method dropdown validation')

            cached = output_cached_wb[ws.title].cell(total, col).value
            if cached is None:
                warnings.append(f'{ws.title}:{letter}{total}: formula has no cached result; importer must recalculate')

        # Check static identity/format details at coordinates unchanged by recipe insertion.
        for address in (['A1', 'B7', 'B51'] if args.kind == 'test' else ['A1', 'A8', 'A9']):
            a, b = template[address], ws[address]
            if (a.value is not None) and (a.value != b.value):
                failures.append(f'{ws.title}:{address}: standard header label changed')
            if a.font.name != b.font.name or a.number_format != b.number_format:
                failures.append(f'{ws.title}:{address}: static template cell style changed')

        if ws.freeze_panes != template.freeze_panes:
            failures.append(f'{ws.title}: freeze panes changed')
        if len(ws.merged_cells.ranges) < len(template.merged_cells.ranges):
            failures.append(f'{ws.title}: fewer merged ranges than template')
        if not print_covers(ws, max([col for col, _ in ids], default=3), ws.max_row):
            failures.append(f'{ws.title}: print area does not include all content')
        details.append(item)

    report = {
        'passed': not failures,
        'output': str(args.output),
        'kind': args.kind,
        'sheets': details,
        'failures': failures,
        'warnings': warnings,
        'scope': 'Structural checks only. Semantic source-to-target and per-measurement comparison is required separately.',
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if not failures else 1


if __name__ == '__main__':
    sys.exit(main())
