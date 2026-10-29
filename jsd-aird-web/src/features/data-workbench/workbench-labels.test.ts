import { describe, expect, it } from 'vitest';
import type { DataFieldValueView, DataWorkbookRecord, DataWorkbookRegion } from '@/services/data/data-api';
import { customerFieldName, customerFieldPath, customerGroupName, internalWorkbookLabel, workbookRecordOptions } from './workbench-labels';

const region: DataWorkbookRegion = { regionId: 'comparison', name: '测试数据', structureType: 'COLUMN_TABLE', recordAxis: 'COLUMN', fieldCount: 129, recordCount: 8, fieldGroups: [] };
const records: DataWorkbookRecord[] = Array.from({ length: 8 }, (_, i) => ({
  recordId: `record-${i}`, regionId: 'comparison', label: `IMPORT:STRUCT:sheet-1:comparison:COLUMN:${i + 1}`, sequence: i + 3, excluded: false,
}));
const field = (index: number, value: string, overrides: Partial<DataFieldValueView> = {}): DataFieldValueView => ({
  recordId: records[index]!.recordId, recordGroupId: records[index]!.recordId, componentId: 'comparison',
  bindingId: 'sample-number', fieldCode: 'sample.number', fieldName: '样品/方案编号', valuePath: '/sample',
  valueSource: 'INPUT', valueStatus: 'VALID', required: false, identity: false, trainingEligible: false, ragEligible: false,
  rawValue: value, effectiveValue: value, editable: true, excluded: false, ...overrides,
});

describe('shared workbench display labels', () => {
  it('shows real sample numbers and Chinese fallbacks while preserving stable option IDs', () => {
    const fields = records.slice(0, 6).map((_, i) => field(i, `GEN-T00${i + 1}`));
    const before = structuredClone({ records, fields });
    const options = workbookRecordOptions(records, region, fields);
    expect(options.map((option) => option.label)).toEqual(['GEN-T001', 'GEN-T002', 'GEN-T003', 'GEN-T004', 'GEN-T005', 'GEN-T006', '对照组 7', '对照组 8']);
    expect(options.map((option) => option.value)).toEqual(records.map((record) => record.recordId));
    expect({ records, fields }).toEqual(before);
  });

  it('does not treat generated identity values as human-facing record names', () => {
    expect(workbookRecordOptions(records, region, [field(0, records[0]!.label, { identity: true })])[0]?.label).toBe('对照组 1');
    expect(workbookRecordOptions(records, { ...region, name: '库存明细', regionId: 'stock', recordAxis: 'ROW' }, [])[0]?.label).toBe('记录 1');
  });

  it('uses declared identities, corrected values and existing business labels without damaging codes', () => {
    const named = records.map((record) => ({ ...record, label: '产品_AB-01' }));
    expect(workbookRecordOptions(named.slice(0, 1), region, [])[0]?.label).toBe('产品_AB-01');
    expect(workbookRecordOptions(records, region, [field(0, 'old', { effectiveValue: undefined, correctedValue: 'T_NEW_001' })])[0]?.label).toBe('T_NEW_001');
    expect(workbookRecordOptions(records, region, [field(0, 'MAT_A-01', { fieldName: '物料编码', fieldCode: 'material.code', identity: true })])[0]?.label).toBe('MAT_A-01');
    expect(workbookRecordOptions(records, region, [field(0, '排除的样品', { excluded: true })])[0]?.label).toBe('对照组 1');
  });

  it('distinguishes duplicate names and scopes identity fields to the selected record and region', () => {
    const options = workbookRecordOptions(records, region, [field(0, 'T001'), field(1, 'T001'), field(2, '串区', { componentId: 'other' })]);
    expect(options.slice(0, 3).map((option) => option.label)).toEqual(['T001（对照组 1）', 'T001（对照组 2）', '对照组 3']);
  });

  it('translates internal group keys and paths while retaining scientific names and units', () => {
    expect(customerGroupName('basic')).toBe('基础信息');
    expect(customerGroupName('conditions > resin')).toBe('实验条件 / 树脂物性');
    expect(customerFieldPath('conditions > 样品/方案编号')).toBe('实验条件 > 样品/方案编号');
    expect(customerFieldPath('design > 理论羟值（mg KOH/g）')).toBe('设计参数 > 理论羟值（mg KOH/g）');
    expect(customerFieldPath('基本信息 / 物料名称')).toBe('基本信息 / 物料名称');
    expect(customerFieldName('FIELD.UNKNOWN', 'resin > 酸值')).toBe('酸值');
    expect(customerFieldName('FIELD.UNKNOWN')).toBe('未命名字段');
    for (const name of ['pH', 'UV', 'EB', 'NCO', 'MAT_A-01']) expect(customerFieldName(name)).toBe(name);
    expect(customerGroupName('IMPORT:STRUCT:sheet-1:comparison')).toBe('');
  });

  it('recognizes only system identifier shapes rather than hiding valid business values', () => {
    for (const value of ['IMPORT:STRUCT:sheet-1:comparison:COLUMN:1', 'INSTANCE:field:1', 'FIELD.CALC.1', '12345678-1234-1234-1234-123456789012']) expect(internalWorkbookLabel(value)).toBe(true);
    for (const value of ['SIM-SYN-006', 'MAT_GEN_01', 'NCO', 'pH', 'UV', 'EB', 'mg KOH/g', 'GEN-T001']) expect(internalWorkbookLabel(value)).toBe(false);
  });
});
