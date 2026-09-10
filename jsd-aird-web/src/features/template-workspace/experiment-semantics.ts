import type {
  ExperimentImportConfiguration,
  ExperimentFieldSemantic,
} from './types';

export const EXPERIMENT_IMPORT_KEY = 'x-jsd-experiment-import';

const EXPERIMENT_FIELD_DEFINITIONS: Array<[string, string]> = [
  ['BASIC.SOURCE_IDENTITY', '基础信息 / 来源编号'],
  ['BASIC.TITLE', '基础信息 / 标题'],
  ['BASIC.PURPOSE', '基础信息 / 实验目的'],
  ['BASIC.PLAN', '基础信息 / 实验方案'],
  ['BASIC.EXPERIMENT_DATE', '基础信息 / 实验日期'],
  ['BASIC.OWNER', '基础信息 / 负责人'],
  ['FORMULA.MATERIAL_ID', '配方 / 材料ID'],
  ['FORMULA.MATERIAL_CODE', '配方 / 材料编码'],
  ['FORMULA.MATERIAL_NAME', '配方 / 材料名称'],
  ['FORMULA.RATIO', '配方 / 比例'],
  ['FORMULA.ACTUAL_QTY', '配方 / 实际用量'],
  ['FORMULA.UNIT', '配方 / 单位'],
  ['FORMULA.RAW_VALUE', '配方 / 原始值'],
  ['FORMULA.RAW_UNIT', '配方 / 原始单位'],
  ['PROCESS.STEP_NO', '工艺 / 步骤号'],
  ['PROCESS.OPERATION', '工艺 / 操作'],
  ['PROCESS.TEMPERATURE', '工艺 / 温度'],
  ['PROCESS.DURATION', '工艺 / 时长'],
  ['PROCESS.APPLICATION_CONDITION', '工艺 / 应用条件'],
  ['PROCESS.OTHER', '工艺 / 其他'],
  ['TEST.TEST_ITEM', '测试 / 测试项目'],
  ['TEST.VALUE', '测试 / 测试结果'],
  ['TEST.UNIT', '测试 / 单位'],
  ['TEST.JUDGEMENT', '测试 / 判定'],
  ['TEST.TEST_METHOD', '测试 / 测试方法'],
  ['TEST.TEST_CONDITION', '测试 / 测试条件'],
  ['TEST.SUBSTRATE', '测试 / 基材'],
  ['CONCLUSION.RESULT_STATUS', '结论 / 结果状态'],
  ['CONCLUSION.MAIN_CONCLUSION', '结论 / 主要结论'],
  ['CONCLUSION.FAILURE_CATEGORY', '结论 / 失败分类'],
  ['OTHER.DYNAMIC_VALUE', '其他 / 动态字段'],
];

export const EXPERIMENT_FIELD_OPTIONS: Array<{
  value: string;
  label: string;
  semantic: ExperimentFieldSemantic;
}> = EXPERIMENT_FIELD_DEFINITIONS.map(([value, label]) => {
  const [domain, field] = value.split('.') as [ExperimentFieldSemantic['domain'], string];
  return { value, label, semantic: { domain, field } };
});

export function readExperimentImport(
  schema: Record<string, unknown>,
): ExperimentImportConfiguration {
  const source = isRecord(schema[EXPERIMENT_IMPORT_KEY])
    ? schema[EXPERIMENT_IMPORT_KEY]
    : {};
  return {
    templateUsage: source.templateUsage === 'EXPERIMENT_DATA' ? 'EXPERIMENT_DATA' : 'GENERAL_DATA',
    recordMode: source.recordMode === 'SINGLE_FILE' || source.recordMode === 'BY_IDENTITY'
      ? source.recordMode
      : undefined,
    identities: Array.isArray(source.identities)
      ? structuredClone(source.identities) as ExperimentImportConfiguration['identities']
      : [],
    listProjections: Array.isArray(source.listProjections)
      ? structuredClone(source.listProjections) as ExperimentImportConfiguration['listProjections']
      : [],
    recognitionSummary: isRecord(source.recognitionSummary)
      ? structuredClone(source.recognitionSummary) as ExperimentImportConfiguration['recognitionSummary']
      : undefined,
  };
}

export function writeExperimentImport(
  schema: Record<string, unknown>,
  configuration: ExperimentImportConfiguration,
) {
  const next = structuredClone(schema);
  next[EXPERIMENT_IMPORT_KEY] = structuredClone(configuration);
  return next;
}

export function semanticValue(semantic?: ExperimentFieldSemantic) {
  return semantic ? `${semantic.domain}.${semantic.field}` : undefined;
}

export function semanticFromValue(value?: string) {
  return EXPERIMENT_FIELD_OPTIONS.find((item) => item.value === value)?.semantic;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}
