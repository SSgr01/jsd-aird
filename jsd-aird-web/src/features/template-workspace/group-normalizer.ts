import type { FieldModel } from './types';

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
  const name = value?.trim() || '基础信息';
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
  return {
    modelVersion: 4,
    groups,
    fields: next.fields.map((field) => ({
      ...field,
      groupId: groupIdMap.get(field.groupId) ?? fallback,
    })),
    blocks: Array.isArray(next.blocks) ? next.blocks : [],
    semanticAnnotations: Array.isArray(next.semanticAnnotations) ? next.semanticAnnotations : [],
  };
}
