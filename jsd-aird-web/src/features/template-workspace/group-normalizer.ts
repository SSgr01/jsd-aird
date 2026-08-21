import type { FieldModel, LabelStatus, TemplateFieldType } from './types';
import { mergeLocators } from './locator';

const SYSTEM_GROUPS: Record<string, { name: string; code: string }> = {
  基本信息: { name: '基础信息', code: 'BASIC_INFORMATION' },
  基础信息: { name: '基础信息', code: 'BASIC_INFORMATION' },
  基础资料: { name: '基础信息', code: 'BASIC_INFORMATION' },
  通用信息: { name: '基础信息', code: 'BASIC_INFORMATION' },
  原料信息: { name: '原料信息', code: 'MATERIAL_INFORMATION' },
  物料信息: { name: '原料信息', code: 'MATERIAL_INFORMATION' },
  工艺条件: { name: '工艺条件', code: 'PROCESS_CONDITIONS' },
  性能测试: { name: '性能测试', code: 'PERFORMANCE_TEST' },
  测试数据: { name: '性能测试', code: 'PERFORMANCE_TEST' },
  审核信息: { name: '审核信息', code: 'REVIEW_INFORMATION' },
  其他信息: { name: '其他信息', code: 'OTHER_INFORMATION' },
};

export function normalizeGroupName(value?: string) {
  const name = typeof value === 'string' ? value.trim() || '基础信息' : '基础信息';
  return SYSTEM_GROUPS[name]?.name ?? name;
}

export function groupCode(value?: string) {
  return SYSTEM_GROUPS[normalizeGroupName(value)]?.code ?? 'CUSTOM';
}

export function normalizeFieldModel(model: FieldModel): FieldModel {
  const next = structuredClone(model);
  const canonical = new Map<string, string>();
  const groupIdMap = new Map<string, string>();
  const groups: FieldModel['groups'] = [];
  for (const group of [...next.groups].sort((left, right) => left.order - right.order)) {
    const name = normalizeGroupName(group.name);
    const existingId = canonical.get(name);
    if (existingId) {
      groupIdMap.set(group.id, existingId);
      continue;
    }
    canonical.set(name, group.id);
    groupIdMap.set(group.id, group.id);
    groups.push({ ...group, name, groupCode: groupCode(name), order: groups.length });
  }
  if (!groups.length) {
    groups.push({ id: 'group-basic', name: '基础信息', groupCode: 'BASIC_INFORMATION', order: 0 });
  }
  const fallback = groups[0]?.id ?? 'group-basic';
  const fields = next.fields.map((field) => {
    const fieldType: TemplateFieldType = field.fieldType
      || (field.displayRole === 'REGION'
        || ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION'].includes(field.kind)
        || field.mappingKind === 'REPEAT_REGION'
        ? 'REGION'
        : field.mappingKind === 'REPEAT_FIELD'
          ? 'TABLE_COLUMN'
          : 'FIELD');
    const locator = mergeLocators(field.locator);
    const labelStatus: LabelStatus = fieldType === 'REGION'
      ? 'NOT_APPLICABLE'
      : locator.label ? 'RESOLVED' : 'UNRESOLVED';
    return {
      ...field,
      name: typeof field.name === 'string' ? field.name : '',
      fieldCode: typeof field.fieldCode === 'string' ? field.fieldCode : '',
      valueType: typeof field.valueType === 'string' ? field.valueType : 'UNKNOWN',
      groupId: groupIdMap.get(field.groupId) ?? fallback,
      fieldType,
      displayRole: field.displayRole || (fieldType === 'REGION' ? 'REGION' : 'FIELD'),
      labelStatus,
      locator,
      pathSegments: Array.isArray(field.pathSegments) && field.pathSegments.length
        ? field.pathSegments
        : [typeof field.name === 'string' ? field.name : ''],
    };
  });
  const fieldsById = new Map(fields.map((field) => [field.id, field]));
  const pathFor = (field: FieldModel['fields'][number], visiting = new Set<string>()): string[] => {
    if (visiting.has(field.id)) return [field.name].filter(Boolean);
    const explicitPath = (field.pathSegments ?? []).map((segment) => segment.trim()).filter(Boolean);
    // Recognition can provide a semantic path even when the physical parent
    // region is intentionally not represented as a business field. Preserve
    // that path instead of collapsing every table child to the same name.
    if (explicitPath.length > 1) return explicitPath;
    const nextVisiting = new Set(visiting).add(field.id);
    const parent = field.parentFieldId ? fieldsById.get(field.parentFieldId) : undefined;
    return [
      ...(parent ? pathFor(parent, nextVisiting) : []),
      field.name,
    ].filter(Boolean);
  };
  const normalizedFields = fields.map((field) => ({
    ...field,
    pathSegments: pathFor(field),
  }));
  return {
    modelVersion: 5,
    groups,
    fields: normalizedFields,
    blocks: Array.isArray(next.blocks) ? next.blocks : [],
    semanticAnnotations: Array.isArray(next.semanticAnnotations) ? next.semanticAnnotations : [],
    staticRegions: Array.isArray(next.staticRegions) ? next.staticRegions : [],
  };
}
