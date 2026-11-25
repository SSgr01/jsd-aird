import type { SpectrumMethodPreset, SpectrumMethodSchema, SpectrumTestMethodSnapshot } from '@/services/spectrum/spectrum-api';

// Preserve old UV metadata without asking users to enter the same conditions twice.
export const isLegacyMethodCondition = (categoryCode: string | undefined, key: string) => categoryCode === 'UV' && ['wavelengthRange', 'concentration'].includes(key);

export const emptyMethodSnapshot = (methodType: string, legacyText?: string): SpectrumTestMethodSnapshot => ({
  schemaVersion: 1, methodType, instrument: {}, conditions: {},
  ...(methodType === 'UV' ? { conditions: { blankReference: '与溶剂一致' } } : {}),
  ...(legacyText ? { legacyText } : {}),
});
export function applyMethodPreset(preset: SpectrumMethodPreset, method: SpectrumMethodSchema, legacyText?: string): SpectrumTestMethodSnapshot {
  const snapshot = structuredClone(preset.snapshot);
  for (const field of method.fields) if (field.variable) delete snapshot.conditions[field.key];
  if (method.code === 'UV') snapshot.conditions.blankReference = '与溶剂一致';
  return { ...snapshot, presetId: preset.id, presetRevision: String(preset.version), presetName: preset.name, ...(legacyText ? { legacyText } : {}) };
}
export function conditionNotices(snapshot: SpectrumTestMethodSnapshot, method: SpectrumMethodSchema): string[] {
  const missing = method.comparisonKeys.filter(key => {
    if (method.code === 'MECHANICAL' && ['width', 'thickness'].includes(key)) {
      const specimens = snapshot.conditions.specimens;
      if (Array.isArray(specimens) && specimens.length && specimens.every((row: Record<string, unknown>) => typeof row[key] === 'number' && row[key] > 0)) return false;
    }
    const value = snapshot.conditions[key];
    return value == null || value === '' || value === '未确认' || Array.isArray(value) && !value.length;
  }).map(key => method.fields.find(field => field.key === key)?.label ?? key);
  const result = missing.length ? [`尚缺／未确认：${missing.join('、')}。可以保存，分析时按问题限制比较或计算。`] : [];
  if (method.code === 'UV' && typeof snapshot.conditions.rangeMin === 'number' && snapshot.conditions.rangeMin < 190) result.push('起始波长低于常规 UV-Vis 范围，请核对方法；系统不会自动改值。');
  if (typeof snapshot.conditions.solvent === 'string' && /PM/i.test(snapshot.conditions.solvent)) result.push('PM 的化学全名及适用波段待核对，不猜测化学名称。');
  if (typeof snapshot.conditions.concentration === 'string' && (/^[−-]|^0(?:\.0+)?(?:\s|$)/.test(snapshot.conditions.concentration) || /^[+]?\d+(?:\.\d+)?$/.test(snapshot.conditions.concentration))) result.push('请核对浓度／稀释倍数的正值和单位（或倍数说明），未据此计算。');
  if (method.code === 'GC') {
    const rows = snapshot.conditions.ovenProgram as Record<string, unknown>[] | undefined;
    if (!rows?.length || rows.some((row, index) => row.temperature == null || row.hold == null || index > 0 && row.rate == null)) result.push('升温程序尚不完整，不能从起终温和总时间推算升温速率。');
  }
  return result;
}
