import { generateUUID } from '@/utils/uuid';
import {
  createWorkbookInstance,
  readWorkbookInstance,
  type WorkbookTemplateInstance,
} from '@/features/template-workspace/workbook-instance';
import type {
  BusinessField,
  FieldModel,
  TemplateBinding,
  ExperimentImportConfiguration,
  ExperimentDomain,
} from '@/features/template-workspace/types';
import { labSheet, labFieldPosition, labAddress, labLastColumn } from './lab-template';

/** Compatibility boundary for the previously published packages; editor knows only generic geometry. */
export function labWorkbookInstance(
  snapshot: Record<string, unknown>,
): WorkbookTemplateInstance | undefined {
  const saved = readWorkbookInstance(snapshot);
  if (saved) return saved;
  const sheet = labSheet(snapshot),
    def = sheet?.custom?.labTemplate;
  if (!sheet || !def) return undefined;
  const last = labLastColumn(sheet),
    fields: BusinessField[] = [],
    mapping: TemplateBinding[] = [];
  const firstIdentity = labFieldPosition(sheet, 'sample.experimentNo');
  const total = labFieldPosition(sheet, 'formula.total');
  const lastRow = Math.max(...Object.keys(sheet.cellData ?? {}).map(Number));
  if (!firstIdentity || !total) return undefined;
  const parent: TemplateBinding = {
    bindingId: 'comparison',
    componentId: 'comparison',
    fieldCode: 'comparison',
    dataPath: '/sourceGroups',
    role: 'REPEAT_REGION',
    mappingKind: 'REPEAT_REGION',
    repeatAxis: 'COLUMN',
    recordHeight: 1,
    recordWidth: 1,
    recordStride: 1,
    locatorType: 'TABLE_REGION',
    locator: {
      sheetId: sheet.id,
      sheetName: sheet.name,
      range: `B7:${labAddress(lastRow, last)}`,
      dataRange: `C7:${labAddress(lastRow, last)}`,
      structureType: 'COLUMN_TABLE',
      componentId: 'comparison',
    },
    syncDirection: 'TWO_WAY',
    primaryBinding: true,
    bindingStatus: 'VALID',
  };
  mapping.push(parent);
  fields.push({
    id: 'comparison',
    bindingId: 'comparison',
    groupId: 'comparison',
    name: '实验对照组',
    displayRole: 'REGION',
    fieldType: 'REGION',
    kind: 'COLUMN_TABLE',
    mappingKind: 'REPEAT_REGION',
    repeatAxis: 'COLUMN',
    recordWidth: 1,
    recordHeight: 1,
    recordStride: 1,
    dataPath: '/sourceGroups',
    valueType: 'array',
    required: false,
    reviewStatus: 'CONFIRMED',
    locator: parent.locator,
  });
  for (const field of Object.values(def.fields)) {
    const at = labFieldPosition(sheet, field.fieldCode);
    if (!at) continue;
    const sample = field.scope === 'SAMPLE';
    const range = sample
      ? `C${at.row + 1}:${labAddress(at.row, last)}`
      : labAddress(at.row, at.column);
    const locator = {
      sheetId: sheet.id,
      sheetName: sheet.name,
      componentId: sample ? 'comparison' : 'common',
      address: range,
      valueRange: range,
      labelRange: labAddress(at.row, sample ? 1 : Math.max(0, at.column - 1)),
      valueMode: sample ? 'ARRAY_ROW' : 'SCALAR',
      valueSource: field.valueSource,
      unit: field.unit,
      resultKind: field.resultKind,
    };
    const binding: TemplateBinding = {
      bindingId: field.fieldCode,
      componentId: sample ? 'comparison' : 'common',
      parentBindingId: sample ? 'comparison' : undefined,
      fieldCode: field.fieldCode,
      dataPath: `/fields/${field.fieldCode}`,
      role: 'FIELD',
      mappingKind: sample ? 'REPEAT_FIELD' : 'SCALAR',
      repeatAxis: sample ? 'COLUMN' : undefined,
      locatorType: 'CELL_RANGE',
      locator,
      syncDirection: 'TWO_WAY',
      primaryBinding: true,
      bindingStatus: 'VALID',
      experimentField: { domain: field.domain as ExperimentDomain, field: field.semantic },
    };
    mapping.push(binding);
    fields.push({
      id: field.fieldCode,
      bindingId: binding.bindingId,
      fieldCode: field.fieldCode,
      dataPath: binding.dataPath,
      groupId: field.groupId,
      name: field.label,
      parentFieldId: sample ? 'comparison' : undefined,
      kind: 'SCALAR',
      mappingKind: binding.mappingKind,
      repeatAxis: binding.repeatAxis,
      valueType: field.resultKind === 'NUMBER' ? 'number' : 'string',
      unit: field.unit,
      required: false,
      reviewStatus: field.local && !field.confirmed ? 'NEEDS_CONFIRMATION' : 'CONFIRMED',
      valueSource: field.valueSource as BusinessField['valueSource'],
      locator,
      experimentField: binding.experimentField,
      fieldOrigin: field.local ? 'INSTANCE_LOCAL' : 'TEMPLATE_LOCAL',
    });
  }
  const model: FieldModel = {
    modelVersion: 4,
    groups: [
      { id: 'comparison', name: '实验对照组', order: -1 },
      ...def.regions.map((r, n) => ({ id: r.id, name: r.label, order: n })),
    ],
    fields,
    blocks: [],
    semanticAnnotations: [],
  };
  const execution: ExperimentImportConfiguration = {
    templateUsage: 'EXPERIMENT_DATA',
    recordMode: 'SINGLE_FILE',
    identities: [
      {
        identityType: 'EXPERIMENT_NO',
        sourceKind: 'BINDING',
        componentId: 'comparison',
        bindingId: 'sample.experimentNo',
      },
    ],
    listProjections: [
      {
        listProjectionId: 'formula-materials',
        displayName: '实验配方',
        unit: 'g',
        domain: 'FORMULA',
        componentId: 'comparison',
        parentBindingId: 'comparison',
        recordAxis: 'COLUMN',
        itemAxis: 'ROW',
        labelRange: `B${firstIdentity.row + 2}:B${total.row}`,
        valueRange: `C${firstIdentity.row + 2}:${labAddress(total.row - 1, last)}`,
        totalRange: `C${total.row + 1}:${labAddress(total.row, last)}`,
        labelSemantic: 'MATERIAL_CODE',
        valueSemantic: 'ACTUAL_QTY',
      },
    ],
  };
  const instance = createWorkbookInstance(model, mapping, execution);
  for (const record of instance.records)
    record.id =
      sheet.columnData?.[String(record.index - 1)]?.custom?.lab?.sampleKey ?? generateUUID();
  for (const item of instance.items)
    item.id =
      sheet.cellData?.[String(item.index - 1)]?.['1']?.custom?.lab?.lineId ?? generateUUID();
  return instance;
}
