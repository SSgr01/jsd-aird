import type { DataPreview, DataTemplateOption } from './data-api';

export const templateCategory = (item: DataTemplateOption) => item.category?.trim() || '未分类';
export function templateGroups(items: DataTemplateOption[], category?: string) {
  const groups = new Map<string, DataTemplateOption[]>();
  for (const item of items) {
    const name = templateCategory(item);
    if (category && category !== name) continue;
    groups.set(name, [...(groups.get(name) || []), item]);
  }
  return [...groups].sort(([a], [b]) => a.localeCompare(b, 'zh-CN')).map(([label, values]) => ({
    label,
    options: values.map((item) => ({ value: item.versionId, label: `${item.name} · ${item.templateCode} · V${item.versionNo} · ${item.experimentImportReady ? '实验数据' : '普通数据'}` })),
  }));
}

export function sheetSummary(preview: DataPreview, sheetId: string) {
  const sheet = preview.sheets.find((item) => item.sheetId === sheetId);
  const mappings = preview.mappings.filter((item) => item.sheetId === sheetId && item.action === 'MAP' && item.fieldCode?.trim());
  const rows = preview.rows.filter((item) => item.sheetId === sheetId && !item.excluded);
  const blockers = preview.issues.filter((item) => item.sheetId === sheetId && item.severity === 'BLOCKER'
    && !['RESOLVED', 'IGNORED'].includes(item.status));
  return {
    mappingCount: mappings.length, recordCount: rows.length, blockerCount: blockers.length,
    message: !sheet?.selected ? '未选择导入'
      : !mappings.length ? '未找到可执行映射：核对模板覆盖范围，或明确取消导入此工作表'
        : !rows.length ? '没有识别到记录：核对数据区域和起止行'
          : blockers.length ? '存在阻断问题，请核对字段值' : '数据已读取',
  };
}

export function confirmationBlockReason(preview: DataPreview) {
  const count = preview.issues.filter((issue) => issue.severity === 'BLOCKER' && !['RESOLVED', 'IGNORED'].includes(issue.status)).length;
  if (count) return `有 ${count} 项阻断问题，请在下方定位并修正；草稿修正会保留`;
  if (['INCOMPATIBLE', 'REVIEW_REQUIRED'].includes(preview.job.compatibilityStatus || '')) return '模板数据区域需要核对，请打开字段映射';
  if (preview.job.status !== 'WAITING_CONFIRM') return '完成工作表选择、字段映射和校验后可确认数据';
  return undefined;
}
