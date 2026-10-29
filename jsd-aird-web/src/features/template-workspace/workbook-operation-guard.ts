import type { EditorSelection, TemplateBinding } from './types';
import type { WorkbookTemplateInstance } from './workbook-instance';
import { parseRange } from './structure-migration';
import { locatorLabelRange, locatorValueRange } from './locator';

type Rect = { startRow: number; endRow: number; startColumn: number; endColumn: number };
type Area = { sheetId: string; range: Rect; bindingId: string; label: boolean };
const overlap = (a: Rect, b: Rect) => a.startRow <= b.endRow && a.endRow >= b.startRow && a.startColumn <= b.endColumn && a.endColumn >= b.startColumn;
const record = (value: unknown): Record<string, unknown> => value && typeof value === 'object' ? value as Record<string, unknown> : {};
const text = (value: unknown) => typeof value === 'string' ? value : '';
const rect = (value: unknown): Rect | undefined => {
  const r = record(value);
  return ['startRow','endRow','startColumn','endColumn'].every(key => typeof r[key] === 'number' && Number.isFinite(r[key]))
    ? r as Rect : undefined;
};
const a1Rect = (value: string): Rect | undefined => {
  const r = parseRange(value); return r && { startRow: r.startRow - 1, endRow: r.endRow - 1, startColumn: r.startColumn - 1, endColumn: r.endColumn - 1 };
};
export const isGuardedWorkbookOperation = (id: string) => /\.(?:copy-sheet|remove-sheet|(?:insert|delete)-range-move-(?:down|up|left|right)|move-range(?:-confirm)?|move-rows|move-cols|move-columns|sort-range|reorder-range|add-worksheet-merge)$/.test(id);

export function copyableSheetMetadata(metadata: Record<string, unknown>) {
  const next = { ...metadata }; delete next.templateInstance; delete next.labTemplate; return next;
}

/** The effect corridor matters: shifting an unbound cell can move a bound field below it. */
export function workbookOperationBlock(command: { id: string; params?: object }, context: {
  instance?: WorkbookTemplateInstance; bindings: TemplateBinding[]; sheetId: string; selection?: EditorSelection;
}): string | undefined {
  const p = record(command.params), id = command.id;
  const sid = text(p.subUnitId) || text(p.sheetId) || context.sheetId;
  const mappings = context.instance?.mapping ?? context.bindings;
  const areas: Area[] = mappings.flatMap(binding => {
    const sheetId = text(binding.locator.sheetId);
    return [
      { range: a1Rect(locatorValueRange(binding.locator) || text(binding.locator.dataRange)), label: false },
      { range: a1Rect(locatorLabelRange(binding.locator)), label: true },
    ].flatMap(item => item.range ? [{ ...item, range: item.range, sheetId, bindingId: binding.bindingId }] : []);
  });
  for (const projection of context.instance?.execution?.listProjections ?? []) {
    const parent = mappings.find(binding => binding.bindingId === projection.parentBindingId || projection.componentId && binding.componentId === projection.componentId);
    for (const value of [projection.labelRange, projection.valueRange, projection.unitRange]) {
      const range = value && a1Rect(value);
      if (range && parent) areas.push({ sheetId: text(parent.locator.sheetId), range, bindingId: `projection:${projection.listProjectionId}`, label: false });
    }
  }
  const local = areas.filter(area => area.sheetId === sid);
  const selected = rect(p.range) ?? (context.selection?.sheetId === sid ? a1Rect(context.selection.address) : undefined);
  if (['sheet.command.copy-sheet','sheet.command.remove-sheet'].includes(id)) {
    if (local.length) return '此工作表包含字段或明细绑定，暂不支持直接复制或删除。';
  }
  if (/\.(insert|delete)-range-move-(down|up|left|right)$/.test(id) && selected) {
    const vertical = id.endsWith('down') || id.endsWith('up');
    const corridor = { ...selected, ...(vertical ? { endRow: Number.MAX_SAFE_INTEGER } : { endColumn: Number.MAX_SAFE_INTEGER }) };
    if (local.some(area => overlap(area.range, corridor))) return '此操作会移动已绑定的填写位置，请使用整行或整列插删。';
  }
  if (/\.(move-range(?:-confirm)?|move-rows|move-cols|move-columns)$/.test(id)) {
    const from = record(p.from), to = record(p.to);
    const sourceSheet = text(p.fromSubUnitId) || text(from.subUnitId) || sid, targetSheet = text(p.toSubUnitId) || text(to.subUnitId) || sid;
    const source = rect(p.fromRange) ?? rect(from.range) ?? selected;
    const target = rect(p.toRange) ?? rect(to.range);
    const movingRows = id.endsWith('move-rows'), movingColumns = id.endsWith('move-cols') || id.endsWith('move-columns');
    if (movingRows || movingColumns) {
      if (!source || !target) return areas.some(area => area.sheetId === sourceSheet) ? '此区域包含字段绑定，暂不支持拖动移动行列。' : undefined;
      const corridor = { startRow: movingRows ? Math.min(source.startRow,target.startRow) : 0,
        endRow: movingRows ? Math.max(source.endRow,target.endRow) : Number.MAX_SAFE_INTEGER,
        startColumn: movingColumns ? Math.min(source.startColumn,target.startColumn) : 0,
        endColumn: movingColumns ? Math.max(source.endColumn,target.endColumn) : Number.MAX_SAFE_INTEGER };
      if (areas.some(area => area.sheetId === sourceSheet && overlap(area.range,corridor))) return '此区域包含字段绑定，暂不支持拖动移动行列。';
    } else if (areas.some(area => area.sheetId === sourceSheet && source && overlap(area.range,source)
      || area.sheetId === targetSheet && target && overlap(area.range,target))) return '此区域包含字段绑定，请复制粘贴内容，保留原填写位置。';
  }
  if (/\.(sort-range|reorder-range)$/.test(id) && selected && local.some(area => overlap(area.range,selected))) return '此区域包含字段或投料顺序，暂不支持直接排序。';
  if (id === 'sheet.command.add-worksheet-merge' || id === 'sheet.mutation.add-worksheet-merge') {
    const ranges = (Array.isArray(p.ranges) ? p.ranges : Array.isArray(p.selections) ? p.selections.map(item => record(item).range) : [p.range]).flatMap(value => rect(value) ?? []);
    for (const range of ranges) {
      const touched = local.filter(area => overlap(area.range,range));
      const values = touched.filter(area => !area.label && mappings.find(binding => binding.bindingId === area.bindingId)?.mappingKind !== 'REPEAT_REGION');
      const labels = touched.filter(area => area.label && mappings.find(binding => binding.bindingId === area.bindingId)?.mappingKind !== 'REPEAT_REGION');
      if (new Set(values.map(area => area.bindingId)).size > 1 || new Set(labels.map(area => area.bindingId)).size > 1
        || labels.some(label => values.some(value => value.bindingId === label.bindingId)) || values.some(area => {
        const binding = mappings.find(binding => binding.bindingId === area.bindingId);
        if (binding?.mappingKind === 'REPEAT_FIELD') {
          const parent = mappings.find(item => item.bindingId === binding.parentBindingId);
          const columns = (binding.repeatAxis ?? parent?.repeatAxis) === 'COLUMN';
          const first = columns ? range.startColumn : range.startRow, last = columns ? range.endColumn : range.endRow;
          const records = context.instance?.records.filter(record => record.regionId === binding.parentBindingId && record.index - 1 <= last && record.index - 1 + record.size > first);
          if (records?.length) return records.length > 1 || first < records[0]!.index - 1 || last >= records[0]!.index - 1 + records[0]!.size;
          return last > first;
        }
        return area.bindingId.startsWith('projection:') && (range.endRow > range.startRow || range.endColumn > range.startColumn);
      })) return '不能合并不同字段、物料条目或对照组的填写格。';
    }
  }
  return undefined;
}
