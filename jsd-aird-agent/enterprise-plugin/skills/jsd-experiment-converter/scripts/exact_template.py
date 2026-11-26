"""Strict identity-layout fast path; unfamiliar layouts remain semantic tasks."""
from openpyxl import load_workbook
from openpyxl.utils import get_column_letter

from workflow import digest, state_dir, relposix, write_json
from template_ops import layout


def prepare(root):
    candidates = [(kind, path) for kind, category in [('test', '测试实验'), ('synthesis', '合成实验')]
                  for path in (root / '原始实验' / category).glob('*.xlsx')]
    if len(candidates) != 1:
        return {'eligible': False, 'reason': '精确模板模式仅接受当前任务的一份 XLSX'}
    kind, source = candidates[0]
    if kind != 'test':
        return {'eligible': False, 'reason': '精确模板模式当前仅适用于测试实验'}
    template = root / '标准模板' / (kind + '.xlsx')
    original, blank = load_workbook(source), load_workbook(template)
    try:
        if len(original.worksheets) != 1 or len(blank.worksheets) != 1:
            return {'eligible': False, 'reason': '多工作表或隐藏元数据需要独立核对'}
        src, target = original.worksheets[0], blank.worksheets[0]
        if (src.max_row, src.max_column) != (target.max_row, target.max_column):
            return {'eligible': False, 'reason': '源表尺寸与模板不一致'}
        if src._images or src._charts:
            return {'eligible': False, 'reason': '源表含图片或图表，需要独立核对'}
        if set(map(str, src.merged_cells)) != set(map(str, target.merged_cells)):
            return {'eligible': False, 'reason': '合并区域不一致'}
        protected = {c.coordinate for row in target for c in row
                     if c.value is not None and c.data_type != 'f'}
        if any(src[coord].value != target[coord].value for coord in protected):
            return {'eligible': False, 'reason': '模板标签位置或内容不一致'}
        populated = {c.coordinate for row in src for c in row if c.value is not None}
        if any(src[coord].number_format != target[coord].number_format for coord in populated - protected):
            return {'eligible': False, 'reason': '源值格式与模板不一致，不能自动解释单位'}
        info = layout(target, kind)
        columns = [col for col in range(info['first_col'], info['last_col'] + 1)
                   if src.cell(info['recipe_id_row'], col).value is not None]
        if not columns or columns != list(range(info['first_col'], max(columns) + 1)):
            return {'eligible': False, 'reason': '实验编号缺失或分组列不连续'}
        if any(c.value is not None and target[c.coordinate].data_type != 'f' and c.column > max(columns) and c.column >= info['first_col']
               and c.row >= min(info['id_row'], info['recipe_id_row']) for row in src for c in row):
            return {'eligible': False, 'reason': '编号组外还有数据，不能自动分组'}
        rows = [row for row in range(info['first'], info['last'] + 1) if src.cell(row, 2).value is not None]
        if any(src.cell(row, col).value is not None for row in range(info['first'], info['last'] + 1)
               if row not in rows for col in columns):
            return {'eligible': False, 'reason': '存在无物料名称的投料数据'}
        def coords(row):
            return [f'{get_column_letter(col)}{row}' for col in columns]
        groups, samples = coords(info['recipe_id_row']), coords(info['id_row'])
        if any(src[coord].value is None for coord in samples):
            return {'eligible': False, 'reason': '样品编号缺失，不能自动补写'}
        names = [f'B{row}' for row in rows]
        amounts = [coords(row) for row in rows]
        totals = coords(info['total'])
        consumed = protected | set(groups + samples + names + totals) | {coord for line in amounts for coord in line}
        blank_anchors = {target.cell(area.min_row, area.min_col).coordinate for area in target.merged_cells
                         if target.cell(area.min_row, area.min_col).value is None}
        if any(not (coord in blank_anchors or src[coord].column >= 3 and target.cell(src[coord].row, 2).value is not None
                    or src[coord].row < info['id_row'] and src[coord].column > 1
                    and target.cell(src[coord].row, src[coord].column - 1).value is not None)
               for coord in populated - consumed if target[coord].data_type != 'f'):
            return {'eligible': False, 'reason': '标准输入区外存在数据，需独立映射'}
        # Unused group total formulas belong to the blank form, not observations.
        inherited_formulas = {c.coordinate for row in target for c in row if c.data_type == 'f'
                              and c.column not in columns and src[c.coordinate].value == c.value}
        inherited_formulas |= {src.cell(info['total'], col).coordinate for col in range(info['first_col'], info['last_col'] + 1)
            if col not in columns and src.cell(info['total'], col).value ==
            f'=IF(COUNTA({get_column_letter(col)}{info["first"]}:{get_column_letter(col)}{info["last"]})=0,"",SUM({get_column_letter(col)}{info["first"]}:{get_column_letter(col)}{info["last"]}))'}
        consumed |= inherited_formulas
        writes = [{'src': coord, 'dst': coord} for coord in sorted(populated - consumed)]
        plan = {'version': 1, 'strategy': 'exact-template-layout-v1', 'source': relposix(source, root),
                'kind': kind, 'source_sha256': digest(source), 'template_sha256': digest(template),
                'sheets': [{'source_sheet': src.title, 'groups': groups, 'sample_groups': samples,
                    'recipe': {'names': names, 'amounts': amounts, 'totals': totals, 'preserve_repeated_lines': True},
                    'writes': writes, 'structural': [{'src': coord, 'reason': '与空模板同坐标标签完全一致'}
                        for coord in sorted(protected)] + [{'src': coord, 'reason': '未使用分组的空表合计公式，执行器核对空组证据'}
                        for coord in sorted(inherited_formulas)], 'unplaced': []}], 'skipped': []}
        plan_path = state_dir(root) / 'plans' / ('exact-' + plan['source_sha256'][:16] + '.json')
        write_json(plan_path, plan)
    finally:
        original.close()
        blank.close()
    # Reuse the existing fingerprint, source coverage, arithmetic, style and
    # export validation. Matching a layout never grants human approval.
    from execute_plan import execute
    return {'eligible': True, 'strategy': plan['strategy'], 'plan': relposix(plan_path, root),
            'result': execute(root, plan_path), 'human_review': 'pending'}
