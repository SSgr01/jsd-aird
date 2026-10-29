import type { DataFormalRecord } from '@/services/data/data-api';

export const publicationLabels: Record<string, string> = { DRAFT: '导入草稿', CONFIRMED: '已核对，待送审', PENDING_REVIEW: '待审核', RETURNED: '已驳回', APPROVED: '审核通过，待发布', PUBLISHED: '已发布', DISABLED: '已停用' };
export const actionLabels: Record<string, string> = { CONFIRMED: '确认导入数据', SUBMITTED: '提交审核', WITHDRAWN: '撤回修改', RETURNED: '驳回', APPROVED: '审核通过', PUBLISHED: '发布', DISABLED: '停用', RESTORED: '恢复使用', REVISION_CREATED: '创建修订', RECORD_UPDATED: '维护正式记录' };
export const recordLabels: Record<string, string> = { ACTIVE: '有效', DISABLED: '停用' };
const text = (value: unknown): string => {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') return value.toString();
  return JSON.stringify(value) || '';
};

export function snapshotRows(snapshot: Record<string, unknown>) {
  const result: Array<{ key: string; field: string; raw: string; effective: string; location: string }> = [];
  const rows = (snapshot.rows || []) as Array<{ id: string; sheetId: string; rowNumber: number; normalizedValues: Record<string, Record<string, unknown>>; sourceMetadata?: { cells?: Record<string, Record<string, unknown>> }; excluded?: boolean }>;
  const sheets = (snapshot.sheets || []) as Array<{ sheetId: string; sheetName: string }>;
  rows.filter((row) => !row.excluded).forEach((row) => Object.entries(row.normalizedValues).forEach(([key, value]) => {
    const cell = row.sourceMetadata?.cells?.[text(value.sourceColumn)];
    const sheet = text(cell?.sheetName) || sheets.find((item) => item.sheetId === row.sheetId)?.sheetName || row.sheetId;
    const column = text(value.sourceColumn);
    const address = text(cell?.cellAddress) || (/^[A-Z]{1,4}$/i.test(column) ? `${column}${row.rowNumber}` : `${row.rowNumber} 行`);
    result.push({ key: `${row.id}:${key}`, field: text(value.labelPath || value.fieldName || value.fieldCode || key), raw: text(value.rawValue),
      effective: text(value.normalizedValue), location: `${sheet} · ${address}` });
  }));
  const recognition = snapshot.recognition as { workspace?: { candidates?: Array<Record<string, unknown>> } } | undefined;
  recognition?.workspace?.candidates?.filter((value) => value.status !== 'IGNORED').forEach((value, index) => result.push({
    key: `candidate:${index}`, field: text(value.labelPath || value.fieldCode), raw: text(value.rawText),
    effective: text(Object.hasOwn(value, 'correctedValue') ? value.correctedValue : value.parsedValue), location: text(value.sourceCoordinate),
  }));
  return result;
}

export function formalFields(record: DataFormalRecord) {
  const recognized = record.values.recognizedItems as Array<Record<string, unknown>> | undefined;
  return recognized ? recognized.map((value, index) => ({ key: String(index), field: text(value.labelPath || value.fieldCode), value: text(value.effectiveValue), unit: text(value.standardUnit || value.unit), location: text(value.sourceCoordinate) }))
    : Object.entries(record.values).map(([key, entry]) => {
      const value = entry as Record<string, unknown>;
      const source=record.locations?.find((item) => item.bindingId === value.bindingId && item.valuePath === value.valuePath);
      return { key, field: text(value.labelPath || value.fieldName || value.fieldCode || key), value: text(value.normalizedValue), unit: text(value.normalizedUnit), location: source ? [source.sheetName,source.address].filter(Boolean).join(' · ') : record.sheetName || '' };
    });
}

