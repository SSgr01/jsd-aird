import type {
  DataFieldValueView, DataWorkbookFieldDefinition, DataWorkbookRecord, DataWorkbookRegion,
} from '@/services/data/data-api';
import { displayValue } from './value-display';

const groupNames: Record<string, string> = {
  basic: '基础信息', basic_info: '基础信息', conditions: '实验条件', resin: '树脂物性',
  plan: '实验方案', recipe: '配方', formula: '配方', paint: '涂料物性', coating: '涂层性能',
  design: '设计参数', process: '合成工艺', appearance: '出料外观', results: '测试结果',
  conclusion: '结论', remarks: '备注', summary: '小结', best: '最优方案',
  comparison: '实验对照组', samples: '实验对照组', common: '共用信息', shared: '共用信息',
};

/** Detect system identities, not user material/sample codes or scientific abbreviations. */
export function internalWorkbookLabel(value: string | undefined) {
  const text = value?.trim() || '';
  return /^(?:IMPORT|INSTANCE|SOURCE|RECORD|BINDING|COMPONENT|REGION|PROJECTION|FIELD):/i.test(text)
    || /^(?:AUTO|TABLE|DATA|MATERIAL|PRODUCTION|WORKFLOW|FIELD)\.[A-Z0-9_.-]+$/i.test(text)
    || /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(text)
    || /^(?:sheet-\w+:|column:|row:|\/)/i.test(text);
}

export function customerLabelText(value?: string) {
  return (value || '').replace(/_/g, ' ').replace(/\s+/g, ' ')
    .replace(/([\u3400-\u9fff])\s+(?=[\u3400-\u9fff])/g, '$1')
    .replace(/\s+([（(）)，、：；])/g, '$1').replace(/([（(])\s+/g, '$1').trim();
}

export function workbenchLabelParts(value?: string) {
  // A slash inside a unit or field name is not a hierarchy separator.
  return (value || '').split(/\s*(?:>|›)\s*|\s+\/\s+/).filter(Boolean);
}

export function customerGroupName(value?: string) {
  return workbenchLabelParts(value)
    .map((part) => groupNames[part.trim().toLowerCase()] || (internalWorkbookLabel(part) ? '' : customerLabelText(part)))
    .filter(Boolean).join(' / ');
}

export function customerFieldPath(value?: string) {
  const parts = workbenchLabelParts(value);
  return parts.map((part, index) => index < parts.length - 1
    ? customerGroupName(part) : internalWorkbookLabel(part) ? '' : customerFieldName(part))
    .filter(Boolean).join(/[>›]/.test(value || '') ? ' > ' : ' / ');
}

export function customerFieldName(name?: string, path?: string): string {
  if (name && !internalWorkbookLabel(name)) return /^[A-Z0-9][A-Z0-9_.-]*$/i.test(name.trim())
    ? name.trim() : customerLabelText(name);
  const leaf = workbenchLabelParts(path).at(-1);
  return leaf && !internalWorkbookLabel(leaf) ? customerFieldName(leaf) : '未命名字段';
}

function businessValue(value: unknown) {
  // Actual codes retain punctuation and underscores exactly as entered.
  const text = displayValue(value).trim();
  return text && !internalWorkbookLabel(text) && !/^(?:\/|未填写|未测|不适用|-)$/.test(text) ? text : '';
}

function identityPriority(field: DataFieldValueView, definition?: DataWorkbookFieldDefinition) {
  const name = (definition?.displayName || field.fieldName).replace(/\s+/g, '');
  if (/^(?:样品(?:\/方案)?编号|配方编号|对照组编号)$/.test(name)
    || field.fieldCode === 'sample.number') return 0;
  if (/^(?:实验编号|实验号|样品名称|配方名称|对照组名称)$/.test(name)
    || field.fieldCode === 'sample.experimentNo') return 1;
  return field.identity || definition?.identity ? 2 : 3;
}

/** Display labels only: option values remain the original stable record IDs. */
export function workbookRecordOptions(
  records: DataWorkbookRecord[], region: DataWorkbookRegion | undefined,
  fields: DataFieldValueView[], definitions: DataWorkbookFieldDefinition[] = [],
) {
  const comparison = region?.recordAxis?.toUpperCase() === 'COLUMN'
    && /实验|对照|样品|配方|方案|comparison|samples/i.test(`${region.name} ${region.regionId}`);
  const fallback = (index: number) => `${comparison ? '对照组' : '记录'} ${index + 1}`;
  const definitionByBinding = new Map(definitions.map((field) => [field.bindingId, field]));
  const options = records.map((record, index) => {
    const candidates = fields.filter((field) => !field.excluded
      && (field.recordGroupId || field.recordId) === record.recordId
      && (!field.componentId || field.componentId === record.regionId))
      .map((field) => ({ value: businessValue(field.effectiveValue ?? field.correctedValue ?? field.normalizedValue ?? field.rawValue),
        priority: identityPriority(field, definitionByBinding.get(field.bindingId)) }))
      .filter((candidate) => candidate.value && candidate.priority < 3)
      .sort((a, b) => a.priority - b.priority);
    const current = businessValue(record.label);
    const generic = /^(?:第\s*\d+\s*条记录|记录(?:列\s*[A-Z]+|\s*\d+)|对照组\s*\d+|表单信息)$/i.test(current);
    const label = candidates[0]?.value || (!generic && current ? current : '')
      || (region?.structureType.toUpperCase().includes('FORM') && records.length === 1 ? '共用信息' : fallback(index));
    return { value: record.recordId, label };
  });
  const counts = new Map<string, number>();
  options.forEach((option) => counts.set(option.label, (counts.get(option.label) || 0) + 1));
  return options.map((option, index) => (counts.get(option.label) || 0) > 1
    ? { ...option, label: `${option.label}（${fallback(index)}）` } : option);
}
