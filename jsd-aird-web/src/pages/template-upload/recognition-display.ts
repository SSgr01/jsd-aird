import type {
  RecognitionSuggestion,
  TemplateImportJob,
} from '@/services/templates/template-api';

export type DisplaySuggestion = {
  id: string;
  label: string;
  type: string;
  decision: string;
  location: string;
  details: string;
};

export function recognitionCounts(job: TemplateImportJob) {
  const counts = job.recognitionSummary?.counts;
  return {
    fields: counts?.reviewableFields ?? 0,
    pending: counts?.pendingFields ?? 0,
    structures: counts?.structureCandidates ?? 0,
    conflicts: counts?.structureConflictGroups ?? 0,
    quality: counts?.qualityIssues ?? job.result.qualityIssueCount ?? 0,
  };
}

export function buildDisplaySuggestions(items: RecognitionSuggestion[]): DisplaySuggestion[] {
  const groups = new Map<string, RecognitionSuggestion[]>();
  for (const item of items) {
    // The semantic model envelope is an audit artifact, not a user-facing field.
    if (item.suggestionType === 'SEMANTIC_MODEL') continue;
    const structural = isStructuralSuggestion(item);
    if (!structural && ['REJECTED', 'REJECTED_BY_RESOLUTION'].includes(item.decision)) continue;
    const groupKey =
      structural && item.payload.resolutionGroupId
        ? `structure:${item.payload.resolutionGroupId}`
        : `suggestion:${item.id}`;
    const group = groups.get(groupKey) ?? [];
    group.push(item);
    groups.set(groupKey, group);
  }

  return [...groups.values()].flatMap((group) => {
    const first = group[0];
    if (!first) return [];
    const structural = isStructuralSuggestion(first);
    const optionRegions = new Map<string, RecognitionSuggestion[]>();
    for (const item of group) {
      const optionId = item.payload.resolutionAlternativeId ?? item.id;
      const regions = optionRegions.get(optionId) ?? [];
      regions.push(item);
      optionRegions.set(optionId, regions);
    }
    const alternatives = [...optionRegions.values()].map((regions) => {
      const source = regions[0]?.payload.alternativeRole ?? regions[0]?.source;
      const sourceName = source === 'PHYSICAL' ? '物理判断' : source === 'MODEL' ? '模型分区' : '结构方案';
      const members = regions
        .map(
          (item) =>
            `${safeText(item.payload.kind ?? item.payload.blockType, '结构')} ${structureRange(item)}`,
        )
        .join(' + ');
      return `${sourceName}：${members}`;
    });
    const conflict = structural && optionRegions.size > 1;
    const primary = group.find((item) => item.decision === 'ACCEPTED') ?? first;
    return [
      {
        id: conflict ? `group:${primary.payload.resolutionGroupId ?? primary.id}` : primary.id,
        label: conflict
          ? `结构候选（${optionRegions.size} 个方案）`
          : safeText(primary.payload.fieldName, '待命名区域'),
        type: conflict ? 'STRUCTURE_CONFLICT' : primary.suggestionType,
        decision: conflict ? '待选择' : primary.decision,
        location:
          alternatives.join('；') ||
          locatorDisplay(primary.payload.locator?.address, primary.payload.locator?.range),
        details: conflict
          ? `请在工作台按方案选择：${alternatives.join('；')}`
          : primary.payload.resolutionReason === 'MODEL_PARTITION_EXACT_COVER'
            ? '模型区域严格覆盖了物理候选，已自动采用模型分区。'
            : (primary.payload.reason ?? primary.filterDetail ?? '—'),
      },
    ];
  });
}

function isStructuralSuggestion(item: RecognitionSuggestion) {
  const kind = item.payload.kind;
  return (
    ['TABLE_REGION', 'TABLE_FIELD', 'MATRIX', 'ROW_TABLE', 'COLUMN_TABLE', 'FORM_REGION'].includes(
      item.suggestionType,
    ) ||
    ['TABLE_REGION', 'TABLE_FIELD', 'MATRIX', 'ROW_TABLE', 'COLUMN_TABLE', 'FORM_REGION'].includes(
      kind ?? '',
    )
  );
}

function structureRange(item: RecognitionSuggestion) {
  return safeText(
    item.payload.blockName && item.payload.blockName.includes('!')
      ? item.payload.blockName
      : (item.payload.locator?.range ?? item.payload.locator?.address),
    '范围待确认',
  );
}

function safeText(value: unknown, fallback = '') {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : fallback;
}

function locatorDisplay(...values: unknown[]) {
  return (
    values.find((value): value is string => typeof value === 'string' && value.trim().length > 0) ||
    '—'
  );
}
